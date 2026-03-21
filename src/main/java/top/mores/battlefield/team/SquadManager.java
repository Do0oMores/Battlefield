package top.mores.battlefield.team;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import top.mores.battlefield.config.BattlefieldServerConfig;
import top.mores.battlefield.game.BattlefieldGameManager;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class SquadManager {
    private static final Random RANDOM = new Random();

    private SquadManager() {
    }

    public static int getSquad(ServerPlayer p) {
        TeamId team = TeamManager.getTeam(p);
        if (team == TeamId.SPECTATOR) return 0;
        return SquadSavedData.get(p.serverLevel()).mapFor(team).getOrDefault(p.getUUID(), 0);
    }

    public static UUID getSquadUuid(ServerPlayer p) {
        TeamId team = TeamManager.getTeam(p);
        int squadId = getSquad(p);
        if (team == TeamId.SPECTATOR || squadId <= 0) return null;

        String matchId = BattlefieldGameManager.getPlayerAreaName(p.getUUID());
        if (matchId == null || matchId.isBlank()) return null;

        String key = matchId + ":" + team.name() + ":" + squadId;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    public static int getSquad(ServerLevel level, UUID playerId, TeamId team) {
        if (team == TeamId.SPECTATOR) return 0;
        return SquadSavedData.get(level).mapFor(team).getOrDefault(playerId, 0);
    }

    public static List<UUID> getSquadMembers(ServerLevel level, TeamId team, int squadId) {
        if (team == TeamId.SPECTATOR || squadId <= 0) return Collections.emptyList();
        Map<UUID, Integer> map = SquadSavedData.get(level).mapFor(team);
        List<UUID> list = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : map.entrySet()) {
            if (entry.getValue() == squadId) list.add(entry.getKey());
        }
        return list;
    }

    /** 创建一个“新小队”并加入；若已有小队则自动离开原小队 */
    public static boolean createSquad(ServerPlayer p) {
        TeamId team = TeamManager.getTeam(p);
        if (team == TeamId.SPECTATOR) return false;

        ServerLevel level = p.serverLevel();
        SquadSavedData data = SquadSavedData.get(level);
        Map<UUID, Integer> map = data.mapFor(team);

        int current = map.getOrDefault(p.getUUID(), 0);

        // 必须找“不是当前小队”的空闲 squadId，才算真正创建新小队
        int newSquadId = 0;
        int teamSquadCap = BattlefieldServerConfig.get().teamSquadCap;
        for (int i = 1; i <= teamSquadCap; i++) {
            if (i == current) continue;
            if (!squadExists(map, i)) {
                newSquadId = i;
                break;
            }
        }

        if (newSquadId == 0) {
            return false;
        }

        // 直接覆盖 = 自动离开原小队
        map.put(p.getUUID(), newSquadId);
        data.setDirty();
        return true;
    }

    /** 加入指定小队；若当前在其它小队，会自动离开原小队 */
    public static boolean joinSquad(ServerPlayer p, int squadId) {
        TeamId team = TeamManager.getTeam(p);
        if (team == TeamId.SPECTATOR) return false;
        if (squadId < 1 || squadId > BattlefieldServerConfig.get().teamSquadCap) return false;

        ServerLevel level = p.serverLevel();
        SquadSavedData data = SquadSavedData.get(level);
        Map<UUID, Integer> map = data.mapFor(team);

        int current = map.getOrDefault(p.getUUID(), 0);
        if (current == squadId) {
            return true; // 已在该小队，视为成功
        }

        if (!squadExists(map, squadId)) return false;
        if (countMembers(map, squadId) >= BattlefieldServerConfig.get().squadCap) return false;

        // 直接覆盖 = 自动离开原小队
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

    public static void autoAssignSquad(ServerPlayer p) {
        TeamId team = TeamManager.getTeam(p);
        if (team == TeamId.SPECTATOR) return;

        ServerLevel level = p.serverLevel();
        SquadSavedData data = SquadSavedData.get(level);
        Map<UUID, Integer> map = data.mapFor(team);

        int current = map.getOrDefault(p.getUUID(), 0);
        if (current > 0) return;

        List<Integer> joinable = new ArrayList<>();
        for (int i = 1; i <= BattlefieldServerConfig.get().teamSquadCap; i++) {
            if (squadExists(map, i) && countMembers(map, i) < BattlefieldServerConfig.get().squadCap) {
                joinable.add(i);
            }
        }

        int squad;
        if (!joinable.isEmpty()) {
            squad = joinable.get(RANDOM.nextInt(joinable.size()));
        } else {
            squad = firstFreeSquadId(map);
            if (squad == 0) return;
        }

        map.put(p.getUUID(), squad);
        data.setDirty();
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

    /** UI 用：当前阵营已有的小队 ID */
    public static List<Integer> getExistingSquadIds(ServerLevel level, TeamId team) {
        if (team == TeamId.SPECTATOR) return Collections.emptyList();

        Map<UUID, Integer> map = SquadSavedData.get(level).mapFor(team);
        List<Integer> result = new ArrayList<>();
        for (int i = 1; i <= BattlefieldServerConfig.get().teamSquadCap; i++) {
            if (squadExists(map, i)) {
                result.add(i);
            }
        }
        return result;
    }

    /** UI 用：小队人数 */
    public static int getSquadMemberCount(ServerLevel level, TeamId team, int squadId) {
        if (team == TeamId.SPECTATOR || squadId <= 0) return 0;
        return countMembers(SquadSavedData.get(level).mapFor(team), squadId);
    }

    /** UI 用：TEAM A / TEAM B / TEAM C ... */
    public static String getSquadDisplayName(int squadId) {
        return "【TEAM " + toLetters(squadId) + "】";
    }

    private static String toLetters(int num) {
        StringBuilder sb = new StringBuilder();
        while (num > 0) {
            num--;
            sb.insert(0, (char) ('A' + (num % 26)));
            num /= 26;
        }
        return sb.toString();
    }

    private static void removeFromTeamSquad(ServerLevel level, UUID uuid, TeamId team) {
        SquadSavedData data = SquadSavedData.get(level);
        Map<UUID, Integer> map = data.mapFor(team);
        Integer squadId = map.remove(uuid);
        if (squadId == null || squadId <= 0) {
            data.setDirty();
            return;
        }

        // map.remove 后如果这个 squadId 没人引用了，则自然“删除”
        data.setDirty();
    }

    private static int firstFreeSquadId(Map<UUID, Integer> map) {
        for (int i = 1; i <= BattlefieldServerConfig.get().teamSquadCap; i++) {
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