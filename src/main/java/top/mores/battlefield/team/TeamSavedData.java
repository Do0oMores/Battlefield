package top.mores.battlefield.team;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TeamSavedData extends SavedData {
    private static final String DATA_NAME="battlefield_teams";
    private final Map<UUID,TeamId> teams=new HashMap<>();

    public Map<UUID,TeamId> getTeams(){
        return teams;
    }
    public TeamId getTeam(UUID uuid) {
        return teams.getOrDefault(uuid, TeamId.SPECTATOR);
    }

    public void setTeam(UUID uuid, TeamId team) {
        teams.put(uuid, team);
        setDirty();
    }

    public void remove(UUID uuid) {
        teams.remove(uuid);
        setDirty();
    }

    public static TeamSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TeamSavedData::load, TeamSavedData::new, DATA_NAME);
    }

    public static TeamSavedData load(CompoundTag tag) {
        TeamSavedData data = new TeamSavedData();
        ListTag list = tag.getList("teams", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            UUID uuid = e.getUUID("uuid");
            String teamName = e.getString("team");
            data.teams.put(uuid, TeamId.fromString(teamName));
        }
        return data;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, TeamId> en : teams.entrySet()) {
            CompoundTag e = new CompoundTag();
            e.putUUID("uuid", en.getKey());
            e.putString("team", en.getValue().name());
            list.add(e);
        }
        tag.put("teams", list);
        return tag;
    }
}
