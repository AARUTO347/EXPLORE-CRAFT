import arc.*;
import arc.func.*;
import arc.struct.*;
import mindustry.core.GameState.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.net.*;
import mindustry.ui.*;
import mindustry.ui.builder.*;
import mindustry.ui.builder.UiBuilder.*;
import org.junit.jupiter.api.*;

import static mindustry.Vars.*;
import static mindustry.ui.builder.UiBuilder.*;
import static org.junit.jupiter.api.Assertions.*;

public class MenuSecurityTests{
    static MenuSecurityTests current;
    Net previousNet;
    State previousState;
    FakeProvider provider;
    FakeNet testNet;
    FakeConnection first, second;
    int menuId, inputId, builderId;
    int menuEvents, textEvents, builderEvents;
    Seq<Integer> choices = new Seq<>();
    Seq<String> texts = new Seq<>();
    Seq<MenuResult> results = new Seq<>();

    @BeforeAll
    static void launch(){
        ApplicationTests.launchApplication();
        Events.on(MenuOptionChooseEvent.class, e -> { if(current != null) current.menuEvents++; });
        Events.on(TextInputEvent.class, e -> { if(current != null) current.textEvents++; });
        Events.on(MenuBuilderOptionChooseEvent.class, e -> { if(current != null) current.builderEvents++; });
    }

    @BeforeEach
    void setup(){
        previousNet = net;
        previousState = state.getState();
        provider = new FakeProvider();
        net = testNet = new FakeNet(provider);
        first = new FakeConnection();
        second = new FakeConnection();
        provider.connections.add(first, second);
        state.set(State.playing);
        menuId = Menus.registerMenu((p, option) -> choices.add(option));
        inputId = Menus.registerTextInput((p, text) -> texts.add(text));
        builderId = Menus.registerMenuBuilder((p, result) -> results.add(result));
        current = this;
    }

    @AfterEach
    void restore(){
        current = null;
        net = previousNet;
        state.set(previousState);
    }

    @Test
    void onlyIssuedConnectionCanReplyBeforeEvents(){
        choose(first, menuId, 0);
        choose(first, -1, -1);
        input(first, inputId, "spoof");
        builder(first, builderId, result(7, "go"));
        builder(first, builderId, null);
        assertCounts(0, 0, 0);

        Call.menu(first, menuId, "", "", new String[][]{{"OK"}});
        Call.textInput(first, inputId, "", "", 3, "", false);
        Call.menuBuilder(first, builderId, 7, null, true, true, true, table().add(button("OK").clicked("go")));
        assertInstanceOf(MenuCallPacket.class, first.sent.get(0));
        assertInstanceOf(TextInputCallPacket.class, first.sent.get(1));
        assertInstanceOf(MenuBuilderCallPacket.class, first.sent.get(2));
        assertNotSame(first.menuState, second.menuState);
        choose(second, menuId, 0);
        input(second, inputId, "abc");
        builder(second, builderId, result(7, "go"));
        assertCounts(0, 0, 0);

        choose(first, menuId, 0);
        input(first, inputId, "abc");
        builder(first, builderId, result(7, "go"));
        assertCounts(1, 1, 1);
    }

    @Test
    void nullTargetDoesNotBroadcast(){
        assertThrows(NullPointerException.class, () -> Call.menu(null, menuId, "private", "", new String[][]{{"OK"}}));
        assertEquals(0, first.sent.size);
        assertEquals(0, second.sent.size);
    }

    @Test
    void broadcastSnapshotsRecipientsAndTracksAllBeforeSending(){
        FakeConnection outsider = new FakeConnection();
        first.onSend = () -> {
            provider.connections.remove(second);
            provider.connections.add(outsider);
            choose(second, menuId, 0); //Already tracked, even though its packet has not been sent yet.
        };
        Call.menu(menuId, "", "", new String[][]{{"OK"}});
        assertEquals(1, first.sent.size);
        assertEquals(1, second.sent.size);
        assertEquals(0, outsider.sent.size);
        assertSame(first.sent.first(), second.sent.first());
        assertTrue(first.reliable.first());
        choose(first, menuId, 0);
        choose(outsider, menuId, 0);
        assertCounts(2, 0, 0);
    }

    @Test
    void legacyLimitsCountRenderedNonNullButtonsAndDoNotConsumeInvalidReplies(){
        Call.menu(first, menuId, "", "", new String[][]{{null, "A"}, {}, null, {"B", null, "C"}});
        choose(first, menuId, 3);
        choose(first, menuId, -2);
        assertCounts(0, 0, 0);
        choose(first, menuId, 2);
        choose(first, menuId, 2);
        assertEquals(2, choices.first().intValue());
        assertCounts(1, 0, 0);

        Call.menu(first, menuId, "", "", null);
        choose(first, menuId, 0);
        choose(first, menuId, -1);
        choose(first, menuId, -1);
        assertEquals(-1, choices.peek().intValue());
        assertCounts(2, 0, 0);
    }

    @Test
    void textLimitsNumericAndAllowEmptyApplyToBothGeneratedOverloads(){
        Call.textInput(first, inputId, "", "", 3, "", false);
        input(first, inputId, "abcd");
        input(first, inputId, "");
        assertCounts(0, 0, 0);
        input(first, inputId, null);
        input(first, inputId, null);
        assertNull(texts.first());
        assertCounts(0, 1, 0);

        Call.textInput(inputId, "", "", 3, "", true);
        input(first, inputId, "+1");
        input(first, inputId, "1a");
        input(first, inputId, "1234");
        input(first, inputId, "");
        assertCounts(0, 1, 0);
        input(first, inputId, "123");
        input(first, inputId, "123");
        input(second, inputId, null);
        assertCounts(0, 3, 0);
        assertInstanceOf(TextInputCallPacket.class, second.sent.peek());

        Call.textInput(first, inputId, "", "", 0, "", false, true);
        assertInstanceOf(TextInputCallPacket2.class, first.sent.peek());
        input(first, inputId, "a");
        input(first, inputId, "");
        assertCounts(0, 4, 0);
        Call.textInput(inputId, "", "", 2, "", false, true);
        input(first, inputId, "abc");
        input(first, inputId, "ab");
        input(second, inputId, "");
        assertCounts(0, 6, 0);
    }

    @Test
    void oneShotCallbacksCanReissueTheSameId(){
        int[] calls = new int[3];
        int[] ids = new int[3];
        ids[0] = Menus.registerMenu((p, option) -> {
            if(++calls[0] == 1) Call.menu(p.con, ids[0], "", "", new String[][]{{"again"}});
        });
        ids[1] = Menus.registerTextInput((p, text) -> {
            if(++calls[1] == 1) Call.textInput(p.con, ids[1], "", "", 2, "", false);
        });
        ids[2] = Menus.registerMenuBuilder((p, res) -> {
            if(++calls[2] == 1) Call.menuBuilder(p.con, ids[2], 8, null, true, true, true, table().add(button("again").clicked("go")));
        });
        Call.menu(first, ids[0], "", "", new String[][]{{"OK"}});
        Call.textInput(first, ids[1], "", "", 2, "", false);
        Call.menuBuilder(first, ids[2], 7, null, true, true, true, table().add(button("OK").clicked("go")));
        choose(first, ids[0], 0);
        choose(first, ids[0], 0);
        choose(first, ids[0], 0);
        input(first, ids[1], "ab");
        input(first, ids[1], "ab");
        input(first, ids[1], "ab");
        builder(first, ids[2], result(7, "go"));
        builder(first, ids[2], result(8, "go"));
        builder(first, ids[2], result(8, "go"));
        assertArrayEquals(new int[]{2, 2, 2}, calls);
        assertEquals(2, menuEvents);
        assertEquals(2, textEvents);
        assertEquals(2, builderEvents);
    }

    @Test
    void followUpMenusPersistInGameButCancelHideAndReplace(){
        Call.followUpMenu(first, menuId, "", "", new String[][]{{"A", "B"}});
        choose(first, menuId, 2);
        choose(first, menuId, 1);
        choose(first, menuId, 0);
        assertCounts(2, 0, 0);
        choose(first, menuId, -1);
        choose(first, menuId, 0);
        choose(first, menuId, -1);
        assertCounts(3, 0, 0);

        Call.followUpMenu(menuId, "", "", new String[][]{{"A", "B"}});
        Call.followUpMenu(first, menuId, "", "", new String[][]{{"A"}});
        choose(first, menuId, 1);
        choose(first, menuId, 0);
        Call.hideFollowUpMenu(first, menuId);
        choose(first, menuId, 0);
        assertCounts(4, 0, 0);
        choose(second, menuId, 1);
        Call.hideFollowUpMenu(menuId);
        choose(second, menuId, -1);
        assertCounts(5, 0, 0);

        state.set(State.menu);
        Call.followUpMenu(first, menuId, "", "", new String[][]{{"A"}});
        choose(first, menuId, 0);
        choose(first, menuId, 0);
        assertCounts(6, 0, 0);
    }

    @Test
    void builderChecksTokenActionAndOriginalFieldSchemaWithoutConsumingInvalidReplies(){
        TableBuilder tree = table()
            .add(field("").id("name").maxLength(3).enter("enter"))
            .add(check().id("check"))
            .add(slider(0, 10, 1).id("slider"))
            .add(button("OK").id("button").clicked("go"))
            .add(field("").id("conditional").condition("portrait").maxLength(2));
        Call.menuBuilder(first, builderId, 7, null, true, true, true, tree);
        tree.add(button("not issued").clicked("later"));
        builder(first, builderId, result(8, "go"));
        builder(first, builderId, result(7, "unknown"));
        builder(first, builderId, result(7, "later"));
        builder(first, builderId, result(7, "go", "unknown", "x"));
        builder(first, builderId, result(7, "go", "name", "abcd"));
        builder(first, builderId, result(7, "go", "name", 1f));
        builder(first, builderId, result(7, "go", "check", "true"));
        builder(first, builderId, result(7, "go", "button", 1f));
        builder(first, builderId, result(7, "go", "slider", true));
        builder(first, builderId, result(7, "go", "slider", Float.NaN));
        builder(first, builderId, result(7, "go", "slider", 11f));
        builder(first, builderId, result(7, null, "name", "abc"));
        assertCounts(0, 0, 0);

        MenuResult valid = result(7, "enter", "name", "abc");
        valid.values.put("check", true);
        valid.values.put("button", false);
        valid.values.put("slider", 5f);
        builder(first, builderId, valid); //Conditional field may legitimately be absent.
        builder(first, builderId, valid);
        assertCounts(0, 0, 1);
        assertSame(valid, results.first());
    }

    @Test
    void builderTextDefaultAndCapAre1000AndActionsCanOmitValues(){
        Call.menuBuilder(builderId, 7, null, true, true, true, table()
            .add(field("").id("default"))
            .add(field("").id("capped").maxLength(2000))
            .add(button("OK").clicked("go")));
        builder(first, builderId, result(7, "go", "default", "a".repeat(1001)));
        builder(first, builderId, result(7, "go", "capped", "a".repeat(1001)));
        assertCounts(0, 0, 0);
        MenuResult valid = result(7, "go", "default", "a".repeat(1000));
        valid.values.put("capped", "a".repeat(1000));
        builder(first, builderId, valid);
        builder(second, builderId, result(7, "go"));
        assertCounts(0, 0, 2);
    }

    @Test
    void persistentBuildersAllowActionsUntilCancellation(){
        Call.menuBuilder(first, builderId, 7, null, false, true, true, table().add(button("OK").clicked("go")));
        builder(first, builderId, result(7, "go"));
        builder(first, builderId, result(7, "go"));
        builder(first, builderId, result(7, null));
        builder(first, builderId, result(7, null));
        builder(first, builderId, result(7, "go"));
        assertCounts(0, 0, 3);
    }

    @Test
    void replacedAndHiddenBuildersAuthorizeOnlyOneLateCancellation(){
        TableBuilder tree = table().add(button("OK").clicked("go"));
        Call.menuBuilder(first, builderId, 7, null, false, true, true, tree);
        Call.menuBuilder(first, builderId, 8, null, false, true, true, tree);
        builder(first, builderId, result(7, "go"));
        builder(first, builderId, result(7, null, "unknown", false));
        assertCounts(0, 0, 0);
        builder(first, builderId, result(7, null));
        builder(first, builderId, result(7, null));
        builder(first, builderId, result(8, "go"));
        assertCounts(0, 0, 2);

        Call.menuBuilder(first, builderId, 9, null, false, true, true, tree);
        builder(first, builderId, result(8, "go"));
        builder(first, builderId, result(8, null));
        Call.hideMenuBuilder(first, builderId);
        builder(first, builderId, result(9, "go"));
        builder(first, builderId, result(9, null));
        builder(first, builderId, result(9, null));
        assertCounts(0, 0, 4);

        Call.menuBuilder(builderId, 10, null, true, true, true, tree);
        Call.hideMenuBuilder(builderId);
        builder(first, builderId, result(10, null));
        builder(second, builderId, result(10, null));
        builder(second, builderId, result(10, "go"));
        assertCounts(0, 0, 6);
    }

    @Test
    void olderVisibleBuildersRemainUsableWhenNotHidden(){
        TableBuilder tree = table().add(button("OK").clicked("go"));
        Call.menuBuilder(first, builderId, 7, null, false, true, true, tree);
        Call.menuBuilder(first, builderId, 8, null, false, false, true, tree);
        builder(first, builderId, result(7, "go"));
        builder(first, builderId, result(8, "go"));
        builder(first, builderId, result(7, "go"));
        assertCounts(0, 0, 3);
        Call.hideMenuBuilder(first, builderId);
        builder(first, builderId, result(8, "go"));
        builder(first, builderId, result(7, "go"));
        assertCounts(0, 0, 4);
        builder(first, builderId, result(7, null));
        builder(first, builderId, result(7, "go"));
        assertCounts(0, 0, 5);
    }

    @Test
    void remoteLinksOnlyAllowHttpAndHttps(){
        assertTrue(Menus.isAllowedUri("https://example.com/path"));
        assertTrue(Menus.isAllowedUri("HTTP://example.com/"));
        assertTrue(Menus.isAllowedUri("http://[::1]/"));
        for(String value : new String[]{null, "file:///C:/Windows", "steam://run/730", "javascript:alert(1)",
            "ms-settings:privacy", "https:opaque", "//example.com", "https://", "https://example.com/\n"}){
            assertFalse(Menus.isAllowedUri(value), value);
        }
    }

    @Test
    void retiredTokenCancellationsAreBoundedWithoutExpiringTheActiveMenu(){
        TableBuilder tree = table().add(button("OK").clicked("go"));
        for(long token = 1; token <= 70; token++){
            Call.menuBuilder(first, builderId, token, null, false, true, true, tree);
        }
        builder(first, builderId, result(1, null)); //Old cancellation-only tombstone has been evicted.
        builder(first, builderId, result(69, "go"));
        assertCounts(0, 0, 0);
        builder(first, builderId, result(69, null));
        builder(first, builderId, result(69, null));
        builder(first, builderId, result(70, "go"));
        builder(first, builderId, result(70, "go"));
        assertCounts(0, 0, 3);
    }

    @Test
    void builderUpdatesReviseSubtreeLimitsAndPreserveOutsideFields(){
        TableBuilder update = table().add(field("").id("inside").maxLength(2)).add(button("new").clicked("new"));
        Call.menuBuilderUpdate(first, builderId, "region", update);
        builder(first, builderId, result(7, "new"));
        assertCounts(0, 0, 0); //An update cannot open or authorize a menu.

        Call.menuBuilder(first, builderId, 7, null, false, true, true, table()
            .add(field("").id("outside").maxLength(2))
            .add(button("outside").clicked("outside"))
            .add(pane().id("region").add(field("").id("inside").maxLength(10)).add(button("old").clicked("old"))));
        Call.menuBuilderUpdate(first, builderId, "region", update);
        builder(first, builderId, result(7, "old"));
        builder(first, builderId, result(7, "new", "inside", "abc"));
        builder(first, builderId, result(7, "new", "outside", "abc"));
        assertCounts(0, 0, 0);
        MenuResult valid = result(7, "new", "inside", "ab");
        valid.values.put("outside", "ab");
        builder(first, builderId, valid);
        builder(first, builderId, result(7, "outside", "outside", "ab"));
        assertCounts(0, 0, 2);

        Call.menuBuilderUpdate(first, builderId, "outside", table().add(button("bad target").clicked("bad")));
        builder(first, builderId, result(7, "bad"));
        Call.menuBuilderUpdate(builderId, "region", table()
            .add(table().id("nested").add(field("").id("inside").maxLength(5)))
            .add(button("newer").clicked("newer")));
        builder(second, builderId, result(7, "newer"));
        builder(first, builderId, result(7, "new"));
        builder(first, builderId, result(7, "newer", "inside", "abcdef"));
        assertCounts(0, 0, 2);
        builder(first, builderId, result(7, "newer", "inside", "abcde"));
        Call.menuBuilderUpdate(first, builderId, "nested", table().add(field("").id("inside").maxLength(1)));
        builder(first, builderId, result(7, "newer", "inside", "ab"));
        builder(first, builderId, result(7, "newer", "inside", "a"));
        assertCounts(0, 0, 4);
    }

    @Test
    void localAndClientCallbacksKeepExistingBehavior(){
        Player local = Player.create();
        Menus.menuChoose(local, menuId, 999);
        Menus.textInputResult(local, inputId, "unissued local text");
        Menus.menuBuilderChoose(local, builderId, result(99, "local"));
        assertCounts(1, 1, 1);

        testNet.serverMode = false;
        Call.menuChoose(first.player, menuId, 999);
        Call.textInputResult(first.player, inputId, "unissued client text");
        Call.menuBuilderChoose(first.player, builderId, result(99, "client"));
        assertCounts(2, 2, 2);
        assertEquals(3, provider.clientSent.size);
        assertInstanceOf(MenuChooseCallPacket.class, provider.clientSent.get(0));
        assertInstanceOf(TextInputResultCallPacket.class, provider.clientSent.get(1));
        assertInstanceOf(MenuBuilderChooseCallPacket.class, provider.clientSent.get(2));
    }

    void assertCounts(int menus, int inputs, int builders){
        assertEquals(menus, choices.size);
        assertEquals(inputs, texts.size);
        assertEquals(builders, results.size);
        assertEquals(menus, menuEvents);
        assertEquals(inputs, textEvents);
        assertEquals(builders, builderEvents);
    }

    static void choose(FakeConnection con, int id, int option){
        MenuChooseCallPacket packet = new MenuChooseCallPacket();
        packet.menuId = id;
        packet.option = option;
        packet.handleServer(con);
    }

    static void input(FakeConnection con, int id, String text){
        TextInputResultCallPacket packet = new TextInputResultCallPacket();
        packet.textInputId = id;
        packet.text = text;
        packet.handleServer(con);
    }

    static void builder(FakeConnection con, int id, MenuResult result){
        MenuBuilderChooseCallPacket packet = new MenuBuilderChooseCallPacket();
        packet.menuId = id;
        packet.results = result;
        packet.handleServer(con);
    }

    static MenuResult result(long token, String action){
        MenuResult result = new MenuResult(action);
        result.token = token;
        return result;
    }

    static MenuResult result(long token, String action, String key, Object value){
        MenuResult result = result(token, action);
        result.values.put(key, value);
        return result;
    }

    static class FakeConnection extends NetConnection{
        final Seq<Object> sent = new Seq<>();
        final Seq<Boolean> reliable = new Seq<>();
        Runnable onSend;

        FakeConnection(){
            super("127.0.0.1");
            player = Player.create();
            player.con = this;
            hasConnected = true;
        }

        @Override
        public void send(Object packet, boolean reliable){
            sent.add(packet);
            this.reliable.add(reliable);
            if(onSend != null){
                Runnable action = onSend;
                onSend = null;
                action.run();
            }
        }

        @Override
        public void close(){}
    }

    static class FakeNet extends Net{
        boolean serverMode = true;

        FakeNet(FakeProvider provider){ super(provider); }
        @Override public boolean active(){ return true; }
        @Override public boolean server(){ return serverMode; }
        @Override public boolean client(){ return !serverMode; }
    }

    static class FakeProvider implements Net.NetProvider{
        final Seq<NetConnection> connections = new Seq<>();
        final Seq<Object> clientSent = new Seq<>();

        @Override public Iterable<? extends NetConnection> getConnections(){ return connections; }
        @Override public void sendClient(Object packet, boolean reliable){ clientSent.add(packet); }
        @Override public void connectClient(String ip, int port, Runnable success){ success.run(); }
        @Override public void disconnectClient(){}
        @Override public void discoverServers(Cons<Host> callback, Runnable done){ done.run(); }
        @Override public void pingHost(String address, int port, Cons<Host> valid, Cons<Exception> failed){}
        @Override public void hostServer(int port){}
        @Override public void closeServer(){}
    }
}
