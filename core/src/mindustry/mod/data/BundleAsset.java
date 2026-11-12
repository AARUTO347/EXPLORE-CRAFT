package mindustry.mod.data;

import arc.files.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import mindustry.io.*;

import java.io.*;

public class BundleAsset extends DataAsset{
    public @Nullable OrderedMap<String, String> cachedBundle;

    public void tryLoadCache(){
        if(cachedBundle != null) return;
        try{
            var bundle = new OrderedMap<String, String>();
            Fi file = getCacheFile();
            if(file != null){
                try(var reader = openReader(file)){
                    PropertiesUtils.load(bundle, reader);
                }
            }
            cachedBundle = bundle;
        }catch(Exception e){
            Log.err(e);
        }
    }

    public static Reader openReader(Fi file) throws IOException{
        checkedFileLength(file, SaveFileReader.maxChunkBytes);
        return new InputStreamReader(new FilterInputStream(file.read()){
            private long remaining = SaveFileReader.maxChunkBytes;

            @Override
            public int read() throws IOException{
                int value = in.read();
                if(value != -1){
                    if(remaining == 0) throw new IOException("Bundle exceeds byte limit");
                    remaining--;
                }
                return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException{
                if(length == 0) return 0;
                if(remaining == 0) return read() == -1 ? -1 : 0;
                int amount = in.read(bytes, offset, (int)Math.min(length, remaining));
                if(amount > 0) remaining -= amount;
                return amount;
            }
        }, Strings.utf8);
    }

    @Override
    public void updateData(byte[] data){
        super.updateData(data);
        this.cachedBundle = null;
    }

    @Override
    public DataAssetType getType(){
        return DataAssetType.bundle;
    }
}
