package top.mores.battlefield.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.game.BattlefieldGameManager;

import java.util.function.Supplier;

/**
 * 客户端请求当前对局的底图。
 * <p>
 * 只在"手里没有这个 mapId 的纹理"时发：底图按 mapId 缓存，一局最多传一次。
 * 无载荷，服务端只按请求者自己所在的对局取图，客户端指定不了任何东西。
 */
public final class C2SRequestDeployMap {

    public static final int MIN_INTERVAL_TICKS = 20;

    public static void encode(C2SRequestDeployMap msg, FriendlyByteBuf buf) {
    }

    public static C2SRequestDeployMap decode(FriendlyByteBuf buf) {
        return new C2SRequestDeployMap();
    }

    public static void handle(C2SRequestDeployMap msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sp = ctx.getSender();
            if (sp == null) return;
            if (!PacketRateLimiter.allow(sp, "deploy_map", MIN_INTERVAL_TICKS)) return;
            BattlefieldGameManager.requestDeployMap(sp);
        });
        ctx.setPacketHandled(true);
    }
}
