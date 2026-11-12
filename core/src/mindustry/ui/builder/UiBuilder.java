package mindustry.ui.builder;

import arc.graphics.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;

/** Builder-style API for building dialogs on servers. It is recommended to statically import all the methods in this class. */
public class UiBuilder{

    public static TableBuilder parse(String source){ return UiDslParser.parse(source); }
    public static TableBuilder table(){ return new TableBuilder(); }
    public static StackBuilder stack(){ return new StackBuilder(); }
    public static PaneBuilder pane(){ return new PaneBuilder(); }
    public static LabelBuilder label(String text){ return new LabelBuilder().text(text); }
    public static ImageBuilder image(){ return new ImageBuilder(); }
    public static ImageBuilder image(String region){ return new ImageBuilder().region(region); }
    public static ButtonBuilder button(String text){ return new ButtonBuilder().text(text); }
    public static ImageButtonBuilder imageButton(String icon){ return new ImageButtonBuilder().icon(icon); }
    public static FieldBuilder field(String text){ return new FieldBuilder().text(text); }
    public static CheckBuilder check(){ return new CheckBuilder(); }
    public static CheckBuilder check(String text){ return new CheckBuilder().text(text); }
    public static SliderBuilder slider(float min, float max, float step){ return new SliderBuilder().min(min).max(max).step(step); }
    public static SpaceBuilder space(){ return new SpaceBuilder(); }
    public static DefaultsBuilder defaults(){ return new DefaultsBuilder(); }
    public static ButtonTableBuilder buttonTable(){ return new ButtonTableBuilder(); }

    /** Copies only the reply schema, without retaining or modifying the caller's builder. */
    public static MenuSchema snapshot(NodeBuilder<?> builder){
        return new MenuSchema(schemaChildren(builder));
    }

    private static Seq<SchemaNode> schemaChildren(NodeBuilder<?> builder){
        Seq<SchemaNode> children = new Seq<>();
        if(builder != null){
            for(Entry entry : builder.entries){
                if(entry.value instanceof NodeBuilder<?> node && node.type != UiKey.defaults){
                    children.add(new SchemaNode(node));
                }
            }
        }
        return children;
    }

    /** Server-side structural validation only; client layout conditions are not evaluated here. */
    public static class MenuSchema{
        private final Seq<SchemaNode> children;
        private final ObjectMap<String, SchemaNode> fields = new ObjectMap<>();
        private final ObjectSet<String> actions = new ObjectSet<>();

        private MenuSchema(Seq<SchemaNode> children){
            this.children = children;
            rebuild();
        }

        /** Mirrors replacement of a named Table/ScrollPane's contents; other fields remain issued. */
        public boolean update(String tableId, MenuSchema replacement){
            if(tableId == null) return false;
            SchemaNode target = find(children, tableId);
            if(target == null || !(target.type == UiKey.table || target.type == UiKey.pane || target.type == UiKey.buttonTable)) return false;
            target.children = replacement.children;
            rebuild();
            return true;
        }

        public boolean accepts(MenuResult result){
            if(result.result == null || !actions.contains(result.result) || result.values == null ||
                result.result.length() > MenuResult.maxResultLen || result.values.size > MenuResult.maxTotalValues) return false;

            int total = result.result.length();
            for(var entry : result.values){
                SchemaNode field = fields.get(entry.key);
                if(field == null || entry.key.length() > MenuResult.maxValueLen || !field.accepts(entry.value)) return false;
                total += entry.key.length();
                if(entry.value instanceof String text) total += text.length();
                if(total > MenuResult.maxTotalStringLen) return false;
            }
            //Values can be absent: conditions may omit nodes, and update callbacks can report only a subtree.
            return true;
        }

        private void rebuild(){
            fields.clear();
            actions.clear();
            collect(children);
        }

        private void collect(Seq<SchemaNode> nodes){
            for(SchemaNode node : nodes){
                collect(node.children);
                if(node.action != null) actions.add(node.action);
                if(node.id != null){
                    //Like idElements, the last rendered node with this ID wins, even if it has no value.
                    fields.remove(node.id);
                    if(node.hasValue()) fields.put(node.id, node);
                }
            }
        }

        private static SchemaNode find(Seq<SchemaNode> nodes, String id){
            SchemaNode found = null;
            for(SchemaNode node : nodes){
                SchemaNode nested = find(node.children, id);
                if(nested != null) found = nested;
                if(id.equals(node.id)) found = node;
            }
            return found;
        }
    }

    private static class SchemaNode{
        final UiKey type;
        final String id, action;
        final int maxLength;
        final float min, max;
        Seq<SchemaNode> children;

        SchemaNode(NodeBuilder<?> node){
            type = node.type;
            id = node.str(UiKey.id);
            action = node.bool(UiKey.disabled) ? null : switch(type){
                case button, imageButton, buttonTable -> node.str(UiKey.clicked);
                case field -> node.str(UiKey.enter);
                default -> null;
            };
            int length = (int)node.num(UiKey.maxLength, 1000);
            //TextField treats nonpositive lengths as unlimited; the wire format still caps these at 1000.
            maxLength = length <= 0 ? 1000 : Math.min(length, 1000);
            min = node.num(UiKey.min, 0f);
            max = node.num(UiKey.max, 1f);
            children = switch(type){
                case table, pane, stack, buttonTable -> schemaChildren(node);
                default -> new Seq<>();
            };
        }

        boolean hasValue(){
            return switch(type){
                case field, check, slider, button, imageButton, buttonTable -> true;
                default -> false;
            };
        }

        boolean accepts(Object value){
            return switch(type){
                case field -> value instanceof String text && text.length() <= maxLength;
                case slider -> value instanceof Float number && Float.isFinite(number) && number >= min && number <= max;
                case check, button, imageButton, buttonTable -> value instanceof Boolean;
                default -> false;
            };
        }
    }

    public static abstract class NodeBuilder<T extends NodeBuilder<T>>{
        final UiKey type;
        final Seq<Entry> entries = new Seq<>();

        NodeBuilder(UiKey type){
            this.type = type;
        }

        @SuppressWarnings("unchecked")
        private T self(){
            return (T)this;
        }

        protected T prop(UiKey key, String value){ entries.add(new Entry(key, value)); return self(); }
        protected T prop(UiKey key, float value){ entries.add(new Entry(key, value)); return self(); }
        protected T prop(UiKey key, boolean value){ entries.add(new Entry(key, value)); return self(); }

        public T id(String id){ return prop(UiKey.id, id); }
        public T condition(String cond){ return prop(UiKey.condition, cond); }
        public T grow(){ return prop(UiKey.grow, true); }
        public T growX(){ return prop(UiKey.growX, true); }
        public T growY(){ return prop(UiKey.growY, true); }
        public T fill(){ return prop(UiKey.fill, true); }
        public T fillX(){ return prop(UiKey.fillX, true); }
        public T fillY(){ return prop(UiKey.fillY, true); }
        public T expand(){ return prop(UiKey.expand, true); }
        public T expandX(){ return prop(UiKey.expandX, true); }
        public T expandY(){ return prop(UiKey.expandY, true); }
        public T width(float w){ return prop(UiKey.width, w); }
        public T height(float h){ return prop(UiKey.height, h); }
        public T size(float s){ return prop(UiKey.size, s); }
        public T minWidth(float w){ return prop(UiKey.minWidth, w); }
        public T maxWidth(float w){ return prop(UiKey.maxWidth, w); }
        public T minHeight(float h){ return prop(UiKey.minHeight, h); }
        public T maxHeight(float h){ return prop(UiKey.maxHeight, h); }
        public T pad(float p){ return prop(UiKey.pad, p); }
        public T padTop(float p){ return prop(UiKey.padTop, p); }
        public T padLeft(float p){ return prop(UiKey.padLeft, p); }
        public T padBottom(float p){ return prop(UiKey.padBottom, p); }
        public T padRight(float p){ return prop(UiKey.padRight, p); }
        public T align(String align){ return prop(UiKey.align, align); }
        public T colspan(int c){ return prop(UiKey.colspan, (float)c); }
        public T uniform(){ return prop(UiKey.uniform, true); }
        public T uniformX(){ return prop(UiKey.uniformX, true); }
        public T uniformY(){ return prop(UiKey.uniformY, true); }
        public T color(Color color){ return prop(UiKey.color, color.toString()); }
        public T color(String colorHex){ return prop(UiKey.color, colorHex); }

        public void write(Writes out){
            out.b(type.ordinal());
            writeEntries(out, entries);
        }

        static void writeEntries(Writes out, Seq<Entry> entries){
            out.s(entries.size);
            for(Entry e : entries) writeEntry(out, e);
        }

        public static NodeBuilder<?> read(Reads in){
            UiKey type = UiKey.all[in.ub()];
            NodeBuilder<?> node = create(type);
            readEntries(in, node.entries);
            return node;
        }

        static void readEntries(Reads in, Seq<Entry> entries){
            int count = in.us();
            for(int i = 0; i < count; i++) entries.add(readEntry(in));
        }

        static NodeBuilder<?> create(UiKey type){
            return switch(type){
                case table -> new TableBuilder();
                case pane -> new PaneBuilder();
                case stack -> new StackBuilder();
                case label -> new LabelBuilder();
                case image -> new ImageBuilder();
                case button -> new ButtonBuilder();
                case imageButton -> new ImageButtonBuilder();
                case field -> new FieldBuilder();
                case check -> new CheckBuilder();
                case slider -> new SliderBuilder();
                case space -> new SpaceBuilder();
                case defaults -> new DefaultsBuilder();
                case buttonTable -> new ButtonTableBuilder();
                default -> throw new IllegalArgumentException("Unknown node type " + type);
            };
        }

        static void writeEntry(Writes out, Entry e){
            out.s(e.key.ordinal());
            if(e.value instanceof String s){
                out.b(0);
                out.str(s);
            }else if(e.value instanceof Float f){
                out.b(1);
                out.f(f);
            }else if(e.value instanceof Boolean b){
                out.b(2);
                out.bool(b);
            }else if(e.value instanceof NodeBuilder<?> node){
                out.b(3);
                node.write(out);
            }else{
                throw new ArcRuntimeException("Unsupported entry value type: " + e.value);
            }
        }

        static Entry readEntry(Reads in){
            UiKey key = UiKey.all[in.us()];
            int tag = in.ub();
            Object value = switch(tag){
                case 0 -> in.str();
                case 1 -> in.f();
                case 2 -> in.bool();
                case 3 -> NodeBuilder.read(in);
                default -> throw new ArcRuntimeException("Unknown entry value tag " + tag);
            };
            return new Entry(key, value);
        }

        String str(UiKey key){
            for(Entry e : entries) if(e.key == key && e.value instanceof String s) return s;
            return null;
        }

        String str(UiKey key, String def){
            String v = str(key);
            return v != null ? v : def;
        }

        Float num(UiKey key){
            for(Entry e : entries) if(e.key == key && e.value instanceof Float f) return f;
            return null;
        }

        float num(UiKey key, float def){
            Float v = num(key);
            return v != null ? v : def;
        }

        boolean bool(UiKey key){
            return bool(key, false);
        }

        boolean bool(UiKey key, boolean def){
            for(Entry e : entries) if(e.key == key && e.value instanceof Boolean b) return b;
            return def;
        }

        @Override
        public String toString(){
            return UiDslWriter.write(this);
        }
    }

    static class Entry{
        final UiKey key;
        final Object value;

        Entry(UiKey key, Object value){
            this.key = key;
            this.value = value;
        }

        @Override
        public String toString(){
            return "Entry{" +
            "key=" + key +
            ", value=" + value +
            '}';
        }
    }

    /** Shared behavior for node types that can contain children (table, pane). */
    public static abstract class ContainerBuilder<T extends ContainerBuilder<T>> extends NodeBuilder<T>{
        ContainerBuilder(UiKey type){
            super(type);
        }

        @SuppressWarnings("unchecked")
        private T self(){
            return (T)this;
        }

        public T row(){
            entries.add(new Entry(UiKey.row, true));
            return self();
        }

        /** Generic nesting point - append any already-configured node as a child. */
        public T add(NodeBuilder<?> child){
            entries.add(new Entry(child.type, child));
            return self();
        }
    }

    public static class StackBuilder extends ContainerBuilder<StackBuilder>{
        StackBuilder(){ super(UiKey.stack); }
    }

    public static class TableBuilder extends ContainerBuilder<TableBuilder>{
        TableBuilder(){ super(UiKey.table); }

        public TableBuilder background(String name){ return prop(UiKey.background, name); }
        public TableBuilder margin(float m){ return prop(UiKey.margin, m); }
        public TableBuilder wrap(boolean wrap){ return prop(UiKey.wrap, wrap); }
    }

    public static class PaneBuilder extends ContainerBuilder<PaneBuilder>{
        PaneBuilder(){ super(UiKey.pane); }

        public PaneBuilder style(String style){ return prop(UiKey.style, style); }
    }

    public static class LabelBuilder extends NodeBuilder<LabelBuilder>{
        LabelBuilder(){ super(UiKey.label); }

        public LabelBuilder text(String text){ return prop(UiKey.text, text); }
        public LabelBuilder wrap(){ return prop(UiKey.wrap, true); }
        public LabelBuilder style(String style){ return prop(UiKey.style, style); }
        public LabelBuilder labelAlign(String align){ return prop(UiKey.labelAlign, align); }
    }

    public static class ImageBuilder extends NodeBuilder<ImageBuilder>{
        ImageBuilder(){ super(UiKey.image); }

        public ImageBuilder region(String region){ return prop(UiKey.region, region); }
        public ImageBuilder placeholder(String placeholder){ return prop(UiKey.placeholder, placeholder); }
        public ImageBuilder scaling(Scaling scaling){ return prop(UiKey.scaling, scaling.name()); }
    }

    public static class ButtonBuilder extends NodeBuilder<ButtonBuilder>{
        ButtonBuilder(){ super(UiKey.button); }

        public ButtonBuilder text(String text){ return prop(UiKey.text, text); }
        public ButtonBuilder icon(String icon){ return prop(UiKey.icon, icon); }
        public ButtonBuilder placeholder(String placeholder){ return prop(UiKey.placeholder, placeholder); }
        public ButtonBuilder style(String style){ return prop(UiKey.style, style); }
        public ButtonBuilder clicked(String result){ return prop(UiKey.clicked, result); }
        public ButtonBuilder disabled(boolean disabled){ return prop(UiKey.disabled, disabled); }
        public ButtonBuilder group(String group){ return prop(UiKey.group, group); }
        public ButtonBuilder checked(boolean checked){ return prop(UiKey.checked, checked); }
    }

    public static class ImageButtonBuilder extends NodeBuilder<ImageButtonBuilder>{
        ImageButtonBuilder(){ super(UiKey.imageButton); }

        public ImageButtonBuilder icon(String icon){ return prop(UiKey.icon, icon); }
        public ImageButtonBuilder placeholder(String placeholder){ return prop(UiKey.placeholder, placeholder); }
        public ImageButtonBuilder style(String style){ return prop(UiKey.style, style); }
        public ImageButtonBuilder clicked(String result){ return prop(UiKey.clicked, result); }
        public ImageButtonBuilder disabled(boolean disabled){ return prop(UiKey.disabled, disabled); }
        public ImageButtonBuilder group(String group){ return prop(UiKey.group, group); }
        public ImageButtonBuilder checked(boolean checked){ return prop(UiKey.checked, checked); }
    }

    public static class FieldBuilder extends NodeBuilder<FieldBuilder>{
        FieldBuilder(){ super(UiKey.field); }

        public FieldBuilder text(String text){ return prop(UiKey.text, text); }
        public FieldBuilder hint(String hint){ return prop(UiKey.hint, hint); }
        public FieldBuilder maxLength(int length){ return prop(UiKey.maxLength, (float)length); }
        public FieldBuilder style(String style){ return prop(UiKey.style, style); }
        public FieldBuilder enter(String result){ return prop(UiKey.enter, result); }
        public FieldBuilder disabled(boolean disabled){ return prop(UiKey.disabled, disabled); }
    }

    public static class CheckBuilder extends NodeBuilder<CheckBuilder>{
        CheckBuilder(){ super(UiKey.check); }

        public CheckBuilder text(String text){ return prop(UiKey.text, text); }
        public CheckBuilder checked(boolean checked){ return prop(UiKey.checked, checked); }
        public CheckBuilder style(String style){ return prop(UiKey.style, style); }
        public CheckBuilder disabled(boolean disabled){ return prop(UiKey.disabled, disabled); }
        public CheckBuilder group(String group){ return prop(UiKey.group, group); }
    }

    public static class SliderBuilder extends NodeBuilder<SliderBuilder>{
        SliderBuilder(){ super(UiKey.slider); }

        public SliderBuilder min(float min){ return prop(UiKey.min, min); }
        public SliderBuilder max(float max){ return prop(UiKey.max, max); }
        public SliderBuilder step(float step){ return prop(UiKey.step, step); }
        public SliderBuilder defaultValue(float value){ return prop(UiKey.defaultValue, value); }
        public SliderBuilder style(String style){ return prop(UiKey.style, style); }
        public SliderBuilder disabled(boolean disabled){ return prop(UiKey.disabled, disabled); }
    }

    public static class SpaceBuilder extends NodeBuilder<SpaceBuilder>{
        SpaceBuilder(){ super(UiKey.space); }
    }

    public static class DefaultsBuilder extends NodeBuilder<DefaultsBuilder>{
        DefaultsBuilder(){ super(UiKey.defaults); }
    }

    public static class ButtonTableBuilder extends ContainerBuilder<ButtonTableBuilder>{
        ButtonTableBuilder(){ super(UiKey.buttonTable); }

        public ButtonTableBuilder style(String style){ return prop(UiKey.style, style); }
        public ButtonTableBuilder clicked(String result){ return prop(UiKey.clicked, result); }
        public ButtonTableBuilder margin(float m){ return prop(UiKey.margin, m); }
        public ButtonTableBuilder disabled(boolean disabled){ return prop(UiKey.disabled, disabled); }
        public ButtonTableBuilder group(String group){ return prop(UiKey.group, group); }
        public ButtonTableBuilder checked(boolean checked){ return prop(UiKey.checked, checked); }
    }
}