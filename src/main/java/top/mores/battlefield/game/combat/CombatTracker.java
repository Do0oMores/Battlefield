package top.mores.battlefield.game.combat;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 每名参与者的战斗状态：最后一次挨打的时间、压制值、下一次允许回血的时刻，以及<b>动态回血上限</b>。
 * <p>
 * 只存在于内存里（{@code HashMap}），不落 SavedData —— 脱战计时跨服、跨局都没有意义，
 * 而「谁在挨打」这件事一旦被存档，重启后就会冒出一堆过期状态。回血上限跟着同一份状态走：
 * 它属于「这一条命」，死亡、离局都一起丢掉，复活时按最大生命值重新初始化。
 * <p>
 * 这里刻意不引用任何 Minecraft 类型。脱战判定、压制衰减、回血上限的加减法都是最容易算错的地方
 * （边界该用 {@code >=} 还是 {@code >}、衰减按 tick 还是按秒、上限能不能被抬高），
 * 做成纯逻辑才能用单元测试把边界钉死。
 * <p>
 * 时间基准统一由调用方传入，并且必须是 {@code server.getTickCount()}：
 * Mohist 下每个世界是独立文件夹、各有各的 level.dat，{@code getGameTime()} 在不同维度之间
 * 并不保证同源，而这个系统完全靠 tick 差值做判断，混用两个时钟会得到毫秒级的漂移。
 */
public final class CombatTracker {

    /** 单名玩家的战斗状态。对外只读，写入一律走 {@link CombatTracker} 的方法。 */
    public static final class State {
        /** 最后一次真正吃到伤害的 tick。0 表示「还没挨过打」。 */
        private long lastDamageTick;
        /** 下一次允许结算回血的 tick，用来给回血节奏定相位。 */
        private long nextRegenTick;
        /** 压制值上一次结算的 tick。 */
        private long lastDecayTick;
        /** 0..1 的压制值。 */
        private double suppression;
        /**
         * 自然回血能回到的最高血量（战地式的「动态回血上限」）。
         * <p>
         * 负数表示「还没初始化」——第一次用到时按当时的最大生命值补上，
         * 这样不需要在加入对局、切维度、复活等一堆入口里都记着初始化一次。
         */
        private double regenCeiling = -1.0;

        public long lastDamageTick() {
            return lastDamageTick;
        }

        public long nextRegenTick() {
            return nextRegenTick;
        }

        public double suppression() {
            return suppression;
        }

        /** 原始字段，可能还是 -1（未初始化）。业务代码请用 {@link CombatTracker#regenCeiling}。 */
        public double rawRegenCeiling() {
            return regenCeiling;
        }
    }

    private static final double MAX_SUPPRESSION = 1.0;

    private final Map<UUID, State> states = new HashMap<>();

    /** 取（必要时创建）某人的状态。 */
    public State state(UUID id) {
        return states.computeIfAbsent(id, k -> new State());
    }

    @Nullable
    public State peek(UUID id) {
        return states.get(id);
    }

    /**
     * 记录一次有效伤害。
     * <p>
     * 只应该由「事件没被取消」的路径调用：友伤被 {@code FriendlyFireHandler} 取消时不能重置脱战计时，
     * 否则队友误伤一下就能让敌人多等 6 秒。
     */
    public void markDamaged(UUID id, long now) {
        State s = state(id);
        s.lastDamageTick = now;
        // 让延迟一到就能立刻结算，而不是再等一个 interval 的相位
        s.nextRegenTick = now;
    }

    /**
     * 复活 / 重新部署：计时从当前 tick 重新起算，压制清空，<b>回血上限重置为最大生命值</b>。
     * 避免「刚复活就回血」「刚复活还带着死亡前的压制」以及「刚复活就背着上一命的回血上限」。
     */
    public void markRespawn(UUID id, long now, double maxHealth) {
        State s = state(id);
        s.lastDamageTick = now;
        s.nextRegenTick = now;
        s.lastDecayTick = now;
        s.suppression = 0.0;
        s.regenCeiling = Math.max(0.0, maxHealth);
    }

    public void markRegen(UUID id, long nextTick) {
        state(id).nextRegenTick = nextTick;
    }

    // ===================================================================================
    // 动态回血上限（Battlefield V 式：受伤越重，能自然回的血越少）
    // ===================================================================================

    /** 当前的自然回血上限。未初始化时按 {@code maxHealth} 初始化，并顺手钳进 [0, maxHealth]。 */
    public double regenCeiling(UUID id, double maxHealth) {
        State s = state(id);
        double cap = Math.max(0.0, maxHealth);
        if (s.regenCeiling < 0.0) {
            s.regenCeiling = cap;
        } else if (s.regenCeiling > cap) {
            // 最大生命值被调低（比如中途离局摘掉 100 血修饰器）时不能让上限悬空
            s.regenCeiling = cap;
        }
        return s.regenCeiling;
    }

    /** 直接写上限（医疗包 / 管理员指令用）。自动钳进 [0, maxHealth]。 */
    public void setRegenCeiling(UUID id, double ceiling, double maxHealth) {
        State s = state(id);
        s.regenCeiling = Math.max(0.0, Math.min(ceiling, Math.max(0.0, maxHealth)));
    }

    /**
     * 受伤后收紧上限。
     * <p>
     * 规则（与《战地》一致）：一次受伤最多只有 {@code maxRecoverable} 点能靠自然回血补回来，
     * 剩下的「永久伤」才真正砍上限；并且<b>只能降不能升</b> ——
     * 否则「先被打残、再故意摔 1 点」就能把上限刷回满血。
     * <p>
     * 核心原则：<b>自然回血补不回来的，只有历史伤害里那部分永久伤</b>。
     * 所以这里同时算两个候选值取大的：
     * <ul>
     *   <li>{@code 旧上限 - 永久伤}：伤害池模型，只有这次伤害里超出可恢复额度的部分才砍上限；</li>
     *   <li>{@code 剩余血量 + 可恢复量}：需求文档里给的写法，在「满血挨打」时与上面完全等价。</li>
     * </ul>
     * 只取后者的写法在<b>玩家还在回血途中</b>时会出问题：上限 70、血量 40 的人再挨 1 点，
     * {@code 39 + 1 = 40}，于是掉了 30 点上限 —— 那 30 点本来是能回上来的，
     * 这就把「可恢复伤害」变成了「永久伤」，正好违反上面那条原则。取两者较大值可以同时满足：
     * 满血挨打时行为与需求文档逐字一致，回血途中不额外惩罚。
     *
     * @param oldHealth      受伤前的血量（事件触发时血量还没被扣）
     * @param actualLoss     这次真实掉的血（护甲、抗性、吸收盾都已结算完）
     * @param maxRecoverable 单次最多可自然恢复的血量
     * @param maxHealth      当前最大生命值
     * @return 更新后的上限
     */
    public double applyDamageToCeiling(UUID id, double oldHealth, double actualLoss,
                                       double maxRecoverable, double maxHealth) {
        double cap = Math.max(0.0, maxHealth);
        double health = Math.max(0.0, Math.min(oldHealth, cap));
        // 一次打空血时「真实损失」只到 0 为止，不能按事件里的溢出伤害算
        double loss = Math.max(0.0, Math.min(actualLoss, health));
        double remaining = health - loss;
        double recoverable = Math.min(loss, Math.max(0.0, maxRecoverable));
        double permanent = loss - recoverable;

        State s = state(id);
        if (s.regenCeiling < 0.0) s.regenCeiling = cap;
        double oldCeiling = Math.min(s.regenCeiling, cap);

        double byDamagePool = oldCeiling - permanent;
        double byRemainingHealth = remaining + recoverable;
        // min(旧上限, ...) 就是「低伤害不能抬高已有上限」的全部实现
        s.regenCeiling = Math.max(0.0, Math.min(Math.min(oldCeiling, Math.max(byDamagePool, byRemainingHealth)), cap));
        return s.regenCeiling;
    }

    /**
     * 写入压制值（由支援兵系统调用）。累加式：多名敌人同时压制会更快顶到 1.0。
     */
    public void applySuppression(UUID id, long now, double amount) {
        if (!(amount > 0.0)) return;
        State s = state(id);
        s.lastDecayTick = now;
        s.suppression = Math.min(MAX_SUPPRESSION, s.suppression + amount);
    }

    /**
     * 按经过的 tick 衰减压制值，返回衰减后的值。
     * <p>
     * 每 tick 调一次，{@code perSecond = 0.5} 时 2 秒从 1.0 掉到 0 —— 压制是「正在被火力覆盖」的瞬时状态，
     * 不是永久 debuff，否则玩家找到一个压制过的墙角就再也回不了血。
     */
    public double decaySuppression(UUID id, long now, double perSecond) {
        State s = state(id);
        long dt = now - s.lastDecayTick;
        if (dt <= 0) return s.suppression;
        s.lastDecayTick = now;
        if (s.suppression <= 0.0) return 0.0;
        if (!(perSecond > 0.0)) return s.suppression;
        s.suppression = Math.max(0.0, s.suppression - perSecond * dt / 20.0);
        return s.suppression;
    }

    /** 距最后一次受伤是否已经超过 {@code delayTicks}。没有记录的人视为早已脱战。 */
    public boolean isOutOfCombat(UUID id, long now, long delayTicks) {
        State s = states.get(id);
        if (s == null) return true;
        return now - s.lastDamageTick >= delayTicks;
    }

    public void clear(UUID id) {
        states.remove(id);
    }

    public void clearAll() {
        states.clear();
    }

    public int size() {
        return states.size();
    }
}
