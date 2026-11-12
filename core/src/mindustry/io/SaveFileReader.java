package mindustry.io;

import arc.struct.*;
import arc.struct.ObjectMap.*;
import arc.util.io.*;

import java.io.*;

public abstract class SaveFileReader{
    public static final ObjectMap<String, String> fallback = ObjectMap.of(
    "dart-mech-pad", "legacy-mech-pad",
    "dart-ship-pad", "legacy-mech-pad",
    "javelin-ship-pad", "legacy-mech-pad",
    "trident-ship-pad", "legacy-mech-pad",
    "glaive-ship-pad", "legacy-mech-pad",
    "alpha-mech-pad", "legacy-mech-pad",
    "tau-mech-pad", "legacy-mech-pad",
    "omega-mech-pad", "legacy-mech-pad",
    "delta-mech-pad", "legacy-mech-pad",

    "draug-factory", "legacy-unit-factory",
    "spirit-factory", "legacy-unit-factory",
    "phantom-factory", "legacy-unit-factory",
    "wraith-factory", "legacy-unit-factory",
    "ghoul-factory", "legacy-unit-factory-air",
    "revenant-factory", "legacy-unit-factory-air",
    "dagger-factory", "legacy-unit-factory",
    "crawler-factory", "legacy-unit-factory",
    "titan-factory", "legacy-unit-factory-ground",
    "fortress-factory", "legacy-unit-factory-ground",

    "mass-conveyor", "payload-conveyor",
    "vestige", "scepter",
    "turbine-generator", "steam-generator",

    "rocks", "stone-wall",
    "sporerocks", "spore-wall",
    "icerocks", "ice-wall",
    "dunerocks", "dune-wall",
    "sandrocks", "sand-wall",
    "shalerocks", "shale-wall",
    "snowrocks", "snow-wall",
    "saltrocks", "salt-wall",
    "dirtwall", "dirt-wall",

    "ignarock", "basalt",
    "holostone", "dacite",
    "holostone-wall", "dacite-wall",
    "rock", "boulder",
    "snowrock", "snow-boulder",
    "cliffs", "stone-wall",
    "craters", "crater-stone",
    "deepwater", "deep-water",
    "water", "shallow-water",
    "sand", "sand-floor",
    "slag", "molten-slag",

    "cryofluidmixer", "cryofluid-mixer",
    "block-forge", "constructor",
    "block-unloader", "payload-unloader",
    "block-loader", "payload-loader",
    "thermal-pump", "impulse-pump",
    "alloy-smelter", "surge-smelter",
    "steam-vent", "rhyolite-vent",
    "fabricator", "tank-fabricator",
    "basic-reconstructor", "refabricator"
    );

    public static final ObjectMap<String, String> modContentNameMap = ObjectMap.of(
    "craters", "crater-stone",
    "deepwater", "deep-water",
    "water", "shallow-water",
    "slag", "molten-slag"
    );

    protected static final ReusableByteOutStream byteOutput = new ReusableByteOutStream(), byteOutput2 = new ReusableByteOutStream();
    protected static final DataOutputStream dataBytes = new DataOutputStream(byteOutput), dataBytes2 = new DataOutputStream(byteOutput2);
    protected static final Writes writes1 = new Writes(dataBytes), writes2 = new Writes(dataBytes2);
    public static final int maxChunkBytes = 256 * 1024 * 1024;
    public static final int maxMapTiles = 4 * 1024 * 1024;
    public static final int maxEntities = 1_000_000;
    protected static boolean chunkNested = false;

    public static String mapFallback(String name){
        return fallback.get(name, name);
    }

    //TODO: unify readRegion with readChunk, they do the same thing and both should have good error messages
    public void readRegion(String name, DataInput stream, CounterInputStream counter, IORunner<DataInput> cons) throws IOException{
        counter.resetCount();
        int length;
        try{
            length = readChunk(stream, (chunkStream, len) -> cons.accept(chunkStream));
        }catch(Throwable e){
            throw new IOException("Error reading region \"" + name + "\".", e);
        }

        if(length != counter.count - 4){
            throw new IOException("Error reading region \"" + name + "\": read length mismatch. Expected: " + length + "; Actual: " + (counter.count - 4));
        }
    }

    public void writeRegion(String name, DataOutput stream, IORunner<DataOutput> cons) throws IOException{
        try{
            writeChunk(stream, writes -> cons.accept(writes.output));
        }catch(Throwable e){
            throw new IOException("Error writing region \"" + name + "\".", e);
        }
    }

    /** Write a chunk of input to the stream. An integer of some length is written first, followed by the data. */
    public void writeChunk(DataOutput output, IORunner<Writes> runner) throws IOException{
        boolean wasNested = chunkNested;

        chunkNested = true;

        //regions can be nested once, so use a different output if it's already nested
        ReusableByteOutStream dout = wasNested ? byteOutput2 : byteOutput;

        try{
            //reset output position
            dout.reset();
            //write the needed info
            runner.accept(wasNested ? writes2 : writes1);

            //write the length of the stream contents as an int, then write the contents
            int length = checkedLength(dout.size(), maxChunkBytes, "chunk");
            output.writeInt(length);
            output.write(dout.getBytes(), 0, length);
        }finally{
            chunkNested = wasNested;
        }
    }

    /** Reads a chunk of some length. Use the runner for reading to catch more descriptive errors. */
    public int readChunk(DataInput input, IORunnerLength<DataInput> runner) throws IOException{
        int length = checkedLength(input.readInt(), maxChunkBytes, "chunk");
        requireBytes(input, length);
        ChunkDataInputStream chunk = new ChunkDataInputStream(input, length);
        runner.accept(chunk, length);
        if(chunk.remaining() != 0) throw new IOException("Unread chunk bytes: " + chunk.remaining());
        return length;
    }

    /** Reads a chunk of some length. Use the runner for reading to catch more descriptive errors. */
    public int readChunkReads(DataInput input, IORunnerLength<Reads> runner) throws IOException{
        return readChunk(input, (in, length) -> runner.accept(new Reads(in), length));
    }

    /** Skip a chunk completely, discarding the bytes. */
    public void skipChunk(DataInput input) throws IOException{
        readChunk(input, (in, length) -> {
            int skipped = in.skipBytes(length);
            if(length != skipped) throw new EOFException("Truncated skipped chunk.");
        });
    }

    /** Reads a legacy chunk where the length is only 2 bytes. */
    public int readLegacyShortChunk(DataInput input, IORunnerLength<Reads> runner) throws IOException{
        int length = input.readUnsignedShort();
        requireBytes(input, length);
        ChunkDataInputStream chunk = new ChunkDataInputStream(input, length);
        runner.accept(new Reads(chunk), length);
        if(chunk.remaining() != 0) throw new IOException("Unread legacy chunk bytes: " + chunk.remaining());
        return length;
    }

    /** Skip a legacy chunk completely, discarding the bytes. */
    public void skipLegacyShortChunk(DataInput input) throws IOException{
        readLegacyShortChunk(input, (in, length) -> {
            int skipped = in.input.skipBytes(length);
            if(length != skipped) throw new EOFException("Truncated skipped legacy chunk.");
        });
    }

    public void writeStringMap(DataOutput stream, ObjectMap<String, String> map) throws IOException{
        checkedLength(map.size, 65535, "metadata entries");
        stream.writeShort(map.size);
        for(Entry<String, String> entry : map.entries()){
            stream.writeUTF(entry.key);
            stream.writeUTF(entry.value);
        }
    }

    public StringMap readStringMap(DataInput stream) throws IOException{
        StringMap map = new StringMap();
        int size = stream.readUnsignedShort();
        for(int i = 0; i < size; i++){
            map.put(stream.readUTF(), stream.readUTF());
        }
        return map;
    }

    public static int checkedLength(int value, int maximum, String name) throws IOException{
        if(value < 0 || value > maximum) throw new IOException("Invalid " + name + " length/count: " + value + " (maximum " + maximum + ")");
        return value;
    }

    public static int validateMapDimensions(int width, int height) throws IOException{
        if(width <= 0 || height <= 0 || width > Short.MAX_VALUE || height > Short.MAX_VALUE
        || (long)width * height > maxMapTiles){
            throw new IOException("Invalid or oversized map dimensions: " + width + "x" + height);
        }
        return width * height;
    }

    public static void requireBytes(DataInput input, long bytes) throws IOException{
        if(bytes < 0 || bytes > maxChunkBytes) throw new IOException("Invalid byte count: " + bytes);
        long remaining = input instanceof ChunkDataInputStream chunk ? chunk.remaining()
            : input instanceof ByteBufferInput buffer ? buffer.buffer.remaining() : -1;
        if(remaining >= 0 && bytes > remaining) throw new EOFException("Declared data exceeds remaining input.");
    }

    public static byte[] readBytes(DataInput input, int length) throws IOException{
        return readBytes(input, length, maxChunkBytes);
    }

    public static byte[] readBytes(DataInput input, int length, int maximum) throws IOException{
        checkedLength(length, Math.min(maximum, maxChunkBytes), "byte data");
        requireBytes(input, length);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(length, 4096));
        byte[] buffer = new byte[Math.min(length, 4096)];
        for(int remaining = length; remaining > 0;){
            int count = Math.min(remaining, buffer.length);
            input.readFully(buffer, 0, count);
            out.write(buffer, 0, count);
            remaining -= count;
        }
        return out.toByteArray();
    }

    public static final class ChunkDataInputStream extends DataInputStream{
        private final ChunkInput chunk;

        private ChunkDataInputStream(DataInput input, int length){
            this(new ChunkInput(input, length));
        }

        private ChunkDataInputStream(ChunkInput chunk){
            super(chunk);
            this.chunk = chunk;
        }

        public int remaining(){
            return chunk.remaining;
        }
    }

    private static class ChunkInput extends InputStream{
        private final DataInput input;
        private int remaining;

        ChunkInput(DataInput input, int length){
            this.input = input;
            remaining = length;
        }

        @Override
        public int read() throws IOException{
            if(remaining == 0) return -1;
            int value = input.readUnsignedByte();
            remaining--;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException{
            if(length == 0) return 0;
            if(remaining == 0) return -1;
            int count = Math.min(length, remaining);
            input.readFully(bytes, offset, count);
            remaining -= count;
            return count;
        }

        @Override
        public long skip(long length) throws IOException{
            int count = input.skipBytes((int)Math.min(Math.max(0L, length), remaining));
            remaining -= count;
            return count;
        }

        @Override
        public int available(){
            return remaining;
        }
    }

    public abstract void read(DataInputStream stream, CounterInputStream counter, SaveReadState state) throws IOException;

    public abstract void write(DataOutputStream stream, SaveOptions options) throws IOException;

    public interface IORunner<T>{
        void accept(T stream) throws IOException;
    }

    public interface IORunnerLength<T>{
        void accept(T stream, int length) throws IOException;
    }

    public interface CustomChunk{
        void write(DataOutput stream) throws IOException;
        void read(DataInput stream) throws IOException;

        default void read(DataInput stream, int length) throws IOException{
            read(stream);
        }

        /** @return whether this chunk is enabled at all */
        default boolean shouldWrite(){
            return true;
        }

        /** @return whether this chunk should be written to connecting clients (default true) */
        default boolean writeNet(){
            return true;
        }
    }
}
