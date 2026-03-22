package top.mores.battlefield.net.ammo;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.block.TaczAmmoStationBlock;

import java.util.function.Supplier;

public class C2SAmmoStationInteractPacket {
    private final BlockPos pos;

    public C2SAmmoStationInteractPacket(BlockPos pos) {
        this.pos = pos;
    }

    public static void encode(C2SAmmoStationInteractPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
    }

    public static C2SAmmoStationInteractPacket decode(FriendlyByteBuf buf) {
        return new C2SAmmoStationInteractPacket(buf.readBlockPos());
    }

    public static void handle(C2SAmmoStationInteractPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;

            if (player.level().isLoaded(msg.pos)) {
                BlockState state = player.level().getBlockState(msg.pos);
                if (!(state.getBlock() instanceof TaczAmmoStationBlock)) return;

                // 防止隔墙/超距乱用
                double maxDistSq = 25.0D; // 5格
                if (player.distanceToSqr(
                        msg.pos.getX() + 0.5,
                        msg.pos.getY() + 0.5,
                        msg.pos.getZ() + 0.5) > maxDistSq) {
                    return;
                }

                TaczAmmoStationBlock.trySupply(player.level(), msg.pos, state, player);
            }
        });
        ctx.setPacketHandled(true);
    }
}