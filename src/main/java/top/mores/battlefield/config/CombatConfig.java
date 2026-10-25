package top.mores.battlefield.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 生命上限与脱战回血的参数（100 血基线）。
 * <p>
 * 单独拆出来的原因有两个：
 * <ol>
 *   <li>{@link BattlefieldServerConfig} 的静态字段要读 {@code FMLPaths}，那个类在单元测试里根本加载不了，
 *       而这套参数是「必须能在运行时改、改完要立刻看到效果」的数值，最需要断言；</li>
 *   <li>{@code Settings} 的构造器已经有 46 个位置参数，再往里塞 12 个同类型的数字，
 *       迟早会有人把 {@code regenDelayTicks} 和 {@code regenIntervalTicks} 写反。</li>
 * </ol>
 * 这里刻意不引用任何 Minecraft 类型，全部是基本类型 → 可以直接在 JUnit 里跑。
 */
public final class CombatConfig {
    public static final boolean DEFAULT_REGEN_ENABLED = true;
    /** 100 血基线。改这个必须同步校准 {@link #gunDamageScale}，否则 TTK 会整体走样。 */
    public static final double DEFAULT_MAX_HEALTH = 100.0;
    public static final int DEFAULT_REGEN_DELAY_TICKS = 120;
    /**
     * 回血结算间隔，默认 2 tick（0.1 秒）。
     * <p>
     * 这个值直接决定「数字怎么涨」：间隔 10 tick + 每次 5 血和间隔 2 tick + 每次 1 血
     * 的效率都是 10 HP/s，但前者是整段跳（60 → 65 → 70），后者是一点一点往上跳。
     * 要的就是后者 —— 血条和数字连续上涨，玩家才看得出"我正在回血"而不是"血突然多了一截"。
     * 代价是客户端血量同步包从 2 次/秒升到 10 次/秒（32 人 ≈ 320 包/秒，仍然是原版移动包的量级）。
     */
    public static final int DEFAULT_REGEN_INTERVAL_TICKS = 2;
    /** 每次结算恢复的血量。默认 1 点 / 0.1 秒 = 10 HP/s。 */
    public static final double DEFAULT_REGEN_HEAL_PER_INTERVAL = 1.0;
    public static final double DEFAULT_MAX_HEALTH_FRACTION = 1.0;
    public static final int DEFAULT_SUPPRESSION_DELAY_BONUS_TICKS = 60;
    public static final double DEFAULT_SUPPRESSION_RATE_MULTIPLIER = 0.25;
    public static final double DEFAULT_SUPPRESSION_DECAY_PER_SECOND = 0.5;
    public static final boolean DEFAULT_SHOOTING_BREAKS_REGEN = false;
    public static final double DEFAULT_GUN_DAMAGE_SCALE = 1.0;
    public static final boolean DEFAULT_REGEN_OUT_OF_AREA = false;
    /** 动态回血上限总开关。关掉后回到「脱战就能回满」的老行为。 */
    public static final boolean DEFAULT_ENABLE_REGEN_CEILING = true;
    /**
     * 单次受伤最多有多少点能靠自然回血补回来（100 血标尺下 30 点）。
     * <p>
     * 这是「战地式伤情」的核心：挨 15 点可以回满，挨 60 点只能回到 70，剩下的必须靠医疗包。
     * 换成原版 20 血标尺时应填 6（= 30% 最大生命）。
     */
    public static final double DEFAULT_MAX_RECOVERABLE_DAMAGE = 30.0;

    /** 脱战回血总开关。关闭后完全依赖医疗包/医疗站。 */
    public final boolean regenEnabled;
    /** 对局内玩家最大生命上限。 */
    public final double maxHealth;

    /** 最后一次受伤到开始回血的间隔（tick）。 */
    public final int regenDelayTicks;
    /** 回血结算间隔（tick）。越小数字跳得越细，客户端同步包越密。 */
    public final int regenIntervalTicks;
    /** 每次结算恢复的血量。效率 = 本值 / (间隔 / 20)。 */
    public final double regenHealPerInterval;
    /** 最多恢复到上限的百分比（0~1）。 */
    public final double maxHealthFraction;

    /** 被压制时回血延迟额外增加的 tick。 */
    public final int suppressionDelayBonusTicks;
    /** 被压制时回血速率的倍率（0~1）。 */
    public final double suppressionRateMultiplier;
    /** 压制值每秒自然衰减量（0~1）。 */
    public final double suppressionDecayPerSecond;

    /** 自己开火是否重置脱战计时。 */
    public final boolean shootingBreaksRegen;
    /** TACZ 子弹伤害倍率，用于对齐 100 血基线。 */
    public final double gunDamageScale;
    /** 离开可活动区域的击杀倒计时期间是否仍可回血。 */
    public final boolean regenOutOfArea;

    /** 是否启用动态回血上限（受伤越重，能自然回的血越少）。 */
    public final boolean enableRegenCeiling;
    /** 单次受伤最多可自然恢复的血量。 */
    public final double maxRecoverableDamage;

    /**
     * 本次解析中越界、被回退成默认值的字段名。
     * <p>
     * 调参阶段最常见的困惑就是「我明明填了 10，怎么还是 5 血一次」——数值写坏时静默回退，
     * 玩家只会以为配置没生效。这里把字段名收集起来，由 {@code reload()} 统一打一条 warn。
     */
    private final List<String> fallbacks;

    CombatConfig(boolean regenEnabled,
                 double maxHealth,
                 int regenDelayTicks,
                 int regenIntervalTicks,
                 double regenHealPerInterval,
                 double maxHealthFraction,
                 int suppressionDelayBonusTicks,
                 double suppressionRateMultiplier,
                 double suppressionDecayPerSecond,
                 boolean shootingBreaksRegen,
                 double gunDamageScale,
                 boolean regenOutOfArea,
                 boolean enableRegenCeiling,
                 double maxRecoverableDamage,
                 List<String> fallbacks) {
        this.regenEnabled = regenEnabled;
        this.maxHealth = maxHealth;
        this.regenDelayTicks = regenDelayTicks;
        this.regenIntervalTicks = regenIntervalTicks;
        this.regenHealPerInterval = regenHealPerInterval;
        this.maxHealthFraction = maxHealthFraction;
        this.suppressionDelayBonusTicks = suppressionDelayBonusTicks;
        this.suppressionRateMultiplier = suppressionRateMultiplier;
        this.suppressionDecayPerSecond = suppressionDecayPerSecond;
        this.shootingBreaksRegen = shootingBreaksRegen;
        this.gunDamageScale = gunDamageScale;
        this.regenOutOfArea = regenOutOfArea;
        this.enableRegenCeiling = enableRegenCeiling;
        this.maxRecoverableDamage = maxRecoverableDamage;
        this.fallbacks = List.copyOf(fallbacks);
    }

    public List<String> fallbacks() {
        return fallbacks;
    }

    /** 回血效率（HP/秒）。{@code regenIntervalTicks} 天生 >= 1，不会除零。 */
    public double regenHealPerSecond() {
        return regenHealPerInterval * 20.0 / regenIntervalTicks;
    }

    static CombatConfig defaults() {
        return new CombatConfig(
                DEFAULT_REGEN_ENABLED,
                DEFAULT_MAX_HEALTH,
                DEFAULT_REGEN_DELAY_TICKS,
                DEFAULT_REGEN_INTERVAL_TICKS,
                DEFAULT_REGEN_HEAL_PER_INTERVAL,
                DEFAULT_MAX_HEALTH_FRACTION,
                DEFAULT_SUPPRESSION_DELAY_BONUS_TICKS,
                DEFAULT_SUPPRESSION_RATE_MULTIPLIER,
                DEFAULT_SUPPRESSION_DECAY_PER_SECOND,
                DEFAULT_SHOOTING_BREAKS_REGEN,
                DEFAULT_GUN_DAMAGE_SCALE,
                DEFAULT_REGEN_OUT_OF_AREA,
                DEFAULT_ENABLE_REGEN_CEILING,
                DEFAULT_MAX_RECOVERABLE_DAMAGE,
                Collections.emptyList());
    }

    /**
     * 从 JSON 原始值构造。字段缺失（null）走默认值、不报警；字段存在但越界才记进 {@link #fallbacks()}。
     * 任何情况下都不抛异常 —— 这个对象是在 tick 路径上被读的，炸一次就是整局停摆。
     */
    static CombatConfig from(BattlefieldServerConfig.Root root) {
        if (root == null) return defaults();

        List<String> fallbacks = new ArrayList<>(2);
        return new CombatConfig(
                root.combatRegenEnabled != null ? root.combatRegenEnabled : DEFAULT_REGEN_ENABLED,
                positive(root.maxHealth, DEFAULT_MAX_HEALTH, "maxHealth", fallbacks),
                atLeastOne(root.regenDelayTicks, DEFAULT_REGEN_DELAY_TICKS, "regenDelayTicks", fallbacks),
                atLeastOne(root.regenIntervalTicks, DEFAULT_REGEN_INTERVAL_TICKS, "regenIntervalTicks", fallbacks),
                positive(root.regenHealPerInterval, DEFAULT_REGEN_HEAL_PER_INTERVAL, "regenHealPerInterval", fallbacks),
                fraction(root.regenMaxHealthFraction, DEFAULT_MAX_HEALTH_FRACTION),
                nonNegative(root.suppressionDelayBonusTicks, DEFAULT_SUPPRESSION_DELAY_BONUS_TICKS,
                        "suppressionDelayBonusTicks", fallbacks),
                fraction(root.suppressionRateMultiplier, DEFAULT_SUPPRESSION_RATE_MULTIPLIER),
                fraction(root.suppressionDecayPerSecond, DEFAULT_SUPPRESSION_DECAY_PER_SECOND),
                root.shootingBreaksRegen != null ? root.shootingBreaksRegen : DEFAULT_SHOOTING_BREAKS_REGEN,
                positive(root.gunDamageScale, DEFAULT_GUN_DAMAGE_SCALE, "gunDamageScale", fallbacks),
                root.regenOutOfArea != null ? root.regenOutOfArea : DEFAULT_REGEN_OUT_OF_AREA,
                root.enableRegenCeiling != null ? root.enableRegenCeiling : DEFAULT_ENABLE_REGEN_CEILING,
                nonNegativeDouble(root.maxRecoverableDamage, DEFAULT_MAX_RECOVERABLE_DAMAGE,
                        "maxRecoverableDamage", fallbacks),
                fallbacks
        );
    }

    /**
     * 百分比字段：null 用默认值，其余一律钳到 [0,1]。
     * <p>
     * 不能复用 {@code positiveDouble()} —— 它把 0 当成非法值，而 {@code suppressionRateMultiplier: 0}
     * （压制期间完全不能回血）是合法配置。
     */
    static double fraction(Double value, double fallback) {
        if (value == null || value.isNaN()) return fallback;
        return Math.min(1.0, Math.max(0.0, value));
    }

    private static double positive(Double value, double fallback, String name, List<String> fallbacks) {
        if (value == null) return fallback;
        if (!(value > 0.0)) {
            fallbacks.add(name);
            return fallback;
        }
        return value;
    }

    private static int atLeastOne(Integer value, int fallback, String name, List<String> fallbacks) {
        if (value == null) return fallback;
        if (value < 1) {
            fallbacks.add(name);
            return fallback;
        }
        return value;
    }

    /**
     * 允许 0 的小数字段。
     * <p>
     * {@code maxRecoverableDamage = 0} 是合法配置 —— 意思是「自然回血一点都补不回来，只能靠医疗包」，
     * 不能像 {@code positive()} 那样把它当作非法值退回默认的 30。
     */
    private static double nonNegativeDouble(Double value, double fallback, String name, List<String> fallbacks) {
        if (value == null) return fallback;
        if (!(value >= 0.0)) {  // 负数与 NaN 一起挡掉
            fallbacks.add(name);
            return fallback;
        }
        return value;
    }

    private static int nonNegative(Integer value, int fallback, String name, List<String> fallbacks) {
        if (value == null) return fallback;
        if (value < 0) {
            fallbacks.add(name);
            return fallback;
        }
        return value;
    }
}
