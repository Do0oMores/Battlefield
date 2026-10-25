package top.mores.battlefield.deploy.map;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 底图导出测试：PNG 必须能被标准解码器读回，且逐像素与烘焙结果一致。
 * 客户端就是用 {@code NativeImage.read} 解这份字节的，颜色/透明度错了底图就是一片糊。
 */
class BakedDeployMapTest {

    private static BakedDeployMap sample() {
        int[] pixels = new int[]{
                0xFFFF0000, 0xFF00FF00,
                0x800000FF, 0x00000000
        };
        return new BakedDeployMap("area-1-3-abcd", 100, 200, 100 + 2 * 3 - 1, 200 + 2 * 3 - 1,
                3, 2, 2, pixels);
    }

    @Test
    void pngRoundTripPreservesPixels() throws Exception {
        BakedDeployMap map = sample();
        byte[] png = map.toPng();
        assertTrue(png.length > 8, "PNG 至少要有签名");

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image);
        assertEquals(map.width(), image.getWidth());
        assertEquals(map.height(), image.getHeight());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                assertEquals(map.argb()[y * map.width() + x], image.getRGB(x, y),
                        "像素不一致 @" + x + "," + y);
            }
        }
    }

    @Test
    void offsetOnlyMovesBounds() {
        BakedDeployMap map = sample();
        BakedDeployMap moved = map.offset(16384, -32);
        assertEquals(100 + 16384, moved.minX());
        assertEquals(200 - 32, moved.minZ());
        assertEquals(map.mapId(), moved.mapId(), "同一张地形图，id 不应该因为槽位而变");
        assertEquals(map.width(), moved.width());
        assertEquals(map.argb(), moved.argb());
    }

    @Test
    void offsetByZeroReturnsSameInstance() {
        BakedDeployMap map = sample();
        assertEquals(map, map.offset(0, 0));
    }
}
