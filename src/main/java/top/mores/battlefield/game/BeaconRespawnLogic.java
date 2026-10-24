package top.mores.battlefield.game;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import top.mores.battlefield.server.entity.RespawnBeaconEntity;
import top.mores.battlefield.team.SquadManager;
import top.mores.battlefield.team.TeamId;
import top.mores.battlefield.team.TeamManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 复活信标的可用性判定与落地。
 * <p>
 * 这里只处理「能不能用」和「落到哪」，不改动信标本身的放置/血量逻辑。
 */
public class BeaconRespawnLogic {
    private BeaconRespawnLogic() {
    }

    /**
     * 收集当前玩家可以使用的信标。
     * <p>
     * 过滤条件：同小队、同一场对局、同阵营、实体存活、所在维度是服务端世界。
     * 注意不要在这里做「能不能站人」的检查 —— 那一步留给 {@link #tryRespawn}，
     * 因为站人位置会随方块变化，属于使用时刻的状态。
     */
    public static List<BeaconSpawnCandidate> collect(ServerPlayer deadPlayer) {
        List<BeaconSpawnCandidate> result = new ArrayList<>();

        if (deadPlayer == null || deadPlayer.server == null) return result;

        for (RespawnBeaconEntity beacon : RespawnBeaconManager.getSquadBeacons(deadPlayer)) {
            // 信标可能位于未加载的区块：此时实体引用无效，直接跳过
            if (!(beacon.level() instanceof ServerLevel level)) continue;

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
                    label,
                    level
            ));
        }
        return result;
    }

    /**
     * 按「离死亡点最近」的顺序自动挑一个信标复活。
     * 逐个尝试，避免某个信标脚下被方块堵死就整局都用不了。
     *
     * @return 是否成功在信标处复活；false 表示没有可用信标，调用方应回退到主出生点
     */
    public static boolean tryAutoRespawn(ServerPlayer player) {
        List<BeaconSpawnCandidate> candidates = collect(player);
        if (candidates.isEmpty()) return false;

        Vec3 from = player.position();
        candidates.sort(Comparator.comparingDouble(c -> c.pos().distanceToSqr(from)));

        for (BeaconSpawnCandidate candidate : candidates) {
            if (tryRespawn(player, candidate.beaconUuid())) {
                return true;
            }
        }
        return false;
    }

    public static boolean tryRespawn(ServerPlayer player, UUID beaconUuid) {
        RespawnBeaconEntity beacon = RespawnBeaconManager.findBeacon(player.server, beaconUuid);
        if (beacon == null || !beacon.isAlive()) return false;

        UUID squadId = SquadManager.getSquadUuid(player);
        String matchId = BattlefieldGameManager.getPlayerAreaName(player.getUUID());

        if (squadId == null || matchId == null) return false;
        if (!squadId.equals(beacon.getSquadUuid())) return false;
        if (!matchId.equals(beacon.getMatchId())) return false;
        if (TeamManager.getTeam(player) != beacon.getTeamId()) return false;
        if (beacon.getTeamId() == TeamId.SPECTATOR) return false;

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
