package top.mores.battlefield.net;

import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.game.BattlefieldGameManager;

import java.util.function.Supplier;

/**
 * 客户端请求重新部署。
 * <p>
 * 包里只带「部署点稳定标识 + 配装槽位」。标识是 {@code base} / {@code point:A} /
 * {@code beacon:<uuid>} 这类服务端自己算出来的键，坐标完全由服务端按当前对局状态解析，
 * 客户端既不能指定落点，也不能凭一个旧 id 复活到已经易主的据点。
 * <p>
 * 服务端无论成功失败都会回一个 {@link S2CDeployResultPacket}：失败时界面保持打开并显示原因。
 */
public final class C2SDeployRequest {

    public static final int MAX_OPTION_ID = 48;

    private final String optionId;
    private final int bpSlot; // 1..9

    public C2SDeployRequest(String optionId, int bpSlot) {
        this.optionId = optionId == null ? "" : optionId;
        this.bpSlot = bpSlot;
    }

    public static void encode(C2SDeployRequest msg, FriendlyByteBuf buf) {
        String id = msg.optionId.length() <= MAX_OPTION_ID ? msg.optionId : msg.optionId.substring(0, MAX_OPTION_ID);
        buf.writeUtf(id, MAX_OPTION_ID);
        buf.writeVarInt(msg.bpSlot);
    }

    public static C2SDeployRequest decode(FriendlyByteBuf buf) {
        return new C2SDeployRequest(buf.readUtf(MAX_OPTION_ID), buf.readVarInt());
    }

    public static void handle(C2SDeployRequest msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sp = ctx.getSender();
            if (sp == null) return;

            BattlefieldGameManager.DeployResult result =
                    BattlefieldGameManager.deploy(sp, msg.optionId, msg.bpSlot);

            // 阵亡部署会走 PlayerList#respawn，旧 ServerPlayer 已被替换成新实例；
            // 结果包必须发给当前实例，否则连接虽然还能发（connection 是复用的），玩家引用已经过期。
            ServerPlayer target = sp.server.getPlayerList().getPlayer(sp.getUUID());
            if (target == null) return;

            if (!result.ok()) {
                target.displayClientMessage(
                        Component.literal(result.message()).withStyle(ChatFormatting.RED), true);
            }
            BattlefieldNet.sendToPlayer(target, new S2CDeployResultPacket(result.ok(), result.message()));
        });
        ctx.setPacketHandled(true);
    }
}
