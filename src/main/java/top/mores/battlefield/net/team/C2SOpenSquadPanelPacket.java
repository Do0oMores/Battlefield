package top.mores.battlefield.net.team;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.net.PacketRateLimiter;
import top.mores.battlefield.team.ui.SquadUiSync;

import java.util.function.Supplier;

public record C2SOpenSquadPanelPacket() {
    public static void encode(C2SOpenSquadPanelPacket msg, FriendlyByteBuf buf) {
    }

    public static C2SOpenSquadPanelPacket decode(FriendlyByteBuf buf) {
        return new C2SOpenSquadPanelPacket();
    }

    public static void handle(C2SOpenSquadPanelPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) return;
            if (!PacketRateLimiter.allow(sender, "squad_panel", 10)) return;
            SquadUiSync.sendTo(sender);
        });
        ctx.get().setPacketHandled(true);
    }
}