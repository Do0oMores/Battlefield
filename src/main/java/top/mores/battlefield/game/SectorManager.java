package top.mores.battlefield.game;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import top.mores.battlefield.breakthrough.CapturePoint;
import top.mores.battlefield.breakthrough.Sector;
import top.mores.battlefield.net.BattlefieldNet;
import top.mores.battlefield.team.SquadManager;
import top.mores.battlefield.team.TeamId;
import top.mores.battlefield.team.TeamManager;

import java.util.List;

public class SectorManager {
    public static final int CAPTURE_INTERVAL_TICKS = 10; // 0.5s

    public static final int BASE_STEP = 4;   // 每 0.5s 最少推进 4
    public static final int BONUS_STEP = 2;
    public static final int EMPTY_DECAY_STEP = 2;
    public static final boolean DECAY_ON_TIE = false;

    public void tick(GameSession session) {
        if (!session.running) return;

        Sector sector = session.currentSector();
        if (sector == null) {
            session.running = false;
            SquadManager.clearAll(session.level);
            broadcast(session.level, "攻方已推进至最后战线，测试局结束！");
            return;
        }

        for (CapturePoint p : sector.points) {
            updatePoint(session, p);
        }

        if (sector.isClearedByAttackers()) {
            session.currentSectorIndex++;
            broadcast(session.level, "攻方推进至下一战线！当前Index=" + session.currentSectorIndex);
            Sector next = session.currentSector();
            if (next != null) {
                BattlefieldNet.sendSectorAreas(session.level, session.currentSectorIndex, next.attackerAreas, next.defenderAreas);
            }
        }
    }

    private void updatePoint(GameSession session, CapturePoint point) {
        ServerLevel level = session.level;

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

            if (team == point.owner) {
                ScoreManager.addDefendScore(sp, 5);
            } else {
                ScoreManager.addCaptureScore(sp, 5);
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
                if (old < 100) point.setProgress(Math.min(100, old + EMPTY_DECAY_STEP));
            } else if (point.owner == TeamId.DEFENDERS) {
                if (old > -100) point.setProgress(Math.max(-100, old - EMPTY_DECAY_STEP));
            }
            return;
        }

        // ② 人数相同：僵持（默认不动；若你想“僵持也慢慢回 owner”，打开 DECAY_ON_TIE）
        if (attackers == defenders) {
            if (DECAY_ON_TIE) {
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
            step = BASE_STEP + BONUS_STEP * (diff - 1);        // progress 增大：偏向 ATTACKERS
        } else {
            int diff = defenders - attackers;
            step = -(BASE_STEP + BONUS_STEP * (diff - 1));     // progress 减小：偏向 DEFENDERS
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
        level.getServer().getPlayerList().broadcastSystemMessage(Component.literal("[BT] " + msg), false);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
