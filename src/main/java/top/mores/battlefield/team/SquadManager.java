package top.mores.battlefield.team;

import net.minecraft.server.level.ServerPlayer;
import top.mores.battlefield.config.BattlefieldServerConfig;
import top.mores.battlefield.game.BattlefieldGameManager;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 小队归属。
 * <p>
 * 按「对局 → 阵营 → 玩家 → 小队号」分桶，只存在内存里：
 * 原实现是一张全服共用的 SavedData 表，两局并行时 A 局攻方 1 队和 B 局攻方 1 队是同一个小队
 * （人数上限共享、面板互见、自动分队会把两局的人塞进同一队）。
 * 小队只在对局存续期间有意义，对局不会跨重启存活，因此也不需要落盘。
 */
public final class SquadManager {
    private static final Random RANDOM = new Random();

    /** 对局 ID → 阵营 → (玩家 → 小队号)。只在主线程访问。 */
    private static final Map<String, EnumMap<TeamId, Map<UUID, Integer>>> MATCH_SQUADS = new HashMap<>();

    private SquadManager() {
    }

    private static Map<UUID, Integer> mapFor(String matchId, TeamId team, boolean create) {
        if (matchId == null || team == TeamId.SPECTATOR) return create ? new HashMap<>() : Collections.emptyMap();
        EnumMap<TeamId, Map<UUID, Integer>> byTeam = create
                ? MATCH_SQUADS.computeIfAbsent(matchId, k -> new EnumMap<>(TeamId.class))
                : MATCH_SQUADS.get(matchId);
        if (byTeam == null) return Collections.emptyMap();
        return create ? byTeam.computeIfAbsent(team, k -> new HashMap<>()) : byTeam.getOrDefault(team, Collections.emptyMap());
    }

    private static String matchOf(UUID playerId) {
        return BattlefieldGameManager.getPlayerMatchId(playerId);
    }

    public static int getSquad(ServerPlayer p) {
        TeamId team = TeamManager.getTeam(p);
        if (team == TeamId.SPECTATOR) return 0;
        return mapFor(matchOf(p.getUUID()), team, false).getOrDefault(p.getUUID(), 0);
    }

    public static UUID buildSquadUuid(String matchId, TeamId team, int squadId) {
        if (team == TeamId.SPECTATOR || squadId <= 0) return null;
        if (matchId == null || matchId.isBlank()) return null;

        String key = matchId + ":" + team.name() + ":" + squadId;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    public static UUID getSquadUuid(ServerPlayer p) {
        TeamId team = TeamManager.getTeam(p);
        int squadId = getSquad(p);
        return buildSquadUuid(matchOf(p.getUUID()), team, squadId);
    }

    public static int getSquadScore(ServerPlayer p) {
        return SquadScoreManager.getScore(getSquadUuid(p));
    }

    private static void clearSquadScoreIfEmpty(Map<UUID, Integer> map, int oldSquadId, UUID oldSquadUuid) {
        if (oldSquadId <= 0 || oldSquadUuid == null) return;
        if (!squadExists(map, oldSquadId)) {
            SquadScoreManager.clearSquad(oldSquadUuid);
        }
    }

    /** UI 用：与 viewer 同局同阵营、指定小队的成员。 */
    public static List<UUID> getSquadMembers(ServerPlayer viewer, int squadId) {
        TeamId team = TeamManager.getTeam(viewer);
        if (team == TeamId.SPECTATOR || squadId <= 0) return Collections.emptyList();
        List<UUID> list = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : mapFor(matchOf(viewer.getUUID()), team, false).entrySet()) {
            if (entry.getValue() == squadId) list.add(entry.getKey());
        }
        return list;
    }

    public static boolean createSquad(ServerPlayer p) {
        TeamId team = TeamManager.getTeam(p);
        String matchId = matchOf(p.getUUID());
        if (team == TeamId.SPECTATOR || matchId == null) return false;

        Map<UUID, Integer> map = mapFor(matchId, team, true);
        int current = map.getOrDefault(p.getUUID(), 0);
        UUID oldSquadUuid = buildSquadUuid(matchId, team, current);

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

        map.put(p.getUUID(), newSquadId);
        clearSquadScoreIfEmpty(map, current, oldSquadUuid);
        return true;
    }

    public static boolean joinSquad(ServerPlayer p, int squadId) {
        TeamId team = TeamManager.getTeam(p);
        String matchId = matchOf(p.getUUID());
        if (team == TeamId.SPECTATOR || matchId == null) return false;
        if (squadId < 1 || squadId > BattlefieldServerConfig.get().teamSquadCap) return false;

        Map<UUID, Integer> map = mapFor(matchId, team, true);
        int current = map.getOrDefault(p.getUUID(), 0);
        if (current == squadId) {
            return true;
        }

        if (!squadExists(map, squadId)) return false;
        if (countMembers(map, squadId) >= BattlefieldServerConfig.get().squadCap) return false;

        UUID oldSquadUuid = buildSquadUuid(matchId, team, current);
        map.put(p.getUUID(), squadId);
        clearSquadScoreIfEmpty(map, current, oldSquadUuid);
        return true;
    }

    public static void leaveSquad(ServerPlayer p) {
        TeamId team = TeamManager.getTeam(p);
        if (team == TeamId.SPECTATOR) return;
        removeFromTeamSquad(p.getUUID(), matchOf(p.getUUID()), team);
    }

    public static void onTeamChanged(ServerPlayer p, TeamId oldTeam, TeamId newTeam) {
        String matchId = matchOf(p.getUUID());
        if (oldTeam != TeamId.SPECTATOR) {
            removeFromTeamSquad(p.getUUID(), matchId, oldTeam);
        }
        if (newTeam != TeamId.SPECTATOR) {
            autoAssignSquad(p);
        }
    }

    public static void autoAssignSquad(ServerPlayer p) {
        TeamId team = TeamManager.getTeam(p);
        String matchId = matchOf(p.getUUID());
        if (team == TeamId.SPECTATOR || matchId == null) return;

        Map<UUID, Integer> map = mapFor(matchId, team, true);
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
    }

    /** 对局结束：丢掉该局全部小队与小队分。 */
    public static void clearMatch(String matchId) {
        if (matchId == null) return;
        MATCH_SQUADS.remove(matchId);
        int cap = BattlefieldServerConfig.get().teamSquadCap;
        for (TeamId team : new TeamId[]{TeamId.ATTACKERS, TeamId.DEFENDERS}) {
            for (int i = 1; i <= cap; i++) {
                SquadScoreManager.clearSquad(buildSquadUuid(matchId, team, i));
            }
        }
    }

    public static void clearAll() {
        MATCH_SQUADS.clear();
        SquadScoreManager.clearAll();
    }

    /** UI 用：viewer 所在局、所在阵营已有的小队 ID */
    public static List<Integer> getExistingSquadIds(ServerPlayer viewer) {
        TeamId team = TeamManager.getTeam(viewer);
        if (team == TeamId.SPECTATOR) return Collections.emptyList();

        Map<UUID, Integer> map = mapFor(matchOf(viewer.getUUID()), team, false);
        List<Integer> result = new ArrayList<>();
        for (int i = 1; i <= BattlefieldServerConfig.get().teamSquadCap; i++) {
            if (squadExists(map, i)) {
                result.add(i);
            }
        }
        return result;
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

    private static void removeFromTeamSquad(UUID uuid, String matchId, TeamId team) {
        if (matchId == null) return;
        Map<UUID, Integer> map = mapFor(matchId, team, false);
        if (map.isEmpty()) return;

        Integer removed = map.remove(uuid);
        if (removed == null || removed <= 0) {
            return;
        }
        clearSquadScoreIfEmpty(map, removed, buildSquadUuid(matchId, team, removed));
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
