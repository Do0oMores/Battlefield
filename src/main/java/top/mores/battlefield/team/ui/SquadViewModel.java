package top.mores.battlefield.team.ui;

import java.util.List;
import java.util.UUID;

public final class SquadViewModel {
    private SquadViewModel() {
    }

    public record SquadMemberView(UUID uuid, String name) {
    }

    public record SquadEntryView(
            int squadId,
            String displayName,
            List<SquadMemberView> members,
            boolean canJoin,
            boolean isMine
    ) {
    }

    public record SquadPanelView(
            List<SquadEntryView> squads,
            int selfSquadId,
            int squadCap
    ) {
    }
}