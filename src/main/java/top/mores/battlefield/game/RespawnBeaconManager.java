package top.mores.battlefield.game;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import top.mores.battlefield.ModEntities;
import top.mores.battlefield.server.entity.RespawnBeaconEntity;
import top.mores.battlefield.team.TeamId;
import top.mores.battlefield.team.TeamManager;

import java.util.*;

public final class RespawnBeaconManager {
    private static final Map<UUID, UUID> OWNER_TO_BEACON = new HashMap<>();

    private RespawnBeaconManager() {
    }

    public static RespawnBeaconEntity placeOrReplace(ServerPlayer owner, Vec3 pos, float yaw) {
        String matchId = GameSession.getMatchId(owner); // TODO 替换为你的战局管理器
        UUID squadId = SquadManager.getSquadUuid(owner);       // TODO 替换为你的小队方法
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
        return beacon;
    }

    public static void unregister(RespawnBeaconEntity beacon) {
        UUID owner = beacon.getOwnerUuid();
        if (owner == null) return;

        UUID current = OWNER_TO_BEACON.get(owner);
        if (beacon.getUUID().equals(current)) {
            OWNER_TO_BEACON.remove(owner);
        }
    }

    public static void removeOwnerBeacon(MinecraftServer server, UUID ownerUuid) {
        UUID beaconUuid = OWNER_TO_BEACON.remove(ownerUuid);
        if (beaconUuid == null) return;

        RespawnBeaconEntity beacon = findBeacon(server, beaconUuid);
        if (beacon != null) {
            beacon.discard();
        }
    }

    public static RespawnBeaconEntity findBeacon(MinecraftServer server, UUID beaconUuid) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(beaconUuid);
            if (entity instanceof RespawnBeaconEntity beacon) {
                return beacon;
            }
        }
        return null;
    }

    public static List<RespawnBeaconEntity> getSquadBeacons(ServerPlayer viewer) {
        UUID squadId = SquadManager.getSquadUuid(viewer);  // TODO 替换为你的小队方法
        String matchId = GameSessionManager.getMatchId(viewer);
        TeamId team = TeamManager.getTeam(viewer);

        if (squadId == null || matchId == null) {
            return Collections.emptyList();
        }

        List<RespawnBeaconEntity> result = new ArrayList<>();

        for (ServerLevel level : viewer.server.getAllLevels()) {
            List<RespawnBeaconEntity> list = level.getEntitiesOfClass(
                    RespawnBeaconEntity.class,
                    new AABB(-30000000, -64, -30000000, 30000000, 320, 30000000),
                    b -> b.isAlive()
                            && squadId.equals(b.getSquadUuid())
                            && matchId.equals(b.getMatchId())
                            && b.getTeamId() == team
            );
            result.addAll(list);
        }

        return result;
    }

    public static void clearMatch(MinecraftServer server, String matchId) {
        for (ServerLevel level : server.getAllLevels()) {
            List<RespawnBeaconEntity> list = level.getEntitiesOfClass(
                    RespawnBeaconEntity.class,
                    new AABB(-30000000, -64, -30000000, 30000000, 320, 30000000),
                    b -> matchId.equals(b.getMatchId())
            );
            for (RespawnBeaconEntity beacon : list) {
                unregister(beacon);
                beacon.discard();
            }
        }
    }
}
