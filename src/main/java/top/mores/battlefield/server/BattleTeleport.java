package top.mores.battlefield.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.config.SectorConfigLoader;
import top.mores.battlefield.game.PlayerSnapshotStore;
import top.mores.battlefield.server.arena.BattleWorlds;

import javax.annotation.Nullable;

/**
 * 对局相关传送的统一出口。
 * <p>
 * 解析顺序沿用三轮修复的结论：NMS 维度已加载 → 直接 NMS 传送（同步、可校验）；
 * 只存在于 Bukkit 侧 → 交给 Bukkit；都解析不到 → 告警并取消（不静默丢进错误的维度）。
 * <p>
 * 在此基础上补了两件事：
 * <ul>
 *   <li>传送前先下坐骑、甩掉乘客、关容器、叫醒、清掉速度/摔落/着火，避免带着矿车跨维度或卡在床上；</li>
 *   <li>「送玩家离场」有三级兜底（返回点 → lobby → 主世界出生点），任何一级失败都不会把人留在战场维度。</li>
 * </ul>
 */
public final class BattleTeleport {
    private BattleTeleport() {
    }

    /** 同步 NMS 传送。@return 是否已经到达目标维度 */
    public static boolean toLevel(ServerPlayer player, ServerLevel level, double x, double y, double z, float yaw, float pitch) {
        if (level == null) return false;
        prepare(player);
        player.teleportTo(level, x, y, z, yaw, pitch);
        if (player.serverLevel() != level) {
            Battlefield.LOGGER.warn("[Battlefield] 传送后玩家 {} 仍在 {}，目标 {}（可能被其他 mod 取消）",
                    player.getGameProfile().getName(), player.serverLevel().dimension().location(), level.dimension().location());
            return false;
        }
        return true;
    }

    /**
     * 按配置坐标传送：NMS → Bukkit → 告警取消。
     *
     * @return true 表示已传送或已交给 Bukkit 排队；false 表示世界解析失败
     */
    public static boolean toPosition(ServerPlayer player, SectorConfigLoader.Position pos, @Nullable Runnable onRejected) {
        if (pos == null) return false;
        MinecraftServer server = player.getServer();
        if (server == null) return false;

        ServerLevel level = pos.resolveLevel(server, null);
        if (level != null) {
            return toLevel(player, level, pos.x(), pos.y(), pos.z(), player.getYRot(), player.getXRot());
        }

        prepare(player);
        if (MohistTeleport.teleportToWorld(player, pos.world(), pos.x(), pos.y(), pos.z(),
                player.getYRot(), player.getXRot(), onRejected)) {
            return true;
        }

        Battlefield.LOGGER.warn("[Battlefield] 世界 '{}' 无法解析，已取消传送（玩家 {}）",
                pos.world(), player.getGameProfile().getName());
        return false;
    }

    /**
     * 送玩家离场：进场前的位置 → arena 的 lobby → 主世界出生点。
     * 返回点本身在战场维度里（例如管理员在战场里执行了 /bt join）时直接跳过。
     */
    public static void sendHome(ServerPlayer player, @Nullable PlayerSnapshotStore.ReturnPoint ret,
                                @Nullable SectorConfigLoader.Position lobby) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        if (ret != null) {
            ServerLevel level = server.getLevel(ret.dimension());
            if (level != null && !BattleWorlds.isBattleLevel(level)
                    && toLevel(player, level, ret.x(), ret.y(), ret.z(), ret.yaw(), ret.pitch())) {
                return;
            }
        }
        if (lobby != null && toPosition(player, lobby, () -> toWorldSpawn(player))) {
            return;
        }
        toWorldSpawn(player);
    }

    private static void toWorldSpawn(ServerPlayer player) {
        ServerLevel overworld = player.server.overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        toLevel(player, overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, player.getYRot(), player.getXRot());
    }

    private static void prepare(ServerPlayer player) {
        if (player.isSleeping()) player.stopSleeping();
        if (player.isPassenger()) player.stopRiding();
        player.ejectPassengers();
        if (player.containerMenu != player.inventoryMenu) player.closeContainer();
        player.setDeltaMovement(0, 0, 0);
        player.fallDistance = 0.0f;
        player.clearFire();
    }
}
