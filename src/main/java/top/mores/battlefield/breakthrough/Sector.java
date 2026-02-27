package top.mores.battlefield.breakthrough;

import java.util.List;

public class Sector {
    public final String id; // "S1"
    public final List<CapturePoint> points;

    public final List<AreaCircle> attackerAreas;
    public final List<AreaCircle> defenderAreas;

    public record AreaCircle(double x, double z, double r) {}

    public Sector(String id,
                  List<CapturePoint> points,
                  List<AreaCircle> attackerAreas,
                  List<AreaCircle> defenderAreas) {
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
