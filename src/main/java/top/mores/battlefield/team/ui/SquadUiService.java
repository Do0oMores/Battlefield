package top.mores.battlefield.team.ui;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import top.mores.battlefield.config.BattlefieldServerConfig;
import top.mores.battlefield.team.SquadManager;
import top.mores.battlefield.team.TeamId;
import top.mores.battlefield.team.TeamManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static top.mores.battlefield.team.ui.SquadViewModel.*;

public final class SquadUiService {
    private SquadUiService() {
    }

    public static SquadPanelView buildFor(ServerPlayer viewer) {
        TeamId team = TeamManager.getTeam(viewer);
        int squadCap = BattlefieldServerConfig.get().squadCap;

        if (team == TeamId.SPECTATOR) {
            return new SquadPanelView(List.of(), 0, squadCap);
        }

        ServerLevel level = viewer.serverLevel();
        int selfSquadId = SquadManager.getSquad(viewer);

        List<SquadEntryView> entries = new ArrayList<>();
        for (int squadId : SquadManager.getExistingSquadIds(level, team)) {
            List<SquadMemberView> members = new ArrayList<>();
            for (UUID uuid : SquadManager.getSquadMembers(level, team, squadId)) {
                ServerPlayer member = level.getServer().getPlayerList().getPlayer(uuid);
                String name = member != null ? member.getGameProfile().getName() : uuid.toString().substring(0, 8);
                members.add(new SquadMemberView(uuid, name));
            }

            members.sort(Comparator.comparing(SquadMemberView::name));

            boolean isMine = squadId == selfSquadId;
            boolean canJoin = !isMine && members.size() < squadCap;

            entries.add(new SquadEntryView(
                    squadId,
                    SquadManager.getSquadDisplayName(squadId),
                    members,
                    canJoin,
                    isMine
            ));
        }

        entries.sort(Comparator.comparingInt(SquadEntryView::squadId));
        return new SquadPanelView(entries, selfSquadId, squadCap);
    }
}