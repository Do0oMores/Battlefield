package top.mores.battlefield.game;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 赛前快照。
 * <p>
 * 起因：{@code startEnding()} 会把参与者背包直接清空，而 {@code finishAndReset()}
 * 只把人传回大厅，不做任何还原 —— 玩家带进来的装备等于被吞掉。
 * 这里在玩家<b>进入对局世界</b>时存一份「背包 + 选中槽位 + 游戏模式 + 进场前位置 + 重生点」，
 * 然后清空背包（装备由部署配装发放），离开对局 / 结算结束时原样写回并送回原位置。
 * <p>
 * 进场即清空是为了堵住复制：快照拍完背包还在身上的话，玩家可以在对局中把东西存进末影箱或交给他人，
 * 结算时快照再还一份。
 * <p>
 * 数据落在主世界的 DataStorage 里，因此不像玩家当前维度那样会跨维度丢失，
 * 也能在服务器异常重启后于下次登录时补发（见 {@code onLoggedIn}）。
 * <p>
 * 掉线时只还原背包（这一步会随紧接着的玩家存档一起落盘），位置记为「待返回」，
 * 下次登录再传送——不在登出事件里做跨维度传送。
 */
public class PlayerSnapshotStore extends SavedData {
    private static final String DATA_NAME = "battlefield_player_snapshots";

    private final Map<UUID, CompoundTag> snapshots = new HashMap<>();

    /** 返回点：维度 + 坐标 + 朝向。 */
    public record ReturnPoint(ResourceKey<Level> dimension, double x, double y, double z, float yaw, float pitch) {
    }

    public static PlayerSnapshotStore get(ServerLevel level) {
        if (level == null) throw new IllegalArgumentException("level == null");
        net.minecraft.server.MinecraftServer server = level.getServer();
        ServerLevel anchor = server != null ? server.overworld() : level;
        return anchor.getDataStorage()
                .computeIfAbsent(PlayerSnapshotStore::load, PlayerSnapshotStore::new, DATA_NAME);
    }

    /**
     * 记录玩家当前状态并清空背包。已有快照时不覆盖（避免重复进入把"赛前"覆盖成"赛中"）。
     */
    public static void captureAndClear(ServerPlayer player) {
        PlayerSnapshotStore store = get(player.serverLevel());
        UUID id = player.getUUID();
        if (!store.snapshots.containsKey(id)) {
            CompoundTag tag = new CompoundTag();
            tag.put("Inventory", player.getInventory().save(new ListTag()));
            tag.putInt("Selected", player.getInventory().selected);
            tag.putString("GameMode", player.gameMode.getGameModeForPlayer().getName());

            CompoundTag ret = new CompoundTag();
            ret.putString("Dim", player.level().dimension().location().toString());
            ret.putDouble("X", player.getX());
            ret.putDouble("Y", player.getY());
            ret.putDouble("Z", player.getZ());
            ret.putFloat("Yaw", player.getYRot());
            ret.putFloat("Pitch", player.getXRot());
            tag.put("Return", ret);

            CompoundTag respawn = new CompoundTag();
            respawn.putString("Dim", player.getRespawnDimension().location().toString());
            if (player.getRespawnPosition() != null) {
                respawn.put("Pos", NbtUtils.writeBlockPos(player.getRespawnPosition()));
            }
            respawn.putFloat("Angle", player.getRespawnAngle());
            respawn.putBoolean("Forced", player.isRespawnForced());
            tag.put("Respawn", respawn);

            store.snapshots.put(id, tag);
            store.setDirty();
        }

        player.getInventory().clearContent();
        player.removeAllEffects();
        if (player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) {
            player.setGameMode(GameType.SURVIVAL);
        }
        player.setHealth(player.getMaxHealth());
        player.inventoryMenu.broadcastChanges();
    }

    /**
     * 还原背包 / 游戏模式 / 重生点并删除快照。
     *
     * @return 进场前的位置；没有快照时返回 null
     */
    @Nullable
    public static ReturnPoint restore(ServerPlayer player) {
        PlayerSnapshotStore store = get(player.serverLevel());
        CompoundTag tag = store.snapshots.remove(player.getUUID());
        if (tag == null) return null;
        store.setDirty();
        if (!tag.getBoolean("Pending")) {
            applyInventory(player, tag);
        }
        return readReturn(tag);
    }

    /**
     * 掉线时调用：立即还原背包（随后的玩家存档会带上它），返回点保留到下次登录。
     */
    public static void restoreInventoryKeepReturn(ServerPlayer player) {
        PlayerSnapshotStore store = get(player.serverLevel());
        CompoundTag tag = store.snapshots.get(player.getUUID());
        if (tag == null || tag.getBoolean("Pending")) return;
        applyInventory(player, tag);
        tag.remove("Inventory");
        tag.putBoolean("Pending", true);
        store.setDirty();
    }

    public static boolean hasSnapshot(ServerPlayer player) {
        return get(player.serverLevel()).snapshots.containsKey(player.getUUID());
    }

    public int size() {
        return snapshots.size();
    }

    private static void applyInventory(ServerPlayer player, CompoundTag tag) {
        player.getInventory().load(tag.getList("Inventory", Tag.TAG_COMPOUND));
        player.getInventory().selected = Mth.clamp(tag.getInt("Selected"), 0, 8);

        GameType previous = GameType.byName(tag.getString("GameMode"), GameType.SURVIVAL);
        if (player.gameMode.getGameModeForPlayer() != previous) {
            player.setGameMode(previous);
        }

        // 重生点：对局里被改成了本方出生点，这里还给玩家原来的床 / 重生锚
        CompoundTag respawn = tag.getCompound("Respawn");
        ResourceKey<Level> dim = dimension(respawn.getString("Dim"));
        BlockPos pos = respawn.contains("Pos") ? NbtUtils.readBlockPos(respawn.getCompound("Pos")) : null;
        player.setRespawnPosition(dim != null ? dim : Level.OVERWORLD, pos, respawn.getFloat("Angle"),
                respawn.getBoolean("Forced"), false);

        // 立即把背包同步给客户端，避免出现"服务端有物品、客户端还是空的"
        player.inventoryMenu.broadcastChanges();
        player.sendSystemMessage(net.minecraft.network.chat.Component
                .literal("已恢复进入对局前的背包。")
                .withStyle(ChatFormatting.GRAY));
    }

    @Nullable
    private static ReturnPoint readReturn(CompoundTag tag) {
        if (!tag.contains("Return", Tag.TAG_COMPOUND)) return null;
        CompoundTag r = tag.getCompound("Return");
        ResourceKey<Level> dim = dimension(r.getString("Dim"));
        if (dim == null) return null;
        return new ReturnPoint(dim, r.getDouble("X"), r.getDouble("Y"), r.getDouble("Z"), r.getFloat("Yaw"), r.getFloat("Pitch"));
    }

    @Nullable
    private static ResourceKey<Level> dimension(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? null : ResourceKey.create(Registries.DIMENSION, rl);
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
