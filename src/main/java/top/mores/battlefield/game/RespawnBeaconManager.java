package top.mores.battlefield.game;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import top.mores.battlefield.ModEntities;
import top.mores.battlefield.server.entity.RespawnBeaconEntity;
import top.mores.battlefield.team.SquadManager;
import top.mores.battlefield.team.TeamId;
import top.mores.battlefield.team.TeamManager;

import java.util.*;

/**
 * 复活信标登记。
 * <p>
 * 按对局建索引（对局 ID → 信标 UUID），查找时只在该局的战场维度里按 UUID 取实体。
 * 原实现每次复活都对<b>所有维度</b>做 ±3000 万的 AABB 扫描，多局并行时成本还要再乘局数；
 * 而且 matchId 用的是 arena 名，上一局留在世界存档里的信标在下一局仍然有效。
 */
public final class RespawnBeaconManager {
    private static final Map<UUID, UUID> OWNER_TO_BEACON = new HashMap<>();
    private static final Map<String, Set<UUID>> MATCH_BEACONS = new HashMap<>();

    private RespawnBeaconManager() {
    }

    public static RespawnBeaconEntity placeOrReplace(ServerPlayer owner, Vec3 pos, float yaw) {
        String matchId = BattlefieldGameManager.getPlayerMatchId(owner.getUUID());
        UUID squadId = SquadManager.getSquadUuid(owner);
        if (matchId == null || squadId == null) return null;

        removeOwnerBeacon(owner.server, owner.getUUID());

        RespawnBeaconEntity beacon = ModEntities.RESPAWN_BEACON.get().create(owner.serverLevel());
        if (beacon == null) return null;

        beacon.moveTo(pos.x, pos.y, pos.z, yaw, 0.0f);
        beacon.setOwnerUuid(owner.getUUID());
        beacon.setSquadUuid(squadId);
        beacon.setMatchId(matchId);
        beacon.setTeamId(TeamManager.getTeam(owner));
        beacon.setHealthValue(40.0f);

        owner.serverLevel().addFreshEntity(beacon);
        OWNER_TO_BEACON.put(owner.getUUID(), beacon.getUUID());
        MATCH_BEACONS.computeIfAbsent(matchId, k -> new HashSet<>()).add(beacon.getUUID());
        return beacon;
    }

    public static void unregister(RespawnBeaconEntity beacon) {
        UUID owner = beacon.getOwnerUuid();
        if (owner != null && beacon.getUUID().equals(OWNER_TO_BEACON.get(owner))) {
            OWNER_TO_BEACON.remove(owner);
        }
        Set<UUID> set = MATCH_BEACONS.get(beacon.getMatchId());
        if (set != null) set.remove(beacon.getUUID());
    }

    public static void removeOwnerBeacon(MinecraftServer server, UUID ownerUuid) {
        UUID beaconUuid = OWNER_TO_BEACON.remove(ownerUuid);
        if (beaconUuid == null) return;

        RespawnBeaconEntity beacon = findBeacon(server, beaconUuid);
        if (beacon != null) {
            unregister(beacon);
            beacon.discard();
        }
    }

    public static RespawnBeaconEntity findBeacon(MinecraftServer server, UUID beaconUuid) {
        ServerLevel level = BattlefieldGameManager.matchLevelOfBeacon(beaconUuid);
        if (level != null) {
            Entity entity = level.getEntity(beaconUuid);
            return entity instanceof RespawnBeaconEntity beacon ? beacon : null;
        }
        return null;
    }

    public static List<RespawnBeaconEntity> getSquadBeacons(ServerPlayer viewer) {
        UUID squadId = SquadManager.getSquadUuid(viewer);
        String matchId = BattlefieldGameManager.getPlayerMatchId(viewer.getUUID());
        TeamId team = TeamManager.getTeam(viewer);
        if (squadId == null || matchId == null) {
            return Collections.emptyList();
        }

        Set<UUID> ids = MATCH_BEACONS.get(matchId);
        ServerLevel level = BattlefieldGameManager.matchLevel(matchId);
        if (ids == null || ids.isEmpty() || level == null) return Collections.emptyList();

        List<RespawnBeaconEntity> result = new ArrayList<>();
        for (UUID id : ids) {
            if (level.getEntity(id) instanceof RespawnBeaconEntity b
                    && b.isAlive()
                    && squadId.equals(b.getSquadUuid())
                    && matchId.equals(b.getMatchId())
                    && b.getTeamId() == team) {
                result.add(b);
            }
        }
        return result;
    }

    /** 判断某个信标 UUID 属于哪一局（供 {@link BattlefieldGameManager#matchLevelOfBeacon} 使用）。 */
    static String matchOfBeacon(UUID beaconUuid) {
        for (Map.Entry<String, Set<UUID>> e : MATCH_BEACONS.entrySet()) {
            if (e.getValue().contains(beaconUuid)) return e.getKey();
        }
        return null;
    }

    /** 对局结束：删掉该局所有信标实体与登记。 */
    public static void clearMatch(String matchId, ServerLevel level, Collection<UUID> participants) {
        Set<UUID> ids = MATCH_BEACONS.remove(matchId);
        if (ids != null && level != null) {
            for (UUID id : ids) {
                Entity e = level.getEntity(id);
                if (e != null) e.discard();
            }
        }
        participants.forEach(OWNER_TO_BEACON::remove);
    }

    public static void clearAll() {
        OWNER_TO_BEACON.clear();
        MATCH_BEACONS.clear();
    }
}
