package top.mores.battlefield.game;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import top.mores.battlefield.server.entity.RespawnBeaconEntity;
import top.mores.battlefield.team.TeamManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class BeaconRespawnLogic {
    private BeaconRespawnLogic() {
    }

    public static List<BeaconSpawnCandidate> collect(ServerPlayer deadPlayer) {
        List<BeaconSpawnCandidate> result = new ArrayList<>();

        for (RespawnBeaconEntity beacon : RespawnBeaconManager.getSquadBeacons(deadPlayer)) {
            String label = "信标";
            if (beacon.getOwnerUuid() != null) {
                ServerPlayer owner = deadPlayer.server.getPlayerList().getPlayer(beacon.getOwnerUuid());
                if (owner != null) {
                    label = "信标 - " + owner.getGameProfile().getName();
                }
            }

            result.add(new BeaconSpawnCandidate(
                    beacon.getUUID(),
                    beacon.getOwnerUuid(),
                    beacon.position(),
                    beacon.getYRot(),
                    label
            ));
        }
        return result;
    }

    public static boolean tryRespawn(ServerPlayer player, UUID beaconUuid) {
        RespawnBeaconEntity beacon = RespawnBeaconManager.findBeacon(player.server, beaconUuid);
        if (beacon == null || !beacon.isAlive()) return false;

        UUID squadId = SquadManager.getSquadUuid(player); // TODO 替换
        String matchId = GameSessionManager.getMatchId(player); // TODO 替换

        if (squadId == null || matchId == null) return false;
        if (!squadId.equals(beacon.getSquadUuid())) return false;
        if (!matchId.equals(beacon.getMatchId())) return false;
        if (TeamManager.getTeam(player) != beacon.getTeamId()) return false;

        if (!(beacon.level() instanceof ServerLevel level)) return false;

        Optional<Vec3> standPos = RespawnBeaconPlacement.findStandablePosNearBeacon(level, beacon.position());
        if (standPos.isEmpty()) return false;

        Vec3 p = standPos.get();

        // 这里只做可落地检查，不做敌人/交火安全检查
        player.teleportTo(level, p.x, p.y, p.z, beacon.getYRot(), 0.0f);

        // 放置者本人使用自己的信标复活一次后，信标消失
        if (player.getUUID().equals(beacon.getOwnerUuid())) {
            RespawnBeaconManager.unregister(beacon);
            beacon.discard();
        }

        return true;
    }
}
