package top.mores.battlefield.deploy.map;

import top.mores.battlefield.Battlefield;
import top.mores.battlefield.breakthrough.CapturePoint;
import top.mores.battlefield.breakthrough.Sector;
import top.mores.battlefield.config.BattlefieldServerConfig;
import top.mores.battlefield.config.SectorConfigLoader;
import top.mores.battlefield.server.arena.ArenaTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 底图烘焙的调度与缓存。
 * <p>
 * 烘焙只发生一次：模板读完（后台线程）→ 交给本类的单线程池再算一遍像素 → 结果按
 * {@code arena + 模板指纹 + 栅格参数} 缓存。同一张图连开多局、甚至多个槽位并行，
 * 都只算一次；换模板（指纹变）才会重算。
 * <p>
 * 缓存是 LRU，上限 6 张：一张 320x320 的底图约 400KB，6 张不到 3MB，够用且不会无限涨。
 */
public final class DeployMapService {

    private static final int CACHE_LIMIT = 6;

    private static final Map<String, BakedDeployMap> CACHE =
            new LinkedHashMap<>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BakedDeployMap> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Battlefield-MapBake");
        thread.setDaemon(true);
        return thread;
    });

    private DeployMapService() {
    }

    public static CompletableFuture<BakedDeployMap> bakeAsync(ArenaTemplate template, DeployMapRaster raster) {
        String mapId = mapId(template, raster);
        BakedDeployMap cached;
        synchronized (CACHE) {
            cached = CACHE.get(mapId);
        }
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        return CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            BakedDeployMap map = TemplateMapBaker.bake(mapId, template, raster);
            synchronized (CACHE) {
                CACHE.put(mapId, map);
            }
            Battlefield.LOGGER.info("[Battlefield] 底图烘焙完成：{}（{}x{}，{} 格/像素，耗时 {} ms）",
                    mapId, map.width(), map.height(), map.blocksPerPixel(),
                    (System.nanoTime() - start) / 1_000_000);
            return map;
        }, EXECUTOR);
    }

    /**
     * 底图只覆盖"能打的地方"：所有战线的可活动矩形 + 据点范围，外扩 8 格后夹到模板边界。
     * 没配战线时退回整张模板范围。
     */
    public static DeployMapRaster rasterFor(SectorConfigLoader.ArenaConfig arena, ArenaTemplate template) {
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        for (Sector sector : arena.sectors) {
            for (Sector.AreaRect rect : sector.attackerAreas) {
                minX = Math.min(minX, Math.min(rect.x1(), rect.x2()));
                maxX = Math.max(maxX, Math.max(rect.x1(), rect.x2()));
                minZ = Math.min(minZ, Math.min(rect.z1(), rect.z2()));
                maxZ = Math.max(maxZ, Math.max(rect.z1(), rect.z2()));
            }
            for (Sector.AreaRect rect : sector.defenderAreas) {
                minX = Math.min(minX, Math.min(rect.x1(), rect.x2()));
                maxX = Math.max(maxX, Math.max(rect.x1(), rect.x2()));
                minZ = Math.min(minZ, Math.min(rect.z1(), rect.z2()));
                maxZ = Math.max(maxZ, Math.max(rect.z1(), rect.z2()));
            }
            for (CapturePoint point : sector.points) {
                minX = Math.min(minX, point.x - point.radius);
                maxX = Math.max(maxX, point.x + point.radius);
                minZ = Math.min(minZ, point.z - point.radius);
                maxZ = Math.max(maxZ, point.z + point.radius);
            }
        }

        SectorConfigLoader.Bounds bounds = template.bounds;
        if (minX > maxX) {
            minX = bounds.minX();
            maxX = bounds.maxX();
            minZ = bounds.minZ();
            maxZ = bounds.maxZ();
        } else {
            minX = Math.max(bounds.minX(), minX - 8);
            maxX = Math.min(bounds.maxX(), maxX + 8);
            minZ = Math.max(bounds.minZ(), minZ - 8);
            maxZ = Math.min(bounds.maxZ(), maxZ + 8);
        }

        BattlefieldServerConfig.Settings cfg = BattlefieldServerConfig.get();
        return DeployMapRaster.of((int) Math.floor(minX), (int) Math.floor(minZ),
                (int) Math.ceil(maxX), (int) Math.ceil(maxZ),
                Math.max(1, cfg.deployMapBlocksPerPixel), Math.max(32, cfg.deployMapMaxPixels));
    }

    public static String mapId(ArenaTemplate template, DeployMapRaster raster) {
        String fingerprint = template.fingerprint == null ? "0" : template.fingerprint;
        String shortFingerprint = fingerprint.substring(0, Math.min(8, fingerprint.length()));
        return template.id + "-" + shortFingerprint
                + "-" + raster.blocksPerPixel()
                + "-" + Integer.toHexString((raster.minX() * 31 + raster.minZ()) * 31 + raster.width());
    }

    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }
}
