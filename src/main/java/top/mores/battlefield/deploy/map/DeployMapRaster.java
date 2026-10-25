package top.mores.battlefield.deploy.map;

/**
 * 底图栅格几何（纯计算，可单测）。
 * <p>
 * 负责"世界坐标 → 像素"的一切换算：每像素代表几格、图有多宽多高、像素对应哪一格。
 * 采样和画图都不关心这些，只问它要坐标，避免把换算逻辑散到两处。
 * <p>
 * 注意像素覆盖范围会向上取整，所以 {@link #maxX()} / {@link #maxZ()} 是<b>对齐后</b>的边界：
 * 客户端按它对位贴图，标记才不会和底图错开半格。
 */
public record DeployMapRaster(int minX, int minZ, int maxX, int maxZ,
                              int blocksPerPixel, int width, int height) {

    public static final int MAX_BLOCKS_PER_PIXEL = 32;

    public static DeployMapRaster of(int x1, int z1, int x2, int z2, int requestedBpp, int maxPixels) {
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);

        int limit = Math.max(8, maxPixels);
        int bpp = Math.max(1, requestedBpp);
        int w = pixelSpan(maxX - minX + 1, bpp);
        int h = pixelSpan(maxZ - minZ + 1, bpp);
        while ((w > limit || h > limit) && bpp < MAX_BLOCKS_PER_PIXEL) {
            bpp++;
            w = pixelSpan(maxX - minX + 1, bpp);
            h = pixelSpan(maxZ - minZ + 1, bpp);
        }

        int alignedMaxX = minX + w * bpp - 1;
        int alignedMaxZ = minZ + h * bpp - 1;
        return new DeployMapRaster(minX, minZ, alignedMaxX, alignedMaxZ, bpp, w, h);
    }

    private static int pixelSpan(int blocks, int bpp) {
        return Math.max(1, (blocks + bpp - 1) / bpp);
    }

    /** 像素列中心对应的世界 X。 */
    public int worldX(int pixelX) {
        return minX + pixelX * blocksPerPixel + blocksPerPixel / 2;
    }

    public int worldZ(int pixelZ) {
        return minZ + pixelZ * blocksPerPixel + blocksPerPixel / 2;
    }

    public int index(int pixelX, int pixelZ) {
        return pixelZ * width + pixelX;
    }

    public int pixelCount() {
        return width * height;
    }

    /** 世界 X 落在哪一列（越界返回 -1）。 */
    public int pixelX(int worldX) {
        int px = Math.floorDiv(worldX - minX, blocksPerPixel);
        return px >= 0 && px < width ? px : -1;
    }

    public int pixelZ(int worldZ) {
        int pz = Math.floorDiv(worldZ - minZ, blocksPerPixel);
        return pz >= 0 && pz < height ? pz : -1;
    }
}
