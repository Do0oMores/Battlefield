package top.mores.battlefield.net.team;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.team.ui.TeamHud;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static top.mores.battlefield.team.ui.SquadViewModel.*;

public record S2CSquadPanelPacket(SquadPanelView view) {
    public static void encode(S2CSquadPanelPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.view.selfSquadId());
        buf.writeVarInt(msg.view.squadCap());

        buf.writeVarInt(msg.view.squads().size());
        for (SquadEntryView squad : msg.view.squads()) {
            buf.writeVarInt(squad.squadId());
            buf.writeUtf(squad.displayName());
            buf.writeBoolean(squad.canJoin());
            buf.writeBoolean(squad.isMine());

            buf.writeVarInt(squad.members().size());
            for (SquadMemberView member : squad.members()) {
                buf.writeUUID(member.uuid());
                buf.writeUtf(member.name());
            }
        }
    }

    public static S2CSquadPanelPacket decode(FriendlyByteBuf buf) {
        int selfSquadId = buf.readVarInt();
        int squadCap = buf.readVarInt();

        int squadSize = buf.readVarInt();
        List<SquadEntryView> squads = new ArrayList<>();

        for (int i = 0; i < squadSize; i++) {
            int squadId = buf.readVarInt();
            String displayName = buf.readUtf();
            boolean canJoin = buf.readBoolean();
            boolean isMine = buf.readBoolean();

            int memberSize = buf.readVarInt();
            List<SquadMemberView> members = new ArrayList<>();
            for (int j = 0; j < memberSize; j++) {
                UUID uuid = buf.readUUID();
                String name = buf.readUtf();
                members.add(new SquadMemberView(uuid, name));
            }

            squads.add(new SquadEntryView(
                    squadId,
                    displayName,
                    members,
                    canJoin,
                    isMine
            ));
        }

        return new S2CSquadPanelPacket(new SquadPanelView(squads, selfSquadId, squadCap));
    }

    public static void handle(S2CSquadPanelPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandler.handle(msg))
        );
        ctx.get().setPacketHandled(true);
    }

    private static final class ClientHandler {
        private static void handle(S2CSquadPanelPacket msg) {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;

            if (mc.screen instanceof TeamHud hud) {
                hud.applySnapshot(msg.view);
            } else {
                mc.setScreen(new TeamHud(msg.view));
            }
        }
    }
}