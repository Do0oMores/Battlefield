package top.mores.battlefield.net.team;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.team.ui.SquadUiSync;

import java.util.function.Supplier;

public record C2SSquadActionPacket(Action action, int squadId) {
    public enum Action {
        JOIN,
        CREATE
    }

    public static void encode(C2SSquadActionPacket msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.action);
        buf.writeVarInt(msg.squadId);
    }

    public static C2SSquadActionPacket decode(FriendlyByteBuf buf) {
        return new C2SSquadActionPacket(
                buf.readEnum(Action.class),
                buf.readVarInt()
        );
    }

    public static void handle(C2SSquadActionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) return;
            SquadUiSync.handleAction(sender, msg);
        });
        ctx.get().setPacketHandled(true);
    }
}