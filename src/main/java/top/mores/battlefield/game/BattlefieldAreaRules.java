package top.mores.battlefield.game;

import top.mores.battlefield.breakthrough.CapturePoint;
import top.mores.battlefield.client.ClientGameState;
import top.mores.battlefield.net.S2CGameStatePacket;

import java.util.List;

public final class BattlefieldAreaRules {
    private BattlefieldAreaRules() {}

    public static final double AREA_RADIUS_SCALE = 1.6;
    public static final int OUTSIDE_AREA_KILL_TICKS = 200;

    /**
     * 只显示本方可活动区域：
     * - 攻方(0)：progress >= 0
     * - 守方(1)：progress <= 0
     *
     * 如果你希望 progress==0 两边都显示，把 >= / <= 保留；
     * 如果你希望 progress==0 两边都不显示，把它改成 > / <。
     */
    public static boolean isPointInMovableRange(byte myTeam, int pointProgress) {
        if (myTeam == 0) return pointProgress >= 0;
        if (myTeam == 1) return pointProgress <= 0;
        return false;
    }

    public static boolean isInsideMovableArea(byte myTeam, double x, double z) {
        List<top.mores.battlefield.net.S2CSectorAreaPacket.AreaCircle> circles =
                (myTeam == 0) ? ClientGameState.attackerAreas :
                        (myTeam == 1) ? ClientGameState.defenderAreas :
                                java.util.Collections.emptyList();

        for (var c : circles) {
            double dx = x - c.x;
            double dz = z - c.z;
            double r = c.r;
            if (dx * dx + dz * dz <= r * r) return true;
        }
        return false;
    }

    public static boolean isInsideMovableAreaServer(byte myTeam, double x, double z, List<CapturePoint> points) {
        for (CapturePoint point : points) {
            if (!isPointInMovableRange(myTeam, point.getProgress())) continue;
            double dx = x - point.x;
            double dz = z - point.z;
            double radius = point.radius * AREA_RADIUS_SCALE;
            if (dx * dx + dz * dz <= radius * radius) return true;
        }
        return false;
    }
}
