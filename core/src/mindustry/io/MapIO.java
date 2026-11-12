package mindustry.io;

import arc.files.*;
import arc.graphics.*;
import arc.struct.*;
import arc.util.io.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.maps.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;
import mindustry.world.blocks.storage.*;

import java.io.*;
import java.util.zip.*;

import static mindustry.Vars.*;

/** Reads and writes map files. */
public class MapIO{
    private static final int[] pngHeader = {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    /** Checks the sizing header only; callers must validatePng on the complete bytes before decoding. */
    public static int[] pngDimensions(byte[] bytes, int maxDimension) throws IOException{
        if(bytes == null || bytes.length < 33) throw new IOException("Truncated PNG header");
        for(int i = 0; i < pngHeader.length; i++){
            if((bytes[i] & 0xff) != pngHeader[i]) throw new IOException("Invalid PNG signature");
        }
        if(pngInt(bytes, 8) != 13 || pngInt(bytes, 12) != 0x49484452) throw new IOException("PNG must begin with a 13-byte IHDR");
        int width = pngInt(bytes, 16), height = pngInt(bytes, 20);
        if(width <= 0 || height <= 0 || width > maxDimension || height > maxDimension || (long)width * height > Integer.MAX_VALUE / 4){
            throw new IOException("Invalid PNG dimensions: " + width + "x" + height + " (maximum " + maxDimension + ")");
        }
        return new int[]{width, height};
    }

    /** Validates allocation-relevant PNG framing without replacing the native decoder or limiting PNG formats. */
    public static void validatePng(byte[] bytes, int maxDimension) throws IOException{
        if(bytes == null || bytes.length > SaveFileReader.maxChunkBytes) throw new IOException("PNG exceeds byte limit");
        pngDimensions(bytes, maxDimension);
        boolean imageData = false;
        for(int offset = 8; offset < bytes.length;){
            if(bytes.length - offset < 12) throw new IOException("Truncated PNG chunk");
            long length = pngInt(bytes, offset) & 0xffffffffL;
            long end = (long)offset + 12 + length;
            if(end > bytes.length) throw new IOException("PNG chunk exceeds remaining bytes");
            int type = pngInt(bytes, offset + 4);
            if(type == 0x49484452 && offset != 8) throw new IOException("Duplicate PNG IHDR");
            if(type == 0x49444154) imageData = true;
            if(type == 0x49454e44){
                if(length != 0 || end != bytes.length || !imageData) throw new IOException("Invalid PNG end chunk");
                return;
            }
            offset = (int)end;
        }
        throw new IOException("Missing PNG end chunk");
    }

    private static int pngInt(byte[] bytes, int offset){
        return (bytes[offset] & 0xff) << 24 | (bytes[offset + 1] & 0xff) << 16 | (bytes[offset + 2] & 0xff) << 8 | bytes[offset + 3] & 0xff;
    }

    public static boolean isImage(Fi file){
        try(InputStream stream = file.read(32)){
            for(int i1 : pngHeader){
                if(stream.read() != i1){
                    return false;
                }
            }
            return true;
        }catch(IOException e){
            return false;
        }
    }

    public static Map createMap(Fi file, boolean custom) throws IOException{
        try(InputStream is = new InflaterInputStream(file.read(bufferSize)); CounterInputStream counter = new CounterInputStream(is); DataInputStream stream = new DataInputStream(counter)){
            SaveIO.readHeader(stream);
            int version = stream.readInt();
            SaveVersion ver = SaveIO.getSaveWriter(version);
            if(ver == null) throw new IOException("Unknown save version: " + version + ". Are you trying to load a save from a newer version?");
            StringMap tags = new StringMap();
            ver.readRegion("meta", stream, counter, in -> tags.putAll(ver.readStringMap(in)));
            int width = tags.getInt("width"), height = tags.getInt("height");
            SaveFileReader.validateMapDimensions(width, height);
            return new Map(file, width, height, tags, custom, version, Version.build);
        }
    }

    public static void writeMap(Fi file, Map map) throws IOException{
        writeMap(file, map, true);
    }

    /** @param embed if true, assets will be embedded in the map - this is needed for external export. */
    public static void writeMap(Fi file, Map map, boolean embed) throws IOException{
        try{
            SaveIO.write(file, new SaveOptions(){{
                extraTags = map.tags;
                embedAssets = embed;
            }});
        }catch(Exception e){
            throw new IOException(e);
        }
    }

    public static void loadMap(Map map){
        SaveIO.load(map.file);
    }

    public static void loadMap(Map map, WorldContext cons){
        SaveIO.load(map.file, cons);
    }

    public static Pixmap generatePreview(Map map) throws IOException{
        map.spawns = 0;
        map.teams.clear();

        try(InputStream is = new InflaterInputStream(map.file.read(bufferSize)); CounterInputStream counter = new CounterInputStream(is); DataInputStream stream = new DataInputStream(counter)){
            SaveIO.readHeader(stream);
            int version = stream.readInt();
            SaveVersion ver = SaveIO.getSaveWriter(version);
            if(ver == null) throw new IOException("Unknown save version: " + version + ". Are you trying to load a save from a newer version?");
            ver.readRegion("meta", stream, counter, ver::readStringMap);

            int width = map.width, height = map.height;
            int len = SaveFileReader.validateMapDimensions(width, height);
            Pixmap floors = new Pixmap(width, height);
            final Pixmap walls;
            try{
                walls = new Pixmap(width, height);
            }catch(RuntimeException | Error e){
                floors.dispose();
                throw e;
            }
            boolean success = false;
            try{
                int black = 255;
                int shade = Color.rgba8888(0f, 0f, 0f, 0.5f);
                short[] floorIds = new short[len];
                boolean[] overlays = new boolean[len];

                CachedTile tile = new CachedTile(){
                    @Override
                    public void setBlock(Block type){
                        //do not super.setBlock as that affects the current world
                        this.block = type;
                        this.build = type.newBuilding().init(this, this.team(), false, 0);
                        int c = colorFor(type, Blocks.air, Blocks.air, team());
                        if(c != black){
                            walls.setRaw(x, floors.height - 1 - y, c);
                            floors.set(x, floors.height - 1 - y + 1, shade);
                        }
                    }
                };

                //version 12 has content patches here, version 11 has them after the content header
                if(ver.version >= 12) ver.skipChunk(stream);
                ver.readRegion("content", stream, counter, MapIO::readPreviewContentHeader);
                if(ver.version == 11) ver.skipChunk(stream);
                ver.readRegion("preview_map", stream, counter, in -> ver.readMap(in, new SaveReadState(new WorldContext(){
                    @Override
                    public void resize(int actualWidth, int actualHeight){
                        if(actualWidth != width || actualHeight != height){
                            throw new IllegalArgumentException("Map dimensions do not match metadata.");
                        }
                    }
                    @Override public boolean isGenerating(){return false;}
                    @Override public void begin(){
                        world.setGenerating(true);
                    }
                    @Override public void end(){
                        world.setGenerating(false);
                    }

                    @Override
                    public void onReadBuilding(){
                        //read team colors
                        if(tile.build != null){
                            int c = tile.build.team.color.rgba8888();
                            int size = tile.block().size;
                            int offsetx = -(size - 1) / 2;
                            int offsety = -(size - 1) / 2;
                            for(int dx = 0; dx < size; dx++){
                                for(int dy = 0; dy < size; dy++){
                                    int drawx = tile.x + dx + offsetx, drawy = tile.y + dy + offsety;
                                    walls.set(drawx, floors.height - 1 - drawy, c);
                                }
                            }

                            if(tile.build.block instanceof CoreBlock){
                                map.teams.add(tile.build.team.id);
                            }
                        }
                    }

                    @Override
                    public Tile tile(int index){
                        tile.x = (short)(index % width);
                        tile.y = (short)(index / width);
                        return tile;
                    }

                    @Override
                    public Tile create(int x, int y, int floorID, int overlayID, int wallID){
                        floors.set(x, floors.height - 1 - y, colorFor(Blocks.air, content.block(floorID), content.block(overlayID), Team.derelict));

                        if(content.block(overlayID) == Blocks.spawn){
                            map.spawns ++;
                        }
                        floorIds[x + y * width] = (short)floorID;
                        overlays[x + y * width] = overlayID != 0;
                        return tile;
                    }

                    @Override
                    public void onReadTileData(){
                        Block block = tile.block();
                        Block floor = content.block(floorIds[tile.x + tile.y*width]);

                        if(!block.synthetic() && block != Blocks.air){
                            int color = block.minimapColor(tile);
                            if(color != 0){
                                walls.set(tile.x, walls.height - 1 - tile.y, color);
                            }
                        }else if(!overlays[tile.array()] && block == Blocks.air){
                            int color = floor.minimapColor(tile);
                            if(color != 0){
                                floors.set(tile.x, floors.height - 1 - tile.y, color);
                            }
                        }
                    }
                }){{
                    preview = true;
                }}));

                floors.draw(walls, true);
                success = true;
                return floors;
            }finally{
                walls.dispose();
                if(!success) floors.dispose();
            }
        }finally{
            content.setTemporaryMapper(null);
        }
    }

    private static void readPreviewContentHeader(DataInput stream) throws IOException{
        //reads content header while refusing to fire patch loaded event
        int mapped = SaveFileReader.checkedLength(stream.readUnsignedByte(), ContentType.all.length, "preview content types");

        MappableContent[][] map = new MappableContent[ContentType.all.length][0];

        for(int i = 0; i < mapped; i++){
            int typeId = SaveFileReader.checkedLength(stream.readUnsignedByte(), ContentType.all.length - 1, "preview content type ID");
            ContentType type = ContentType.all[typeId];
            int total = stream.readUnsignedShort();
            map[type.ordinal()] = new MappableContent[total];

            for(int j = 0; j < total; j++){
                String name = stream.readUTF();
                //fallback only for blocks
                map[type.ordinal()][j] = content.getByName(type, type == ContentType.block ? SaveFileReader.fallback.get(name, name) : name);
            }
        }

        content.setTemporaryMapper(map);
    }

    public static Pixmap generatePreview(Tiles tiles){
        Pixmap pixmap = new Pixmap(tiles.width, tiles.height);
        for(int x = 0; x < pixmap.width; x++){
            for(int y = 0; y < pixmap.height; y++){
                Tile tile = tiles.getn(x, y);
                int color = 0;
                if(!tile.block().synthetic() && tile.block() != Blocks.air){
                    color = tile.block().minimapColor(tile);
                }else if(tile.overlay() == Blocks.air && tile.block() == Blocks.air){
                    color = tile.floor().minimapColor(tile);
                }
                if(color == 0) color = colorFor(tile.block(), tile.floor(), tile.overlay(), tile.team());
                pixmap.set(x, pixmap.height - 1 - y, color);
            }
        }
        return pixmap;
    }

    public static int colorFor(Block wall, Block floor, Block overlay, Team team){
        if(wall.synthetic()){
            return team.color.rgba();
        }
        return (((Floor)overlay).wallOre ? overlay.mapColor.rgba() : wall.solid ? wall.mapColor.rgba() : !overlay.useColor ? floor.mapColor.rgba() :
            (!(overlay instanceof OverlayFloor) ? Pixmap.blend((overlay.mapColor.rgba() & ~0xff) | 128, floor.mapColor.rgba()) : overlay.mapColor.rgba()));
    }

    public static Pixmap writeImage(Tiles tiles){
        Pixmap pix = new Pixmap(tiles.width, tiles.height);
        for(Tile tile : tiles){
            //while synthetic blocks are possible, most of their data is lost, so in order to avoid questions like
            //"why is there air under my drill" and "why are all my conveyors facing right", they are disabled
            int color = tile.block().hasColor && !tile.block().hasBuilding() ? tile.block().mapColor.rgba() : tile.floor().mapColor.rgba();
            pix.set(tile.x, tiles.height - 1 - tile.y, color);
        }
        return pix;
    }

    public static void readImage(Pixmap pixmap, Tiles tiles){
        for(Tile tile : tiles){
            int color = pixmap.get(tile.x, pixmap.height - 1 - tile.y);
            Block block = ColorMapper.get(color);

            //ignore buildings; reading images is only intended for environment tiles
            if(block.hasBuilding()) continue;

            if(block.isOverlay()){
                tile.setOverlay(block.asFloor());
            }else if(block.isFloor()){
                tile.setFloor(block.asFloor());
            }else if(block.isMultiblock()){
                tile.setBlock(block, Team.derelict, 0);
            }else{
                tile.setBlock(block);
            }
        }

        for(Tile tile : tiles){
            //default to stone floor
            if(tile.floor() == Blocks.air){
                tile.setFloor((Floor)Blocks.stone);
            }
        }
    }
}
