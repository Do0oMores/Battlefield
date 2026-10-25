package top.mores.battlefield.game;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 赛前背包快照。
 * <p>
 * 起因：{@code startEnding()} 会把参与者背包直接清空，而 {@code finishAndReset()}
 * 只把人传回大厅，不做任何还原 —— 玩家带进来的装备等于被吞掉。
 * 这里在加入对局时把「背包 + 选中槽位 + 游戏模式」存一份，
 * 离开对局 / 结算结束时原样写回。
 * <p>
 * 数据落在主世界的 DataStorage 里，因此不像玩家当前维度那样会跨维度丢失，
 * 也能在服务器异常重启后于下次登录时补发（见 {@code onPlayerLoggedIn}）。
 */
public class PlayerSnapshotStore extends SavedData {
    private static final String DATA_NAME = "battlefield_player_snapshots";

    private final Map<UUID, CompoundTag> snapshots = new HashMap<>();

    public static PlayerSnapshotStore get(ServerLevel level) {
        if (level == null) throw new IllegalArgumentException("level == null");
        net.minecraft.server.MinecraftServer server = level.getServer();
        ServerLevel anchor = server != null ? server.overworld() : level;
        return anchor.getDataStorage()
                .computeIfAbsent(PlayerSnapshotStore::load, PlayerSnapshotStore::new, DATA_NAME);
    }

    /**
     * 记录玩家当前状态。已有快照时不覆盖（避免重复加入把"赛前"覆盖成"赛中"）。
     */
    public static void capture(ServerPlayer player) {
        PlayerSnapshotStore store = get(player.serverLevel());
        UUID id = player.getUUID();
        if (store.snapshots.containsKey(id)) return;

        CompoundTag tag = new CompoundTag();
        tag.put("Inventory", player.getInventory().save(new ListTag()));
        tag.putInt("Selected", player.getInventory().selected);
        tag.putString("GameMode", player.gameMode.getGameModeForPlayer().getName());

        store.snapshots.put(id, tag);
        store.setDirty();
    }

    /**
     * 还原并删除快照。
     *
     * @return 是否确实还原过（没有快照时返回 false）
     */
    public static boolean restore(ServerPlayer player) {
        PlayerSnapshotStore store = get(player.serverLevel());
        CompoundTag tag = store.snapshots.remove(player.getUUID());
        if (tag == null) return false;
        store.setDirty();

        player.getInventory().load(tag.getList("Inventory", Tag.TAG_COMPOUND));
        player.getInventory().selected = Mth.clamp(tag.getInt("Selected"), 0, 8);

        GameType previous = GameType.byName(tag.getString("GameMode"), GameType.SURVIVAL);
        if (player.gameMode.getGameModeForPlayer() != previous) {
            player.setGameMode(previous);
        }

        // 立即把背包同步给客户端，避免出现"服务端有物品、客户端还是空的"
        player.inventoryMenu.broadcastChanges();
        player.sendSystemMessage(net.minecraft.network.chat.Component
                .literal("已恢复进入对局前的背包。")
                .withStyle(ChatFormatting.GRAY));
        return true;
    }

    public static boolean hasSnapshot(ServerPlayer player) {
        return get(player.serverLevel()).snapshots.containsKey(player.getUUID());
    }

    public int size() {
        return snapshots.size();
    }

    public static PlayerSnapshotStore load(CompoundTag tag) {
        PlayerSnapshotStore data = new PlayerSnapshotStore();
        ListTag list = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID("uuid")) continue;
            data.snapshots.put(entry.getUUID("uuid"), entry.getCompound("data"));
        }
        return data;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, CompoundTag> entry : snapshots.entrySet()) {
            CompoundTag item = new CompoundTag();
            item.putUUID("uuid", entry.getKey());
            item.put("data", entry.getValue());
            list.add(item);
        }
        tag.put("players", list);
        return tag;
    }
}
