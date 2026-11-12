import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import arc.util.serialization.*;
import mindustry.core.*;
import mindustry.ctype.*;
import mindustry.entities.abilities.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.logic.*;
import mindustry.net.*;
import mindustry.net.ArcNetProvider.*;
import mindustry.net.Packets.*;
import mindustry.world.*;
import net.jpountz.lz4.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

public class PacketBoundsTests{
    private static final int marker = 0x12345678;
    private Net previousNet;

    @BeforeAll
    static void launch(){
        ApplicationTests.launchApplication(false);
    }

    @BeforeEach
    void clientEndpoint(){
        previousNet = net;
        net = new Net(null);
    }

    @AfterEach
    void restoreEndpoint(){
        net = previousNet;
    }

    @Test
    void tinyHeadersAndInvalidFlagsAreRejected(){
        var serializer = new PacketSerializer();
        byte id = Net.getPacketId(new StreamChunk());
        assertNull(serializer.read(ByteBuffer.allocate(0)));
        assertNull(serializer.read(ByteBuffer.allocate(3).position(3)));
        for(byte[] header : new byte[][]{{id}, {id, 0}, {id, 0, 6}, {-2}, {-2, 0}, {-2, 3}}){
            assertThrows(ArcRuntimeException.class, () -> serializer.read(ByteBuffer.wrap(header)));
        }
        assertThrows(ArcRuntimeException.class, () -> serializer.read(frame(6, 2, new byte[6])));
        assertThrows(ArcRuntimeException.class, () -> serializer.read(frame(32769, 0, new byte[0])));
        assertThrows(ArcRuntimeException.class, () -> serializer.read(frame(32769, 1, new byte[0])));
        assertThrows(ArcRuntimeException.class, () -> serializer.read(frame(6, 0, new byte[5])));
        assertThrows(ArcRuntimeException.class, () -> serializer.read(frame(6, 0, new byte[7])));
        assertThrows(RuntimeException.class, () -> serializer.read(frame(6, 1, new byte[0])));
        assertThrows(RuntimeException.class, () -> serializer.read(frame(6, 1, new byte[]{0x60, 0, 0})));
    }

    @Test
    void decompressionCannotUsePrimedTrailingBytes(){
        var serializer = new PacketSerializer();
        byte[] large = chunkBody(new byte[512]);
        assertEquals(512, ((StreamChunk)serializer.read(frame(large.length, 1, compress(large)))).data.length);
        byte[] empty = chunkBody(new byte[0]);
        assertEquals(0, ((StreamChunk)serializer.read(frame(empty.length, 1, compress(empty)))).data.length);
        byte[] larger = chunkBody(new byte[1024]);
        assertEquals(1024, ((StreamChunk)serializer.read(frame(larger.length, 1, compress(larger)))).data.length);
        assertThrows(ArcRuntimeException.class, () -> serializer.read(frame(empty.length + 3, 1, compress(empty))));
        byte[] missingData = bytes(w -> { w.i(7); w.s(3); });
        assertThrows(ArcRuntimeException.class, () -> serializer.read(frame(missingData.length, 1, compress(missingData))));
        assertThrows(ArcRuntimeException.class, () -> serializer.read(frame(9, 0, empty)));
        byte[] encoded = compress(larger);
        assertThrows(RuntimeException.class, () -> serializer.read(frame(larger.length, 1, Arrays.copyOf(encoded, encoded.length - 1))));
    }

    @Test
    void serializerHonorsPositionLimitSlicesAndDirectBuffers(){
        byte[] data = {11, 22, 33, 44};
        byte[] body = chunkBody(data);
        for(int compression : new int[]{0, 1}){
            ByteBuffer source = frame(body.length, compression, compression == 0 ? body : compress(body));
            byte[] encoded = new byte[source.remaining()];
            source.get(encoded);
            for(boolean direct : new boolean[]{false, true}){
                ByteBuffer backing = direct ? ByteBuffer.allocateDirect(encoded.length + 20) : ByteBuffer.allocate(encoded.length + 20);
                backing.position(9);
                backing.put(encoded);
                backing.limit(9 + encoded.length).position(3);
                ByteBuffer slice = backing.slice();
                slice.position(6);
                for(ByteBuffer view : new ByteBuffer[]{backing.duplicate().position(9), slice.duplicate(), slice.asReadOnlyBuffer()}){
                    int limit = view.limit();
                    StreamChunk result = (StreamChunk)new PacketSerializer().read(view);
                    assertEquals(7, result.id);
                    assertArrayEquals(data, result.data);
                    assertEquals(limit, view.position());
                }
            }
        }
    }

    @Test
    void streamChunkFullDecodedBodyRoundTripsAndRejectsSmallTransport(){
        var packet = new StreamChunk();
        packet.id = 7;
        packet.data = new byte[32762];
        packet.data[32761] = 42;
        var serializer = new PacketSerializer();
        ByteBuffer buffer = ByteBuffer.allocate(32772);
        serializer.write(buffer, packet);
        assertEquals(32772, buffer.position());
        buffer.flip();
        assertEquals(32768, buffer.getShort(1) & 0xffff);
        assertEquals(0, buffer.get(3));
        StreamChunk result = (StreamChunk)serializer.read(buffer);
        assertEquals(packet.id, result.id);
        assertArrayEquals(packet.data, result.data);
        for(int capacity : new int[]{16384, 25000}){
            ByteBuffer transport = ByteBuffer.allocate(capacity);
            assertThrows(ArcRuntimeException.class, () -> serializer.write(transport, packet));
            assertEquals(0, transport.position());
        }
        packet.data = new byte[32763];
        assertThrows(ArcRuntimeException.class, () -> packet.write(writes(ByteBuffer.allocate(8))));
        for(int count : new int[]{32763, 32768, 65535, 1}){
            assertThrows(ArcRuntimeException.class, () -> new StreamChunk().read(reads(bytes(w -> { w.i(7); w.s(count); }))));
        }
    }

    @Test
    void compressedDecoded32768IsNotLimitedToEncodedCapacity(){
        var packet = connectPacket();
        packet.name = "x".repeat(32736);
        for(int capacity : new int[]{16384, 25000}){
            ByteBuffer transport = ByteBuffer.allocate(capacity);
            var serializer = new PacketSerializer();
            serializer.write(transport, packet);
            transport.flip();
            assertEquals(32768, transport.getShort(1) & 0xffff);
            assertEquals(1, transport.get(3));
            assertTrue(transport.remaining() < capacity);
            ConnectPacket result = (ConnectPacket)serializer.read(transport);
            assertEquals(packet.name, result.name);
            assertEquals(0, result.mods.size);
        }
        assertThrows(ArcRuntimeException.class, () -> new PacketSerializer().write(ByteBuffer.allocate(32), packet));
        packet.name += "x";
        assertThrows(ArcRuntimeException.class, () -> new PacketSerializer().write(ByteBuffer.allocate(25000), packet));
    }

    @Test
    void connectModsAreUnsignedAndUuidCrcAlignmentIsUnchanged(){
        var packet = connectPacket();
        packet.mobile = true;
        packet.color = marker;
        for(int i = 0; i < 128; i++) packet.mods.add("m" + i);
        byte[] encoded = bytes(w -> { packet.write(w); w.i(marker); });
        Reads read = reads(encoded);
        var result = new ConnectPacket();
        result.read(read);
        assertEquals(128, result.mods.size);
        assertEquals("m127", result.mods.peek());
        assertTrue(result.mobile);
        assertEquals(marker, result.color);
        assertEquals(16, Base64Coder.decode(result.uuid).length);
        assertArrayEquals(Base64Coder.decode(packet.uuid), Arrays.copyOf(Base64Coder.decode(result.uuid), 8));
        assertEquals(marker, read.i());
        while(packet.mods.size < 256) packet.mods.add("m");
        assertThrows(ArcRuntimeException.class, () -> packet.write(writes(ByteBuffer.allocate(8))));
        byte[] headerOnly = bytes(w -> { connectPacket().write(w); });
        headerOnly[headerOnly.length - 1] = (byte)128;
        assertThrows(ArcRuntimeException.class, () -> new ConnectPacket().read(reads(headerOnly)));
    }

    @Test
    void streamBeginValidatesBeforeHandling(){
        assertThrows(RuntimeException.class, () -> new StreamBegin().read(reads(bytes(w -> {
            w.i(7); w.i(-1); w.b(Net.packetIdWorldStream);
        }))));
        assertThrows(RuntimeException.class, () -> new StreamBegin().read(reads(bytes(w -> {
            w.i(7); w.i(1); w.b(Net.getPacketId(new ConnectPacket()));
        }))));
        assertThrows(RuntimeException.class, () -> new StreamBegin().read(reads(bytes(w -> {
            w.i(7); w.i(Integer.MAX_VALUE); w.b(Net.packetIdWorldStream);
        }))));
        assertDoesNotThrow(() -> new StreamBegin().read(reads(bytes(w -> {
            w.i(7); w.i(1); w.b(Net.packetIdWorldStream);
        }))));
    }

    @Test
    void objectArrayCountsAreCheckedBeforeAllocation(){
        for(byte type : new byte[]{TypeIO.intSeqType, TypeIO.vec2ArrayType, TypeIO.intArrayType}){
            for(int count : new int[]{-1, -2, 1}){
                assertThrows(ArcRuntimeException.class, () -> TypeIO.readObjectSafe(reads(bytes(w -> { w.b(type); w.s(count); }))));
            }
        }
        for(byte type : new byte[]{TypeIO.byteArrayType, TypeIO.booleanArrayType, TypeIO.objectArrayType}){
            for(int count : new int[]{-1, -2, 1, Integer.MAX_VALUE}){
                assertThrows(ArcRuntimeException.class, () -> TypeIO.readObjectSafe(reads(bytes(w -> { w.b(type); w.i(count); }))));
            }
        }
        assertThrows(ArcRuntimeException.class, () -> TypeIO.readObjectSafe(reads(new byte[]{TypeIO.point2ArrayType, (byte)128})));
    }

    @Test
    void intObjectsRespectNestedArrayPolicyAndEstablishedLimits(){
        byte[] nested = bytes(w -> { w.b(TypeIO.objectArrayType); w.i(1); w.b(TypeIO.intArrayType); w.s(0); });
        assertThrows(RuntimeException.class, () -> TypeIO.readObjectSafe(reads(nested)));
        assertThrows(RuntimeException.class, () -> TypeIO.readObject(reads(bytes(w -> { w.b(TypeIO.intArrayType); w.s(0); })), false, null, true, false));
        for(boolean safe : new boolean[]{false, true}){
            int max = safe ? 1000 : 200;
            byte[] valid = bytes(w -> { w.b(TypeIO.intArrayType); w.s(max); for(int i = 0; i < max; i++) w.i(i); });
            assertEquals(max, ((int[])TypeIO.readObject(reads(valid), false, null, safe)).length);
            byte[] invalid = bytes(w -> { w.b(TypeIO.intArrayType); w.s(max + 1); });
            assertThrows(ArcRuntimeException.class, () -> TypeIO.readObject(reads(invalid), false, null, safe));
        }
    }

    @Test
    void nullableCountsKeepOnlyMinusOneSentinel() throws IOException{
        assertNull(TypeIO.readPlans(reads(bytes(w -> w.s(-1)))));
        assertNull(TypeIO.readPlansQueue(reads(bytes(w -> w.i(-1)))));
        assertNull(TypeIO.readPlansQueueNet(reads(bytes(w -> w.i(-1)))));
        assertNull(TypeIO.readString(ByteBuffer.wrap(bytes(w -> w.s(-1)))));
        assertNull(TypeIO.readStringData(new ByteBufferInput(ByteBuffer.wrap(bytes(w -> w.s(-1))))));
        for(int count : new int[]{-2, 1}){
            byte[] shortCount = bytes(w -> w.s(count));
            byte[] intCount = bytes(w -> w.i(count));
            assertThrows(ArcRuntimeException.class, () -> TypeIO.readPlans(reads(shortCount)));
            assertThrows(ArcRuntimeException.class, () -> TypeIO.readPlansQueue(reads(intCount)));
            assertThrows(ArcRuntimeException.class, () -> TypeIO.readPlansQueueNet(reads(intCount)));
            assertThrows(ArcRuntimeException.class, () -> TypeIO.readClientPlans(reads(shortCount)));
            assertThrows(ArcRuntimeException.class, () -> TypeIO.readString(ByteBuffer.wrap(shortCount)));
            assertThrows(IOException.class, () -> TypeIO.readStringData(new ByteBufferInput(ByteBuffer.wrap(shortCount))));
        }
        assertThrows(ArcRuntimeException.class, () -> TypeIO.readClientPlans(reads(bytes(w -> w.s(-1)))));
    }

    @Test
    void primitiveAndJsonCountsRejectTinyPayloadsBeforeAllocation(){
        for(int count : new int[]{-1, -2, 1, 1_000_001, Integer.MAX_VALUE}){
            byte[] encoded = bytes(w -> w.i(count));
            assertThrows(ArcRuntimeException.class, () -> TypeIO.readIntSeq(reads(encoded)));
            assertThrows(ArcRuntimeException.class, () -> TypeIO.readRules(reads(encoded)));
            assertThrows(ArcRuntimeException.class, () -> TypeIO.readObjectives(reads(encoded)));
            assertThrows(ArcRuntimeException.class, () -> TypeIO.readObjectiveMarker(reads(encoded)));
        }
        for(int count : new int[]{-1, -2, 1, Short.MAX_VALUE}){
            byte[] encoded = bytes(w -> w.s(count));
            assertThrows(ArcRuntimeException.class, () -> TypeIO.readBytes(reads(encoded)));
            assertThrows(ArcRuntimeException.class, () -> TypeIO.readInts(reads(encoded)));
            assertThrows(ArcRuntimeException.class, () -> TypeIO.readShorts(reads(encoded)));
            assertThrows(ArcRuntimeException.class, () -> TypeIO.readItemStacks(reads(encoded)));
            assertThrows(ArcRuntimeException.class, () -> TypeIO.readLiquidStacks(reads(encoded)));
        }
        byte[] tooLarge = bytes(w -> w.i(1_000_001));
        assertThrows(ArcRuntimeException.class, () -> TypeIO.readIntSeq(new Reads(new DataInputStream(new ByteArrayInputStream(tooLarge)))));
    }

    @Test
    void boundedPacketIntSeqAndLongerSaveSequencesUseDifferentByteBudgets(){
        byte[] packetBody = bytes(w -> { w.i(8191); for(int i = 0; i < 8191; i++) w.i(i); });
        assertEquals(32768, packetBody.length);
        assertEquals(8191, TypeIO.readIntSeq(reads(packetBody)).size);
        ByteBuffer.wrap(packetBody).putInt(8192);
        assertThrows(ArcRuntimeException.class, () -> TypeIO.readIntSeq(reads(packetBody)));
        byte[] saveBody = bytes(w -> { w.i(9000); for(int i = 0; i < 9000; i++) w.i(i); });
        assertEquals(9000, TypeIO.readIntSeq(reads(saveBody)).size);
        IntSeq saved = TypeIO.readIntSeq(new Reads(new DataInputStream(new ByteArrayInputStream(saveBody))));
        assertEquals(9000, saved.size);
        assertEquals(8999, saved.peek());
        byte[] ints = bytes(w -> { w.s(9000); for(int i = 0; i < 9000; i++) w.i(i); });
        assertEquals(9000, TypeIO.readInts(reads(ints)).length);
    }

    @Test
    void saveChunkBoundsPreventReadingFollowingRecord() throws IOException{
        byte[] tinyChunk = bytes(w -> { w.i(4); w.i(1); w.i(marker); });
        var input = new DataInputStream(new ByteArrayInputStream(tinyChunk));
        assertThrows(ArcRuntimeException.class, () -> SaveIO.getSaveWriter().readChunkReads(input, (read, length) -> TypeIO.readIntSeq(read)));
        assertEquals(marker, input.readInt());
        byte[] largeChunk = bytes(w -> {
            w.i(4 + 9000 * 4); w.i(9000); for(int i = 0; i < 9000; i++) w.i(i); w.i(marker);
        });
        var saved = new DataInputStream(new ByteArrayInputStream(largeChunk));
        SaveIO.getSaveWriter().readChunkReads(saved, (read, length) -> assertEquals(9000, TypeIO.readIntSeq(read).size));
        assertEquals(marker, saved.readInt());
    }

    @Test
    void mountAndAbilityCountsAreUnsignedAndSkipsConsumeFullPayload(){
        for(int count : new int[]{1, 128, 255}){
            byte[] abilities = bytes(w -> { w.b(count); for(int i = 0; i < count; i++) w.f(i + 0.5f); w.i(marker); });
            Reads skipped = reads(abilities);
            assertEquals(0, TypeIO.readAbilities(skipped).length);
            assertEquals(marker, skipped.i());
            Reads consumed = reads(abilities);
            Ability[] result = TypeIO.readAbilities(consumed, new Ability[0]);
            assertEquals(count, result.length);
            assertEquals(count - 0.5f, result[count - 1].data);
            assertEquals(marker, consumed.i());
            byte[] mounts = bytes(w -> { w.b(count); for(int i = 0; i < count; i++){ w.b(3); w.f(i); w.f(i + 1); } w.i(marker); });
            Reads mountSkip = reads(mounts);
            TypeIO.readMounts(mountSkip);
            assertEquals(marker, mountSkip.i());
            Reads mountRead = reads(mounts);
            TypeIO.readMounts(mountRead, new WeaponMount[0]);
            assertEquals(marker, mountRead.i());
        }
        for(int count : new int[]{1, 128, 255}){
            byte[] tiny = {(byte)count};
            assertThrows(ArcRuntimeException.class, () -> TypeIO.readAbilities(reads(tiny)));
            assertThrows(ArcRuntimeException.class, () -> TypeIO.readAbilities(reads(tiny), new Ability[0]));
            assertThrows(ArcRuntimeException.class, () -> TypeIO.readMounts(reads(tiny)));
            assertThrows(ArcRuntimeException.class, () -> TypeIO.readMounts(reads(tiny), new WeaponMount[0]));
        }
        assertThrows(ArcRuntimeException.class, () -> TypeIO.writeAbilities(writes(ByteBuffer.allocate(1)), new Ability[256]));
        assertThrows(ArcRuntimeException.class, () -> TypeIO.writeMounts(writes(ByteBuffer.allocate(1)), new WeaponMount[256]));
    }

    @Test
    void invalidPlacementConsumesConfigBeforeFollowingRecord(){
        Tiles previousTiles = world.tiles;
        try{
            world.tiles = new Tiles(1, 1);
            world.tiles.set(0, 0, new Tile(0, 0));
            byte[] records = bytes(w -> {
                w.b(0); w.i(-1); w.s(-1); w.b(0); w.b(1); TypeIO.writeObject(w, new int[]{1, 2});
                w.b(0); w.i(0); w.s(-1); w.b(0); w.b(1); TypeIO.writeObject(w, 42);
                w.b(1); w.i(0); w.i(marker);
            });
            Reads read = reads(records);
            assertNull(TypeIO.readPlan(read));
            BuildPlan missingBlock = TypeIO.readPlan(read);
            assertNotNull(missingBlock);
            assertNull(missingBlock.block);
            assertEquals(42.0, missingBlock.config);
            assertTrue(TypeIO.readPlan(read).breaking);
            assertEquals(marker, read.i());
            assertThrows(ArcRuntimeException.class, () -> TypeIO.readPlan(reads(bytes(w -> { w.b(2); w.i(0); }))));
        }finally{
            world.tiles = previousTiles;
        }
    }

    @Test
    void enumBoundsAllowLastOrdinalAndAllTeamBytes(){
        assertByteEnum(KickReason.all, TypeIO::readKick);
        assertByteEnum(LMarkerControl.all, TypeIO::readMarkerControl);
        assertByteEnum(AdminAction.all, TypeIO::readAction);
        assertSame(LAccess.all[LAccess.all.length - 1], TypeIO.readObject(reads(bytes(w -> { w.b(TypeIO.lAccessType); w.s(LAccess.all.length - 1); }))));
        for(int ordinal : new int[]{-1, LAccess.all.length}){
            assertThrows(ArcRuntimeException.class, () -> TypeIO.readObject(reads(bytes(w -> { w.b(TypeIO.lAccessType); w.s(ordinal); }))));
        }
        int last = ContentType.all.length - 1;
        assertNull(TypeIO.readContent(reads(bytes(w -> { w.b(last); w.s(-1); }))));
        ContentType[] observed = {null};
        assertNull(TypeIO.readObject(reads(bytes(w -> { w.b(TypeIO.contentType); w.b(last); w.s(-1); })), false, (type, id) -> { observed[0] = type; return null; }));
        assertSame(ContentType.all[last], observed[0]);
        assertNull(TypeIO.readObject(reads(bytes(w -> { w.b(TypeIO.techNodeType); w.b(last); w.s(-1); }))));
        byte[] lastContentPlan = bytes(w -> {
            w.s(1); w.i(0); w.s(mindustry.content.Blocks.air.id); w.b(TypeIO.contentType); w.b(last); w.s(-1);
        });
        assertNull(TypeIO.readClientPlans(reads(lastContentPlan)).first().config);
        for(int ordinal : new int[]{ContentType.all.length, 255}){
            assertThrows(ArcRuntimeException.class, () -> TypeIO.readContent(reads(bytes(w -> { w.b(ordinal); w.s(0); }))));
            for(byte type : new byte[]{TypeIO.contentType, TypeIO.techNodeType}){
                assertThrows(ArcRuntimeException.class, () -> TypeIO.readObject(reads(bytes(w -> { w.b(type); w.b(ordinal); w.s(0); }))));
            }
            byte[] clientPlan = bytes(w -> { w.s(1); w.i(0); w.s(mindustry.content.Blocks.air.id); w.b(TypeIO.contentType); w.b(ordinal); w.s(0); });
            assertThrows(ArcRuntimeException.class, () -> TypeIO.readClientPlans(reads(clientPlan)));
        }
        for(int id : new int[]{0, 127, 128, 255}){
            assertSame(Team.all[id], TypeIO.readTeam(reads(new byte[]{(byte)id})));
            assertSame(Team.all[id], TypeIO.readObject(reads(new byte[]{TypeIO.teamType, (byte)id})));
        }
    }

    @Test
    void stringDimensionsStayUnsignedRaggedAndRejectWriterOverflow(){
        String[] many = new String[128];
        Arrays.fill(many, "m");
        assertArrayEquals(many, TypeIO.readStrings(reads(bytes(w -> TypeIO.writeStrings(w, many)))));
        String[][] ragged = {many, {"one"}, {}, {null, "two"}};
        String[][] decoded = TypeIO.readStringArray(reads(bytes(w -> TypeIO.writeStringArray(w, ragged))));
        assertEquals(ragged.length, decoded.length);
        for(int i = 0; i < ragged.length; i++) assertArrayEquals(ragged[i], decoded[i]);
        String[][] rows = new String[128][];
        Arrays.fill(rows, new String[0]);
        assertEquals(128, TypeIO.readStringArray(reads(bytes(w -> TypeIO.writeStringArray(w, rows)))).length);
        assertThrows(ArcRuntimeException.class, () -> TypeIO.writeStrings(writes(ByteBuffer.allocate(1)), new String[256]));
        assertThrows(ArcRuntimeException.class, () -> TypeIO.writeStrings(writes(ByteBuffer.allocate(1)), new String[256], 256));
        assertEquals(12, TypeIO.readStrings(reads(bytes(w -> TypeIO.writeStrings(w, new String[256], 12)))).length);
        assertThrows(ArcRuntimeException.class, () -> TypeIO.writeStringArray(writes(ByteBuffer.allocate(1)), new String[256][]));
        assertThrows(ArcRuntimeException.class, () -> TypeIO.writeStringArray(writes(ByteBuffer.allocate(1)), new String[][]{new String[256]}));
    }

    @Test
    void unitFactoriesAreCheckedAndMissingReferencesRemainNullable(){
        int missing = -1, wrong = -1;
        for(int i = 0; i < EntityMapping.idMap.length; i++){
            if(EntityMapping.idMap[i] == null){
                missing = i;
            }else if(!(EntityMapping.idMap[i].get() instanceof Unit)){
                wrong = i;
            }
            if(missing >= 0 && wrong >= 0) break;
        }
        assertTrue(missing >= 0);
        assertTrue(wrong >= 0);
        for(int type : new int[]{missing, wrong}){
            assertThrows(ArcRuntimeException.class, () -> TypeIO.readUnitContainer(reads(bytes(w -> { w.i(-2); w.b(type); }))));
        }
        assertNull(TypeIO.readUnit(reads(bytes(w -> { w.b(0); w.i(0); }))));
        assertNull(TypeIO.readUnit(reads(bytes(w -> { w.b(2); w.i(-2); }))));
        assertNull(TypeIO.readUnit(reads(bytes(w -> { w.b(1); w.i(-1); }))));
        assertNull(TypeIO.readBuilding(reads(bytes(w -> w.i(-1)))));
        assertNull(TypeIO.readPosEntity(reads(bytes(w -> w.i(-2)))));
        assertThrows(ArcRuntimeException.class, () -> TypeIO.readUnit(reads(bytes(w -> { w.b(3); w.i(0); }))));
    }

    private static <T> void assertByteEnum(T[] values, Function<Reads, T> reader){
        assertSame(values[values.length - 1], reader.apply(reads(new byte[]{(byte)(values.length - 1)})));
        for(int ordinal : new int[]{values.length, 255}){
            assertThrows(ArcRuntimeException.class, () -> reader.apply(reads(new byte[]{(byte)ordinal})));
        }
    }

    private static ConnectPacket connectPacket(){
        var packet = new ConnectPacket();
        packet.uuid = "AAAAAAAAAAA=";
        packet.mods = new Seq<>();
        return packet;
    }

    private static byte[] chunkBody(byte[] data){
        return bytes(w -> { w.i(7); w.s(data.length); w.b(data); });
    }

    private static ByteBuffer frame(int length, int compression, byte[] body){
        ByteBuffer frame = ByteBuffer.allocate(4 + body.length);
        frame.put(Net.getPacketId(new StreamChunk())).putShort((short)length).put((byte)compression).put(body);
        return frame.flip();
    }

    private static byte[] compress(byte[] body){
        return LZ4Factory.fastestInstance().fastCompressor().compress(body);
    }

    private static Reads reads(byte[] bytes){
        return new Reads(new ByteBufferInput(ByteBuffer.wrap(bytes)));
    }

    private static Writes writes(ByteBuffer buffer){
        return new Writes(new ByteBufferOutput(buffer));
    }

    private static byte[] bytes(Consumer<Writes> writer){
        var bytes = new ByteArrayOutputStream();
        writer.accept(new Writes(new DataOutputStream(bytes)));
        return bytes.toByteArray();
    }
}
