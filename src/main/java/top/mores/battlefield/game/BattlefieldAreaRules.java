package top.mores.battlefield.game;

import top.mores.battlefield.breakthrough.CapturePoint;
import top.mores.battlefield.breakthrough.Sector;
import top.mores.battlefield.client.ClientGameState;

import java.util.List;

public final class BattlefieldAreaRules {
    private BattlefieldAreaRules() {
    }

    public static final double AREA_RADIUS_SCALE = 1.6;
    public static final int OUTSIDE_AREA_KILL_TICKS = 200;

    /**
     * 只显示本方可活动区域：
     * - 攻方(0)：progress >= 0
     * - 守方(1)：progress <= 0
     * <p>
     * 如果你希望 progress==0 两边都显示，把 >= / <= 保留；
     * 如果你希望 progress==0 两边都不显示，把它改成 > / <。
     */
    public static boolean isPointInMovableRange(byte myTeam, int pointProgress) {
        if (myTeam == 0) return pointProgress >= 0;
        if (myTeam == 1) return pointProgress <= 0;
        return false;
    }

    public static boolean isInsideMovableArea(byte myTeam, double x, double z) {
        List<top.mores.battlefield.net.S2CSectorAreaPacket.AreaRect> rects =
                (myTeam == 0) ? ClientGameState.attackerAreas :
                        (myTeam == 1) ? ClientGameState.defenderAreas :
                                java.util.Collections.emptyList();

        for (var rect : rects) {
            if (isInsideRect(x, z, rect.x1, rect.z1, rect.x2, rect.z2)) return true;
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

    public static boolean isInsideAreas2D(double x, double z, List<Sector.AreaRect> areas) {
        if (areas == null || areas.isEmpty()) return false;
        for (Sector.AreaRect rect : areas) {
            if (isInsideRect(x, z, rect.x1(), rect.z1(), rect.x2(), rect.z2())) return true;
        }
        return false;
    }

    private static boolean isInsideRect(double x, double z, double x1, double z1, double x2, double z2) {
        double minX = Math.min(x1, x2);
        double maxX = Math.max(x1, x2);
        double minZ = Math.min(z1, z2);
        double maxZ = Math.max(z1, z2);
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }
}
