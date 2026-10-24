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
    /**
     * @param players 本局在线参与者。只统计他们：原实现取的是全服在线玩家，
     *                同维度的其他对局、带着残留阵营的非参与者都会被算进据点人数。
     */
    public void tick(GameSession session, List<ServerPlayer> players) {
        if (!session.running) return;

        Sector sector = session.currentSector();
        if (sector == null) {
            return;
        }

        // 得分发放是否到期，只判断一次：同一 tick 内所有据点共用同一结果
        boolean scoreAwardDue = session.isScoreAwardDue();

        for (CapturePoint p : sector.points) {
            updatePoint(session, p, players, scoreAwardDue);
        }


        if (sector.isClearedByAttackers()) {
            boolean hasNextSector = session.currentSectorIndex + 1 < session.sectors.size();
            if (hasNextSector && session.addMilitary > 0) {
                session.attackerTickets += session.addMilitary;
                broadcast(players, "攻方占领防区，兵力补充 +" + session.addMilitary + "！");
            }

            session.currentSectorIndex++;
            broadcast(players, "攻方推进至下一战线！当前进度=" + session.currentSectorIndex);
            Sector next = session.currentSector();
            if (next != null) {
                BattlefieldNet.sendSectorAreas(players, session.currentSectorIndex, next.attackerAreas, next.defenderAreas);
            }
        }
    }

    private void updatePoint(GameSession session, CapturePoint point, List<ServerPlayer> players, boolean awardScoreNow) {
        ServerLevel level = session.level;

        int attackers = 0;
        int defenders = 0;

        final double r2 = point.radius * point.radius;
        final double cx = point.x, cy = point.y, cz = point.z;

        for (ServerPlayer sp : players) {
            if (sp.serverLevel() != level) continue;

            TeamId team = TeamManager.getTeam(sp);
            if (team == TeamId.SPECTATOR) continue;

            // TODO：倒地不计入占点
            // if (DownedManager.isDowned(sp)) continue;

            Vec3 pos = sp.position();
            double dx = pos.x - cx, dz = pos.z - cz;
            if (dx * dx + dz * dz > r2) continue;

            if (awardScoreNow) {
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
                broadcast(players, "攻方占领据点 " + point.id + "！");
            }
        } else if (now <= -100) {
            point.owner = TeamId.DEFENDERS;
            // 若你也想给守方满控提示/加分，可以在这里加
        }
    }

    /** 只发本局参与者。原实现是全服聊天广播。 */
    private static void broadcast(List<ServerPlayer> players, String msg) {
        Component text = Component.literal("[Battlefield] " + msg);
        for (ServerPlayer sp : players) {
            sp.sendSystemMessage(text);
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
