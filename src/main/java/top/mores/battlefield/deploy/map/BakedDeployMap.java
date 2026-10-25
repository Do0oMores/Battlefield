package top.mores.battlefield.deploy.map;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;

/**
 * 烘焙好的底图。
 * <p>
 * 像素坐标与边界字段一一对应：左上角像素覆盖 {@code (minX..minX+bpp-1, minZ..minZ+bpp-1)}，
 * 客户端按同一组边界贴图，标记就能和地形对齐。
 * <p>
 * 坐标是"导出坐标"——模板坐标加上槽位平移量之后的结果；像素本身与槽位无关，
 * 所以多个槽位可以共用同一份烘焙结果，只在导出时 {@link #offset(int, int)} 平移边界。
 */
public record BakedDeployMap(String mapId,
                             int minX, int minZ, int maxX, int maxZ,
                             int blocksPerPixel,
                             int width, int height,
                             int[] argb) {

    /**
     * 平移边界（像素不变）。槽位不同、地形相同，可以直接复用同一份像素。
     * <p>
     * mapId 必须跟着偏移走：客户端按 mapId 判断「手里这张图还能不能用」，
     * 同一个 id 配不同边界时，第二局会直接沿用上一局的贴图范围——底图被贴到第一局槽位的坐标上，
     * 表现为整张图错位、比例看起来也不对。同一槽位重复推送同一个 id，客户端仍然不会重复解码。
     */
    public BakedDeployMap offset(int dx, int dz) {
        if (dx == 0 && dz == 0) return this;
        return new BakedDeployMap(mapId + "@" + dx + "," + dz, minX + dx, minZ + dz, maxX + dx, maxZ + dz,
                blocksPerPixel, width, height, argb);
    }

    public byte[] toPng() {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 14)) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            // 内存中的 BufferedImage 写 PNG 不会碰磁盘，理论上不会失败；失败就当作没有底图
            throw new UncheckedIOException("底图编码失败", e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BakedDeployMap other)) return false;
        return minX == other.minX && minZ == other.minZ && maxX == other.maxX && maxZ == other.maxZ
                && blocksPerPixel == other.blocksPerPixel && width == other.width && height == other.height
                && mapId.equals(other.mapId) && Arrays.equals(argb, other.argb);
    }

    @Override
    public int hashCode() {
        int result = mapId.hashCode();
        result = 31 * result + minX;
        result = 31 * result + minZ;
        result = 31 * result + width;
        result = 31 * result + height;
        result = 31 * result + Arrays.hashCode(argb);
        return result;
    }

    @Override
    public String toString() {
        return "BakedDeployMap[" + mapId + " " + width + "x" + height + " @" + blocksPerPixel + "bpp]";
    }
}
