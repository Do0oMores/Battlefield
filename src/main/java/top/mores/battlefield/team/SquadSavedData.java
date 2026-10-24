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

public class SquadSavedData extends SavedData {
    private static final String DATA_NAME = "battlefield_squads";

    private final Map<UUID, Integer> attackersSquadByPlayer = new HashMap<>();
    private final Map<UUID, Integer> defendersSquadByPlayer = new HashMap<>();

    /**
     * 与 {@link TeamSavedData#get(ServerLevel)} 一致，固定存放在主世界，
     * 避免玩家跨维度后读到另一份空表导致小队归属丢失。
     */
    public static SquadSavedData get(ServerLevel level) {
        if (level == null) throw new IllegalArgumentException("level == null");
        net.minecraft.server.MinecraftServer server = level.getServer();
        ServerLevel anchor = server != null ? server.overworld() : level;
        return anchor.getDataStorage().computeIfAbsent(SquadSavedData::load, SquadSavedData::new, DATA_NAME);
    }

    public static SquadSavedData load(CompoundTag tag) {
        SquadSavedData data = new SquadSavedData();
        loadMap(tag.getList("attackers", Tag.TAG_COMPOUND), data.attackersSquadByPlayer);
        loadMap(tag.getList("defenders", Tag.TAG_COMPOUND), data.defendersSquadByPlayer);
        return data;
    }

    private static void loadMap(ListTag list, Map<UUID, Integer> target) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            target.put(e.getUUID("uuid"), e.getInt("squad"));
        }
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag) {
        tag.put("attackers", saveMap(attackersSquadByPlayer));
        tag.put("defenders", saveMap(defendersSquadByPlayer));
        return tag;
    }

    private static ListTag saveMap(Map<UUID, Integer> source) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Integer> en : source.entrySet()) {
            CompoundTag e = new CompoundTag();
            e.putUUID("uuid", en.getKey());
            e.putInt("squad", en.getValue());
            list.add(e);
        }
        return list;
    }

    public Map<UUID, Integer> mapFor(TeamId team) {
        return switch (team) {
            case ATTACKERS -> attackersSquadByPlayer;
            case DEFENDERS -> defendersSquadByPlayer;
            default -> throw new IllegalArgumentException("SPECTATOR 没有小队");
        };
    }

    public void clearAll() {
        attackersSquadByPlayer.clear();
        defendersSquadByPlayer.clear();
        setDirty();
    }
}
