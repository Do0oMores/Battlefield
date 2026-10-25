package top.mores.battlefield.game.combat;

/**
 * 一名玩家当前生效的回血档位。
 * <p>
 * 刻意做成纯数据 record、不引用任何 Minecraft 类型：HP/秒 换算与延迟叠加是整套系统里最容易被
 * 单位坑到的两处（tick 与秒、单次量与速率），只有能在 JUnit 里直接算，才敢说数值是对的。
 * <p>
 * 兵种系统（M2）要做的「突击兵延迟 ×0.6、速率 ×1.4」就是 {@link #scaled(double, double)}，
 * 由 {@link RegenProfileProvider} 注入，本系统的结算逻辑一行都不用改。
 *
 * @param delayTicks                最后一次受伤到开始回血的间隔（tick）
 * @param intervalTicks             回血结算间隔（tick）
 * @param healPerInterval           每次结算恢复的血量
 * @param maxFraction               最多恢复到上限的百分比（0~1）
 * @param suppressionRateMultiplier 被压制时的速率倍率（0~1）
 * @param suppressionDelayBonusTicks 被压制时额外增加的延迟（tick）
 */
public record RegenProfile(int delayTicks,
                           int intervalTicks,
                           double healPerInterval,
                           double maxFraction,
                           double suppressionRateMultiplier,
                           int suppressionDelayBonusTicks) {

    /**
     * 默认档位：6 秒脱战、0.1 秒结算一次、每次 1 点 = 10 HP/s。
     * <p>
     * 注意「效率」和「粒度」是两件事：10 tick × 5 血与 2 tick × 1 血的效率完全一样，
     * 但后者在 HUD 上是一点一点往上跳，前者是整段跳变。数字要"平滑上涨"，就得把粒度做细。
     */
    public static final RegenProfile BASE = new RegenProfile(120, 2, 1.0, 1.0, 0.25, 60);

    public RegenProfile {
        delayTicks = Math.max(1, delayTicks);
        intervalTicks = Math.max(1, intervalTicks);
        // 用 !(x > 0) 而不是 Math.max：NaN 走 Math.max 会原样返回 NaN，回血会一路渗进血量里
        healPerInterval = healPerInterval > 0.0 ? healPerInterval : 0.0;
        maxFraction = clamp01(maxFraction);
        suppressionRateMultiplier = clamp01(suppressionRateMultiplier);
        suppressionDelayBonusTicks = Math.max(0, suppressionDelayBonusTicks);
    }

    /** 回血效率（HP/秒）。{@code intervalTicks} 已被钳到 >= 1，不会除零。 */
    public double healPerSecond() {
        return healPerInterval * 20.0 / intervalTicks;
    }

    /** 压制状态下的回血效率（HP/秒）。 */
    public double suppressedHealPerSecond() {
        return healPerSecond() * suppressionRateMultiplier;
    }

    /** 当前状态下要等多久才算脱战。 */
    public long delayTicksFor(boolean suppressed) {
        return delayTicks + (suppressed ? suppressionDelayBonusTicks : 0L);
    }

    /** 本次最多能回到多少血。 */
    public double capFor(double maxHealth) {
        return maxHealth * maxFraction;
    }

    /**
     * 按兵种/状态缩放档位。延迟变短、速率变快都通过这里表达，结算端不认兵种。
     */
    public RegenProfile scaled(double delayScale, double rateScale) {
        return new RegenProfile(
                (int) Math.max(1, Math.round(delayTicks * delayScale)),
                intervalTicks,
                healPerInterval * rateScale,
                maxFraction,
                suppressionRateMultiplier,
                suppressionDelayBonusTicks);
    }

    private static double clamp01(double value) {
        // 不用 Math.min/max 链：NaN 会被原样传出去，配置写坏时直接让回血停在 0 更安全
        if (!(value > 0.0)) return 0.0;
        return Math.min(value, 1.0);
    }
}
