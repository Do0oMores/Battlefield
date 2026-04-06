package top.mores.battlefield.net;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.client.ui.RespawnDeployScreen;

import java.util.function.Supplier;

public final class S2COpenDeployScreenPacket {

    public S2COpenDeployScreenPacket() {
    }

    public static void encode(S2COpenDeployScreenPacket msg, FriendlyByteBuf buf) {
    }

    public static S2COpenDeployScreenPacket decode(FriendlyByteBuf buf) {
        return new S2COpenDeployScreenPacket();
    }

    public static void handle(S2COpenDeployScreenPacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            if (mc.screen instanceof RespawnDeployScreen) return;
            mc.setScreen(new RespawnDeployScreen());
        });
        ctx.setPacketHandled(true);
    }
}
