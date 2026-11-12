import arc.files.*;
import arc.struct.*;
import arc.util.io.*;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.io.versions.*;
import mindustry.world.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

public class SaveBoundsTests{
    @TempDir
    Path directory;

    @BeforeAll
    static void launch(){
        ApplicationTests.launchApplication();
    }

    @AfterEach
    void clearMapper(){
        content.setTemporaryMapper(null);
    }

    @Test
    void invalidChunksNeverCallTheReader() throws IOException{
        var writer = SaveIO.getSaveWriter();
        for(int length : new int[]{-1, Integer.MAX_VALUE, SaveFileReader.maxChunkBytes + 1}){
            assertThrows(IOException.class, () -> writer.readChunk(input(out -> out.writeInt(length)), (in, size) -> fail("Invalid chunk reached callback")));
        }
        assertDoesNotThrow(() -> writer.readChunk(input(out -> out.writeInt(0)), (in, size) -> assertEquals(0, size)));
    }

    @Test
    void chunkReaderCannotConsumeTheNextRecord() throws IOException{
        var writer = SaveIO.getSaveWriter();
        var source = input(out -> {
            out.writeInt(1);
            out.writeByte(7);
            out.writeByte(99);
        });
        assertThrows(IOException.class, () -> writer.readChunk(source, (in, size) -> in.readInt()));
        assertEquals(99, source.readUnsignedByte());
    }

    @Test
    void unreadAndTruncatedChunkBytesAreRejected() throws IOException{
        var writer = SaveIO.getSaveWriter();
        assertThrows(IOException.class, () -> writer.readChunk(input(out -> {
            out.writeInt(2);
            out.writeByte(1);
            out.writeByte(2);
        }), (in, size) -> in.readByte()));
        assertThrows(IOException.class, () -> writer.skipChunk(input(out -> {
            out.writeInt(2);
            out.writeByte(1);
        })));
    }

    @Test
    void nestedChunkReadersRetainTheirOwnInput() throws IOException{
        var writer = SaveIO.getSaveWriter();
        var source = input(out -> {
            out.writeInt(6);
            out.writeInt(1);
            out.writeByte(42);
            out.writeByte(99);
        });
        writer.readChunkReads(source, (outer, size) -> {
            writer.readChunkReads(outer.input, (inner, nestedSize) -> assertEquals(42, inner.ub()));
            assertEquals(99, outer.ub());
        });
        assertEquals(-1, source.read());
    }

    @Test
    void legacyShortChunksHaveTheSameBoundary() throws IOException{
        var source = input(out -> {
            out.writeShort(1);
            out.writeByte(7);
            out.writeByte(99);
        });
        assertThrows(RuntimeException.class, () -> SaveIO.getSaveWriter().readLegacyShortChunk(source, (in, size) -> in.i()));
        assertEquals(99, source.readUnsignedByte());
    }

    @Test
    void metadataLengthIsValidated() throws IOException{
        assertThrows(IOException.class, () -> SaveIO.getSaveWriter().getMeta(input(out -> out.writeInt(-1))));
        assertThrows(IOException.class, () -> SaveIO.getSaveWriter().getMeta(input(out -> {
            out.writeInt(1);
            out.writeByte(0);
        })));
    }

    @Test
    void unsignedMetadataCountsAreNotSilentlyIgnored() throws IOException{
        var source = input(out -> out.writeShort(0xffff));
        assertThrows(IOException.class, () -> SaveIO.getSaveWriter().readStringMap(source));
        var map = StringMap.of("name", "地图", "description", "one\ntwo");
        var data = input(out -> SaveIO.getSaveWriter().writeStringMap(out, map));
        assertEquals(map, SaveIO.getSaveWriter().readStringMap(data));
    }

    @Test
    void byteReaderRejectsDeclarationsBeforeLargeAllocation() throws IOException{
        assertThrows(IOException.class, () -> SaveFileReader.readBytes(input(out -> {}), Integer.MAX_VALUE));
        assertThrows(IOException.class, () -> SaveFileReader.readBytes(input(out -> {}), -1));
        assertThrows(IOException.class, () -> SaveFileReader.readBytes(input(out -> out.writeByte(1)), 1_000_000));
        assertArrayEquals(new byte[0], SaveFileReader.readBytes(input(out -> {}), 0));
        assertArrayEquals(new byte[]{1, 2}, SaveFileReader.readBytes(input(out -> {
            out.writeByte(1);
            out.writeByte(2);
        }), 2));
    }

    @Test
    void mapSizeArithmeticAndBudgetAreCheckedWithoutAllocatingTiles(){
        assertDoesNotThrow(() -> assertEquals(4 * 1024 * 1024, SaveFileReader.validateMapDimensions(2048, 2048)));
        for(int[] size : new int[][]{{0, 1}, {1, 0}, {-1, 2}, {65535, 65535}, {2048, 2049}}){
            assertThrows(IOException.class, () -> SaveFileReader.validateMapDimensions(size[0], size[1]));
        }
    }

    @Test
    void currentAndLegacyMapReadersRejectDimensionsBeforeResize() throws IOException{
        for(SaveVersion version : new SaveVersion[]{new Save3(), new Save7(), new Save13()}){
            var context = new TestContext();
            assertThrows(IOException.class, () -> version.readMap(input(out -> {
                out.writeShort(65535);
                out.writeShort(65535);
            }), new SaveReadState(context)));
            assertEquals(0, context.resizes);
            assertFalse(context.generating);
        }
    }

    @Test
    void floorAndBlockRunsCannotEscapeMapDimensions() throws IOException{
        for(SaveVersion version : new SaveVersion[]{new Save3(), new Save7(), new Save13()}){
            var context = new TestContext();
            assertThrows(IOException.class, () -> version.readMap(input(out -> {
                out.writeShort(1);
                out.writeShort(1);
                out.writeShort(Blocks.stone.id);
                out.writeShort(Blocks.air.id);
                out.writeByte(1);
            }), new SaveReadState(context)));
            assertEquals(0, context.creates);
            assertFalse(context.generating);
        }
        var context = new TestContext();
        assertThrows(IOException.class, () -> new Save13().readMap(input(out -> {
            out.writeShort(1);
            out.writeShort(1);
            out.writeShort(Blocks.stone.id);
            out.writeShort(Blocks.air.id);
            out.writeByte(0);
            out.writeShort(Blocks.air.id);
            out.writeByte(0);
            out.writeByte(1);
        }), new SaveReadState(context)));
        assertEquals(1, context.creates);
        assertFalse(context.generating);
    }

    @Test
    void contentAndEntityMappingIndicesAreValidated() throws IOException{
        var version = SaveIO.getSaveWriter();
        assertThrows(IOException.class, () -> version.readContentHeader(input(out -> {
            out.writeByte(1);
            out.writeByte(255);
        })));
        assertThrows(IOException.class, () -> version.readEntityMapping(input(out -> {
            out.writeShort(1);
            out.writeShort(65535);
        })));
        assertThrows(IOException.class, () -> version.readEntityMapping(input(out -> out.writeShort(EntityMapping.idMap.length + 1))));
    }

    @Test
    void negativeCountsDoNotPassAsEmptySections() throws IOException{
        var version = SaveIO.getSaveWriter();
        assertThrows(IOException.class, () -> version.readCustomChunks(input(out -> out.writeInt(-1))));
        assertThrows(IOException.class, () -> version.readTeamBlocks(input(out -> out.writeInt(-1))));
        assertThrows(IOException.class, () -> version.readWorldEntities(input(out -> out.writeInt(-1)), EntityMapping.idMap, new SaveReadState(new TestContext())));
        assertThrows(IOException.class, () -> version.readDataPatches(input(out -> {
            out.writeInt(1);
            out.writeInt(-1);
        }), new SaveReadState(new TestContext())));
    }

    @Test
    void olderPatchReadersRejectInvalidLength() throws IOException{
        assertThrows(IOException.class, () -> new Save11().readDataPatches(input(out -> {
            out.writeByte(1);
            out.writeInt(Integer.MAX_VALUE);
        }), new SaveReadState(new TestContext())));
        assertThrows(IOException.class, () -> new Save12().readDataPatches(input(out -> {
            out.writeInt(1);
            out.writeInt(1);
            out.writeInt(-1);
        }), new SaveReadState(new TestContext())));
    }

    @Test
    void invalidMapMetadataIsRejectedAndFileReleased() throws IOException{
        Fi file = mapFile(65535, 65535, false);
        assertThrows(IOException.class, () -> MapIO.createMap(file, true));
        Files.delete(file.file().toPath());
    }

    @Test
    void mapPreviewRejectsDimensionsThatDisagreeWithMetadata() throws IOException{
        Fi file = mapFile(1, 1, true);
        var map = new mindustry.maps.Map(file, 1, 1, StringMap.of("name", "test"), true, 13, 0);
        assertThrows(IOException.class, () -> MapIO.generatePreview(map));
        assertFalse(world.isGenerating());
        Files.delete(file.file().toPath());
    }

    private Fi mapFile(int width, int height, boolean withMap) throws IOException{
        Path path = directory.resolve("test.msav");
        var writer = SaveIO.getSaveWriter();
        try(var stream = new DataOutputStream(new DeflaterOutputStream(Files.newOutputStream(path)))){
            stream.write(SaveIO.header);
            stream.writeInt(13);
            writer.writeRegion("meta", stream, out -> writer.writeStringMap(out, StringMap.of("width", width, "height", height)));
            if(withMap){
                stream.writeInt(0);
                writer.writeRegion("content", stream, out -> out.writeByte(0));
                writer.writeRegion("map", stream, out -> {
                    out.writeShort(2);
                    out.writeShort(2);
                });
            }
        }
        return new Fi(path.toFile());
    }

    private static DataInputStream input(BinaryWriter writer) throws IOException{
        var bytes = new ByteArrayOutputStream();
        writer.write(new DataOutputStream(bytes));
        return new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    }

    private interface BinaryWriter{
        void write(DataOutputStream output) throws IOException;
    }

    private static class TestContext implements WorldContext{
        int resizes, creates;
        boolean generating;
        final CachedTile tile = new CachedTile(){
            @Override
            public void setBlock(Block type){
                block = type;
            }
        };

        public Tile tile(int index){ return tile; }
        public void resize(int width, int height){ resizes++; }
        public Tile create(int x, int y, int floor, int overlay, int wall){ creates++; return tile; }
        public boolean isGenerating(){ return generating; }
        public void begin(){ generating = true; }
        public void end(){ generating = false; }
    }
}
