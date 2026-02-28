package top.mores.battlefield.net;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.client.ClientGameState;
import top.mores.battlefield.game.ScoreReason;

import java.util.function.Supplier;

public record S2CScoreToastPacket(int amount, String reasonId) {

    public static void encode(S2CScoreToastPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.amount());
        buf.writeUtf(msg.reasonId(), 32);
    }

    public static S2CScoreToastPacket decode(FriendlyByteBuf buf) {
        int amount = buf.readVarInt();
        String reasonId = buf.readUtf(32);
        return new S2CScoreToastPacket(amount, reasonId);
    }

    public static void handle(S2CScoreToastPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            int nowTick = mc.player.tickCount;

            // 你的客户端枚举（与服务端同名同 valueOf）
            ScoreReason r;
            try {
                r = ScoreReason.valueOf(msg.reasonId());
                ClientGameState.pushScoreToast(msg.amount(), r.text, r.color, nowTick, r.mergeKey);
            } catch (Exception e) {
                // 兜底：reasonId 直接当文本显示
                ClientGameState.pushScoreToast(msg.amount(), msg.reasonId(), 0xFF88FF88, nowTick, null);
            }

            // 同时刷新中心总分显示计时（如果你 HUD 用它做显示/隐藏）
            ClientGameState.hudLastScoreClientTick = nowTick;
        });
        ctx.get().setPacketHandled(true);
    }
}
