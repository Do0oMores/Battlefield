package top.mores.battlefield.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.client.net.BattlefieldClientPackets;

import java.util.function.Supplier;

public final class S2COpenDeployScreenPacket {
    public static void encode(S2COpenDeployScreenPacket msg, FriendlyByteBuf buf) {
    }

    public static S2COpenDeployScreenPacket decode(FriendlyByteBuf buf) {
        return new S2COpenDeployScreenPacket();
    }

    public static void handle(S2COpenDeployScreenPacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> BattlefieldClientPackets.handleOpenDeployScreen(msg)));
        ctx.setPacketHandled(true);
    }
}
