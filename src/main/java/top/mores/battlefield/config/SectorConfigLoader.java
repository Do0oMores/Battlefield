package top.mores.battlefield.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import top.mores.battlefield.breakthrough.CapturePoint;
import top.mores.battlefield.breakthrough.Sector;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SectorConfigLoader {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SectorConfigLoader() {
    }

    // ===== JSON DTO（只用于反序列化） =====

    private static class Root {
        String world;
        Integer time;
        Integer military;
        List<SectorJson> sectors;
    }

    public static final class SectorConfig {
        public final String world;
        public final int timeMinutes;
        public final int military;
        public final List<Sector> sectors;

        public SectorConfig(String world, int timeMinutes, int military, List<Sector> sectors) {
            this.world = world;
            this.timeMinutes = timeMinutes;
            this.military = military;
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

    // ===== 对外入口 =====

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

    // ===== 解析为 Sector =====

    private static SectorConfig parse(Root root) {
        List<Sector> sectors = new ArrayList<>();
        String world = "minecraft:overworld";
        int timeMinutes = 20;
        int military = 300;

        if (root == null) {
            return new SectorConfig(world, timeMinutes, military, sectors);
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

        if (root.sectors == null) return new SectorConfig(world, timeMinutes, military, sectors);

        for (SectorJson s : root.sectors) {
            if (s.id == null || s.points == null || s.points.isEmpty()) continue;

            // points
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

            // attacker areas
            List<Sector.AreaCircle> atkAreas = new ArrayList<>();
            if (s.attackerAreas != null) {
                for (AreaJson a : s.attackerAreas) {
                    if (a == null || a.r <= 0) continue;
                    atkAreas.add(new Sector.AreaCircle(a.x, a.z, a.r));
                }
            }

            // defender areas
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

        return new SectorConfig(world, timeMinutes, military, sectors);
    }

    // ===== 默认模板 =====

    private static void writeDefault(Path file) throws IOException {
        Root root = new Root();
        root.world = "minecraft:overworld";
        root.time = 20;
        root.military = 300;
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
}
