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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SectorConfigLoader {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SectorConfigLoader() {
    }

    private static class Root {
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

    public static final class Position {
        public final String world;
        public final double x;
        public final double y;
        public final double z;

        public Position(String world, double x, double y, double z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

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

    public static final class SectorConfig {
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

        public SectorConfig(String world, int timeMinutes, int military,
                            int maxPlayerNumber, int attackNumber, int defendNumber, int minPlayerNumber,
                            Position wait, Position lobby, Position firstAttackSpawnPoint, Position firstDefendSpawnPoint,
                            List<String> winCommand, List<String> loseCommand,
                            List<Sector> sectors) {
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
        return loadConfig(configDir).sectors;
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
        List<Sector> sectors = new ArrayList<>();
        String world = "minecraft:overworld";
        int timeMinutes = 20;
        int military = 300;
        int maxPlayerNumber = 32;
        int attackNumber = 16;
        int defendNumber = 16;
        int minPlayerNumber = 2;
        Position wait = new Position("minecraft:overworld", 0.5, 64, 0.5);
        Position lobby = new Position("minecraft:overworld", 0.5, 64, 0.5);
        Position firstAttackSpawnPoint = new Position("minecraft:overworld", 16.5, 64, 0.5);
        Position firstDefendSpawnPoint = new Position("minecraft:overworld", -16.5, 64, 0.5);
        List<String> winCommand = Collections.emptyList();
        List<String> loseCommand = Collections.emptyList();

        if (root == null) {
            return new SectorConfig(world, timeMinutes, military, maxPlayerNumber, attackNumber, defendNumber, minPlayerNumber,
                    wait, lobby, firstAttackSpawnPoint, firstDefendSpawnPoint, winCommand, loseCommand, sectors);
        }

        if (root.world != null && !root.world.isBlank()) {
            world = root.world;
        }
        if (root.time != null && root.time > 0) {
            timeMinutes = root.time;
        }
        if (root.military != null && root.military > 0) {
            military = root.military;
        }
        if (root.maxPlayerNumber != null && root.maxPlayerNumber > 0) {
            maxPlayerNumber = root.maxPlayerNumber;
        }
        attackNumber = Math.max(1, maxPlayerNumber / 2);
        defendNumber = Math.max(1, maxPlayerNumber - attackNumber);
        if (root.attackNumber != null && root.attackNumber > 0) {
            attackNumber = root.attackNumber;
        }
        if (root.defendNumber != null && root.defendNumber > 0) {
            defendNumber = root.defendNumber;
        }
        if (root.minPlayerNumber != null && root.minPlayerNumber > 1) {
            minPlayerNumber = root.minPlayerNumber;
        }
        wait = parsePos(root.wait, wait);
        lobby = parsePos(root.lobby, lobby);
        firstAttackSpawnPoint = parsePos(root.firstAttackSpawnPoint, firstAttackSpawnPoint);
        firstDefendSpawnPoint = parsePos(root.firstDefendSpawnPoint, firstDefendSpawnPoint);
        if (root.winCommand != null) {
            winCommand = root.winCommand.stream().filter(s -> s != null && !s.isBlank()).toList();
        }
        if (root.loseCommand != null) {
            loseCommand = root.loseCommand.stream().filter(s -> s != null && !s.isBlank()).toList();
        }

        if (root.sectors == null) return new SectorConfig(world, timeMinutes, military, maxPlayerNumber, attackNumber, defendNumber, minPlayerNumber,
                wait, lobby, firstAttackSpawnPoint, firstDefendSpawnPoint, winCommand, loseCommand, sectors);

        for (SectorJson s : root.sectors) {
            if (s.id == null || s.points == null || s.points.isEmpty()) continue;

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

        return new SectorConfig(world, timeMinutes, military, maxPlayerNumber, attackNumber, defendNumber, minPlayerNumber,
                wait, lobby, firstAttackSpawnPoint, firstDefendSpawnPoint, winCommand, loseCommand, sectors);
    }

    private static Position parsePos(PositionJson p, Position fallback) {
        if (p == null || p.world == null || p.world.isBlank() || p.x == null || p.y == null || p.z == null) {
            return fallback;
        }
        return new Position(p.world, p.x, p.y, p.z);
    }

    private static void writeDefault(Path file) throws IOException {
        Root root = new Root();
        root.world = "minecraft:world";
        root.time = 20;
        root.military = 300;
        root.maxPlayerNumber = 32;
        root.attackNumber = 16;
        root.defendNumber = 16;
        root.minPlayerNumber = 2;
        root.wait = pos("minecraft:world", 0.5, 64, 0.5);
        root.lobby = pos("minecraft:world", 0.5, 64, 0.5);
        root.firstAttackSpawnPoint = pos("minecraft:world", 20.5, 64, 0.5);
        root.firstDefendSpawnPoint = pos("minecraft:world", -20.5, 64, 0.5);
        root.winCommand = List.of("say {player} 获胜");
        root.loseCommand = List.of("say {player} 失败");
        root.sectors = new ArrayList<>();

        SectorJson s = new SectorJson();
        s.id = "S1";

        AreaJson a1 = new AreaJson();
        a1.x = 100;
        a1.z = 0;
        a1.r = 120;
        s.attackerAreas = List.of(a1);

        AreaJson d1 = new AreaJson();
        d1.x = -100;
        d1.z = 0;
        d1.r = 120;
        s.defenderAreas = List.of(d1);

        PointJson p1 = new PointJson();
        p1.id = "A";
        p1.x = 0;
        p1.y = 64;
        p1.z = 0;
        p1.radius = 6;

        s.points = List.of(p1);
        root.sectors.add(s);

        try (Writer w = Files.newBufferedWriter(file)) {
            GSON.toJson(root, w);
        }
    }

    private static PositionJson pos(String world, double x, double y, double z) {
        PositionJson p = new PositionJson();
        p.world = world;
        p.x = x;
        p.y = y;
        p.z = z;
        return p;
    }
}
