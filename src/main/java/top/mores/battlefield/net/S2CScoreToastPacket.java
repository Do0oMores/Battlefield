package top.mores.battlefield.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.client.net.BattlefieldClientPackets;

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
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> BattlefieldClientPackets.handleScoreToast(msg)));
        ctx.get().setPacketHandled(true);
    }
}
