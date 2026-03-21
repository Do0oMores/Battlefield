package top.mores.battlefield.team.ui;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import top.mores.battlefield.net.BattlefieldNet;
import top.mores.battlefield.net.team.C2SSquadActionPacket;
import top.mores.battlefield.net.team.S2CSquadPanelPacket;
import top.mores.battlefield.team.SquadManager;
import top.mores.battlefield.team.TeamId;
import top.mores.battlefield.team.TeamManager;

public final class SquadUiSync {
    private SquadUiSync() {
    }

    public static void sendTo(ServerPlayer player) {
        BattlefieldNet.sendToPlayer(player, new S2CSquadPanelPacket(SquadUiService.buildFor(player)));
    }

    public static void sendToTeamOf(ServerPlayer player) {
        TeamId team = TeamManager.getTeam(player);
        if (team == TeamId.SPECTATOR) return;

        for (ServerPlayer sp : player.server.getPlayerList().getPlayers()) {
            if (sp.serverLevel() != player.serverLevel()) continue;
            if (TeamManager.getTeam(sp) != team) continue;
            sendTo(sp);
        }
    }

    public static void handleAction(ServerPlayer player, C2SSquadActionPacket packet) {
        boolean ok = switch (packet.action()) {
            case JOIN -> SquadManager.joinSquad(player, packet.squadId());
            case CREATE -> SquadManager.createSquad(player);
        };

        if (!ok) {
            player.sendSystemMessage(Component.literal("小队操作失败"));
        }

        sendToTeamOf(player);
    }
}