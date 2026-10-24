package top.mores.battlefield.server.arena;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import top.mores.battlefield.config.SectorConfigLoader;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 解析完成、只读的地图模板。
 * <p>
 * 由 {@link TemplateStore} 在后台线程构建，构建完成后不再修改，主线程只读，因此不需要加锁。
 * 坐标一律是模板<b>原世界</b>的坐标；放进某个槽位时由 {@link SlotRestoreJob} 统一平移。
 */
public final class ArenaTemplate {
    public final String id;
    /** 模板文件的指纹（文件名 + 大小 + 修改时间），用于判断槽位里装的是不是最新版本。 */
    public final String fingerprint;
    public final SectorConfigLoader.Bounds bounds;
    /** 模板维度的最低区段号（1.20.1 主世界为 -4）。 */
    public final int minSectionY;
    private final Long2ObjectMap<Chunk> chunks;

    ArenaTemplate(String id, String fingerprint, SectorConfigLoader.Bounds bounds, int minSectionY,
                  Long2ObjectMap<Chunk> chunks) {
        this.id = id;
        this.fingerprint = fingerprint;
        this.bounds = bounds;
        this.minSectionY = minSectionY;
        this.chunks = chunks;
    }

    /** @return 模板里该区块的数据；模板没有这个区块（或不在范围内）时返回 null，视为全空气 */
    @Nullable
    public Chunk chunk(int chunkX, int chunkZ) {
        return chunks.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    public int chunkCount() {
        return chunks.size();
    }

    /**
     * @param states       按区段号（相对 {@link #minSectionY}）索引，null 表示整段空气
     * @param biomes       同上，null 表示该段没有群系数据（保持目标世界原样）
     * @param blockEntities 方块实体 NBT，坐标为模板原世界坐标
     * @param entities     实体 NBT，坐标为模板原世界坐标
     */
    public record Chunk(PalettedContainer<BlockState>[] states,
                        PalettedContainerRO<Holder<Biome>>[] biomes,
                        List<CompoundTag> blockEntities,
                        List<CompoundTag> entities) {
    }
}
