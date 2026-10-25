package top.mores.battlefield.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.breakthrough.CapturePoint;
import top.mores.battlefield.breakthrough.Sector;
import top.mores.battlefield.deploy.BreakthroughDeploymentRules;
import top.mores.battlefield.deploy.DeploymentRules;
import top.mores.battlefield.net.S2CGameStatePacket;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class SectorConfigLoader {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 实例化对局默认使用的战场维度（模组自带的 datapack 维度，见 data/battlefield/dimension/battle.json）。 */
    public static final String DEFAULT_BATTLE_WORLD = "battlefield:battle";

    private SectorConfigLoader() {
    }

    private static class Root {
        String areaName;
        String templateWorld;
        String world;
        String gameMode;
        Integer time;
        Integer military;
        Integer addMilitary;
        Integer maxPlayerNumber;
        Integer attackNumber;
        Integer defendNumber;
        Integer minPlayerNumber;
        PositionJson wait;
        PositionJson lobby;
        PositionJson firstAttackSpawnPoint;
        PositionJson firstDefendSpawnPoint;
        List<String> winCommand;
        List<String> loseCommand;
        List<SectorJson> sectors;
        List<ArenaJson> arenas;
    }

    public record Position(String world, double x, double y, double z) {

        public ServerLevel resolveLevel(MinecraftServer server, ServerLevel fallback) {
            ResourceLocation id = ResourceLocation.tryParse(world);
            if (id == null) {
                return fallback;
            }
            ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id));
            return level != null ? level : fallback;
        }

        public Vec3 toVec3() {
            return new Vec3(x, y, z);
        }

        public Position offset(double dx, double dz) {
            return new Position(world, x + dx, y, z + dz);
        }
    }

    /**
     * 对局如何获得自己的地图。
     * <ul>
     *   <li>{@link #STATIC}：直接在 {@code world} 指定的现有世界里打，同一 arena 同时只能有一局，结束后不还原地图；</li>
     *   <li>{@link #INSTANCED}：从只读模板 {@code templates/<template>/} 还原到战场维度的某个「槽位」里，
     *   同一 arena 可以并行多局（每局一个槽位），结束后按模板逐方块还原。</li>
     * </ul>
     */
    public enum Mode {STATIC, INSTANCED}

    /** 模板在原世界中的方块坐标范围（含两端）。 */
    public record Bounds(int minX, int minZ, int maxX, int maxZ) {
        public int minChunkX() {
            return minX >> 4;
        }

        public int minChunkZ() {
            return minZ >> 4;
        }

        public int maxChunkX() {
            return maxX >> 4;
        }

        public int maxChunkZ() {
            return maxZ >> 4;
        }

        public int widthChunks() {
            return maxChunkX() - minChunkX() + 1;
        }

        public int depthChunks() {
            return maxChunkZ() - minChunkZ() + 1;
        }
    }

    public static final class ArenaConfig {
        public final String areaName;
        public final Mode mode;
        /** STATIC：对局所在世界；INSTANCED：仅作记录（模板来源世界），运行时不使用。 */
        public final String world;
        /** INSTANCED：模板目录名（config/battlefield/templates/&lt;template&gt;）。 */
        public final String template;
        /** INSTANCED：承载槽位的战场维度。 */
        public final String battleWorld;
        public final int maxConcurrentMatches;
        public final int prewarmSlots;
        /** INSTANCED：需要还原的模板范围（已含边距，已对齐区块）。 */
        public final Bounds templateBounds;
        /**
         * 玩法模式标识，决定部署规则（见 {@code deploy.DeploymentRules}）。
         * 目前只有 {@code breakthrough}，其余模式尚未实现。
         */
        public final String gameMode;
        public final int timeMinutes;
        public final int military;
        public final int addMilitary;
        public final int maxPlayerNumber;
        public final int attackNumber;
        public final int defendNumber;
        public final int minPlayerNumber;
        /** INSTANCED 下 wait / 两个出生点是模板坐标；lobby 永远是真实世界坐标（离场兜底点）。 */
        public final Position wait;
        public final Position lobby;
        public final Position firstAttackSpawnPoint;
        public final Position firstDefendSpawnPoint;
        public final List<String> winCommand;
        public final List<String> loseCommand;
        public final List<Sector> sectors;

        public ArenaConfig(String areaName, Mode mode, String world, String template, String battleWorld,
                           int maxConcurrentMatches, int prewarmSlots, Bounds templateBounds, String gameMode,
                           int timeMinutes, int military, int addMilitary,
                           int maxPlayerNumber, int attackNumber, int defendNumber, int minPlayerNumber,
                           Position wait, Position lobby, Position firstAttackSpawnPoint, Position firstDefendSpawnPoint,
                           List<String> winCommand, List<String> loseCommand, List<Sector> sectors) {
            this.areaName = areaName;
            this.mode = mode;
            this.world = world;
            this.template = template;
            this.battleWorld = battleWorld;
            this.maxConcurrentMatches = maxConcurrentMatches;
            this.prewarmSlots = prewarmSlots;
            this.templateBounds = templateBounds;
            this.gameMode = gameMode;
            this.timeMinutes = timeMinutes;
            this.military = military;
            this.addMilitary = addMilitary;
            this.maxPlayerNumber = maxPlayerNumber;
            this.attackNumber = attackNumber;
            this.defendNumber = defendNumber;
            this.minPlayerNumber = minPlayerNumber;
            this.wait = wait;
            this.lobby = lobby;
            this.firstAttackSpawnPoint = firstAttackSpawnPoint;
            this.firstDefendSpawnPoint = firstDefendSpawnPoint;
            this.winCommand = winCommand;
            this.loseCommand = loseCommand;
            this.sectors = sectors;
        }

        public boolean instanced() {
            return mode == Mode.INSTANCED;
        }
    }

    public static final class SectorConfig {
        public final Map<String, ArenaConfig> arenas;

        public SectorConfig(Map<String, ArenaConfig> arenas) {
            this.arenas = arenas;
        }

        public ArenaConfig getArena(String arenaId) {
            if (arenaId == null || arenaId.isBlank()) {
                return arenas.values().stream().findFirst().orElse(null);
            }
            return arenas.get(arenaId);
        }

        public String defaultAreaName() {
            return arenas.keySet().stream().findFirst().orElse("default");
        }
    }

    private static class ArenaJson {
        String areaName;
        String id;
        String templateWorld;
        String world;
        String template;
        String battleWorld;
        String gameMode;
        Integer maxConcurrentMatches;
        Integer prewarmSlots;
        BoundsJson templateBounds;
        Integer templateMarginBlocks;
        Integer time;
        Integer military;
        Integer addMilitary;
        Integer maxPlayerNumber;
        Integer attackNumber;
        Integer defendNumber;
        Integer minPlayerNumber;
        Boolean useTemporaryWorld;
        PositionJson wait;
        PositionJson lobby;
        PositionJson firstAttackSpawnPoint;
        PositionJson firstDefendSpawnPoint;
        List<String> winCommand;
        List<String> loseCommand;
        List<SectorJson> sectors;
    }

    private static class BoundsJson {
        Integer minX, minZ, maxX, maxZ;
    }

    private static class SectorJson {
        String id;
        List<AreaJson> attackerAreas;
        List<AreaJson> defenderAreas;
        List<PointJson> points;
    }

    private static class AreaJson {
        double x, z;
        Double x2, z2;
    }

    private static class PointJson {
        String id;
        double x, y, z;
        double radius;
    }

    private static class PositionJson {
        String world;
        Double x;
        Double y;
        Double z;
    }

    public static List<Sector> load(Path configDir) {
        return loadConfig(configDir).arenas.values().stream().findFirst().map(a -> a.sectors).orElse(Collections.emptyList());
    }

    public static SectorConfig loadConfig(Path configDir) {
        try {
            Files.createDirectories(configDir);

            Path file = configDir.resolve("sectors.json");

            if (!Files.exists(file)) {
                writeDefault(file);
            }

            try (Reader reader = Files.newBufferedReader(file)) {
                Root root = GSON.fromJson(reader, Root.class);
                return parse(root);
            }

        } catch (IOException | JsonIOException | JsonSyntaxException e) {
            throw new RuntimeException("[Battlefield] Failed to load sectors.json", e);
        }
    }

    private static SectorConfig parse(Root root) {
        LinkedHashMap<String, ArenaConfig> arenas = new LinkedHashMap<>();
        if (root == null) {
            ArenaConfig arena = parseArena("default", null, null);
            arenas.put(arena.areaName, arena);
            return new SectorConfig(arenas);
        }

        if (root.arenas != null && !root.arenas.isEmpty()) {
            for (ArenaJson arenaJson : root.arenas) {
                String areaName = resolveAreaName(arenaJson);
                if (areaName == null || arenas.containsKey(areaName)) continue;
                arenas.put(areaName, parseArena(areaName, arenaJson, root));
            }
        }

        if (arenas.isEmpty()) {
            String areaName = (root.areaName != null && !root.areaName.isBlank()) ? root.areaName : "default";
            arenas.put(areaName, parseArena(areaName, null, root));
        }

        return new SectorConfig(arenas);
    }

    private static String resolveAreaName(ArenaJson arena) {
        if (arena == null) return null;
        if (arena.areaName != null && !arena.areaName.isBlank()) return arena.areaName;
        if (arena.id != null && !arena.id.isBlank()) return arena.id;
        return null;
    }

    private static ArenaConfig parseArena(String areaName, ArenaJson arena, Root rootFallback) {
        String world = "minecraft:overworld";
        int timeMinutes = 20;
        int military = 300;
        int addMilitary = 0;
        int maxPlayerNumber = 32;
        int attackNumber;
        int defendNumber;
        int minPlayerNumber = 2;

        Position wait = new Position("minecraft:overworld", 0.5, 64, 0.5);
        Position lobby = new Position("minecraft:overworld", 0.5, 64, 0.5);
        Position firstAttackSpawnPoint = new Position("minecraft:overworld", 16.5, 64, 0.5);
        Position firstDefendSpawnPoint = new Position("minecraft:overworld", -16.5, 64, 0.5);
        List<String> winCommand = Collections.emptyList();
        List<String> loseCommand = Collections.emptyList();
        List<Sector> sectors;

        String preferredWorld = arena == null ? null : firstNonBlank(arena.world, arena.templateWorld);
        String fallbackWorld = rootFallback == null ? null : firstNonBlank(rootFallback.world, rootFallback.templateWorld);
        world = read(preferredWorld, fallbackWorld, world);
        timeMinutes = readPositive(arena == null ? null : arena.time, rootFallback == null ? null : rootFallback.time, timeMinutes);
        military = readPositive(arena == null ? null : arena.military, rootFallback == null ? null : rootFallback.military, military);
        addMilitary = readNonNegative(arena == null ? null : arena.addMilitary, rootFallback == null ? null : rootFallback.addMilitary, addMilitary);
        maxPlayerNumber = readPositive(arena == null ? null : arena.maxPlayerNumber, rootFallback == null ? null : rootFallback.maxPlayerNumber, maxPlayerNumber);
        minPlayerNumber = Math.max(2, readPositive(arena == null ? null : arena.minPlayerNumber, rootFallback == null ? null : rootFallback.minPlayerNumber, minPlayerNumber));

        attackNumber = Math.max(1, maxPlayerNumber / 2);
        defendNumber = Math.max(1, maxPlayerNumber - attackNumber);
        attackNumber = readPositive(arena == null ? null : arena.attackNumber, rootFallback == null ? null : rootFallback.attackNumber, attackNumber);
        defendNumber = readPositive(arena == null ? null : arena.defendNumber, rootFallback == null ? null : rootFallback.defendNumber, defendNumber);

        wait = parsePos(arena == null ? null : arena.wait, rootFallback == null ? null : rootFallback.wait, wait);
        lobby = parsePos(arena == null ? null : arena.lobby, rootFallback == null ? null : rootFallback.lobby, lobby);
        firstAttackSpawnPoint = parsePos(arena == null ? null : arena.firstAttackSpawnPoint, rootFallback == null ? null : rootFallback.firstAttackSpawnPoint, firstAttackSpawnPoint);
        firstDefendSpawnPoint = parsePos(arena == null ? null : arena.firstDefendSpawnPoint, rootFallback == null ? null : rootFallback.firstDefendSpawnPoint, firstDefendSpawnPoint);

        List<String> winRaw = arena == null ? null : arena.winCommand;
        if (winRaw == null && rootFallback != null) winRaw = rootFallback.winCommand;
        if (winRaw != null) winCommand = winRaw.stream().filter(s -> s != null && !s.isBlank()).toList();

        List<String> loseRaw = arena == null ? null : arena.loseCommand;
        if (loseRaw == null && rootFallback != null) loseRaw = rootFallback.loseCommand;
        if (loseRaw != null) loseCommand = loseRaw.stream().filter(s -> s != null && !s.isBlank()).toList();

        List<SectorJson> sectorJson = arena == null ? null : arena.sectors;
        if (sectorJson == null && rootFallback != null) sectorJson = rootFallback.sectors;
        sectors = parseSectors(sectorJson);

        // ===== 地图实例化 =====
        String template = arena == null ? null : arena.template;
        Mode mode = (template != null && !template.isBlank()) ? Mode.INSTANCED : Mode.STATIC;
        if (arena != null && Boolean.TRUE.equals(arena.useTemporaryWorld) && mode == Mode.STATIC) {
            // PR #43 的「每局复制整个世界目录」已移除（主线程复制 + createWorld 会卡服，见地图方案文档）
            Battlefield.LOGGER.warn("[Battlefield] arena '{}' 的 useTemporaryWorld 已废弃并被忽略；"
                    + "如需每局独立地图，请改用 \"template\" 字段。本 arena 以 STATIC 模式运行于 {}", areaName, world);
        }
        String battleWorld = read(arena == null ? null : arena.battleWorld, null, DEFAULT_BATTLE_WORLD);
        int maxConcurrent = mode == Mode.INSTANCED
                ? readPositive(arena == null ? null : arena.maxConcurrentMatches, null, 2)
                : 1;
        int prewarm = mode == Mode.INSTANCED
                ? Math.min(maxConcurrent, readNonNegative(arena == null ? null : arena.prewarmSlots, null, 1))
                : 0;
        int margin = readNonNegative(arena == null ? null : arena.templateMarginBlocks, null, 96);
        Bounds bounds = mode == Mode.INSTANCED
                ? resolveBounds(arena.templateBounds, margin, sectors, wait, firstAttackSpawnPoint, firstDefendSpawnPoint)
                : null;

        // 玩法模式：决定部署规则。未注册的模式回落到突破，并在加载期告警，避免配错后静默跑成别的模式
        String gameMode = read(arena == null ? null : arena.gameMode,
                rootFallback == null ? null : rootFallback.gameMode,
                BreakthroughDeploymentRules.MODE_ID);
        if (!DeploymentRules.Registry.knows(gameMode)) {
            Battlefield.LOGGER.warn("[Battlefield] arena '{}' 的 gameMode '{}' 尚未实现，部署规则回落到 '{}'",
                    areaName, gameMode, BreakthroughDeploymentRules.MODE_ID);
            gameMode = BreakthroughDeploymentRules.MODE_ID;
        }

        return new ArenaConfig(areaName, mode, world, mode == Mode.INSTANCED ? template.trim() : null, battleWorld,
                maxConcurrent, prewarm, bounds, gameMode,
                timeMinutes, military, addMilitary, maxPlayerNumber, attackNumber, defendNumber, minPlayerNumber,
                wait, lobby, firstAttackSpawnPoint, firstDefendSpawnPoint, winCommand, loseCommand, sectors);
    }

    /**
     * 模板需要还原的范围：显式配置优先；否则取所有战线区域、据点、出生点的外包矩形再外扩 margin，
     * 让玩家在可活动区域边缘向外看时仍能看到地形（而不是虚空）。最后对齐到区块边界。
     */
    private static Bounds resolveBounds(BoundsJson explicit, int margin, List<Sector> sectors, Position... points) {
        if (explicit != null && explicit.minX != null && explicit.minZ != null && explicit.maxX != null && explicit.maxZ != null) {
            return alignToChunks(Math.min(explicit.minX, explicit.maxX), Math.min(explicit.minZ, explicit.maxZ),
                    Math.max(explicit.minX, explicit.maxX), Math.max(explicit.minZ, explicit.maxZ));
        }

        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Position p : points) {
            if (p == null) continue;
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minZ = Math.min(minZ, p.z());
            maxZ = Math.max(maxZ, p.z());
        }
        for (Sector s : sectors) {
            for (Sector.AreaRect r : s.attackerAreas) {
                minX = Math.min(minX, Math.min(r.x1(), r.x2()));
                maxX = Math.max(maxX, Math.max(r.x1(), r.x2()));
                minZ = Math.min(minZ, Math.min(r.z1(), r.z2()));
                maxZ = Math.max(maxZ, Math.max(r.z1(), r.z2()));
            }
            for (Sector.AreaRect r : s.defenderAreas) {
                minX = Math.min(minX, Math.min(r.x1(), r.x2()));
                maxX = Math.max(maxX, Math.max(r.x1(), r.x2()));
                minZ = Math.min(minZ, Math.min(r.z1(), r.z2()));
                maxZ = Math.max(maxZ, Math.max(r.z1(), r.z2()));
            }
            for (CapturePoint p : s.points) {
                minX = Math.min(minX, p.x - p.radius);
                maxX = Math.max(maxX, p.x + p.radius);
                minZ = Math.min(minZ, p.z - p.radius);
                maxZ = Math.max(maxZ, p.z + p.radius);
            }
        }
        if (minX > maxX) {
            minX = maxX = minZ = maxZ = 0;
        }
        return alignToChunks((int) Math.floor(minX) - margin, (int) Math.floor(minZ) - margin,
                (int) Math.ceil(maxX) + margin, (int) Math.ceil(maxZ) + margin);
    }

    private static Bounds alignToChunks(int minX, int minZ, int maxX, int maxZ) {
        return new Bounds(minX & ~15, minZ & ~15, maxX | 15, maxZ | 15);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second;
    }

    private static String read(String preferred, String fallback, String def) {
        if (preferred != null && !preferred.isBlank()) return preferred;
        if (fallback != null && !fallback.isBlank()) return fallback;
        return def;
    }

    private static int readPositive(Integer preferred, Integer fallback, int def) {
        if (preferred != null && preferred > 0) return preferred;
        if (fallback != null && fallback > 0) return fallback;
        return def;
    }

    private static int readNonNegative(Integer preferred, Integer fallback, int def) {
        if (preferred != null && preferred >= 0) return preferred;
        if (fallback != null && fallback >= 0) return fallback;
        return def;
    }

    private static List<Sector> parseSectors(List<SectorJson> source) {
        if (source == null) return new ArrayList<>();

        List<Sector> sectors = new ArrayList<>();
        for (SectorJson s : source) {
            if (s == null || s.id == null || s.points == null || s.points.isEmpty()) continue;

            List<CapturePoint> points = new ArrayList<>();
            for (PointJson p : s.points) {
                if (p == null || p.id == null || p.id.isBlank() || p.radius <= 0) continue;

                // 网络包只给据点 id 8 个字符（见 S2CGameStatePacket.MAX_POINT_ID_LENGTH），
                // 超长会让客户端 readUtf 抛异常直接掉线，这里在加载期就拦下。
                if (p.id.length() > S2CGameStatePacket.MAX_POINT_ID_LENGTH) {
                    Battlefield.LOGGER.error("[Battlefield] 据点 id '{}' 超过 {} 字符，已忽略（sector={}）",
                            p.id, S2CGameStatePacket.MAX_POINT_ID_LENGTH, s.id);
                    continue;
                }

                CapturePoint cp = new CapturePoint(
                        p.id,
                        p.x,
                        p.y,
                        p.z,
                        p.radius
                );
                points.add(cp);
            }

            List<Sector.AreaRect> atkAreas = new ArrayList<>();
            if (s.attackerAreas != null) {
                for (AreaJson a : s.attackerAreas) {
                    if (a == null || a.x2 == null || a.z2 == null) continue;
                    atkAreas.add(new Sector.AreaRect(a.x, a.z, a.x2, a.z2));
                }
            }

            List<Sector.AreaRect> defAreas = new ArrayList<>();
            if (s.defenderAreas != null) {
                for (AreaJson a : s.defenderAreas) {
                    if (a == null || a.x2 == null || a.z2 == null) continue;
                    defAreas.add(new Sector.AreaRect(a.x, a.z, a.x2, a.z2));
                }
            }

            if (points.isEmpty()) continue;

            sectors.add(new Sector(s.id, points, atkAreas, defAreas));
        }
        return sectors;
    }

    private static Position parsePos(PositionJson preferred, PositionJson fallbackRaw, Position fallback) {
        Position fromPreferred = parsePos(preferred, null);
        if (fromPreferred != null) return fromPreferred;

        Position fromFallback = parsePos(fallbackRaw, null);
        return fromFallback != null ? fromFallback : fallback;
    }

    private static Position parsePos(PositionJson p, Position fallback) {
        if (p == null || p.world == null || p.world.isBlank() || p.x == null || p.y == null || p.z == null) {
            return fallback;
        }
        return new Position(p.world, p.x, p.y, p.z);
    }

    private static void writeDefault(Path file) throws IOException {
        Root root = new Root();
        root.arenas = new ArrayList<>();
        ArenaJson arena = new ArenaJson();
        arena.areaName = "area_alpha";
        arena.world = "minecraft:overworld";
        arena.gameMode = BreakthroughDeploymentRules.MODE_ID;
        arena.time = 20;
        arena.military = 300;
        arena.addMilitary = 100;
        arena.maxPlayerNumber = 32;
        arena.attackNumber = 16;
        arena.defendNumber = 16;
        arena.minPlayerNumber = 2;

        arena.wait = new PositionJson();
        arena.wait.world = "minecraft:overworld";
        arena.wait.x = 0.5;
        arena.wait.y = 64.0;
        arena.wait.z = 0.5;

        arena.lobby = new PositionJson();
        arena.lobby.world = "minecraft:overworld";
        arena.lobby.x = 0.5;
        arena.lobby.y = 64.0;
        arena.lobby.z = 0.5;

        arena.firstAttackSpawnPoint = new PositionJson();
        arena.firstAttackSpawnPoint.world = "minecraft:overworld";
        arena.firstAttackSpawnPoint.x = 16.5;
        arena.firstAttackSpawnPoint.y = 64.0;
        arena.firstAttackSpawnPoint.z = 0.5;

        arena.firstDefendSpawnPoint = new PositionJson();
        arena.firstDefendSpawnPoint.world = "minecraft:overworld";
        arena.firstDefendSpawnPoint.x = -16.5;
        arena.firstDefendSpawnPoint.y = 64.0;
        arena.firstDefendSpawnPoint.z = 0.5;

        arena.winCommand = List.of();
        arena.loseCommand = List.of();
        arena.sectors = new ArrayList<>();

        SectorJson s1 = new SectorJson();
        s1.id = "sector_1";
        s1.points = new ArrayList<>();
        PointJson p1 = new PointJson();
        p1.id = "A";
        p1.x = 32.5;
        p1.y = 64;
        p1.z = 0.5;
        p1.radius = 6;
        s1.points.add(p1);
        arena.sectors.add(s1);
        root.arenas.add(arena);

        try (Writer w = Files.newBufferedWriter(file)) {
            GSON.toJson(root, w);
        }
    }
}
