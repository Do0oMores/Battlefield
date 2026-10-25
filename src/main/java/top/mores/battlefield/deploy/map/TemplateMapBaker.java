package top.mores.battlefield.deploy.map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.MapColor;
import top.mores.battlefield.server.arena.ArenaTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 从只读模板烘焙俯视底图。
 * <p>
 * 模板就是 {@code config/battlefield/templates/<id>/region} 里的 .mca，{@link ArenaTemplate} 已经把
 * 区块数据解析成内存里的 {@link PalettedContainer}。因此这里**不需要加载世界、不需要区块票据、
 * 也不占主线程**：纯读写内存，在后台线程跑，正适合做一次性烘焙。
 * <p>
 * 取色规则：每列从上往下找第一个非空气方块，取它的 {@link MapColor}（和原版地图同一套配色）。
 * 再按"与东侧邻列的高度差"做一次轻微山体阴影，让平地之外的建筑/沟壑能看出层次。
 */
public final class TemplateMapBaker {

    /** 山体阴影强度与上限，避免地形起伏被放大成噪点。 */
    private static final double SHADE_PER_BLOCK = 0.035;
    private static final double SHADE_LIMIT = 0.22;

    private TemplateMapBaker() {
    }

    public static BakedDeployMap bake(String mapId, ArenaTemplate template, DeployMapRaster raster) {
        int count = raster.pixelCount();
        int[] colors = new int[count];
        int[] heights = new int[count];
        Map<Long, Integer> topSectionByChunk = new HashMap<>();

        for (int pz = 0; pz < raster.height(); pz++) {
            int worldZ = raster.worldZ(pz);
            for (int px = 0; px < raster.width(); px++) {
                int index = raster.index(px, pz);
                heights[index] = Integer.MIN_VALUE;
                int worldX = raster.worldX(px);
                colors[index] = sampleColumn(template, worldX, worldZ, topSectionByChunk, heights, index);
            }
        }

        applyHillShade(raster, colors, heights);
        return new BakedDeployMap(mapId, raster.minX(), raster.minZ(), raster.maxX(), raster.maxZ(),
                raster.blocksPerPixel(), raster.width(), raster.height(), colors);
    }

    /**
     * @return 该列的 ARGB（空气为 0），并把地表高度写进 {@code heights[index]}
     */
    private static int sampleColumn(ArenaTemplate template, int worldX, int worldZ,
                                    Map<Long, Integer> topSectionByChunk, int[] heights, int index) {
        int chunkX = worldX >> 4;
        int chunkZ = worldZ >> 4;
        ArenaTemplate.Chunk chunk = template.chunk(chunkX, chunkZ);
        if (chunk == null) return 0;

        PalettedContainer<BlockState>[] sections = chunk.states();
        if (sections == null || sections.length == 0) return 0;

        long key = ChunkPos.asLong(chunkX, chunkZ);
        Integer cachedTop = topSectionByChunk.get(key);
        int start = cachedTop != null ? cachedTop : highestSection(sections);
        if (cachedTop == null) topSectionByChunk.put(key, start);
        if (start < 0) return 0;

        int localX = worldX & 15;
        int localZ = worldZ & 15;

        // 自上而下：先跳过空的区段，再在区段内逐格找第一个非空气方块
        for (int section = start; section >= 0; section--) {
            PalettedContainer<BlockState> container = sections[section];
            if (container == null) continue;
            for (int y = 15; y >= 0; y--) {
                BlockState state = container.get(localX, y, localZ);
                if (state.isAir()) continue;
                heights[index] = (template.minSectionY + section) * 16 + y;
                MapColor color = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                return 0xFF000000 | color.col;
            }
        }
        return 0;
    }

    private static int highestSection(PalettedContainer<BlockState>[] sections) {
        for (int i = sections.length - 1; i >= 0; i--) {
            if (sections[i] != null) return i;
        }
        return -1;
    }

    /** 用东侧邻列的高度差做山体阴影：东边比自己低 → 自己偏亮，反之偏暗。 */
    private static void applyHillShade(DeployMapRaster raster, int[] colors, int[] heights) {
        for (int pz = 0; pz < raster.height(); pz++) {
            for (int px = 0; px < raster.width(); px++) {
                int index = raster.index(px, pz);
                int color = colors[index];
                if ((color >>> 24) == 0) continue;
                if (px + 1 >= raster.width()) continue;

                int east = heights[raster.index(px + 1, pz)];
                int here = heights[index];
                if (here == Integer.MIN_VALUE || east == Integer.MIN_VALUE) continue;

                double delta = Math.max(-SHADE_LIMIT, Math.min(SHADE_LIMIT, (here - east) * SHADE_PER_BLOCK));
                colors[index] = shade(color, 1.0 + delta);
            }
        }
    }

    private static int shade(int argb, double factor) {
        int r = clamp((int) Math.round(((argb >> 16) & 0xFF) * factor));
        int g = clamp((int) Math.round(((argb >> 8) & 0xFF) * factor));
        int b = clamp((int) Math.round((argb & 0xFF) * factor));
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
