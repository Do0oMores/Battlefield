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
 * 包里只带「部署点索引 + 配装槽位」，坐标完全由服务端按 arena 配置解析，
 * 客户端无法指定落点，因此不存在"发个包就能传送到任意位置"的漏洞。
 */
public final class C2SDeployRequest {

    private final int spawnIndex; // 0=队伍主出生点；1..N=第 N 个可用小队信标
    private final int bpSlot;     // 1..9

    public C2SDeployRequest(int spawnIndex, int bpSlot) {
        this.spawnIndex = spawnIndex;
        this.bpSlot = bpSlot;
    }

    public static void encode(C2SDeployRequest msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.spawnIndex);
        buf.writeVarInt(msg.bpSlot);
    }

    public static C2SDeployRequest decode(FriendlyByteBuf buf) {
        int spawnIndex = buf.readVarInt();
        int bpSlot = buf.readVarInt();
        return new C2SDeployRequest(spawnIndex, bpSlot);
    }

    public static void handle(C2SDeployRequest msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sp = ctx.getSender();
            if (sp == null) return;

            BattlefieldGameManager.DeployResult result =
                    BattlefieldGameManager.deploy(sp, msg.spawnIndex, msg.bpSlot);

            if (!result.ok()) {
                sp.displayClientMessage(
                        Component.literal(result.message()).withStyle(ChatFormatting.RED), true);
            }
        });
        ctx.setPacketHandled(true);
    }
}
