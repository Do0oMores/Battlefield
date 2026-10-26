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
    private static final String DATA_NAME = "battlefield_teams";
    private final Map<UUID, TeamId> teams = new HashMap<>();

    private final Map<UUID, top.mores.battlefield.game.medical.MedicalState> medical = new HashMap<>();

    public top.mores.battlefield.game.medical.MedicalState medical(UUID id) {
        return medical.computeIfAbsent(id, key -> new top.mores.battlefield.game.medical.MedicalState());
    }

    public void clearMedical() { medical.clear(); setDirty(); }

    public Map<UUID, TeamId> getTeams() {
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

    /**
     * 固定存放在主世界的 DataStorage 中。
     * <p>
     * SavedData 是「每个维度一份」的，如果按 player.serverLevel() 取，
     * 玩家一旦跨维度（大厅 / 战场 / 末地），队伍数据就会读到另一份空表而瞬间归零。
     * 这里统一锚定到 overworld，调用方不需要关心自己传进来的是哪个维度。
     */
    public static TeamSavedData get(ServerLevel level) {
        ServerLevel anchor = resolveAnchor(level);
        return anchor.getDataStorage().computeIfAbsent(TeamSavedData::load, TeamSavedData::new, DATA_NAME);
    }

    static ServerLevel resolveAnchor(ServerLevel level) {
        if (level == null) return null;
        net.minecraft.server.MinecraftServer server = level.getServer();
        return server != null ? server.overworld() : level;
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
        ListTag medicalList = tag.getList("medical", Tag.TAG_COMPOUND);
        for (int i = 0; i < medicalList.size(); i++) {
            CompoundTag e = medicalList.getCompound(i);
            try {
                var state = new top.mores.battlefield.game.medical.MedicalState();
                state.restore(top.mores.battlefield.game.medical.MedicalState.Role.valueOf(e.getString("role")),
                        e.getUUID("life"), e.getString("match"), e.getString("team"), e.getBoolean("deployed"), e.getInt("reserve"));
                data.medical.put(e.getUUID("uuid"), state);
            } catch (IllegalArgumentException ignored) { /* Invalid saved entry cannot grant supplies. */ }
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
        ListTag medicalList = new ListTag();
        medical.forEach((id, state) -> {
            CompoundTag e = new CompoundTag();
            e.putUUID("uuid", id); e.putUUID("life", state.life);
            e.putString("role", state.role.name()); e.putString("match", state.match); e.putString("team", state.team);
            e.putBoolean("deployed", state.deployed); e.putInt("reserve", state.reserve());
            medicalList.add(e);
        });
        tag.put("medical", medicalList);
        return tag;
    }
}
