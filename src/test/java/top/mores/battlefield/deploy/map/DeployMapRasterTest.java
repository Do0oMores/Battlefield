package top.mores.battlefield.deploy.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 底图栅格几何的纯计算测试。
 * <p>
 * 这里保护的是"像素 ↔ 世界坐标"的一致性：客户端按这套换算贴图并放标记，
 * 一旦对齐边界算错，标记就会整体偏半格到几格，肉眼看起来像"地图不准"。
 */
class DeployMapRasterTest {

    @Test
    void keepsRequestedResolutionWhenItFits() {
        DeployMapRaster raster = DeployMapRaster.of(0, 0, 519, 279, 3, 320);
        assertEquals(3, raster.blocksPerPixel());
        assertEquals(174, raster.width());   // ceil(520 / 3)
        assertEquals(94, raster.height());   // ceil(280 / 3)
        assertEquals(0, raster.minX());
        assertEquals(0, raster.minZ());
    }

    @Test
    void alignsMaxBoundsToWholePixels() {
        DeployMapRaster raster = DeployMapRaster.of(0, 0, 100, 100, 3, 320);
        // 超出的那一部分也要算进图片范围，否则最后半格会被裁掉
        assertEquals(raster.width() * 3 - 1, raster.maxX());
        assertEquals(raster.height() * 3 - 1, raster.maxZ());
        assertTrue(raster.maxX() >= 100);
        assertTrue(raster.maxZ() >= 100);
    }

    @Test
    void lowersResolutionWhenOverPixelLimit() {
        DeployMapRaster raster = DeployMapRaster.of(0, 0, 2000, 2000, 1, 320);
        assertTrue(raster.width() <= 320, "宽度必须被像素上限约束: " + raster.width());
        assertTrue(raster.height() <= 320, "高度必须被像素上限约束: " + raster.height());
        assertTrue(raster.blocksPerPixel() > 1);
    }

    @Test
    void worldToPixelRoundTrip() {
        DeployMapRaster raster = DeployMapRaster.of(-260, -140, 260, 140, 4, 320);
        for (int worldX : new int[]{-260, -128, -3, 0, 3, 128, 259}) {
            int px = raster.pixelX(worldX);
            assertTrue(px >= 0 && px < raster.width(), "越界: " + worldX + " -> " + px);
            int center = raster.worldX(px);
            // 像素中心与原始坐标的偏差不超过半个像素
            assertTrue(Math.abs(center - worldX) <= raster.blocksPerPixel(),
                    "对齐偏差过大: " + worldX + " -> " + center);
        }
    }

    @Test
    void swappedCornersAreNormalized() {
        DeployMapRaster a = DeployMapRaster.of(100, 200, -100, -200, 2, 320);
        assertEquals(-100, a.minX());
        assertEquals(-200, a.minZ());
        assertTrue(a.maxX() >= 100);
        assertTrue(a.maxZ() >= 200);
    }

    @Test
    void degenerateBoundsStillProduceOnePixel() {
        DeployMapRaster raster = DeployMapRaster.of(10, 10, 10, 10, 4, 320);
        assertEquals(1, raster.width());
        assertEquals(1, raster.height());
        assertEquals(1, raster.pixelCount());
    }
}
