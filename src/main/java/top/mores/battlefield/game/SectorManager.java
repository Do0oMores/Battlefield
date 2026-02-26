package top.mores.battlefield.game;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import top.mores.battlefield.breakthrough.CapturePoint;
import top.mores.battlefield.breakthrough.Sector;
import top.mores.battlefield.team.TeamId;
import top.mores.battlefield.team.TeamManager;

import java.util.List;

public class SectorManager {
    public static final int CAPTURE_INTERVAL_TICKS = 10; // 0.5s

    public static final int BASE_STEP = 4;   // 每 0.5s 最少推进 4
    public static final int BONUS_STEP = 2;  // 人数差每多1人，额外+2

    public void tick(GameSession session) {
        if (!session.running) return;

        Sector sector = session.currentSector();
        if (sector == null) {
            session.running = false;
            broadcast(session.level, "攻方已推进至最后战线，测试局结束！");
            return;
        }

        for (CapturePoint p : sector.points) {
            updatePoint(session, p);
        }

        if (sector.isClearedByAttackers()) {
            session.currentSectorIndex++;
            broadcast(session.level, "攻方推进至下一战线！当前Index=" + session.currentSectorIndex);
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

            // TODO：接你后面的倒地系统时，把 DOWNED 排除
            // if (DownedManager.isDowned(sp)) continue;

            Vec3 pos = sp.position();
            double dx = pos.x - cx, dy = pos.y - cy, dz = pos.z - cz;
            if (dx*dx + dy*dy + dz*dz > r2) continue;

            if (team == TeamId.ATTACKERS) attackers++;
            else if (team == TeamId.DEFENDERS) defenders++;
        }

        // debug 记录
        point.lastAttackersIn = attackers;
        point.lastDefendersIn = defenders;

        int delta = attackers - defenders;
        int step = 0;

        if (attackers > defenders) {
            int diff = attackers - defenders;
            step = BASE_STEP + BONUS_STEP * (diff - 1); // 朝攻方方向推进（progress 增大）
        } else if (defenders > attackers) {
            int diff = defenders - attackers;
            step = -(BASE_STEP + BONUS_STEP * (diff - 1)); // 朝守方方向推进（progress 减小）
        }

        if (step == 0) return;

        int old = point.getProgress();
        int now = clamp(old + step, -100, 100);
        point.setProgress(now);

        // 首次攻方占满提示（可删）
        if (!point.awardedCaptureScore && now >= 100) {
            point.awardedCaptureScore = true;
            broadcast(level, "攻方占领据点 " + point.id + "！");
        }
    }

    private static void broadcast(ServerLevel level, String msg) {
        level.getServer().getPlayerList().broadcastSystemMessage(Component.literal("[BT] " + msg), false);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
