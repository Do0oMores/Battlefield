package top.mores.battlefield.breakthrough;

import java.util.List;

public class Sector {
    public final String id; // "S1"
    public final List<CapturePoint> points;

    public Sector(String id, List<CapturePoint> points) {
        this.id=id;
        this.points=points;
    }

    public boolean isClearedByAttackers() {
        for (CapturePoint p : points) {
            if (!p.isCapturedByAttackers()) return false;
        }
        return true;
    }
}
