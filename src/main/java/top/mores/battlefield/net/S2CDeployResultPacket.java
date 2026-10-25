package top.mores.battlefield.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.client.net.BattlefieldClientPackets;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端的部署结果。
 * <p>
 * 失败时客户端必须保持界面打开并显示原因（原实现只发一条 actionbar，界面已经被客户端自己关掉了）。
 */
public final class S2CDeployResultPacket {

    public static final int MAX_MESSAGE = 96;

    private final boolean ok;
    private final String message;

    public S2CDeployResultPacket(boolean ok, String message) {
        this.ok = ok;
        this.message = message == null ? "" : (message.length() <= MAX_MESSAGE ? message : message.substring(0, MAX_MESSAGE));
    }

    public boolean ok() {
        return ok;
    }

    public String message() {
        return message;
    }

    public static void encode(S2CDeployResultPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.ok);
        buf.writeUtf(msg.message, MAX_MESSAGE);
    }

    public static S2CDeployResultPacket decode(FriendlyByteBuf buf) {
        return new S2CDeployResultPacket(buf.readBoolean(), buf.readUtf(MAX_MESSAGE));
    }

    public static void handle(S2CDeployResultPacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> BattlefieldClientPackets.handleDeployResult(msg)));
        ctx.setPacketHandled(true);
    }
}
