package top.mores.battlefield.server;

import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class MohistTeleport {
    private MohistTeleport() {}

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
        sp.server.execute(() -> {
            World w = Bukkit.getWorld(worldName);
            if (w == null && worldName != null && worldName.contains(":")) {
                w = Bukkit.getWorld(worldName.substring(worldName.indexOf(':') + 1));
            }
            if (w == null) {
                sp.sendSystemMessage(net.minecraft.network.chat.Component.literal("世界不存在: " + worldName));
                return;
            }
            Player bp = toBukkit(sp);
            if (bp == null) return;

            Location loc = new Location(w, x, y, z, yaw, pitch);
            bp.setFallDistance(0);
            bp.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            bp.setFireTicks(0);
            bp.teleport(loc);
        });
    }
}
