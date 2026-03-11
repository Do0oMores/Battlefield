package top.mores.battlefield.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.client.AmmoStationClientState;

import java.util.function.Supplier;

public class S2CAmmoStationCooldownPacket {
    private final BlockPos pos;
    private final int remainingTicks;

    public S2CAmmoStationCooldownPacket(BlockPos pos, int remainingTicks) {
        this.pos = pos;
        this.remainingTicks = remainingTicks;
    }

    public static void encode(S2CAmmoStationCooldownPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeVarInt(msg.remainingTicks);
    }

    public static S2CAmmoStationCooldownPacket decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int remainingTicks = buf.readVarInt();
        return new S2CAmmoStationCooldownPacket(pos, remainingTicks);
    }

    public static void handle(S2CAmmoStationCooldownPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> AmmoStationClientState.setCooldown(msg.pos, msg.remainingTicks));
        context.setPacketHandled(true);
    }
}