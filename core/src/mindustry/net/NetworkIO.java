package mindustry.net;

import arc.*;
import arc.files.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.logic.*;
import mindustry.maps.Map;
import mindustry.mod.*;
import mindustry.mod.data.*;
import mindustry.net.Administration.*;
import mindustry.type.*;

import java.io.*;
import java.nio.*;
import java.util.*;

import static mindustry.Vars.*;

public class NetworkIO{

    public static void writeWorld(Player player, OutputStream os){

        try(DataOutputStream stream = new DataOutputStream(os)){
            //write all researched content to rules if hosting
            if(state.isCampaign()){
                state.rules.researched.clear();
                for(ContentType type : ContentType.all){
                    for(Content c : content.getBy(type)){
                        if(c instanceof UnlockableContent u && u.unlocked() && u.techNode != null){
                            state.rules.researched.add(u);
                        }
                    }
                }
            }

            var writer = SaveIO.getSaveWriter();

            //data patches must be first, as rules can involve patched content
            writer.writeDataPatches(stream, false);

            stream.writeUTF(JsonIO.write(state.rules));
            stream.writeUTF(JsonIO.write(state.mapLocales));
            writer.writeStringMap(stream, state.map.tags);

            stream.writeInt(state.wave);
            stream.writeFloat(state.wavetime);
            stream.writeDouble(state.tick);
            stream.writeLong(GlobalVars.rand.seed0);
            stream.writeLong(GlobalVars.rand.seed1);

            stream.writeInt(player.id);
            player.write(new Writes(stream));

            writer.writeContentHeader(stream);
            writer.writeMap(stream);
            //these three calls mimic what writeEntities has, except with a custom filter, which is a bit fragile
            writer.writeEntityMapping(stream);
            writer.writeTeamBlocks(stream);
            writer.writeWorldEntities(stream, state.rules.fog ? u -> !u.inFogTo(player.team()) : null);

            writer.writeMarkers(stream);
            writer.writeCustomChunks(stream, true);
        }catch(IOException e){
            throw new RuntimeException(e);
        }
    }

    public static void loadWorld(InputStream is){

        try(DataInputStream stream = new DataInputStream(is)){
            var writer = SaveIO.getSaveWriter();
            Time.clear();
            writer.readDataPatches(stream, new SaveReadState(world.context));

            state.rules = JsonIO.read(Rules.class, stream.readUTF());
            state.mapLocales = JsonIO.read(MapLocales.class, stream.readUTF());
            state.map = new Map(writer.readStringMap(stream));

            state.wave = stream.readInt();
            state.wavetime = stream.readFloat();
            state.tick = stream.readDouble();
            GlobalVars.rand.seed0 = stream.readLong();
            GlobalVars.rand.seed1 = stream.readLong();

            Reads read = new Reads(stream);

            Groups.clear();
            int id = stream.readInt();
            player.reset();
            player.read(read);
            player.id = id;
            player.add();

            var state = new SaveReadState(world.context);

            writer.readContentHeader(stream);
            writer.readMap(stream, state);
            writer.readEntities(stream, state);
            writer.readMarkers(stream);
            writer.readCustomChunks(stream);

            Groups.all.each(e -> netClient.addRemovedEntity(e.id()));
            Groups.unit.each(e -> netClient.addRemovedEntity(e.id()));
        }catch(IOException e){
            throw new RuntimeException(e);
        }finally{
            content.setTemporaryMapper(null);
        }
    }

    public static final int maxAssetCount = Short.MAX_VALUE, maxAssetBytes = 64 * 1024 * 1024;
    public static final int maxAssetTransferBytes = 256 * 1024 * 1024;

    public static void writeRequiredAssets(OutputStream os, Seq<DataAsset> assets){
        try(DataOutputStream stream = new DataOutputStream(os)){
            int amount = SaveFileReader.checkedLength(assets.size, maxAssetCount, "required asset count");
            //Validate every hash before writing the header. IDs use signed shorts.
            for(int i = 0; i < amount; i++){
                var asset = assets.get(i);
                if(asset.byteHash == null || asset.byteHash.length != 32) throw new IOException("Invalid asset hash: " + asset.path);
            }
            stream.writeInt(amount);
            for(int i = 0; i < amount; i++){
                stream.write(assets.get(i).byteHash);
            }
        }catch(IOException e){
            throw new RuntimeException(e);
        }
    }

    public static Seq<String> readRequiredAssets(InputStream is){
        Seq<String> result = new Seq<>();
        try(DataInputStream stream = new DataInputStream(is)){
            int amount = SaveFileReader.checkedLength(stream.readInt(), maxAssetCount, "required asset count");
            SaveFileReader.requireBytes(stream, (long)amount * 32);
            for(int i = 0; i < amount; i++){
                result.add(DataAssetCache.encodeHash(SaveFileReader.readBytes(stream, 32, 32)));
            }
            if(stream.read() != -1) throw new IOException("Surplus required asset data");
        }catch(IOException e){
            throw new RuntimeException(e);
        }
        return result;
    }

    public static void writeAssets(OutputStream os, Seq<DataAsset> assets){
        try(DataOutputStream stream = new DataOutputStream(os)){
            int amount = SaveFileReader.checkedLength(assets.size, maxAssetCount, "asset count");
            long total = 4L + 4L * amount;
            //Check all metadata and the aggregate before reading any whole file.
            Fi[] files = new Fi[amount];
            int[] lengths = new int[amount];
            for(int i = 0; i < amount; i++){
                files[i] = assets.get(i).getCacheFileNoNull();
                lengths[i] = DataAsset.checkedFileLength(files[i], maxAssetBytes);
                total += lengths[i];
                if(total > maxAssetTransferBytes) throw new IOException("Asset transfer exceeds byte limit");
            }
            stream.writeInt(amount);
            for(int i = 0; i < amount; i++){
                byte[] bytes = DataAsset.readFileBytes(files[i], maxAssetBytes);
                if(bytes.length != lengths[i]) throw new IOException("Asset changed size while packing");
                stream.writeInt(bytes.length);
                stream.write(bytes);
            }
        }catch(IOException e){
            throw new RuntimeException(e);
        }
    }

    public static void loadAssets(InputStream is) throws IOException{
        //available() is exact only for a completed byte-array stream, never for a live stream.
        loadAssets(is, is instanceof ByteArrayInputStream ? is.available() : maxAssetTransferBytes, is instanceof ByteArrayInputStream);
    }

    public static void loadAssets(InputStream is, int advertisedTotal) throws IOException{
        loadAssets(is, advertisedTotal, true);
    }

    private static void loadAssets(InputStream is, int budget, boolean exact) throws IOException{
        try(DataInputStream stream = new DataInputStream(is)){
            SaveFileReader.checkedLength(budget, maxAssetTransferBytes, "asset transfer");
            if(budget < 4) throw new IOException("Truncated asset count");
            int amount = SaveFileReader.checkedLength(stream.readInt(), maxAssetCount, "asset count");
            long consumed = 4, headers = 4L * amount;
            if(headers > budget - consumed) throw new IOException("Truncated asset record headers");
            SaveFileReader.requireBytes(stream, headers);
            for(int i = 0; i < amount; i++){
                int len = SaveFileReader.checkedLength(stream.readInt(), maxAssetBytes, "asset size");
                consumed += 4;
                long reservedHeaders = 4L * (amount - i - 1);
                if(len > budget - consumed - reservedHeaders) throw new IOException("Asset transfer exceeds remaining byte budget");
                byte[] bytes = SaveFileReader.readBytes(stream, len, maxAssetBytes);
                consumed += len;
                assetCache.add(bytes);
            }
            if(exact && consumed != budget) throw new IOException("Asset transfer length mismatch");
            //For incremental streams this waits for finish(), or throws on cancellation.
            if(stream.read() != -1) throw new IOException("Surplus asset transfer data");
        }
    }

    public static void packTexture(OutputStream os, String name, byte[] pngData){
        try(DataOutputStream stream = new DataOutputStream(os)){
            SaveFileReader.checkedLength(pngData.length, maxAssetBytes, "texture size");
            stream.writeUTF(name);
            stream.writeInt(pngData.length);
            stream.write(pngData);
        }catch(IOException e){
            throw new RuntimeException(e);
        }
    }

    public static ByteBuffer writeServerData(){
        String name = (headless ? Config.serverName.string() : player.name);
        String description = headless && !Config.desc.string().equals("off") ? Config.desc.string() : "";
        String map = state.map.name();

        ByteBuffer buffer = ByteBuffer.allocate(500);

        writeString(buffer, name, 100);
        writeString(buffer, map, 64);

        buffer.putInt(Core.settings.getInt("totalPlayers", Groups.player.size()));
        buffer.putInt(state.wave);
        buffer.putInt(Version.build);
        writeString(buffer, Version.type);

        buffer.put((byte)state.rules.mode().ordinal());
        buffer.putInt(netServer.admins.getPlayerLimit());

        writeString(buffer, description, 100);
        writeString(buffer, state.rules.modeName == null ? "" : state.rules.modeName, 50);
        buffer.putShort((short)Core.settings.getInt("port", port));
        return buffer;
    }

    public static Host readServerData(int ping, String hostAddress, ByteBuffer buffer){
        String host = readString(buffer);
        String map = readString(buffer);

        int players = buffer.getInt();
        int wave = buffer.getInt();
        int version = buffer.getInt();
        String vertype = readString(buffer);

        int mode = buffer.get() & 0xff;
        Gamemode gamemode = Gamemode.all[mode < Gamemode.all.length ? mode : 0];
        int limit = buffer.getInt();

        String description = readString(buffer);
        String modeName = readString(buffer);
        int port = buffer.getShort() & 0xffff;
        int hostPort = port != 0 ? port : Vars.port;

        return new Host(ping, host, hostAddress, hostPort, map, wave, players, version, vertype, gamemode, limit, description, modeName.isEmpty() ? null : modeName);
    }

    private static void writeString(ByteBuffer buffer, String string, int maxlen){
        byte[] bytes = string.getBytes(charset);
        //todo truncating this way may lead to wierd encoding errors at the ends of strings...
        if(bytes.length > maxlen){
            bytes = Arrays.copyOfRange(bytes, 0, maxlen);
        }

        buffer.put((byte)bytes.length);
        buffer.put(bytes);
    }

    private static void writeString(ByteBuffer buffer, String string){
        writeString(buffer, string, 32);
    }

    private static String readString(ByteBuffer buffer){
        short length = (short)(buffer.get() & 0xff);
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, charset);
    }
}
