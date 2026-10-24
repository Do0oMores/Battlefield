package top.mores.battlefield.server.arena;

import net.minecraft.world.level.ChunkPos;
import top.mores.battlefield.config.BattlefieldServerConfig;
import top.mores.battlefield.config.SectorConfigLoader;

/**
 * 槽位的几何：模板原坐标 → 战场维度坐标的平移量。
 * <p>
 * 槽位沿 X 轴一字排开，第 k 号槽位的起点在 {@code slotBaseX + k * slotSpacingBlocks}；
 * 模板范围的最小角对齐到这个起点。平移量是 16 的整数倍，区块内坐标不变。
 */
public record SlotGeometry(int slotIndex, int originChunkX, int originChunkZ, SectorConfigLoader.Bounds templateBounds) {

    public static SlotGeometry of(int slotIndex, SectorConfigLoader.Bounds templateBounds) {
        var cfg = BattlefieldServerConfig.get();
        int originChunkX = (cfg.slotBaseX >> 4) + slotIndex * (cfg.slotSpacingBlocks >> 4);
        int originChunkZ = cfg.slotBaseZ >> 4;
        return new SlotGeometry(slotIndex, originChunkX, originChunkZ, templateBounds);
    }

    public int offsetChunkX() {
        return originChunkX - templateBounds.minChunkX();
    }

    public int offsetChunkZ() {
        return originChunkZ - templateBounds.minChunkZ();
    }

    /** 方块坐标平移量（X）。 */
    public double dx() {
        return offsetChunkX() * 16.0;
    }

    /** 方块坐标平移量（Z）。 */
    public double dz() {
        return offsetChunkZ() * 16.0;
    }

    public int blockDx() {
        return offsetChunkX() << 4;
    }

    public int blockDz() {
        return offsetChunkZ() << 4;
    }

    public long originKey() {
        return ChunkPos.asLong(originChunkX, originChunkZ);
    }

    /** 模板宽度是否塞得进一个槽位（并且与相邻槽位留出至少 512 格缓冲，粒子强制渲染距离即 512）。 */
    public boolean fitsSpacing() {
        int width = templateBounds.widthChunks() * 16;
        return width + 512 <= BattlefieldServerConfig.get().slotSpacingBlocks;
    }
}
