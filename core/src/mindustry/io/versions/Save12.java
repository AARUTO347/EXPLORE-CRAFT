package mindustry.io.versions;

import arc.*;
import arc.struct.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.io.*;
import mindustry.mod.data.*;

import java.io.*;

import static mindustry.Vars.*;

/** Changes data patches to be read before content, and adds support for more complex data patch IO. */
public class Save12 extends SaveVersion{

    public Save12(){
        super(12);
    }

    @Override
    public void readDataPatches(DataInput stream, SaveReadState saveState) throws IOException{
        stream.readInt(); //version - ignored for now

        Seq<DataAsset> assets = new Seq<>();

        int patchAmount = checkedLength(stream.readInt(), mindustry.net.NetworkIO.maxAssetCount, "patches");
        for(int i = 0; i < patchAmount; i++){
            int len = stream.readInt();
            byte[] bytes = readBytes(stream, len, mindustry.net.NetworkIO.maxAssetBytes);
            assets.add(new PatchAsset(new String(bytes, Strings.utf8)));
        }

        int imageAmount = checkedLength(stream.readInt(), mindustry.net.NetworkIO.maxAssetCount - patchAmount, "images");
        for(int i = 0; i < imageAmount; i++){
            String name = stream.readUTF();
            //width/height ignored
            stream.readShort();
            stream.readShort();
            byte[] bytes = readBytes(stream, stream.readInt());
            assets.add(new ImageAsset(name, assetCache.add(bytes)));
        }

        Events.fire(new DataPatchLoadEvent(assets));

        state.data.load(assets);
    }
}
