package top.mores.battlefield.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.game.BattlefieldGameManager;

import java.util.function.Supplier;

/**
 * 客户端请求一份最新的部署数据。
 * <p>
 * 用于三种场景：按下打开界面的按键、界面打开期间的定时刷新、部署被拒后立即刷新。
 * 无载荷——服务端只按请求者自己的状态取数，客户端说不了任何话。限频在服务端做，
 * 避免界面刷新变成刷包通道。
 */
public final class C2SRequestDeployData {

    /** 同一玩家两次请求的最小间隔（tick）。 */
    public static final int MIN_INTERVAL_TICKS = 5;

    public static void encode(C2SRequestDeployData msg, FriendlyByteBuf buf) {
    }

    public static C2SRequestDeployData decode(FriendlyByteBuf buf) {
        return new C2SRequestDeployData();
    }

    public static void handle(C2SRequestDeployData msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sp = ctx.getSender();
            if (sp == null) return;
            if (!PacketRateLimiter.allow(sp, "deploy_data", MIN_INTERVAL_TICKS)) return;
            BattlefieldGameManager.requestDeploymentData(sp);
        });
        ctx.setPacketHandled(true);
    }
}
