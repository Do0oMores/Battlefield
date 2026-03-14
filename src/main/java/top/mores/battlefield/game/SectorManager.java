package top.mores.battlefield.game;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import top.mores.battlefield.breakthrough.CapturePoint;
import top.mores.battlefield.breakthrough.Sector;
import top.mores.battlefield.net.BattlefieldNet;
import top.mores.battlefield.team.TeamId;
import top.mores.battlefield.team.TeamManager;

import java.util.List;

import top.mores.battlefield.config.BattlefieldServerConfig;

public class SectorManager {
    public void tick(GameSession session) {
        if (!session.running) return;

        Sector sector = session.currentSector();
        if (sector == null) {
            return;
        }

        for (CapturePoint p : sector.points) {
            updatePoint(session, p);
        }

        if (sector.isClearedByAttackers()) {
            boolean hasNextSector = session.currentSectorIndex + 1 < session.sectors.size();
            if (hasNextSector && session.addMilitary > 0) {
                session.attackerTickets += session.addMilitary;
                broadcast(session.level, "攻方占领防区，兵力补充 +" + session.addMilitary + "！");
            }

            session.currentSectorIndex++;
            broadcast(session.level, "攻方推进至下一战线！当前进度=" + session.currentSectorIndex);
            Sector next = session.currentSector();
            if (next != null) {
                BattlefieldNet.sendSectorAreas(session.level, session.currentSectorIndex, next.attackerAreas, next.defenderAreas);
            }
        }
    }

    private void updatePoint(GameSession session, CapturePoint point) {
        ServerLevel level = session.level;
        boolean shouldAwardScore = level.getGameTime() % BattlefieldServerConfig.get().scoreAwardIntervalTicks == 0;

        int attackers = 0;
        int defenders = 0;

        final double r2 = point.radius * point.radius;
        final double cx = point.x, cy = point.y, cz = point.z;

        List<ServerPlayer> players = level.getServer().getPlayerList().getPlayers();
        for (ServerPlayer sp : players) {
            if (sp.serverLevel() != level) continue;

            TeamId team = TeamManager.getTeam(sp);
            if (team == TeamId.SPECTATOR) continue;

            // TODO：倒地不计入占点
            // if (DownedManager.isDowned(sp)) continue;

            Vec3 pos = sp.position();
            double dx = pos.x - cx, dz = pos.z - cz;
            if (dx * dx + dz * dz > r2) continue;

            if (shouldAwardScore) {
                if (team == point.owner) {
                    ScoreManager.addDefendScore(sp, 5);
                } else {
                    ScoreManager.addCaptureScore(sp, 5);
                }
            }

            if (team == TeamId.ATTACKERS) attackers++;
            else if (team == TeamId.DEFENDERS) defenders++;
        }

        // debug 记录（用于 HUD 的 4:1）
        point.lastAttackersIn = attackers;
        point.lastDefendersIn = defenders;

        int old = point.getProgress();

        // ① 点内无人：向“当前控制方 owner”回满（到 ±100）
        if (attackers == 0 && defenders == 0) {
            if (point.owner == TeamId.ATTACKERS) {
                if (old < 100) point.setProgress(Math.min(100, old + BattlefieldServerConfig.get().captureEmptyDecayStep));
            } else if (point.owner == TeamId.DEFENDERS) {
                if (old > -100) point.setProgress(Math.max(-100, old - BattlefieldServerConfig.get().captureEmptyDecayStep));
            }
            return;
        }

        // ② 人数相同：僵持（默认不动；若你想“僵持也慢慢回 owner”，打开 DECAY_ON_TIE）
        if (attackers == defenders) {
            if (BattlefieldServerConfig.get().captureDecayOnTie) {
                if (point.owner == TeamId.ATTACKERS) {
                    if (old < 100) point.setProgress(Math.min(100, old + 1));
                } else if (point.owner == TeamId.DEFENDERS) {
                    if (old > -100) point.setProgress(Math.max(-100, old - 1));
                }
            }
            return;
        }

        // ③ 人数严格占优的一方才推进（劣势只能减速）
        int step;
        if (attackers > defenders) {
            int diff = attackers - defenders;
            step = BattlefieldServerConfig.get().captureBaseStep + BattlefieldServerConfig.get().captureBonusStep * (diff - 1);        // progress 增大：偏向 ATTACKERS
        } else {
            int diff = defenders - attackers;
            step = -(BattlefieldServerConfig.get().captureBaseStep + BattlefieldServerConfig.get().captureBonusStep * (diff - 1));     // progress 减小：偏向 DEFENDERS
        }

        int now = clamp(old + step, -100, 100);
        point.setProgress(now);

        // ④ 更新 owner：只有“满控”才改变当前控制方
        if (now >= 100) {
            point.owner = TeamId.ATTACKERS;
            if (!point.awardedCaptureScore) {
                point.awardedCaptureScore = true;
                broadcast(level, "攻方占领据点 " + point.id + "！");
            }
        } else if (now <= -100) {
            point.owner = TeamId.DEFENDERS;
            // 若你也想给守方满控提示/加分，可以在这里加
        }
    }

    private static void broadcast(ServerLevel level, String msg) {
        level.getServer().getPlayerList().broadcastSystemMessage(Component.literal("[Battlefield] " + msg), false);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
