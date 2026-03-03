package top.mores.battlefield.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import top.mores.battlefield.breakthrough.CapturePoint;
import top.mores.battlefield.breakthrough.Sector;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class SectorConfigLoader {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SectorConfigLoader() {
    }

    private static class Root {
        String areaName;
        String world;
        Integer time;
        Integer military;
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
    }

    public static final class ArenaConfig {
        public final String areaName;
        public final String world;
        public final int timeMinutes;
        public final int military;
        public final int maxPlayerNumber;
        public final int attackNumber;
        public final int defendNumber;
        public final int minPlayerNumber;
        public final Position wait;
        public final Position lobby;
        public final Position firstAttackSpawnPoint;
        public final Position firstDefendSpawnPoint;
        public final List<String> winCommand;
        public final List<String> loseCommand;
        public final List<Sector> sectors;

        public ArenaConfig(String areaName, String world, int timeMinutes, int military,
                           int maxPlayerNumber, int attackNumber, int defendNumber, int minPlayerNumber,
                           Position wait, Position lobby, Position firstAttackSpawnPoint, Position firstDefendSpawnPoint,
                           List<String> winCommand, List<String> loseCommand, List<Sector> sectors) {
            this.areaName = areaName;
            this.world = world;
            this.timeMinutes = timeMinutes;
            this.military = military;
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
        String world;
        Integer time;
        Integer military;
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
    }

    private static class SectorJson {
        String id;
        List<AreaJson> attackerAreas;
        List<AreaJson> defenderAreas;
        List<PointJson> points;
    }

    private static class AreaJson {
        double x, z, r;
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

        world = read(arena == null ? null : arena.world, rootFallback == null ? null : rootFallback.world, world);
        timeMinutes = readPositive(arena == null ? null : arena.time, rootFallback == null ? null : rootFallback.time, timeMinutes);
        military = readPositive(arena == null ? null : arena.military, rootFallback == null ? null : rootFallback.military, military);
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

        return new ArenaConfig(areaName, world, timeMinutes, military, maxPlayerNumber, attackNumber, defendNumber, minPlayerNumber,
                wait, lobby, firstAttackSpawnPoint, firstDefendSpawnPoint, winCommand, loseCommand, sectors);
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

    private static List<Sector> parseSectors(List<SectorJson> source) {
        if (source == null) return new ArrayList<>();

        List<Sector> sectors = new ArrayList<>();
        for (SectorJson s : source) {
            if (s == null || s.id == null || s.points == null || s.points.isEmpty()) continue;

            List<CapturePoint> points = new ArrayList<>();
            for (PointJson p : s.points) {
                if (p == null || p.id == null || p.id.isBlank() || p.radius <= 0) continue;
                CapturePoint cp = new CapturePoint(
                        p.id,
                        p.x,
                        p.y,
                        p.z,
                        p.radius
                );
                points.add(cp);
            }

            List<Sector.AreaCircle> atkAreas = new ArrayList<>();
            if (s.attackerAreas != null) {
                for (AreaJson a : s.attackerAreas) {
                    if (a == null || a.r <= 0) continue;
                    atkAreas.add(new Sector.AreaCircle(a.x, a.z, a.r));
                }
            }

            List<Sector.AreaCircle> defAreas = new ArrayList<>();
            if (s.defenderAreas != null) {
                for (AreaJson a : s.defenderAreas) {
                    if (a == null || a.r <= 0) continue;
                    defAreas.add(new Sector.AreaCircle(a.x, a.z, a.r));
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
        arena.world = "minecraft:world";
        arena.time = 20;
        arena.military = 300;
        arena.maxPlayerNumber = 32;
        arena.attackNumber = 16;
        arena.defendNumber = 16;
        arena.minPlayerNumber = 2;

        arena.wait = new PositionJson();
        arena.wait.world = "minecraft:world";
        arena.wait.x = 0.5;
        arena.wait.y = 64.0;
        arena.wait.z = 0.5;

        arena.lobby = new PositionJson();
        arena.lobby.world = "minecraft:world";
        arena.lobby.x = 0.5;
        arena.lobby.y = 64.0;
        arena.lobby.z = 0.5;

        arena.firstAttackSpawnPoint = new PositionJson();
        arena.firstAttackSpawnPoint.world = "minecraft:world";
        arena.firstAttackSpawnPoint.x = 16.5;
        arena.firstAttackSpawnPoint.y = 64.0;
        arena.firstAttackSpawnPoint.z = 0.5;

        arena.firstDefendSpawnPoint = new PositionJson();
        arena.firstDefendSpawnPoint.world = "minecraft:world";
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
