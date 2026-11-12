package mindustry.ui;

import arc.*;
import arc.func.*;
import arc.scene.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.annotations.Annotations.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.net.*;
import mindustry.ui.builder.*;
import mindustry.ui.builder.UiBuilder.*;
import mindustry.ui.dialogs.*;

import static mindustry.Vars.*;

/** Class for handling menus and notifications across the network. */
public class Menus{
    private static IntMap<Dialog> followUpMenus = new IntMap<>();
    private static IntMap<MenuDialog> menuDialogs = new IntMap<>();

    private static final Seq<MenuListener> menuListeners = new Seq<>();
    private static final Seq<MenuBuilderListener> menuBuilderListeners = new Seq<>();
    private static final Seq<TextInputListener> textInputListeners = new Seq<>();

    /** Used by generated server send variants, not by the client-only UI methods. */
    public static void sendMenu(NetConnection connection, Object packet, boolean reliable){
        connection.menuState.track(packet);
        connection.send(packet, reliable);
    }

    public static void sendMenu(Object packet, boolean reliable){
        Seq<NetConnection> recipients = new Seq<>();
        for(NetConnection recipient : net.getConnections()) recipients.add(recipient);
        for(NetConnection recipient : recipients) recipient.menuState.track(packet);
        for(NetConnection recipient : recipients) recipient.send(packet, reliable);
    }

    /** Connection-owned issuance state. Invalid replies never consume an outstanding menu. */
    public static class MenuState{
        private final IntMap<LegacyMenu> menus = new IntMap<>();
        private final IntMap<TextInput> inputs = new IntMap<>();
        private final IntMap<BuilderMenu> builders = new IntMap<>();
        private final Seq<RetainedBuilder> retainedBuilders = new Seq<>();
        //Only retired tokens are bounded; active interactive menus have no arbitrary expiry.
        private static final int maxCancelledBuilders = 64;
        private final Seq<BuilderCancellation> cancellations = new Seq<>();

        private void track(Object packet){
            if(packet instanceof MenuCallPacket p){
                menus.put(p.menuId, new LegacyMenu(optionCount(p.options), false));
            }else if(packet instanceof FollowUpMenuCallPacket p){
                menus.put(p.menuId, new LegacyMenu(optionCount(p.options), true));
            }else if(packet instanceof HideFollowUpMenuCallPacket p){
                LegacyMenu menu = menus.get(p.menuId);
                if(menu != null && menu.persistent) menus.remove(p.menuId);
            }else if(packet instanceof TextInputCallPacket p){
                inputs.put(p.textInputId, new TextInput(p.textLength, p.numeric, false));
            }else if(packet instanceof TextInputCallPacket2 p){
                inputs.put(p.textInputId, new TextInput(p.textLength, p.numeric, p.allowEmpty));
            }else if(packet instanceof MenuBuilderCallPacket p){
                if(p.hidePrevious){
                    retire(p.id);
                }else{
                    BuilderMenu previous = builders.remove(p.id);
                    if(previous != null) retainedBuilders.add(new RetainedBuilder(p.id, previous));
                }
                builders.put(p.id, new BuilderMenu(p.token, p.hideOnClick, UiBuilder.snapshot(p.builder)));
            }else if(packet instanceof MenuBuilderUpdateCallPacket p){
                BuilderMenu menu = builders.get(p.id);
                if(menu != null) menu.schema.update(p.tableId, UiBuilder.snapshot(p.builder));
            }else if(packet instanceof HideMenuBuilderCallPacket p){
                retire(p.menuId);
            }
        }

        private static int optionCount(String[][] options){
            int count = 0;
            if(options != null){
                for(String[] row : options){
                    if(row != null){
                        for(String option : row) if(option != null) count++;
                    }
                }
            }
            return count;
        }

        private boolean choose(int id, int option){
            LegacyMenu menu = menus.get(id);
            if(menu == null || option < -1 || option >= menu.options) return false;
            if(option == -1 || !menu.persistent || !state.isGame()) menus.remove(id);
            return true;
        }

        private boolean input(int id, @Nullable String text){
            TextInput input = inputs.get(id);
            if(input == null) return false;
            if(text != null){
                if(text.length() > input.maxLength || (!input.allowEmpty && text.isEmpty())) return false;
                if(input.numeric){
                    for(int i = 0; i < text.length(); i++) if(!Character.isDigit(text.charAt(i))) return false;
                }
            }
            inputs.remove(id);
            return true;
        }

        private boolean chooseBuilder(int id, MenuResult result){
            if(result == null || result.values == null) return false;
            boolean cancelled = result.result == null && result.values.isEmpty();
            //Replacement/hide can provoke a delayed cancellation, but never authorize an old action.
            if(cancelled){
                for(int i = 0; i < cancellations.size; i++){
                    BuilderCancellation old = cancellations.get(i);
                    if(old.id == id && old.token == result.token){
                        cancellations.remove(i);
                        return true;
                    }
                }
            }
            BuilderMenu menu = builders.get(id);
            if(menu != null && menu.token == result.token && (cancelled || menu.schema.accepts(result))){
                if(cancelled || menu.hideOnClick) builders.remove(id);
                return true;
            }
            for(int i = 0; i < retainedBuilders.size; i++){
                RetainedBuilder retained = retainedBuilders.get(i);
                if(retained.id == id && retained.menu.token == result.token && (cancelled || retained.menu.schema.accepts(result))){
                    if(cancelled || retained.menu.hideOnClick) retainedBuilders.remove(i);
                    return true;
                }
            }
            return false;
        }

        private void retire(int id){
            BuilderMenu old = builders.remove(id);
            if(old == null) return;
            for(int i = 0; i < cancellations.size; i++){
                BuilderCancellation entry = cancellations.get(i);
                if(entry.id == id && entry.token == old.token){
                    cancellations.remove(i);
                    break;
                }
            }
            if(cancellations.size >= maxCancelledBuilders) cancellations.remove(0);
            cancellations.add(new BuilderCancellation(id, old.token));
        }
    }

    private static class LegacyMenu{
        final int options;
        final boolean persistent;

        LegacyMenu(int options, boolean persistent){
            this.options = options;
            this.persistent = persistent;
        }
    }

    private static class TextInput{
        final int maxLength;
        final boolean numeric, allowEmpty;

        TextInput(int maxLength, boolean numeric, boolean allowEmpty){
            this.maxLength = Math.max(0, maxLength);
            this.numeric = numeric;
            this.allowEmpty = allowEmpty;
        }
    }

    private static class BuilderMenu{
        final long token;
        final boolean hideOnClick;
        final MenuSchema schema;

        BuilderMenu(long token, boolean hideOnClick, MenuSchema schema){
            this.token = token;
            this.hideOnClick = hideOnClick;
            this.schema = schema;
        }
    }

    private static class RetainedBuilder{
        final int id;
        final BuilderMenu menu;

        RetainedBuilder(int id, BuilderMenu menu){
            this.id = id;
            this.menu = menu;
        }
    }

    private static class BuilderCancellation{
        final int id;
        final long token;

        BuilderCancellation(int id, long token){
            this.id = id;
            this.token = token;
        }
    }

    /** Register a global menu listener. If no option is chosen, the option is returned as -1. This API is soft-deprecated; use {@link MenuBuilder} and {@link #registerMenuBuilder} instead. */
    public static int registerMenu(MenuListener listener){
        menuListeners.add(listener);
        return menuListeners.size - 1;
    }

    /** Register a global menu builder listener. This is not the same as the 'old' MenuListener API; use it with {@link MenuBuilder}. */
    public static int registerMenuBuilder(MenuBuilderListener listener){
        menuBuilderListeners.add(listener);
        return menuBuilderListeners.size - 1;
    }

    /** Register a *global* text input listener. If no text is provided, the text is returned as null. */
    public static int registerTextInput(TextInputListener listener){
        textInputListeners.add(listener);
        return textInputListeners.size - 1;
    }

    //use the MenuBuilder version instead of invoking this with Call
    @Remote(variants = Variant.both)
    public static void menuBuilder(int id, long token, @Nullable String title, boolean hideOnClick, boolean hidePrevious, boolean fillScreen, NodeBuilder<?> builder){
        MenuDialog dialog = new MenuDialog();
        dialog.setFillParent(fillScreen);
        if(title != null){
            dialog.title.setText(title);
        }else{
            dialog.titleTable.remove();
        }

        dialog.addCloseButton();

        dialog.update(() -> {
            if(!net.active()){
                dialog.hide();
            }
        });

        dialog.hidden(() -> {
            if(menuDialogs.get(id) == dialog) menuDialogs.remove(id);
            //let the server know nothing was selected
            if(!dialog.wasHidden){
                var result = new MenuResult();
                result.token = token;
                if(net.client()) Call.menuBuilderChoose(player, id, result);
            }
        });

        dialog.originalListener = result -> {
            result.token = token;
            //Initial and update BuildContexts may retain detached fields. Read the current named elements.
            result.values.clear();
            for(var entry : dialog.idToElement){
                Element element = entry.value;
                if(element instanceof Slider slider) result.values.put(entry.key, slider.getValue());
                else if(element instanceof Stack stack && stack.getChildren().size > 0 && stack.getChildren().first() instanceof Slider slider) result.values.put(entry.key, slider.getValue());
                else if(element instanceof TextField field) result.values.put(entry.key, field.getText());
                else if(element instanceof CheckBox check) result.values.put(entry.key, check.isChecked());
                else if(element instanceof Button button && button.getStyle().checked != null) result.values.put(entry.key, button.isChecked());
            }
            if(net.client()) Call.menuBuilderChoose(player, id, result);

            if(hideOnClick){
                dialog.wasHidden = true; //don't trigger the hidden listener
                dialog.hide();
            }
        };

        var result = UiTreeBuilder.build(dialog.cont, builder, dialog.originalListener);
        dialog.idToElement = result.idElements;
        dialog.addImages(result.images);

        var oldDialog = menuDialogs.remove(id);

        if(hidePrevious && oldDialog != null){
            //old dialog exists, replace it (show with no action)
            dialog.show(Core.scene, null);
            oldDialog.hide(null);
        }else{
            dialog.show();
        }

        menuDialogs.put(id, dialog);
    }

    @Remote(variants = Variant.both)
    public static void menuBuilderUpdate(int id, String tableId, NodeBuilder<?> builder){
        MenuDialog dialog = menuDialogs.get(id);
        if(dialog == null) return;
        Element element = dialog.idToElement.get(tableId);
        Table table = element instanceof Table t ? t : null;
        if(table == null && element instanceof ScrollPane pane && pane.getChildren().size > 0 && pane.getChildren().first() instanceof Table t) table = t;
        if(table == null) return;

        Seq<String> removed = new Seq<>();
        for(var entry : dialog.idToElement){
            if(entry.value != table && entry.value.isDescendantOf(table)) removed.add(entry.key);
        }
        for(String key : removed) dialog.idToElement.remove(key);
        table.clear();
        var result = UiTreeBuilder.build(table, builder, res -> {
            //forward results to original listener
            dialog.originalListener.get(res);
        });
        //save new element IDs and images
        dialog.idToElement.putAll(result.idElements);
        dialog.addImages(result.images);
    }

    @Remote(variants = Variant.both)
    public static void hideMenuBuilder(int menuId) {
        if(!menuDialogs.containsKey(menuId)) return;
        menuDialogs.remove(menuId).hide();
    }

    //do not invoke any of the methods below directly, use Call

    @Remote(targets = Loc.both, called = Loc.both)
    public static void menuBuilderChoose(@Nullable Player player, int menuId, MenuResult results){
        if(player != null){
            if(net.server() && player.con != null && !player.con.menuState.chooseBuilder(menuId, results)) return;
            Events.fire(new MenuBuilderOptionChooseEvent(player, menuId, results));

            if(menuId >= 0 && menuId < menuBuilderListeners.size){
                menuBuilderListeners.get(menuId).get(player, results);
            }
        }
    }

    @Remote(variants = Variant.both)
    public static void menu(int menuId, String title, String message, String[][] options){
        if(title == null) title = "";
        if(message == null) message = "";
        if(options == null) options = new String[0][0];

        showMenu(title, message, options, (option) -> Call.menuChoose(player, menuId, option));
    }

    @Remote(variants = Variant.both)
    public static void followUpMenu(int menuId, String title, String message, String[][] options){
        if(title == null) title = "";
        if(message == null) message = "";
        if(options == null) options = new String[0][0];

        showFollowUpMenu(menuId, title, message, options, (option) -> Call.menuChoose(player, menuId, option));
    }

    @Remote(variants = Variant.both)
    public static void hideFollowUpMenu(int menuId) {
        if(!followUpMenus.containsKey(menuId)) return;
        followUpMenus.remove(menuId).hide();
    }

    static Dialog newMenuDialog(String title, String message, String[][] options, Cons2<Integer, Dialog> buttonListener){
        return new Dialog(title){{
            setFillParent(true);
            removeChild(titleTable);
            cont.add(titleTable).width(400f);

            cont.row();
            cont.image().width(400f).pad(2).colspan(2).height(4f).color(Pal.accent).bottom();
            cont.row();
            cont.pane(table -> {
                table.add(message).width(400f).wrap().get().setAlignment(Align.center);
                table.row();

                int option = 0;
                for(var optionsRow : options){
                    if(optionsRow == null || optionsRow.length == 0) continue;
                    Table buttonRow = table.row().table().get().row();
                    int fullWidth = 400 - (optionsRow.length - 1) * 8; // adjust to count padding as well
                    int width = fullWidth / optionsRow.length;
                    int lastWidth = fullWidth - width * (optionsRow.length - 1); // take the rest of space for uneven table

                    for(int i = 0; i < optionsRow.length; i++){
                        if(optionsRow[i] == null) continue;

                        String optionName = optionsRow[i];
                        int finalOption = option;
                        buttonRow.button(optionName, () -> buttonListener.get(finalOption, this))
                        .size(i == optionsRow.length - 1 ? lastWidth : width, 50).pad(4);
                        option++;
                    }
                }
            }).growX();
        }};
    }

    /** Shows a menu that fires a callback when an option is selected. If nothing is selected, -1 is returned. */
    static void showMenu(String title, String message, String[][] options, Intc callback){
        Dialog dialog = newMenuDialog(title, message, options, (option, myself) -> {
            callback.get(option);
            myself.hide();
        });
        dialog.closeOnBack(() -> callback.get(-1));
        dialog.show();
    }

    /** Shows a menu that hides when another followUp-menu is shown or when nothing is selected.
     * @see #showMenu(String, String, String[][], Intc) */
    static void showFollowUpMenu(int menuId, String title, String message, String[][] options, Intc callback) {
        Dialog dialog = newMenuDialog(title, message, options, (option, myself) -> {
            callback.get(option);
            if(!state.isGame()){
                myself.hide();
            }
        });
        dialog.closeOnBack(() -> {
            followUpMenus.remove(menuId);
            callback.get(-1);
        });

        Dialog oldDialog = followUpMenus.remove(menuId);
        if(oldDialog != null){
            dialog.show(Core.scene, null);
            oldDialog.hide(null);
        }else{
            dialog.show();
        }
        followUpMenus.put(menuId, dialog);
    }

    @Remote(targets = Loc.both, called = Loc.both)
    public static void menuChoose(@Nullable Player player, int menuId, int option){
        if(player != null){
            if(net.server() && player.con != null && !player.con.menuState.choose(menuId, option)) return;
            Events.fire(new MenuOptionChooseEvent(player, menuId, option));
            if(menuId >= 0 && menuId < menuListeners.size){
                menuListeners.get(menuId).get(player, option);
            }
        }
    }

    @Remote(variants = Variant.both)
    public static void textInput(int textInputId, String title, String message, int textLength, String def, boolean numeric){
        textInput(textInputId, title, message, textLength, def, numeric, false);
    }

    @Remote(variants = Variant.both)
    public static void textInput(int textInputId, String title, String message, int textLength, String def, boolean numeric, boolean allowEmpty){
        if(title == null) title = "";
        if(message == null) message = "";
        if(def == null) def = "";

        ui.showTextInput(title, message, textLength, def, numeric, allowEmpty, (text) -> {
            Call.textInputResult(player, textInputId, text);
        }, () -> {
            Call.textInputResult(player, textInputId, null);
        });
    }

    @Remote(targets = Loc.both, called = Loc.both)
    public static void textInputResult(@Nullable Player player, int textInputId, @Nullable String text){
        if(player != null){
            if(net.server() && player.con != null && !player.con.menuState.input(textInputId, text)) return;
            Events.fire(new TextInputEvent(player, textInputId, text));
            if(textInputId >= 0 && textInputId < textInputListeners.size){
                textInputListeners.get(textInputId).get(player, text);
            }
        }
    }

    @Remote(variants = Variant.both, unreliable = true)
    public static void setHudText(String message){
        if(message == null) return;

        ui.hudfrag.setHudText(message);
    }

    @Remote(variants = Variant.both)
    public static void hideHudText(){
        ui.hudfrag.toggleHudText(false);
    }

    /** TCP version */
    @Remote(variants = Variant.both)
    public static void setHudTextReliable(String message){
        setHudText(message);
    }

    @Remote(variants = Variant.both)
    public static void announce(String message){
        if(message == null) return;

        ui.announce(message);
    }

    @Remote(variants = Variant.both)
    public static void infoMessage(String message){
        if(message == null) return;

        ui.showText("", message);
    }

    @Remote(variants = Variant.both, unreliable = true)
    public static void infoPopup(@Nullable String message, @Nullable String id, float duration, int align, int top, int left, int bottom, int right){
        ui.showInfoPopup(message, id, duration, align, top, left, bottom, right);
    }

    @Remote(variants = Variant.both)
    public static void infoPopupReliable(@Nullable String message, @Nullable String id, float duration, int align, int top, int left, int bottom, int right){
        infoPopup(message, id, duration, align, top, left, bottom, right);
    }

    @Remote(variants = Variant.both, unreliable = true)
    public static void infoPopup(@Nullable String message, float duration, int align, int top, int left, int bottom, int right){
        infoPopup(message, null, duration, align, top, left, bottom, right);
    }

    @Remote(variants = Variant.both)
    public static void infoPopupReliable(@Nullable String message, float duration, int align, int top, int left, int bottom, int right){
        infoPopup(message, duration, align, top, left, bottom, right);
    }

    @Remote(variants = Variant.both, unreliable = true)
    public static void label(@Nullable String message, int id, float duration, float worldx, float worldy, int flags){
        ui.showLabel(message, id, duration, worldx, worldy, flags);
    }

    @Remote(variants = Variant.both)
    public static void labelReliable(@Nullable String message, int id, float duration, float worldx, float worldy, int flags){
        label(message, id, duration, worldx, worldy, flags);
    }

    @Remote(variants = Variant.both, unreliable = true)
    public static void label(@Nullable String message, int id, float duration, float worldx, float worldy){
        label(message, id, duration, worldx, worldy, WorldLabel.flagBackground | WorldLabel.flagAutoscale | WorldLabel.flagOutline);
    }

    @Remote(variants = Variant.both)
    public static void labelReliable(@Nullable String message, int id, float duration, float worldx, float worldy){
        label(message, id, duration, worldx, worldy);
    }

    @Remote(variants = Variant.both, unreliable = true)
    public static void label(@Nullable String message, float duration, float worldx, float worldy){
        label(message, -1, duration, worldx, worldy);
    }

    @Remote(variants = Variant.both)
    public static void labelReliable(@Nullable String message, float duration, float worldx, float worldy){
        label(message, -1, duration, worldx, worldy);
    }

    @Remote(variants = Variant.both)
    public static void infoToast(String message, float duration){
        if(message == null) return;

        ui.showInfoToast(message, duration);
    }

    @Remote(variants = Variant.both)
    public static void warningToast(int unicode, String text){
        if(text == null || Fonts.icon.getData().getGlyph((char)unicode) == null) return;

        ui.hudfrag.showToast(Fonts.getGlyph(Fonts.icon, (char)unicode), text);
    }

    @Remote(variants = Variant.both)
    public static void openURI(String uri){
        if(!isAllowedUri(uri)) return;

        ui.showConfirm(Core.bundle.format("linkopen", uri), () -> Core.app.openURI(uri));
    }

    public static boolean isAllowedUri(String value){
        if(value == null) return false;
        try{
            var uri = new java.net.URI(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null;
        }catch(java.net.URISyntaxException e){
            return false;
        }
    }

    @Remote(variants = Variant.both)
    public static void copyToClipboard(String text){
        if(text == null) return;

        ui.showConfirm(Core.bundle.format("clipboardcopy", text), () -> Core.app.setClipboardText(text));
    }

    //internal use only
    @Remote(variants = Variant.both)
    public static void removeWorldLabel(int id){
        var label = Groups.sync.getByID(id);
        if(label instanceof WorldLabelc){
            label.remove();
        }
    }

    static class MenuDialog extends BaseDialog{
        //retained for easy lookup if an element needs to be replaced
        ObjectMap<String, Element> idToElement;
        //every streamed image built into this dialog, keyed by the region name
        ObjectMap<String, Seq<Image>> images = new ObjectMap<>();
        Cons<MenuResult> originalListener;
        boolean wasHidden;

        static{
            // refresh images that are received after the dialog is already opened
            Events.on(TextureStreamEvent.class, e -> {
                for(var dialog : menuDialogs.values()){
                    var imgs = dialog.images.get(e.name);
                    if(imgs != null){
                        var drawable = Core.atlas.drawable(e.name);
                        imgs.each(img -> img.setDrawable(drawable));
                    }
                }
            });
        }

        public MenuDialog(){
            super("");
        }

        void addImages(ObjectMap<String, Seq<Image>> images){
            images.each((region, imgs) -> this.images.get(region, Seq::new).addAll(imgs));
        }
    }

    public interface MenuListener{
        void get(Player player, int option);
    }

    public interface MenuBuilderListener{
        void get(Player player, MenuResult result);
    }

    public interface TextInputListener{
        void get(Player player, @Nullable String text);
    }
}
