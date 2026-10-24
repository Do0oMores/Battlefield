package top.mores.battlefield.server.arena;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.mores.battlefield.Battlefield;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 战场维度相关的全局规则。
 * <p>
 * 战场维度是模组自带的 datapack 维度（{@code data/battlefield/dimension/battle.json}），
 * 随服务器启动由原版注册，<b>运行期间从不创建、卸载或删除世界</b>。
 */
@Mod.EventBusSubscriber(modid = Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BattleWorlds {
    private BattleWorlds() {
    }

    /** 当前配置引用到的战场维度（由 BattlefieldGameManager 在加载配置时登记）。 */
    private static final Set<ResourceKey<Level>> BATTLE_LEVELS = new HashSet<>();

    /** PR #43 的旧方案留下的临时世界目录前缀。 */
    private static final String LEGACY_INSTANCE_PREFIX = "bf_inst_";

    public static void registerBattleLevel(ResourceKey<Level> key) {
        BATTLE_LEVELS.add(key);
    }

    public static boolean isBattleLevel(Level level) {
        return BATTLE_LEVELS.contains(level.dimension());
    }

    @Nullable
    public static ServerLevel resolve(MinecraftServer server, String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, rl));
    }

    /**
     * 战场维度不刷自然生物：地形是还原出来的，洞穴里刷出的怪既影响对局又会被当成「要清理的实体」。
     * 刷怪笼、刷怪蛋、命令生成不受影响。
     */
    @SubscribeEvent
    public static void onFinalizeSpawn(MobSpawnEvent.FinalizeSpawn event) {
        if (!isBattleLevel(event.getLevel().getLevel())) return;
        MobSpawnType type = event.getSpawnType();
        if (type == MobSpawnType.NATURAL || type == MobSpawnType.CHUNK_GENERATION
                || type == MobSpawnType.PATROL || type == MobSpawnType.REINFORCEMENT) {
            event.setSpawnCancelled(true);
        }
    }

    /**
     * 开服、世界加载之前清掉旧方案（每局复制世界）遗留的 {@code bf_inst_*} 目录。
     * 此时还没有任何世界被加载，也没有玩家，同步删除不会卡住游戏 tick。
     */
    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        Path container = event.getServer().getServerDirectory().toPath();
        int removed = 0;
        try (Stream<Path> children = Files.list(container)) {
            for (Path dir : (Iterable<Path>) children::iterator) {
                String name = dir.getFileName().toString();
                if (!name.startsWith(LEGACY_INSTANCE_PREFIX) || !Files.isDirectory(dir)) continue;
                if (!Files.exists(dir.resolve("level.dat")) && !Files.isDirectory(dir.resolve("region"))) continue;
                deleteRecursively(dir);
                removed++;
                Battlefield.LOGGER.warn("[Battlefield] 已清理旧版临时对局世界目录: {}", dir.toAbsolutePath());
            }
        } catch (IOException e) {
            Battlefield.LOGGER.warn("[Battlefield] 扫描旧版临时世界目录失败: {}", container.toAbsolutePath(), e);
        }
        if (removed > 0) {
            Battlefield.LOGGER.warn("[Battlefield] 共清理 {} 个旧版临时世界。若 Multiverse / mohist-config/worlds.yml 中仍登记了这些世界，"
                    + "请手动删除对应条目，否则它们可能被当成新世界重新生成。", removed);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
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
    }
}
