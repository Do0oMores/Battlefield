package top.mores.battlefield.deploy.map;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void offsetMovesBoundsAndChangesMapId() {
        BakedDeployMap map = sample();
        BakedDeployMap moved = map.offset(16384, -32);
        assertEquals(100 + 16384, moved.minX());
        assertEquals(200 - 32, moved.minZ());
        // 客户端按 mapId 判断「手里这张图还能不能用」。边界变了 id 必须跟着变，
        // 否则第二局（换槽位）会沿用第一局的贴图范围，底图错位、比例也不对。
        assertNotEquals(map.mapId(), moved.mapId(), "槽位平移后 mapId 必须变化");
        assertTrue(moved.mapId().startsWith(map.mapId()), "id 应保留地形指纹，只追加槽位偏移");
        assertEquals(map.width(), moved.width());
        assertEquals(map.argb(), moved.argb());
    }

    @Test
    void sameSlotProducesSameMapId() {
        BakedDeployMap map = sample();
        // 同一槽位重复下发时 id 不变，客户端才不会重复解码同一张图
        assertEquals(map.offset(1024, 0).mapId(), map.offset(1024, 0).mapId());
        assertNotEquals(map.offset(1024, 0).mapId(), map.offset(1152, 0).mapId(),
                "不同槽位的 id 必须不同");
    }

    @Test
    void offsetByZeroReturnsSameInstance() {
        BakedDeployMap map = sample();
        assertEquals(map, map.offset(0, 0));
    }
}
