package top.mores.battlefield.server;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.breakthrough.Sector;
import top.mores.battlefield.config.SectorConfigLoader;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public final class BattleInstanceWorldService {
    private static final Set<String> ACTIVE_INSTANCES = Collections.synchronizedSet(new HashSet<>());

    private BattleInstanceWorldService() {
    }

    public static ServerLevel createInstance(ServerLevel defaultLevel, String arenaId, SectorConfigLoader.ArenaConfig arena) {
        String templateName = MohistTeleport.normalizeWorldName(arena.templateWorld);
        if (templateName == null || templateName.isBlank()) return defaultLevel;

        Path worldContainer = Bukkit.getWorldContainer().toPath();
        Path templatePath = worldContainer.resolve(templateName);
        if (!Files.isDirectory(templatePath)) {
            Battlefield.LOGGER.warn("[Battlefield] 模板世界目录不存在: {}", templatePath);
            return defaultLevel;
        }

        String prefix = (arena.instanceWorldPrefix == null || arena.instanceWorldPrefix.isBlank()) ? "bf_inst" : arena.instanceWorldPrefix;
        String instanceName = prefix + "_" + sanitize(arenaId) + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Path instancePath = worldContainer.resolve(instanceName);

        try {
            copyWorldDir(templatePath, instancePath);
            World world = Bukkit.createWorld(new WorldCreator(instanceName));
            if (world == null) {
                Battlefield.LOGGER.error("[Battlefield] 创建副本世界失败: {}", instanceName);
                deleteQuietly(instancePath);
                return defaultLevel;
            }

            ACTIVE_INSTANCES.add(instanceName);
            ServerLevel level = resolveLevel(defaultLevel, "minecraft:" + instanceName);
            if (level == null) {
                Battlefield.LOGGER.warn("[Battlefield] 副本世界已创建但无法解析 ServerLevel，回退默认世界: {}", instanceName);
                releaseByName(instanceName);
                return defaultLevel;
            }
            return level;
        } catch (Exception ex) {
            Battlefield.LOGGER.error("[Battlefield] 创建副本世界异常: arena={} template={}", arenaId, templateName, ex);
            deleteQuietly(instancePath);
            return defaultLevel;
        }
    }

    public static void preloadChunks(ServerLevel level, SectorConfigLoader.ArenaConfig arena) {
        if (level == null) return;
        int radius = Math.max(0, arena.preloadRadiusChunks);
        Set<Long> loaded = new HashSet<>();

        addPosChunks(level, loaded, arena.wait.x(), arena.wait.z(), radius);
        addPosChunks(level, loaded, arena.lobby.x(), arena.lobby.z(), radius);
        addPosChunks(level, loaded, arena.firstAttackSpawnPoint.x(), arena.firstAttackSpawnPoint.z(), radius);
        addPosChunks(level, loaded, arena.firstDefendSpawnPoint.x(), arena.firstDefendSpawnPoint.z(), radius);

        for (Sector sector : arena.sectors) {
            if (sector == null || sector.points == null) continue;
            for (var p : sector.points) {
                addPosChunks(level, loaded, p.x, p.z, radius);
            }
        }
    }

    public static void releaseInstance(ServerLevel level, SectorConfigLoader.ArenaConfig arena) {
        if (level == null || arena == null || !arena.useTemporaryWorld) return;
        String levelName = level.dimension().location().getPath();
        if (!ACTIVE_INSTANCES.remove(levelName)) return;

        try {
            Bukkit.unloadWorld(levelName, false);
        } catch (Throwable t) {
            Battlefield.LOGGER.warn("[Battlefield] 卸载副本世界失败: {}", levelName, t);
        }

        Path worldPath = Bukkit.getWorldContainer().toPath().resolve(levelName);
        deleteQuietly(worldPath);
    }

    public static void releaseAll() {
        List<String> worlds = new ArrayList<>(ACTIVE_INSTANCES);
        for (String world : worlds) {
            releaseByName(world);
        }
    }

    private static ServerLevel resolveLevel(ServerLevel defaultLevel, String world) {
        ResourceLocation id = ResourceLocation.tryParse(world);
        if (id == null) return null;
        ResourceKey<net.minecraft.world.level.Level> key = ResourceKey.create(Registries.DIMENSION, id);
        return defaultLevel.getServer().getLevel(key);
    }

    private static void addPosChunks(ServerLevel level, Set<Long> loaded, double x, double z, int radius) {
        int cx = net.minecraft.util.Mth.floor(x) >> 4;
        int cz = net.minecraft.util.Mth.floor(z) >> 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int nx = cx + dx;
                int nz = cz + dz;
                long key = (((long) nx) << 32) ^ (nz & 0xffffffffL);
                if (!loaded.add(key)) continue;
                level.getChunk(nx, nz);
            }
        }
    }

    private static void copyWorldDir(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path target = to.resolve(from.relativize(dir).toString());
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                if ("session.lock".equalsIgnoreCase(name) || "uid.dat".equalsIgnoreCase(name)) {
                    return FileVisitResult.CONTINUE;
                }
                Path target = to.resolve(from.relativize(file).toString());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) return;
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ex) {
            Battlefield.LOGGER.warn("[Battlefield] 删除副本世界目录失败: {}", path, ex);
        }
    }

    private static void releaseByName(String worldName) {
        try {
            Bukkit.unloadWorld(worldName, false);
        } catch (Throwable ignored) {
        }
        deleteQuietly(Bukkit.getWorldContainer().toPath().resolve(worldName));
        ACTIVE_INSTANCES.remove(worldName);
    }

    private static String sanitize(String s) {
        if (s == null || s.isBlank()) return "arena";
        return s.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
