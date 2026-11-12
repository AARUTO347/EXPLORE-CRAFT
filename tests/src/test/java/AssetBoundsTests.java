import arc.*;
import arc.files.*;
import arc.mock.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import arc.graphics.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.ctype.*;
import mindustry.io.*;
import mindustry.mod.*;
import mindustry.mod.data.*;
import mindustry.net.*;
import mindustry.net.Packets.*;
import mindustry.net.Streamable.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

/** Small deterministic fixtures; hostile metadata never requires a large payload or allocation. */
public class AssetBoundsTests{
    private DataAssetCache oldCache;
    private Net oldNet;
    private NetClient oldClient;
    private Application oldApp;
    private DataManager oldData;
    private boolean oldHeadless;
    private RecordingCache cache;
    private TestNet network;
    private DeferredApplication app;

    @BeforeAll
    static void launch(){
        ApplicationTests.launchApplication(false);
    }

    @BeforeEach
    void setup(){
        oldCache = Vars.assetCache;
        oldNet = Vars.net;
        oldClient = Vars.netClient;
        oldApp = Core.app;
        oldData = Vars.state.data;
        oldHeadless = Vars.headless;
        Vars.assetCache = cache = new RecordingCache();
        Core.app = app = new DeferredApplication();
        Vars.net = network = new TestNet();
        Vars.netClient = new NetClient();
        //Do not start asynchronous world/asset loading in framing-only tests.
        network.handleClient(StreamBegin.class, begin -> {});
        network.handleClient(WorldStream.class, packet -> network.completed++);
        network.handleClient(AssetRequirementStream.class, packet -> network.completed++);
        network.setClientConnected();
        Vars.headless = true;
    }

    @AfterEach
    void restore(){
        network.disconnect();
        app.drain();
        Vars.assetCache = oldCache;
        Vars.net = oldNet;
        Vars.netClient = oldClient;
        Vars.state.data = oldData;
        Vars.headless = oldHeadless;
        Core.app = oldApp;
    }

    @Test
    void embeddedLengthsRejectNegativeMaxAndHeaderOnlyBudgets() throws IOException{
        for(int length : new int[]{-1, Integer.MAX_VALUE, SaveFileReader.maxChunkBytes}){
            assertThrows(IOException.class, () -> new ImageAsset().read(input(ints(length))));
            assertThrows(IOException.class, () -> SaveFileReader.readBytes(input(new byte[0]), length));
        }
        for(int length : new int[]{-1, Integer.MAX_VALUE, DataAsset.maxTextBytes}){
            assertThrows(IOException.class, () -> new PatchAsset("").read(input(ints(length))));
            byte[] contentHeader = encoded(out -> {
                out.writeShort(ContentType.unit.ordinal());
                out.writeInt(length);
            });
            assertThrows(IOException.class, () -> new ContentAsset().read(input(contentHeader)));
        }
        var bounded = new ByteBufferInput(ByteBuffer.wrap(ints(1024)));
        assertThrows(IOException.class, () -> new ImageAsset().read(bounded));
        assertEquals(0, cache.values.size);
    }

    @Test
    void zeroSentinelAndUtf8RoundTrip() throws IOException{
        new ImageAsset().read(input(ints(0)));
        assertEquals(0, cache.values.size, "Empty embedded binary data retains its sentinel meaning");
        for(String text : new String[]{"", "plain", "\u20ac\u6f22\uD801\uDC00"}){
            var patch = new PatchAsset(text);
            var decodedPatch = new PatchAsset("old");
            decodedPatch.read(input(encoded(patch::write)));
            assertEquals(text, decodedPatch.patch);
            assertArrayEquals(text.getBytes(Strings.utf8), patch.getData());
            var content = new ContentAsset("test.json", ContentType.unit, text);
            var decodedContent = new ContentAsset();
            decodedContent.read(input(encoded(content::write)));
            assertEquals(text, decodedContent.data);
            assertEquals(ContentType.unit, decodedContent.type);
        }
        var image = new ImageAsset();
        image.read(input(encoded(out -> {
            out.writeInt(3);
            out.write(new byte[]{1, 2, 3});
        })));
        assertArrayEquals(new byte[]{1, 2, 3}, cache.values.first());
    }

    @Test
    void contentOrdinalValidationPreservesKnownUnsupportedTypes() throws IOException{
        for(int ordinal : new int[]{-1, ContentType.all.length, Short.MAX_VALUE}){
            byte[] bytes = encoded(out -> {
                out.writeShort(ordinal);
                out.writeInt(0);
            });
            assertThrows(IOException.class, () -> new ContentAsset().read(input(bytes)));
        }
        var original = new ContentAsset("planet.json", ContentType.planet, "{}");
        var decoded = new ContentAsset();
        decoded.read(input(encoded(original::write)));
        assertEquals(ContentType.planet, decoded.type);
        assertEquals("{}", decoded.data);
    }

    @Test
    void requiredAssetCountsAndHashesAreExact() throws IOException{
        assertEquals(Short.MAX_VALUE, NetworkIO.maxAssetCount);
        for(int amount : new int[]{-1, NetworkIO.maxAssetCount + 1}){
            assertThrows(RuntimeException.class, () -> NetworkIO.readRequiredAssets(new ByteArrayInputStream(ints(amount))));
        }
        assertEquals(0, NetworkIO.readRequiredAssets(new ByteArrayInputStream(ints(0))).size);
        byte[] hash = new byte[32];
        hash[31] = 7;
        byte[] one = encoded(out -> {
            out.writeInt(1);
            out.write(hash);
        });
        assertEquals(DataAssetCache.encodeHash(hash), NetworkIO.readRequiredAssets(new ByteArrayInputStream(one)).first());
        assertThrows(RuntimeException.class, () -> NetworkIO.readRequiredAssets(new ByteArrayInputStream(Arrays.copyOf(one, one.length - 1))));
        assertThrows(RuntimeException.class, () -> NetworkIO.readRequiredAssets(new ByteArrayInputStream(concat(one, new byte[]{0}))));
        RuntimeException maxTruncated = assertThrows(RuntimeException.class,
            () -> NetworkIO.readRequiredAssets(new ByteArrayInputStream(ints(NetworkIO.maxAssetCount))));
        assertInstanceOf(EOFException.class, maxTruncated.getCause(), "Maximum legal count must pass count validation, then fail on missing hashes");

        var asset = new ImageAsset("one.png", hash);
        var output = new ByteArrayOutputStream();
        NetworkIO.writeRequiredAssets(output, Seq.with(asset));
        assertArrayEquals(one, output.toByteArray());
        asset.byteHash = new byte[31];
        assertThrows(RuntimeException.class, () -> NetworkIO.writeRequiredAssets(new ByteArrayOutputStream(), Seq.with(asset)));
        var tooMany = new Seq<DataAsset>();
        tooMany.size = NetworkIO.maxAssetCount + 1;
        assertThrows(RuntimeException.class, () -> NetworkIO.writeRequiredAssets(new ByteArrayOutputStream(), tooMany));
    }

    @Test
    void assetTransfersReserveHeadersAndRequireExactTotal() throws IOException{
        byte[] two = encoded(out -> {
            out.writeInt(2);
            out.writeInt(1);
            out.writeByte(5);
            out.writeInt(1);
            out.writeByte(6);
        });
        assertEquals(14, two.length);
        assertThrows(IOException.class, () -> NetworkIO.loadAssets(new ByteArrayInputStream(two), 12));
        assertEquals(0, cache.values.size, "Reserve the second header before allocating the first payload");
        NetworkIO.loadAssets(new ByteArrayInputStream(two), 14);
        assertEquals(2, cache.values.size);
        cache.values.clear();
        assertThrows(IOException.class, () -> NetworkIO.loadAssets(new ByteArrayInputStream(two), 15));
        assertThrows(IOException.class, () -> NetworkIO.loadAssets(new ByteArrayInputStream(concat(two, new byte[]{0})), 14));
        assertThrows(IOException.class, () -> NetworkIO.loadAssets(new ByteArrayInputStream(Arrays.copyOf(two, 13)), 14));
        for(int total : new int[]{-1, Integer.MAX_VALUE, NetworkIO.maxAssetTransferBytes + 1}){
            assertThrows(IOException.class, () -> NetworkIO.loadAssets(new ByteArrayInputStream(two), total));
        }
        //The preserved unknown-input API also rejects trailing data and validates each length.
        assertThrows(IOException.class, () -> NetworkIO.loadAssets(new FilterInputStream(new ByteArrayInputStream(concat(two, new byte[]{0}))){}));
        for(byte[] bytes : new byte[][]{ints(-1), ints(NetworkIO.maxAssetCount + 1), ints(1, -1), ints(1, Integer.MAX_VALUE), ints(1, 1)}){
            assertThrows(IOException.class, () -> NetworkIO.loadAssets(new ByteArrayInputStream(bytes)));
        }
        NetworkIO.loadAssets(new ByteArrayInputStream(ints(0)), 4);
        NetworkIO.loadAssets(new ByteArrayInputStream(ints(1, 0)), 8);
        assertEquals(0, cache.values.peek().length);
        assertThrows(ClosedChannelException.class, () -> NetworkIO.loadAssets(new InputStream(){
            @Override
            public int read() throws IOException{
                throw new ClosedChannelException();
            }
        }, 4));
    }

    @Test
    void completedByteArrayTransferCannotBypassAggregateBudget(){
        var input = new ByteArrayInputStream(new byte[4]){
            @Override
            public synchronized int available(){
                return NetworkIO.maxAssetTransferBytes + 1;
            }
        };
        IOException error = assertThrows(IOException.class, () -> NetworkIO.loadAssets(input));
        assertTrue(error.getMessage().contains("asset transfer"));
    }

    @Test
    void fileLimitsAndAggregateAreCheckedBeforeReading() throws IOException{
        var oversized = new MetadataFile((long)SaveFileReader.maxChunkBytes + 1, new byte[0]);
        assertThrows(IOException.class, () -> new FileAsset(oversized).write(new DataOutputStream(new ByteArrayOutputStream())));
        assertFalse(oversized.readCalled);
        var oversizedText = new MetadataFile((long)DataAsset.maxTextBytes + 1, new byte[0]);
        assertThrows(IOException.class, () -> new ContentAsset().readOverride("a.json", oversizedText, ContentType.unit));
        assertThrows(IOException.class, () -> new PatchAsset("").readOverride("a.json", oversizedText));
        assertFalse(oversizedText.readCalled);
        var oversizedNetwork = new MetadataFile((long)NetworkIO.maxAssetBytes + 1, new byte[0]);
        assertThrows(RuntimeException.class, () -> NetworkIO.writeAssets(new ByteArrayOutputStream(), Seq.with(new FileAsset(oversizedNetwork))));
        assertFalse(oversizedNetwork.readCalled);
        Seq<DataAsset> aggregate = new Seq<>();
        Seq<MetadataFile> files = new Seq<>();
        for(int i = 0; i < 5; i++){
            var file = new MetadataFile(NetworkIO.maxAssetBytes, new byte[0]);
            files.add(file);
            aggregate.add(new FileAsset(file));
        }
        assertThrows(RuntimeException.class, () -> NetworkIO.writeAssets(new ByteArrayOutputStream(), aggregate));
        for(var file : files) assertFalse(file.readCalled);
        var changed = new MetadataFile(1, new byte[]{1, 2});
        assertThrows(IOException.class, () -> DataAsset.readFileBytes(changed, NetworkIO.maxAssetBytes));
        var shortFile = new MetadataFile(2, new byte[]{1});
        assertThrows(IOException.class, () -> DataAsset.readFileBytes(shortFile, NetworkIO.maxAssetBytes));
        var small = new MetadataFile(2, new byte[]{1, 2});
        var output = new ByteArrayOutputStream();
        NetworkIO.writeAssets(output, Seq.with(new FileAsset(small)));
        NetworkIO.loadAssets(new ByteArrayInputStream(output.toByteArray()), output.size());
        assertArrayEquals(new byte[]{1, 2}, cache.values.peek());
    }

    @Test
    void bundleByteLimitKeepsPropertyEscapes() throws IOException{
        var tooLarge = new MetadataFile((long)SaveFileReader.maxChunkBytes + 1, new byte[0]);
        var bundle = new BundleAsset(){
            @Override
            public Fi getCacheFile(){
                return tooLarge;
            }
        };
        bundle.tryLoadCache();
        assertFalse(tooLarge.readCalled);
        byte[] bytes = "key\\ with\\ spaces=line\\nvalue\ncontinued=first\\\n second\nunicode=\\u6f22\n".getBytes(Strings.utf8);
        var valid = new MetadataFile(bytes.length, bytes);
        var validBundle = new BundleAsset(){
            @Override
            public Fi getCacheFile(){
                return valid;
            }
        };
        validBundle.tryLoadCache();
        assertEquals("line\nvalue", validBundle.cachedBundle.get("key with spaces"));
        assertEquals("firstsecond", validBundle.cachedBundle.get("continued"));
        assertEquals("\u6f22", validBundle.cachedBundle.get("unicode"));
    }

    @Test
    void streamBuilderRejectsOvershootAndIncompleteBuild() throws IOException{
        var builder = new StreamBuilder(begin(WorldStream.class, 2), false);
        assertThrows(IllegalStateException.class, builder::build);
        assertThrows(IllegalArgumentException.class, () -> builder.add(new byte[3]));
        assertEquals(0, builder.received);
        builder.add(new byte[]{1});
        builder.add(new byte[0]);
        assertFalse(builder.isDone());
        assertThrows(IllegalArgumentException.class, () -> builder.add(new byte[2]));
        builder.add(new byte[]{2});
        assertTrue(builder.isDone());
        assertArrayEquals(new byte[]{1, 2}, builder.build().stream.readAllBytes());
        assertThrows(IllegalArgumentException.class, () -> builder.add(new byte[]{3}));
        builder.close();
        assertThrows(IllegalStateException.class, builder::build);
        var empty = new StreamBuilder(begin(WorldStream.class, 0), false);
        assertEquals(1f, empty.progress());
        assertTrue(empty.isDone());
        assertEquals(0, empty.build().stream.available());
        empty.close();
    }

    @Test
    void streamTypesAndAdvertisedBudgetsAreValidated(){
        for(StreamBegin invalid : new StreamBegin[]{begin(WorldStream.class, -1), begin(WorldStream.class, Integer.MAX_VALUE),
            begin(TextureStream.class, Streamable.maxTextureStreamBytes + 1),
            begin(AssetRequirementStream.class, Streamable.maxRequiredAssetStreamBytes + 1), begin(ConnectPacket.class, 0)}){
            assertThrows(IllegalArgumentException.class, () -> Streamable.validateStreamBegin(invalid));
            assertThrows(IllegalArgumentException.class, () -> new StreamBuilder(invalid, false));
        }
        assertDoesNotThrow(() -> Streamable.validateStreamBegin(begin(TextureStream.class, Streamable.maxTextureStreamBytes)));
        assertDoesNotThrow(() -> Streamable.validateStreamBegin(begin(WorldStream.class, Streamable.maxStreamBytes)));
    }

    @Test
    void incrementalStreamsCoalesceDrainFinishAndCancel() throws Exception{
        var builder = new StreamBuilder(begin(AssetStream.class, 3), true);
        InputStream input = builder.incrementalStream;
        builder.add(new byte[]{1});
        builder.add(new byte[]{2});
        builder.add(new byte[]{3});
        assertThrows(IllegalStateException.class, builder::build);
        finish(builder);
        assertArrayEquals(new byte[]{1, 2, 3}, input.readAllBytes());
        assertEquals(-1, input.read());
        input.close();
        assertThrows(ClosedChannelException.class, input::read);
        var cancelled = new StreamBuilder(begin(AssetStream.class, 1), true);
        cancelled.close();
        assertThrows(ClosedChannelException.class, ((InputStream)cancelled.incrementalStream)::read);
        var empty = new StreamBuilder(begin(AssetStream.class, 0), true);
        finish(empty);
        assertEquals(-1, ((InputStream)empty.incrementalStream).read());
        empty.close();
    }

    @Test
    void netCompletesZeroAndDisconnectsOnInvalidFraming(){
        network.handleClientReceived(begin(WorldStream.class, 0));
        assertEquals(1, network.completed);
        assertNull(network.getCurrentStream());
        assertNull(network.failure);
        var start = begin(WorldStream.class, 1);
        network.handleClientReceived(start);
        var chunk = new StreamChunk();
        chunk.id = start.id;
        chunk.data = new byte[2];
        assertDoesNotThrow(() -> network.handleClientReceived(chunk));
        assertNotNull(network.failure);
        assertFalse(network.active());
    }

    @Test
    void netCapsActiveStreamsAndAggregateWithoutAllocatingBodies(){
        for(int i = 0; i < Net.maxActiveStreams; i++) network.handleClientReceived(begin(WorldStream.class, 1));
        assertNull(network.failure);
        network.handleClientReceived(begin(WorldStream.class, 1));
        assertNotNull(network.failure);
        network.failure = null;
        network.setClientConnected();
        network.handleClientReceived(begin(WorldStream.class, Streamable.maxStreamBytes));
        assertNull(network.failure);
        network.handleClientReceived(begin(WorldStream.class, 1));
        assertNotNull(network.failure);
    }

    @Test
    void replacingAndDisconnectingBuildersCancelsReaders() throws IOException{
        var first = begin(AssetStream.class, 1);
        network.handleClientReceived(first);
        var replacement = begin(WorldStream.class, 1);
        replacement.id = first.id;
        network.handleClientReceived(replacement);
        assertThrows(ClosedChannelException.class, first.incrementalStream::read);
        assertNull(network.failure);
        var pending = begin(AssetStream.class, 1);
        network.handleClientReceived(pending);
        network.disconnect();
        assertThrows(ClosedChannelException.class, pending.incrementalStream::read);
    }

    @Test
    void completedIncrementalQueuesStillCountUntilClosed() throws IOException{
        InputStream first = null;
        for(int i = 0; i < Net.maxActiveStreams; i++){
            var start = begin(AssetStream.class, 1);
            network.handleClientReceived(start);
            if(i == 0) first = start.incrementalStream;
            var chunk = new StreamChunk();
            chunk.id = start.id;
            chunk.data = new byte[]{1};
            network.handleClientReceived(chunk);
        }
        assertNull(network.failure);
        first.close();
        app.drain();
        network.handleClientReceived(begin(WorldStream.class, 1));
        assertNull(network.failure, "Closing a completed reader releases its advertised budget");
        network.handleClientReceived(begin(WorldStream.class, 1));
        assertNotNull(network.failure, "Other completed but unread queues still count");
    }

    @Test
    void netRejectsMalformedBeginsAndOrphanChunksWithoutThrowing(){
        for(StreamBegin invalid : new StreamBegin[]{begin(WorldStream.class, -1), begin(ConnectPacket.class, 0),
            begin(TextureStream.class, Streamable.maxTextureStreamBytes + 1)}){
            network.failure = null;
            network.setClientConnected();
            assertDoesNotThrow(() -> network.handleClientReceived(invalid));
            assertNotNull(network.failure);
            assertFalse(network.active());
        }
        network.failure = null;
        var orphan = new StreamChunk();
        orphan.id = -1;
        orphan.data = new byte[]{1};
        assertDoesNotThrow(() -> network.handleClientReceived(orphan));
        assertNotNull(network.failure);
    }

    @Test
    void modifiedUtfTextureNameFramingRemainsSupported() throws IOException{
        String name = "\u6f22".repeat(65535 / 3);
        var output = new ByteArrayOutputStream();
        NetworkIO.packTexture(output, name, new byte[0]);
        assertEquals(65535 + 6, output.size());
        assertEquals(NetworkIO.maxAssetBytes + output.size(), Streamable.maxTextureStreamBytes);
        try(var input = input(output.toByteArray())){
            assertEquals(name, input.readUTF());
            assertEquals(0, input.readInt());
            assertEquals(-1, input.read());
        }
    }

    @Test
    void textureZeroMeansRemovalAndMalformedLengthsDoNotRemove() throws IOException{
        var data = new RecordingData();
        Vars.state.data = data;
        Vars.headless = false;
        var bytes = new ByteArrayOutputStream();
        NetworkIO.packTexture(bytes, "test", new byte[0]);
        TextureStream removal = new TextureStream();
        removal.stream = new ByteArrayInputStream(bytes.toByteArray());
        network.handleClientReceived(removal);
        assertEquals("test", data.removed);
        assertNull(network.failure);
        data.removed = null;
        for(int length : new int[]{-1, Integer.MAX_VALUE, 1, 0}){
            byte[] malformed = encoded(out -> {
                out.writeUTF("test");
                out.writeInt(length);
                if(length == 0) out.writeByte(1); //surplus after a removal sentinel
            });
            TextureStream packet = new TextureStream();
            packet.stream = new ByteArrayInputStream(malformed);
            network.handleClientReceived(packet);
            assertNull(data.removed);
            assertNotNull(network.failure);
            network.failure = null;
        }
    }

    @Test
    void pngEnvelopeAndDimensionsRejectHostileHeaders() throws IOException{
        byte[] valid = png(1, 1, 8, 6, 0, new byte[]{0, 1, 2, 3, (byte)255});
        MapIO.validatePng(valid, 2000);
        for(int length : new int[]{0, 8, 16, 32, valid.length - 1}){
            assertThrows(IOException.class, () -> MapIO.validatePng(Arrays.copyOf(valid, length), 2000));
        }
        for(int[] size : new int[][]{{0, 1}, {1, 0}, {-1, 1}, {2001, 1}, {1, 2001}, {Integer.MAX_VALUE, Integer.MAX_VALUE}}){
            byte[] hostile = valid.clone();
            ByteBuffer.wrap(hostile).putInt(16, size[0]).putInt(20, size[1]);
            assertThrows(IOException.class, () -> MapIO.validatePng(hostile, 2000));
        }
        byte[] overflowDimensions = valid.clone();
        ByteBuffer.wrap(overflowDimensions).putInt(16, Integer.MAX_VALUE).putInt(20, Integer.MAX_VALUE);
        assertThrows(IOException.class, () -> MapIO.validatePng(overflowDimensions, Integer.MAX_VALUE));
        byte[] duplicate = concat(Arrays.copyOf(valid, 33), Arrays.copyOfRange(valid, 8, 33), Arrays.copyOfRange(valid, 33, valid.length));
        assertThrows(IOException.class, () -> MapIO.validatePng(duplicate, 2000));
        for(int length : new int[]{Integer.MAX_VALUE, -1}){
            byte[] chunkOverflow = valid.clone();
            ByteBuffer.wrap(chunkOverflow).putInt(33, length);
            assertThrows(IOException.class, () -> MapIO.validatePng(chunkOverflow, 2000));
        }
        byte[] wrongIhdr = valid.clone();
        ByteBuffer.wrap(wrongIhdr).putInt(8, 12);
        assertThrows(IOException.class, () -> MapIO.validatePng(wrongIhdr, 2000));
        byte[] wrongSignature = valid.clone();
        wrongSignature[0] = 0;
        assertThrows(IOException.class, () -> MapIO.validatePng(wrongSignature, 2000));
        assertThrows(IOException.class, () -> MapIO.validatePng(concat(valid, new byte[]{0}), 2000));
        byte[] editorOversized = valid.clone();
        ByteBuffer.wrap(editorOversized).putInt(16, 801);
        assertThrows(IOException.class, () -> MapIO.validatePng(editorOversized, 800));
    }

    @Test
    void tinyPngFormatsContinueUsingNativeDecoder() throws IOException{
        ArcNativesLoader.load();
        byte[][] formats = {
            png(1, 1, 8, 6, 0, new byte[]{0, 1, 2, 3, (byte)255}),
            png(1, 1, 8, 0, 0, new byte[]{0, 100}),
            png(1, 1, 16, 0, 0, new byte[]{0, 100, 0}),
            //At 1x1 Adam7 has only its first nonempty pass.
            png(1, 1, 8, 2, 1, new byte[]{0, 1, 2, 3})
        };
        for(byte[] bytes : formats){
            MapIO.validatePng(bytes, 2000);
            Pixmap pixmap = new Pixmap(bytes);
            try{
                assertEquals(1, pixmap.width);
                assertEquals(1, pixmap.height);
            }finally{
                pixmap.dispose();
            }
        }
    }

    private static StreamBegin begin(Class<? extends Packet> type, int total){
        var begin = new StreamBegin();
        begin.type = Net.getPacketClassId(type);
        begin.total = total;
        return begin;
    }

    //IncrementalStream intentionally remains package-private; only its existing public finish API is invoked.
    private static void finish(StreamBuilder builder) throws Exception{
        var method = ((InputStream)builder.incrementalStream).getClass().getMethod("finish");
        method.setAccessible(true);
        method.invoke(builder.incrementalStream);
    }

    private static DataInputStream input(byte[] bytes){
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }

    private static byte[] ints(int... values) throws IOException{
        return encoded(out -> {
            for(int value : values) out.writeInt(value);
        });
    }

    private static byte[] encoded(IOWriter writer) throws IOException{
        var bytes = new ByteArrayOutputStream();
        try(var output = new DataOutputStream(bytes)){
            writer.write(output);
        }
        return bytes.toByteArray();
    }

    private static byte[] concat(byte[]... parts) throws IOException{
        var output = new ByteArrayOutputStream();
        for(byte[] part : parts) output.write(part);
        return output.toByteArray();
    }

    private static byte[] png(int width, int height, int depth, int color, int interlace, byte[] scanline) throws IOException{
        return encoded(out -> {
            out.writeLong(0x89504e470d0a1a0aL);
            chunk(out, "IHDR", encoded(header -> {
                header.writeInt(width);
                header.writeInt(height);
                header.writeByte(depth);
                header.writeByte(color);
                header.writeByte(0);
                header.writeByte(0);
                header.writeByte(interlace);
            }));
            var compressed = new ByteArrayOutputStream();
            try(var deflater = new DeflaterOutputStream(compressed)){
                deflater.write(scanline);
            }
            chunk(out, "IDAT", compressed.toByteArray());
            chunk(out, "IEND", new byte[0]);
        });
    }

    private static void chunk(DataOutputStream out, String name, byte[] bytes) throws IOException{
        byte[] type = name.getBytes(Strings.utf8);
        var crc = new CRC32();
        crc.update(type);
        crc.update(bytes);
        out.writeInt(bytes.length);
        out.write(type);
        out.write(bytes);
        out.writeInt((int)crc.getValue());
    }

    private interface IOWriter{
        void write(DataOutputStream out) throws IOException;
    }

    private static class RecordingCache extends DataAssetCache{
        final Seq<byte[]> values = new Seq<>();

        @Override
        public synchronized byte[] add(byte[] bytes){
            values.add(bytes.clone());
            return Streams.sha256(bytes);
        }
    }

    private static class MetadataFile extends Fi{
        final long size;
        final byte[] contents;
        boolean readCalled;

        MetadataFile(long size, byte[] contents){
            super("metadata-only-asset");
            this.size = size;
            this.contents = contents;
        }

        @Override
        public long length(){
            return size;
        }

        @Override
        public boolean exists(){
            return true;
        }

        @Override
        public InputStream read(){
            readCalled = true;
            return new ByteArrayInputStream(contents);
        }
    }

    private static class FileAsset extends ImageAsset{
        final Fi file;

        FileAsset(Fi file){
            this.file = file;
        }

        @Override
        public Fi getCacheFile(){
            return file;
        }
    }

    private static class RecordingData extends DataManager{
        String removed;

        @Override
        public void removeTexture(String name){
            removed = name;
        }
    }

    private static class DeferredApplication extends MockApplication{
        final Seq<Runnable> posted = new Seq<>();

        @Override
        public void post(Runnable runnable){
            posted.add(runnable);
        }

        void drain(){
            while(!posted.isEmpty()){
                var pending = posted.copy();
                posted.clear();
                pending.each(Runnable::run);
            }
        }
    }

    private static class TestNet extends Net{
        Throwable failure;
        int completed;

        TestNet(){
            super(new TestProvider());
        }

        @Override
        public void handleException(Throwable error){
            failure = error;
        }
    }

    private static class TestProvider implements Net.NetProvider{
        @Override public void connectClient(String ip, int port, Runnable success){ success.run(); }
        @Override public void sendClient(Object object, boolean reliable){}
        @Override public void disconnectClient(){}
        @Override public void discoverServers(arc.func.Cons<Host> cons, Runnable done){ done.run(); }
        @Override public void pingHost(String address, int port, arc.func.Cons<Host> valid, arc.func.Cons<Exception> failed){}
        @Override public void hostServer(int port){}
        @Override public Iterable<NetConnection> getConnections(){ return new Seq<>(); }
        @Override public void sendAllServer(Object object, boolean reliable){}
        @Override public void sendExceptServer(NetConnection except, Object object, boolean reliable){}
        @Override public void closeServer(){}
    }
}
