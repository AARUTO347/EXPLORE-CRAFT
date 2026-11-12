package mindustry.mod.data;

import arc.files.*;
import arc.util.*;
import mindustry.*;
import mindustry.io.*;
import mindustry.mod.*;

import java.io.*;

import static mindustry.Vars.*;

/** Abstract class for a kind of asset used in a data asset modification. */
public abstract class DataAsset implements Comparable<DataAsset>{
    public static final int maxTextBytes = 64 * 1024 * 1024;

    /** Checks metadata before opening a file or allocating its contents. */
    public static int checkedFileLength(Fi file, int maximum) throws IOException{
        long length = file.length();
        if(length < 0 || length > maximum) throw new IOException("Invalid asset file size: " + length);
        return SaveFileReader.checkedLength((int)length, maximum, "asset file");
    }

    /** Reads incrementally, and rejects files that change size while being read. */
    public static byte[] readFileBytes(Fi file, int maximum) throws IOException{
        int length = checkedFileLength(file, maximum);
        try(DataInputStream input = new DataInputStream(file.read())){
            byte[] bytes = SaveFileReader.readBytes(input, length, maximum);
            if(input.read() != -1) throw new IOException("Asset file exceeds advertised size");
            return bytes;
        }
    }

    /** Counts UTF-8 bytes before encoding, including Java's replacement for lone surrogates. */
    protected static byte[] textBytes(String text) throws IOException{
        long length = 0;
        for(int i = 0; i < text.length(); i++){
            char c = text.charAt(i);
            if(c < 0x80){
                length++;
            }else if(c < 0x800){
                length += 2;
            }else if(Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))){
                length += 4;
                i++;
            }else{
                length += Character.isSurrogate(c) ? 1 : 3;
            }
            if(length > maxTextBytes) throw new IOException("Asset text exceeds byte limit");
        }
        byte[] bytes = text.getBytes(Strings.utf8);
        SaveFileReader.checkedLength(bytes.length, maxTextBytes, "asset text");
        return bytes;
    }

    /** set by the server to force content files to point to a specific folder */
    private @Nullable Fi overrideCacheFile;

    /** File path, including name and extension, but excluding base folder prefix. */
    public String path = "";
    /** File name, excluding extension. This is taken from the path. */
    public String name = "";

    /** sha256 of the internal data. this is null for non-external assets. */
    public @Nullable byte[] byteHash;
    public @Nullable String stringHash;

    /** Caches this asset in the asset folder, and updates its hash to correspond to the appropriate cache file. */
    public void updateData(byte[] data){
        setHash(Vars.assetCache.add(data));
    }

    public void setHash(byte[] value){
        if(value.length != 32) throw new IllegalArgumentException("hash must be 32 bytes long: " + value.length);
        byteHash = value;
        stringHash = DataAssetCache.encodeHash(value);
    }

    public void setPath(String path){
        this.path = path.replace('\\', '/');
        this.name = Strings.getFileNameWithoutExtension(path);
    }

    public abstract DataAssetType getType();

    public String getFullPath(){
        return getType().folder + "/" + path;
    }

    /** @return for embedded assets, the data. Not implemented for non-embedded assets (use the cache file). */
    public byte[] getData(){
        throw new UnsupportedOperationException("Not implemented");
    }

    public boolean isAlwaysEmbedded(){
        return getType().embedded;
    }

    public boolean isCached(){
        return overrideCacheFile != null || (stringHash != null && Vars.assetCache.has(stringHash));
    }

    public Fi getCacheFileNoNull(){
        Fi file = getCacheFile();
        if(file == null) throw new RuntimeException("Cache file for asset " + path + " not found!");
        return file;
    }

    public @Nullable Fi getCacheFile(){
        return overrideCacheFile != null ? overrideCacheFile : Vars.assetCache.get(stringHash);
    }

    public void readFromZip(String path, Fi file){
        if(isAlwaysEmbedded()) throw new UnsupportedOperationException("Always-embedded assets need custom implementations for reading.");
        setPath(path);
        try{
            updateData(readFileBytes(file, SaveFileReader.maxChunkBytes));
        }catch(IOException e){
            throw new ArcRuntimeException(e);
        }
    }

    /** Reads this asset in from a file on disk, and makes its hash point to the specified file, instead of a new one in the cache folder. Only used on the server. */
    public void readOverride(String path, Fi file) throws IOException{
        checkedFileLength(file, SaveFileReader.maxChunkBytes);
        setPath(path);
        setHash(file.sha256());
        this.overrideCacheFile = file;
        //manually make this asset's hash refer to its file in the folder instead of using a folder
        assetCache.addOverride(stringHash, file);
    }

    public void read(DataInput stream) throws IOException{
        int length = SaveFileReader.checkedLength(stream.readInt(), SaveFileReader.maxChunkBytes, "embedded asset");
        if(length == 0){
            Log.err("Empty asset in save: @", path);
            return;
        }

        updateData(SaveFileReader.readBytes(stream, length));
    }

    public void write(DataOutput stream) throws IOException{
        Fi file = getCacheFile();
        if(file == null || !file.exists()){
            Log.err("Failed to embed asset in save: missing cache file: " + path);
            stream.writeInt(0);
            return;
        }

        checkedFileLength(file, SaveFileReader.maxChunkBytes);
        try{
            byte[] bytes = readFileBytes(file, SaveFileReader.maxChunkBytes);
            stream.writeInt(bytes.length);
            stream.write(bytes);
        }catch(ArcRuntimeException e){
            Log.err("Failed to write asset to save: " + path, e);
            stream.writeInt(0);
        }
    }

    public static boolean validPath(String path){
        for(String part : path.replace('\\', '/').split("/")){
            if(part.equals("..") || part.equals(".")){
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString(){
        return getClass().getSimpleName() + "{" + path + "}";
    }

    @Override
    public int compareTo(DataAsset asset){
        int cmp = getType().compareTo(asset.getType());
        if(cmp != 0) return cmp;
        return path.compareTo(asset.path);
    }
}
