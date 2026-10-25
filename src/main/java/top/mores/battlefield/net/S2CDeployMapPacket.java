package top.mores.battlefield.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.client.net.BattlefieldClientPackets;

import java.util.function.Supplier;

/**
 * 服务端烘焙好的战区底图（PNG）。
 * <p>
 * 只在对局地图就绪后发一次：客户端按 {@link #mapId()} 缓存纹理，之后每局只要 id 没变就不再请求。
 * 像素坐标与 {@code minX/minZ/maxX/maxZ} 一一对应（每像素 {@code blocksPerPixel} 格），
 * 客户端按同一组边界贴图，标记才能和地形对齐。
 */
public final class S2CDeployMapPacket {

    public static final int MAX_MAP_ID = 48;
    /** 单张底图上限 512KB：320x320 的 PNG 通常 20~60KB，留足余量但不给恶意包放大内存的机会。 */
    public static final int MAX_PNG_BYTES = 512 * 1024;

    private final String mapId;
    private final int minX;
    private final int minZ;
    private final int maxX;
    private final int maxZ;
    private final int blocksPerPixel;
    private final byte[] png;

    public S2CDeployMapPacket(String mapId, int minX, int minZ, int maxX, int maxZ, int blocksPerPixel, byte[] png) {
        this.mapId = mapId == null ? "" : mapId;
        this.minX = minX;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
        this.blocksPerPixel = blocksPerPixel;
        this.png = png == null ? new byte[0] : png;
    }

    public String mapId() {
        return mapId;
    }

    public int minX() {
        return minX;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxZ() {
        return maxZ;
    }

    public int blocksPerPixel() {
        return blocksPerPixel;
    }

    public byte[] png() {
        return png;
    }

    public static void encode(S2CDeployMapPacket msg, FriendlyByteBuf buf) {
        String id = msg.mapId.length() <= MAX_MAP_ID ? msg.mapId : msg.mapId.substring(0, MAX_MAP_ID);
        buf.writeUtf(id, MAX_MAP_ID);
        buf.writeInt(msg.minX);
        buf.writeInt(msg.minZ);
        buf.writeInt(msg.maxX);
        buf.writeInt(msg.maxZ);
        buf.writeVarInt(Math.max(1, msg.blocksPerPixel));
        buf.writeByteArray(msg.png.length > MAX_PNG_BYTES
                ? java.util.Arrays.copyOf(msg.png, MAX_PNG_BYTES) : msg.png);
    }

    public static S2CDeployMapPacket decode(FriendlyByteBuf buf) {
        String mapId = buf.readUtf(MAX_MAP_ID);
        int minX = buf.readInt();
        int minZ = buf.readInt();
        int maxX = buf.readInt();
        int maxZ = buf.readInt();
        int bpp = Math.max(1, buf.readVarInt());
        byte[] png = buf.readByteArray(MAX_PNG_BYTES);
        return new S2CDeployMapPacket(mapId, minX, minZ, maxX, maxZ, bpp, png);
    }

    public static void handle(S2CDeployMapPacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> BattlefieldClientPackets.handleDeployMap(msg)));
        ctx.setPacketHandled(true);
    }
}
