package top.mores.battlefield.server;

import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import top.mores.battlefield.Battlefield;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MohistTeleport {
    private MohistTeleport() {}

    /** 去掉命名空间：minecraft:overworld -> overworld */
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

    /**
     * 原版维度的维度 ID → 可能的 Bukkit 世界名。
     * <p>
     * CraftBukkit 系（含 Mohist）默认把下界/末地作为主世界的子目录挂在
     * {@code <level-name>_nether} / {@code <level-name>_the_end} 下，
     * 与维度 ID 的 {@code the_nether} / {@code the_end} 并不相同。
     * 这里只列「正确的」别名，故意不包含 {@code the_nether} / {@code the_end}：
     * 那两个名字恰好是历史 bug（createWorld）会造出来的假世界，不能被优先命中。
     */
    private static final Map<String, List<String>> VANILLA_WORLD_ALIASES = Map.of(
            "minecraft:overworld", List.of("world", "overworld"),
            "minecraft:the_nether", List.of("world_nether", "DIM-1"),
            "minecraft:the_end", List.of("world_the_end", "DIM1")
    );

    /**
     * 把配置里的世界标识解析成 Bukkit 世界。
     * <p>
     * 解析顺序：
     * <ol>
     *   <li>原版维度：按 {@link #VANILLA_WORLD_ALIASES} 的已知别名，再按维度 Key 匹配；</li>
     *   <li>其他标识：先按 Bukkit 世界名，再按维度 NamespacedKey 匹配；</li>
     *   <li>仍未命中时：<b>只有不带命名空间的裸名</b>才允许 {@code createWorld}。</li>
     * </ol>
     *
     * 为什么第 3 步要加限定：Mohist 上 {@code createWorld} 是「能成功」的，
     * 这正是它危险的地方 —— 它不会抛异常，而是默默生成一个新地图并注册进世界列表，
     * 之后每次开服都会被加载。如果把维度 ID（{@code minecraft:the_nether}）走到这一步，
     * 运维会得到一个和真下界无关的空世界，而且从日志里看不出任何错误。
     * 带命名空间的写法一律视为「维度引用」，必须已经存在。
     *
     * @return 解析到的世界；解析不到返回 null
     */
    public static World resolveWorld(String worldId) {
        if (worldId == null || worldId.isBlank()) return null;

        String normalized = normalizeWorldName(worldId);
        String lowerId = worldId.toLowerCase(Locale.ROOT);

        // ① 原版维度：只查找，绝不创建
        List<String> aliases = VANILLA_WORLD_ALIASES.get(lowerId);
        if (aliases != null) {
            for (String alias : aliases) {
                World world = Bukkit.getWorld(alias);
                if (world != null) return world;
            }
            World byKey = matchByKey(worldId, normalized);
            if (byKey != null) return byKey;

            Battlefield.LOGGER.error("[Battlefield] 原版维度 '{}' 未加载，无法传送。"
                    + "请检查 server.properties 的 level-name / allow-nether / allow-end。"
                    + "（不会为原版维度自动创建世界）", worldId);
            return null;
        }

        // ② 其它标识：名字 → 维度 Key
        World byName = Bukkit.getWorld(normalized);
        if (byName != null) return byName;

        World byKey = matchByKey(worldId, normalized);
        if (byKey != null) return byKey;

        // ③ 只有裸名才允许按需建图（运维显式声明的独立地图，例如 arena_alpha）
        if (!worldId.contains(":")) {
            try {
                World created = Bukkit.createWorld(new WorldCreator(normalized));
                if (created != null) {
                    Battlefield.LOGGER.warn("[Battlefield] 世界 '{}' 不存在，已按配置自动创建（首次生成可能卡顿）。", normalized);
                    return created;
                }
            } catch (Throwable t) {
                Battlefield.LOGGER.error("[Battlefield] 自动创建世界 '{}' 失败", normalized, t);
            }
        }
        return null;
    }

    private static World matchByKey(String worldId, String normalized) {
        for (World world : Bukkit.getWorlds()) {
            NamespacedKey key = world.getKey();
            if (key == null) continue;
            if (key.toString().equalsIgnoreCase(worldId) || key.getKey().equalsIgnoreCase(normalized)) {
                return world;
            }
        }
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

    /**
     * 跨维度传送。世界存在则安排传送并返回 true；世界不存在返回 false，
     * 由调用方决定是回退原版传送还是直接取消。
     */
    public static boolean teleportToWorld(ServerPlayer sp, String worldName,
                                          double x, double y, double z,
                                          float yaw, float pitch) {

        final World world = resolveWorld(worldName);
        if (world == null) {
            return false;
        }

        sp.server.execute(() -> {
            Player bp = toBukkit(sp);
            if (bp == null) return;

            bp.setFallDistance(0);
            bp.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            bp.setFireTicks(0);

            if (!bp.teleport(new Location(world, x, y, z, yaw, pitch))) {
                Battlefield.LOGGER.warn("[Battlefield] Bukkit 传送被拒绝：{} @ {}, {}, {}（玩家 {}）",
                        world.getName(), x, y, z, sp.getGameProfile().getName());
            }
        });
        return true;
    }
}
