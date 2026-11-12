package mindustry.io.versions;

import mindustry.game.*;
import mindustry.game.Teams.*;
import mindustry.io.*;

import java.io.*;

import static mindustry.Vars.*;

public class Save3 extends LegacySaveVersion{

    public Save3(){
        super(3);
    }

    @Override
    public void readEntities(DataInput stream, SaveReadState state) throws IOException{
        int teamc = checkedLength(stream.readInt(), Team.all.length, "teams");
        for(int i = 0; i < teamc; i++){
            Team team = Team.get(checkedLength(stream.readInt(), Team.all.length - 1, "team ID"));
            TeamData data = team.data();
            int blocks = checkedLength(stream.readInt(), maxMapTiles, "block plans");
            for(int j = 0; j < blocks; j++){
                data.plans.addLast(new BlockPlan(stream.readShort(), stream.readShort(), stream.readShort(), content.block(stream.readShort()), stream.readInt()));
            }
        }

        readLegacyEntities(stream);
    }
}
