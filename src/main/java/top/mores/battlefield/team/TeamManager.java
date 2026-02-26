package top.mores.battlefield.team;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public final class TeamManager {
    private TeamManager() {}

    public static final int TEAM_CAP = 16;

    public static TeamId getTeam(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        return TeamSavedData.get(level).getTeam(p.getUUID());
    }

    public static void setTeam(ServerPlayer p, TeamId team) {
        ServerLevel level = p.serverLevel();
        TeamSavedData.get(level).setTeam(p.getUUID(), team);
    }

    public static void clearTeam(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        TeamSavedData.get(level).remove(p.getUUID());
    }

    public static boolean isSameTeam(ServerPlayer a, ServerPlayer b) {
        if (a == null || b == null) return false;
        TeamId ta = getTeam(a);
        TeamId tb = getTeam(b);
        return ta != TeamId.SPECTATOR && ta == tb;
    }

    /** 16v16 自动均衡分配：少人优先，满员则进另一队，两边满员则观战 */
    public static TeamId autoAssign(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        int atk = countTeam(level, TeamId.ATTACKERS);
        int def = countTeam(level, TeamId.DEFENDERS);

        TeamId pick;
        if (atk >= TEAM_CAP && def >= TEAM_CAP) {
            pick = TeamId.SPECTATOR;
        } else if (atk >= TEAM_CAP) {
            pick = TeamId.DEFENDERS;
        } else if (def >= TEAM_CAP) {
            pick = TeamId.ATTACKERS;
        } else {
            pick = (atk <= def) ? TeamId.ATTACKERS : TeamId.DEFENDERS;
        }

        setTeam(p, pick);
        return pick;
    }

    public static int countTeam(ServerLevel level, TeamId team) {
        TeamSavedData data = TeamSavedData.get(level);
        int count = 0;
        for (ServerPlayer sp : level.getServer().getPlayerList().getPlayers()) {
            if (sp.serverLevel() != level) continue;
            UUID id = sp.getUUID();
            if (data.getTeam(id) == team) count++;
        }
        return count;
    }
}
