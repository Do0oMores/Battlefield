package top.mores.battlefield.breakthrough;

import top.mores.battlefield.team.TeamId;

public class CapturePoint {
    public final String id;
    public final double x, y, z;
    public final double radius;
    public TeamId owner = TeamId.DEFENDERS;

    // [-100..100]  默认守方控制
    private int progress = -100;

    // 防止重复加分（可选）
    public boolean awardedCaptureScore = false;

    // 仅用于 debug
    public transient int lastAttackersIn = 0;
    public transient int lastDefendersIn = 0;

    public CapturePoint(String id, double x, double y, double z, double radius) {
        this.id = id;
        this.x = x; this.y = y; this.z = z;
        this.radius = radius;
    }

    public int getProgress() { return progress; }

    public void setProgress(int v) {
        if (v > 100) v = 100;
        if (v < -100) v = -100;
        this.progress = v;
    }

    public boolean isCapturedByAttackers() { return progress >= 100; }
}
