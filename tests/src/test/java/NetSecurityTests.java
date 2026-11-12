import arc.*;
import arc.func.*;
import arc.struct.*;
import arc.util.*;
import mindustry.core.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.net.*;
import mindustry.net.Administration.*;
import mindustry.net.Packets.*;
import org.junit.jupiter.api.*;

import java.io.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

public class NetSecurityTests{

    @BeforeAll
    static void launch(){
        ApplicationTests.launchApplication();
    }

    @Test
    void subnetBanMatchesOnSegmentBoundary(){
        assertTrue(Administration.matchesSubnet("10.1.2.3", "10.1"));
        assertTrue(Administration.matchesSubnet("10.1.2.3", "10.1."));
        assertTrue(Administration.matchesSubnet("10.1", "10.1"));
        assertTrue(Administration.matchesSubnet("2001:db8::1", "2001:db8"));
        assertFalse(Administration.matchesSubnet("10.10.0.1", "10.1"));
        assertFalse(Administration.matchesSubnet("10.100.0.1", "10.1"));
        assertFalse(Administration.matchesSubnet("192.168.0.1", "10.1"));
        assertFalse(Administration.matchesSubnet("10.1.2.3", ""));
    }

    @Test
    void loadAssetsRejectsHostileSizes(){
        assertThrows(IOException.class, () -> NetworkIO.loadAssets(stream(-1)));
        assertThrows(IOException.class, () -> NetworkIO.loadAssets(stream(NetworkIO.maxAssetCount + 1)));
        assertThrows(IOException.class, () -> NetworkIO.loadAssets(stream(1, Integer.MAX_VALUE)));
        assertThrows(IOException.class, () -> NetworkIO.loadAssets(stream(1, -5)));
        assertDoesNotThrow(() -> NetworkIO.loadAssets(stream(0)));
    }

    @Test
    void readOnlyChecksAndRejectedKicksDoNotCreateRecords(){
        var admins = new Administration();
        admins.playerInfo.clear();
        admins.kickedIPs.clear();
        assertFalse(admins.isIDBanned("unknown"));
        assertFalse(admins.isAdmin("unknown", "usid"));
        assertEquals(0, admins.getKickTime("unknown", "127.0.0.1"));
        admins.handleKicked("unknown", "127.0.0.1", 1000);
        assertTrue(admins.getKickTime("unknown", "127.0.0.1") > Time.millis());
        assertEquals(0, admins.playerInfo.size);
    }

    @Test
    void uuidChurnUsesTemporaryCooldown(){
        Object oldLimit = Config.uuidChangeLimit.get();
        try{
            Config.uuidChangeLimit.set(2);
            var admins = new Administration();
            admins.bannedIPs.clear();
            admins.kickedIPs.clear();
            assertFalse(admins.checkUuidChanges("127.0.0.1", "a"));
            assertFalse(admins.checkUuidChanges("127.0.0.1", "a"));
            assertFalse(admins.checkUuidChanges("127.0.0.1", "b"));
            assertTrue(admins.checkUuidChanges("127.0.0.1", "c"));
            assertTrue(admins.checkUuidChanges("127.0.0.1", "a"));
            assertFalse(admins.bannedIPs.contains("127.0.0.1"));
            assertTrue(admins.getKickTime("unknown", "127.0.0.1") > Time.millis());
            assertTrue(admins.getKickTime("unknown", "127.0.0.1") <= Time.millis() + 300_000);
        }finally{
            Config.uuidChangeLimit.set(oldLimit);
        }
    }

    @Test
    void expiredKickEntriesArePruned(){
        var admins = new Administration();
        admins.kickedIPs.clear();
        admins.kickedIPs.put("expired", Time.millis() - 1);
        admins.kickedIPs.put("live", Time.millis() + 60_000);
        admins.pruneExpired();
        assertFalse(admins.kickedIPs.containsKey("expired"));
        assertTrue(admins.kickedIPs.containsKey("live"));
    }

    @Test
    void retentionIsOptInAndPreservesProtectedRecords(){
        Object oldRetention = Config.playerInfoRetentionDays.get();
        try{
            long old = Time.millis() - 90 * 24 * 60 * 60 * 1000L;
            Config.playerInfoRetentionDays.set(0);
            var preserved = new Administration();
            preserved.playerInfo.clear();
            preserved.getInfo("history").lastSeen = old;
            preserved.pruneExpired();
            assertNotNull(preserved.getInfoOptional("history"));

            Config.playerInfoRetentionDays.set(30);
            var admins = new Administration();
            admins.playerInfo.clear();
            admins.whitelist.clear();
            admins.getInfo("inactive").lastSeen = old;
            var admin = admins.getInfo("admin");
            admin.lastSeen = old;
            admin.admin = true;
            var banned = admins.getInfo("banned");
            banned.lastSeen = old;
            banned.banned = true;
            var white = admins.getInfo("white");
            white.lastSeen = old;
            white.adminUsid = "changed-credential";
            admins.whitelist.add("original-credentialwhite");
            admins.getInfo("legacy").lastSeen = 0;
            admins.getInfo("recent").lastSeen = Time.millis();
            admins.pruneExpired();
            assertNull(admins.getInfoOptional("inactive"));
            for(String id : new String[]{"admin", "banned", "white", "legacy", "recent"}){
                assertNotNull(admins.getInfoOptional(id), id);
            }
        }finally{
            Config.playerInfoRetentionDays.set(oldRetention);
        }
    }

    @Test
    void rejectedHandshakesDoNotPersistPlayerRecords(){
        var original = netServer.admins;
        Object oldStrict = Config.strict.get();
        int oldBuild = Version.build;
        try{
            Config.strict.set(false);
            Version.build = 42;
            var admins = new Administration();
            admins.playerInfo.clear();
            admins.bannedIPs.clear();
            admins.kickedIPs.clear();
            admins.subnetBans.clear();
            netServer.admins = admins;
            var missingName = connectPacket("missingName");
            missingName.name = null;
            var missingUuid = connectPacket("missingUuid");
            missingUuid.uuid = null;
            var wrongType = connectPacket("wrongType");
            wrongType.versionType = null;
            var wrongVersion = connectPacket("wrongVersion");
            wrongVersion.version = 43;
            for(var packet : new ConnectPacket[]{missingName, missingUuid, wrongType, wrongVersion}){
                var con = new TestConnection();
                net.handleServerReceived(con, packet);
                assertTrue(con.kicked);
            }
            assertEquals(0, admins.playerInfo.size);
        }finally{
            netServer.admins = original;
            Config.strict.set(oldStrict);
            Version.build = oldBuild;
        }
    }

    @Test
    void confirmationWaitsForWorldAndIsNotRepeated(){
        var player = Player.create();
        assertDoesNotThrow(() -> NetServer.connectConfirm(player));
        var con = new TestConnection();
        con.player = player;
        player.con = con;
        con.hasBegunConnecting = true;
        int[] confirmed = {0};
        boolean[] previouslyConnected = {true};
        Cons<PlayerConnectionConfirmed> listener = event -> {
            if(event.player == player){
                confirmed[0]++;
                previouslyConnected[0] = con.hasConnected;
                NetServer.connectConfirm(player);
            }
        };
        Cons<PlayerJoin> joinListener = event -> {
            if(event.player == player) con.kicked = true;
        };
        Events.on(PlayerConnectionConfirmed.class, listener);
        Events.on(PlayerJoin.class, joinListener);
        try{
            NetServer.connectConfirm(player);
            assertFalse(player.isAdded());
            assertEquals(0, confirmed[0]);
            con.worldSent = true;
            con.receivingAssets = true;
            NetServer.connectConfirm(player);
            assertEquals(0, confirmed[0]);
            con.receivingAssets = false;
            NetServer.connectConfirm(player);
            assertTrue(player.isAdded());
            assertTrue(con.hasConnected);
            assertFalse(previouslyConnected[0]);
            assertEquals(1, confirmed[0]);
            con.kicked = false;
            NetServer.connectConfirm(player);
            assertEquals(1, confirmed[0]);

            player.remove();
            con.worldSent = true;
            NetServer.connectConfirm(player);
            assertTrue(player.isAdded());
            assertTrue(previouslyConnected[0]);
            assertEquals(2, confirmed[0]);
            NetServer.connectConfirm(player);
            assertEquals(2, confirmed[0]);
        }finally{
            Events.remove(PlayerConnectionConfirmed.class, listener);
            Events.remove(PlayerJoin.class, joinListener);
            player.remove();
        }
    }

    @Test
    void confirmationListenerCanDetachConnection(){
        var player = Player.create();
        var con = new TestConnection();
        player.con = con;
        con.worldSent = true;
        Cons<PlayerConnectionConfirmed> listener = event -> {
            if(event.player == player) player.con = null;
        };
        Events.on(PlayerConnectionConfirmed.class, listener);
        try{
            assertDoesNotThrow(() -> NetServer.connectConfirm(player));
            assertFalse(con.hasConnected);
            assertFalse(con.worldSent);
        }finally{
            Events.remove(PlayerConnectionConfirmed.class, listener);
            player.remove();
        }
    }

    static ConnectPacket connectPacket(String uuid){
        var packet = new ConnectPacket();
        packet.uuid = uuid;
        packet.usid = "usid";
        packet.name = "Player";
        packet.mods = new Seq<>();
        packet.version = Version.build;
        packet.versionType = Version.type;
        return packet;
    }

    static class TestConnection extends NetConnection{
        TestConnection(){
            super("127.0.0.1");
        }

        @Override
        public void send(Object object, boolean reliable){
        }

        @Override
        public void close(){
        }
    }

    static InputStream stream(int... ints) throws IOException{
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        for(int i : ints) out.writeInt(i);
        return new ByteArrayInputStream(bytes.toByteArray());
    }
}
