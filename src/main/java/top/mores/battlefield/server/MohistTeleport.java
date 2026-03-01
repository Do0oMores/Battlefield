package top.mores.battlefield.server;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;

public final class MohistTeleport {
    private MohistTeleport() {}

    public static String normalizeWorldName(String worldName) {
        if (worldName == null) return null;
        int idx = worldName.indexOf(':');
        return (idx >= 0) ? worldName.substring(idx + 1) : worldName;
    }

    /** 获取玩家当前 Bukkit 世界名；失败返回 null */
    public static String getCurrentWorldName(ServerPlayer sp) {
        try {
            Object bukkitEntity = sp.getClass().getMethod("getBukkitEntity").invoke(sp);
            if (bukkitEntity instanceof Player bp && bp.getWorld() != null) {
                return bp.getWorld().getName();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Player toBukkit(ServerPlayer sp) {
        try {
            Object bukkitEntity = sp.getClass().getMethod("getBukkitEntity").invoke(sp);
            return (Player) bukkitEntity;
        } catch (Throwable t) {
            return Bukkit.getPlayer(sp.getUUID());
        }
    }

    public static void teleportToWorld(ServerPlayer sp, String worldName,
                                       double x, double y, double z,
                                       float yaw, float pitch) {

        final String wn = normalizeWorldName(worldName);

        sp.server.execute(() -> {
            Player bp = toBukkit(sp);
            if (bp == null) return;

            World w = Bukkit.getWorld(wn);
            if (w == null) {
                try {
                    w = Bukkit.createWorld(new WorldCreator(wn));
                } catch (Throwable ignored) {}
            }

            if (w == null) {
                sp.sendSystemMessage(Component.literal("世界不存在或未加载: " + wn));
                return;
            }

            Location loc = new Location(w, x, y, z, yaw, pitch);

            bp.setFallDistance(0);
            bp.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            bp.setFireTicks(0);

            boolean ok = bp.teleport(loc);
            sp.sendSystemMessage(Component.literal("TP -> " + wn + " @ " + x + "," + y + "," + z));
            if (!ok) {
                sp.sendSystemMessage(Component.literal("传送失败（可能被插件拦截）: " + wn));
            }
        });
    }
}