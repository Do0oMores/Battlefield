package top.mores.battlefield.game;

import top.mores.battlefield.breakthrough.CapturePoint;
import top.mores.battlefield.net.S2CGameStatePacket;

import java.util.List;

public final class BattlefieldAreaRules {
    private BattlefieldAreaRules() {
    }

    public static final double AREA_RADIUS_SCALE = 1.6;
    public static final int OUTSIDE_AREA_KILL_TICKS = 200;

    public static boolean isPointInMovableRange(byte myTeam, int pointProgress) {
        if (myTeam == 0) return true;
        return pointProgress < 100;
    }

    public static boolean isInsideMovableArea(byte myTeam, double x, double z, List<S2CGameStatePacket.PointInfo> points) {
        for (S2CGameStatePacket.PointInfo point : points) {
            if (!isPointInMovableRange(myTeam, point.progress)) continue;
            double dx = x - point.x;
            double dz = z - point.z;
            double radius = point.radius * AREA_RADIUS_SCALE;
            if (dx * dx + dz * dz <= radius * radius) return true;
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
