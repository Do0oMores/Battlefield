package top.mores.battlefield.breakthrough;

import java.util.List;

public class Sector {
    public final String id; // "S1"
    public final List<CapturePoint> points;

    public final List<AreaRect> attackerAreas;
    public final List<AreaRect> defenderAreas;

    public record AreaRect(double x1, double z1, double x2, double z2) {}

    public Sector(String id,
                  List<CapturePoint> points,
                  List<AreaRect> attackerAreas,
                  List<AreaRect> defenderAreas) {
        this.id = id;
        this.points = points;
        this.attackerAreas = attackerAreas;
        this.defenderAreas = defenderAreas;
    }

    public boolean isClearedByAttackers() {
        for (CapturePoint p : points) {
            if (!p.isCapturedByAttackers()) return false;
        }
        return true;
    }
}
