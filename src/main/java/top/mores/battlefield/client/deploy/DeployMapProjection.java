package top.mores.battlefield.client.deploy;

/**
 * 世界 XZ → 屏幕坐标的线性投影，等比缩放并居中。
 * <p>
 * 刻意与渲染分离：地图容器只问 {@link #screenX(double)} / {@link #screenY(double)}，
 * 换底图、换缩放范围都不需要动绘制代码。世界 Z 轴向下映射到屏幕 Y 轴（俯视，北在上）。
 */
public final class DeployMapProjection {

    private final int x;
    private final int y;
    private final int w;
    private final int h;
    private final double centerX;
    private final double centerZ;
    private final double scale;

    private DeployMapProjection(int x, int y, int w, int h, double centerX, double centerZ, double scale) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.scale = scale;
    }

    /**
     * 把世界范围装进给定矩形，留出 8% 边距，等比缩放。
     *
     * @param fillRatio 内容占矩形的比例（0~1），用于给图例和标题留空间
     */
    public static DeployMapProjection fit(int x, int y, int w, int h,
                                          double minX, double minZ, double maxX, double maxZ,
                                          double fillRatio) {
        double spanX = Math.max(1.0, maxX - minX);
        double spanZ = Math.max(1.0, maxZ - minZ);
        double usableW = Math.max(16, w) * clamp(fillRatio, 0.2, 1.0);
        double usableH = Math.max(16, h) * clamp(fillRatio, 0.2, 1.0);
        double scale = Math.min(usableW / spanX, usableH / spanZ);
        if (!Double.isFinite(scale) || scale <= 0) scale = 1.0;
        return new DeployMapProjection(x, y, w, h, (minX + maxX) * 0.5, (minZ + maxZ) * 0.5, scale);
    }

    public int screenX(double worldX) {
        return (int) Math.round(x + w * 0.5 + (worldX - centerX) * scale);
    }

    public int screenY(double worldZ) {
        return (int) Math.round(y + h * 0.5 + (worldZ - centerZ) * scale);
    }

    /** 屏幕 X → 世界 X（画网格与刻度用）。 */
    public double worldX(int screenX) {
        return centerX + (screenX - (x + w * 0.5)) / scale;
    }

    /** 屏幕 Y → 世界 Z。 */
    public double worldZ(int screenY) {
        return centerZ + (screenY - (y + h * 0.5)) / scale;
    }

    public double scale() {
        return scale;
    }

    public boolean contains(int sx, int sy) {
        return sx >= x && sx < x + w && sy >= y && sy < y + h;
    }

    /** 网格步长（方块）：选一个屏幕间距不小于 36px 的整数。 */
    public int gridStep() {
        int[] candidates = {8, 16, 32, 64, 128, 256, 512};
        for (int c : candidates) {
            if (c * scale >= 36) return c;
        }
        return 1024;
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }
}
