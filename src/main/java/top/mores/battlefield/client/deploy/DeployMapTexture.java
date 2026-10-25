package top.mores.battlefield.client.deploy;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.net.S2CDeployMapPacket;

/**
 * 部署界面的战区底图纹理缓存。
 * <p>
 * 服务端按 {@code mapId}（arena + 模板指纹 + 栅格参数）烘焙一次，客户端按同一个 id 缓存纹理：
 * 同一张图连开多局、甚至换槽位，只要模板没变就不会再传、也不会再解码。
 * <p>
 * 纹理是运行时生成的，用 {@link DynamicTexture} 注册成独立资源；换图时先释放旧纹理，避免泄漏。
 */
public final class DeployMapTexture {

    /** 画到屏幕上的那份元数据：贴图 + 像素尺寸 + 世界边界。 */
    public record View(ResourceLocation texture, int textureWidth, int textureHeight,
                       int minX, int minZ, int maxX, int maxZ, int blocksPerPixel) {
    }

    private static String mapId = "";
    private static ResourceLocation location;
    private static int textureWidth;
    private static int textureHeight;
    private static int minX;
    private static int minZ;
    private static int maxX;
    private static int maxZ;
    private static int blocksPerPixel;

    private DeployMapTexture() {
    }

    public static boolean has(String id) {
        return id != null && !id.isBlank() && id.equals(mapId) && location != null;
    }

    @Nullable
    public static View view() {
        if (location == null) return null;
        return new View(location, textureWidth, textureHeight, minX, minZ, maxX, maxZ, blocksPerPixel);
    }

    /**
     * 解码并注册底图。失败（图片损坏 / 显存不足）时只记日志，界面继续用坐标示意图，不崩。
     */
    public static boolean apply(S2CDeployMapPacket packet) {
        if (has(packet.mapId())) return true;

        String id = packet.mapId();
        if (id.isBlank() || packet.png().length == 0) return false;

        try {
            NativeImage image = NativeImage.read(packet.png());
            DynamicTexture texture = new DynamicTexture(image);
            texture.setFilter(true, false);

            releaseOld();
            location = Minecraft.getInstance().getTextureManager()
                    .register("battlefield_deploy_map/" + sanitize(id), texture);

            textureWidth = image.getWidth();
            textureHeight = image.getHeight();
            minX = packet.minX();
            minZ = packet.minZ();
            maxX = packet.maxX();
            maxZ = packet.maxZ();
            blocksPerPixel = Math.max(1, packet.blocksPerPixel());
            mapId = id;
            return true;
        } catch (Exception e) {
            Battlefield.LOGGER.warn("[Battlefield] 部署底图解码失败（mapId={}，{} 字节）", id, packet.png().length, e);
            clear();
            return false;
        }
    }

    public static void clear() {
        releaseOld();
        mapId = "";
        textureWidth = 0;
        textureHeight = 0;
        blocksPerPixel = 0;
    }

    private static void releaseOld() {
        if (location != null) {
            try {
                Minecraft.getInstance().getTextureManager().release(location);
            } catch (Exception ignored) {
                // 纹理可能已经被别处释放，忽略即可
            }
            location = null;
        }
    }

    /** 资源路径只允许小写字母、数字和 {@code _-./}，其余替换掉。 */
    private static String sanitize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = Character.toLowerCase(raw.charAt(i));
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.' || c == '/';
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }
}
