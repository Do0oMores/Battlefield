package top.mores.battlefield.team;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class SquadManager {
    private SquadManager() {
    }

    public static final int SQUAD_CAP = 4;
    public static final int TEAM_SQUAD_CAP = 16;

    public static int getSquad(ServerPlayer p) {
        TeamId team = TeamManager.getTeam(p);
        if (team == TeamId.SPECTATOR) return 0;
        return SquadSavedData.get(p.serverLevel()).mapFor(team).getOrDefault(p.getUUID(), 0);
    }

    public static int getSquad(ServerLevel level, UUID playerId, TeamId team) {
        if (team == TeamId.SPECTATOR) return 0;
        return SquadSavedData.get(level).mapFor(team).getOrDefault(playerId, 0);
    }

    public static List<UUID> getSquadMembers(ServerLevel level, TeamId team, int squadId) {
        if (team == TeamId.SPECTATOR || squadId <= 0) return java.util.Collections.emptyList();
        Map<UUID, Integer> map = SquadSavedData.get(level).mapFor(team);
        List<UUID> list = new ArrayList<>();
        for (Map.Entry<UUID, Integer> en : map.entrySet()) {
            if (en.getValue() == squadId) list.add(en.getKey());
        }
        return list;
    }

    public static boolean createSquad(ServerPlayer p) {
        TeamId team = TeamManager.getTeam(p);
        if (team == TeamId.SPECTATOR) return false;

        ServerLevel level = p.serverLevel();
        SquadSavedData data = SquadSavedData.get(level);
        Map<UUID, Integer> map = data.mapFor(team);

        int existing = map.getOrDefault(p.getUUID(), 0);
        if (existing > 0) return true;

        for (int i = 1; i <= TEAM_SQUAD_CAP; i++) {
            if (!squadExists(map, i)) {
                map.put(p.getUUID(), i);
                data.setDirty();
                return true;
            }
        }
        return false;
    }

    public static boolean joinSquad(ServerPlayer p, int squadId) {
        TeamId team = TeamManager.getTeam(p);
        if (team == TeamId.SPECTATOR) return false;
        if (squadId < 1 || squadId > TEAM_SQUAD_CAP) return false;

        ServerLevel level = p.serverLevel();
        SquadSavedData data = SquadSavedData.get(level);
        Map<UUID, Integer> map = data.mapFor(team);

        if (!squadExists(map, squadId)) return false;
        if (countMembers(map, squadId) >= SQUAD_CAP) return false;

        map.put(p.getUUID(), squadId);
        data.setDirty();
        return true;
    }

    public static void leaveSquad(ServerPlayer p) {
        TeamId team = TeamManager.getTeam(p);
        if (team == TeamId.SPECTATOR) return;
        removeFromTeamSquad(p.serverLevel(), p.getUUID(), team);
    }

    public static void onTeamChanged(ServerPlayer p, TeamId oldTeam, TeamId newTeam) {
        ServerLevel level = p.serverLevel();
        if (oldTeam != TeamId.SPECTATOR) {
            removeFromTeamSquad(level, p.getUUID(), oldTeam);
        }
        if (newTeam != TeamId.SPECTATOR) {
            autoAssignSquad(p);
        }
    }

    public static int autoAssignSquad(ServerPlayer p) {
        TeamId team = TeamManager.getTeam(p);
        if (team == TeamId.SPECTATOR) return 0;

        ServerLevel level = p.serverLevel();
        SquadSavedData data = SquadSavedData.get(level);
        Map<UUID, Integer> map = data.mapFor(team);

        int current = map.getOrDefault(p.getUUID(), 0);
        if (current > 0) return current;

        List<Integer> joinable = new ArrayList<>();
        for (int i = 1; i <= TEAM_SQUAD_CAP; i++) {
            if (squadExists(map, i) && countMembers(map, i) < SQUAD_CAP) {
                joinable.add(i);
            }
        }

        int squad;
        if (!joinable.isEmpty()) {
            squad = joinable.get(new Random().nextInt(joinable.size()));
        } else {
            squad = firstFreeSquadId(map);
            if (squad == 0) return 0;
        }

        map.put(p.getUUID(), squad);
        data.setDirty();
        return squad;
    }

    public static void resetForMatchStart(ServerLevel level) {
        SquadSavedData data = SquadSavedData.get(level);
        data.clearAll();

        for (ServerPlayer sp : level.getServer().getPlayerList().getPlayers()) {
            if (sp.serverLevel() != level) continue;
            TeamId t = TeamManager.getTeam(sp);
            if (t == TeamId.SPECTATOR) continue;
            autoAssignSquad(sp);
        }
    }

    public static void clearAll(ServerLevel level) {
        SquadSavedData.get(level).clearAll();
    }

    private static void removeFromTeamSquad(ServerLevel level, UUID uuid, TeamId team) {
        SquadSavedData data = SquadSavedData.get(level);
        Map<UUID, Integer> map = data.mapFor(team);
        Integer squadId = map.remove(uuid);
        if (squadId == null || squadId <= 0) {
            data.setDirty();
            return;
        }

        if (countMembers(map, squadId) == 0) {
            // 空小队自动消失（无需额外存储）
        }
        data.setDirty();
    }

    private static int firstFreeSquadId(Map<UUID, Integer> map) {
        for (int i = 1; i <= TEAM_SQUAD_CAP; i++) {
            if (!squadExists(map, i)) return i;
        }
        return 0;
    }

    private static boolean squadExists(Map<UUID, Integer> map, int squadId) {
        for (int sid : map.values()) {
            if (sid == squadId) return true;
        }
        return false;
    }

    private static int countMembers(Map<UUID, Integer> map, int squadId) {
        int count = 0;
        for (int sid : map.values()) {
            if (sid == squadId) count++;
        }
        return count;
    }
}
