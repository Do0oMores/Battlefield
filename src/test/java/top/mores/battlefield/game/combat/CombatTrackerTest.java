package top.mores.battlefield.game.combat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脱战判定与压制衰减的边界。
 * <p>
 * 这两处错了不会崩服，只会让「6 秒」变成「5.95 秒」或者「永远」——
 * 而玩家和策划都只会说「回血好像不太对」，没人能定位到某个 {@code >=} 写成了 {@code >}。
 */
class CombatTrackerTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Test
    void noRecordMeansAlreadyOutOfCombat() {
        CombatTracker tracker = new CombatTracker();
        assertTrue(tracker.isOutOfCombat(PLAYER, 1000, 120));
        assertNull(tracker.peek(PLAYER), "查询不该顺手创建状态，否则离线玩家会越攒越多");
        assertEquals(0, tracker.size());
    }

    @Test
    void outOfCombatBoundaryIsInclusive() {
        CombatTracker tracker = new CombatTracker();
        tracker.markDamaged(PLAYER, 1000);

        assertFalse(tracker.isOutOfCombat(PLAYER, 1119, 120), "第 119 tick 还没脱战");
        assertTrue(tracker.isOutOfCombat(PLAYER, 1120, 120), "第 120 tick 正好到点，必须开始回血");
    }

    @Test
    void damageResetsTheRegenPhase() {
        CombatTracker tracker = new CombatTracker();
        tracker.markRegen(PLAYER, 5000);
        tracker.markDamaged(PLAYER, 3000);

        // 挨打之后延迟一到就该结算，而不是继续等上一个 interval 的相位
        assertEquals(3000L, tracker.state(PLAYER).nextRegenTick());
        assertEquals(3000L, tracker.state(PLAYER).lastDamageTick());
    }

    @Test
    void suppressionDecaysHalfPerSecond() {
        CombatTracker tracker = new CombatTracker();
        tracker.applySuppression(PLAYER, 0, 1.0);
        assertEquals(1.0, tracker.state(PLAYER).suppression(), 1.0E-9);

        // 20 tick = 1 秒，0.5/s → 掉一半
        assertEquals(0.5, tracker.decaySuppression(PLAYER, 20, 0.5), 1.0E-9);
        // 再过 1 秒归零，不会变成负数
        assertEquals(0.0, tracker.decaySuppression(PLAYER, 40, 0.5), 1.0E-9);
        assertEquals(0.0, tracker.decaySuppression(PLAYER, 200, 0.5), 1.0E-9);
    }

    @Test
    void suppressionAccumulatesButCapsAtOne() {
        CombatTracker tracker = new CombatTracker();
        tracker.applySuppression(PLAYER, 0, 0.8);
        tracker.applySuppression(PLAYER, 0, 0.8);
        assertEquals(1.0, tracker.state(PLAYER).suppression(), 1.0E-9);
    }

    @Test
    void decayNeedsElapsedTimeToDoAnything() {
        CombatTracker tracker = new CombatTracker();
        tracker.applySuppression(PLAYER, 100, 1.0);
        // 同一 tick 内连算两次不该掉血（tick 循环里可能被重复调用）
        assertEquals(1.0, tracker.decaySuppression(PLAYER, 100, 0.5), 1.0E-9);
        assertEquals(1.0, tracker.decaySuppression(PLAYER, 100, 0.5), 1.0E-9);
    }

    @Test
    void respawnRestartsTheClockAndDropsSuppression() {
        CombatTracker tracker = new CombatTracker();
        tracker.markDamaged(PLAYER, 100);
        tracker.applySuppression(PLAYER, 110, 1.0);

        tracker.markRespawn(PLAYER, 20_000, 100.0);

        assertEquals(20_000L, tracker.state(PLAYER).lastDamageTick());
        assertEquals(0.0, tracker.state(PLAYER).suppression(), 1.0E-9);
        // 刚复活不算脱战：否则一落地就往满血清
        assertFalse(tracker.isOutOfCombat(PLAYER, 20_000, 120));
        assertTrue(tracker.isOutOfCombat(PLAYER, 20_120, 120));
    }

    @Test
    void clearDropsTheRecord() {
        CombatTracker tracker = new CombatTracker();
        tracker.markDamaged(PLAYER, 100);
        assertEquals(1, tracker.size());

        tracker.clear(PLAYER);
        assertEquals(0, tracker.size());
        assertTrue(tracker.isOutOfCombat(PLAYER, 101, 120));
    }

    // ===================================================================================
    // 动态回血上限：提示词里的 A~E 五个场景 + 边界
    // ===================================================================================

    @Test
    void ceilingStartsAtMaxHealth() {
        // 没有记录时等于最大生命值：原版 20 血和战地 100 血两种标尺都成立
        assertEquals(100.0, new CombatTracker().regenCeiling(PLAYER, 100.0), 1.0E-9);
        assertEquals(20.0, new CombatTracker().regenCeiling(PLAYER, 20.0), 1.0E-9);
    }

    @Test
    void scenarioALightDamageRecoversFully() {
        CombatTracker tracker = new CombatTracker();
        // 100 血挨 15 点：15 <= 30，全额可恢复
        double ceiling = tracker.applyDamageToCeiling(PLAYER, 100.0, 15.0, 30.0, 100.0);
        assertEquals(100.0, ceiling, 1.0E-9);
    }

    @Test
    void scenarioBHeavyDamageCapsRecovery() {
        CombatTracker tracker = new CombatTracker();
        // 100 血挨 60 点：只有 30 能自然回，上限压到 70
        double ceiling = tracker.applyDamageToCeiling(PLAYER, 100.0, 60.0, 30.0, 100.0);
        assertEquals(70.0, ceiling, 1.0E-9);
    }

    @Test
    void scenarioCFallDamageCannotRaiseTheCeiling() {
        CombatTracker tracker = new CombatTracker();
        tracker.applyDamageToCeiling(PLAYER, 100.0, 60.0, 30.0, 100.0);   // 上限 70
        // 已经自然回到 70，再摔 5 点：计算结果是 70，但绝不能被这次小伤害刷回 100
        double ceiling = tracker.applyDamageToCeiling(PLAYER, 70.0, 5.0, 30.0, 100.0);
        assertEquals(70.0, ceiling, 1.0E-9);
    }

    @Test
    void scenarioDRepeatedHeavyDamageKeepsTightening() {
        CombatTracker tracker = new CombatTracker();
        tracker.applyDamageToCeiling(PLAYER, 100.0, 60.0, 30.0, 100.0);   // 上限 70
        // 70 血再挨 40 点：剩 30，最多补 30 -> 60
        double ceiling = tracker.applyDamageToCeiling(PLAYER, 70.0, 40.0, 30.0, 100.0);
        assertEquals(60.0, ceiling, 1.0E-9);
    }

    @Test
    void scenarioEResetLiftsTheCeilingBack() {
        CombatTracker tracker = new CombatTracker();
        tracker.applyDamageToCeiling(PLAYER, 100.0, 60.0, 30.0, 100.0);
        assertEquals(70.0, tracker.regenCeiling(PLAYER, 100.0), 1.0E-9);

        // 医疗包 / 管理员满血：上限整个还回来
        tracker.setRegenCeiling(PLAYER, 100.0, 100.0);
        assertEquals(100.0, tracker.regenCeiling(PLAYER, 100.0), 1.0E-9);
    }

    @Test
    void overkillDamageIsClampedToCurrentHealth() {
        CombatTracker tracker = new CombatTracker();
        // 10 血挨 50 点：真实损失只算 10（不是 50），10 点又都在可恢复额度内 -> 上限不动。
        // （真死了的话状态会被 onDeath 清掉，这里要验的是别把溢出伤害算进上限。）
        assertEquals(100.0, tracker.applyDamageToCeiling(PLAYER, 10.0, 50.0, 30.0, 100.0), 1.0E-9);

        // 真正会留下永久伤的是「血量还很足、伤害很大」：100 血挨 90 -> 永久伤 60 -> 上限 40
        assertEquals(40.0, new CombatTracker().applyDamageToCeiling(PLAYER, 100.0, 90.0, 30.0, 100.0), 1.0E-9);
    }

    @Test
    void recoverableDamageDuringRegenDoesNotShrinkTheCeiling() {
        CombatTracker tracker = new CombatTracker();
        tracker.applyDamageToCeiling(PLAYER, 100.0, 60.0, 30.0, 100.0);   // 上限 70，血量 40

        // 还在回血半路上（40）再挨 5 点：这 5 点完全可恢复，不该再砍上限
        assertEquals(70.0, tracker.applyDamageToCeiling(PLAYER, 40.0, 5.0, 30.0, 100.0), 1.0E-9);
        // 哪怕只挨 1 点，也不能把上限砸到当前血量
        assertEquals(70.0, tracker.applyDamageToCeiling(PLAYER, 35.0, 1.0, 30.0, 100.0), 1.0E-9);
    }

    @Test
    void heavyDamageDuringRegenCostsOnlyThePermanentPart() {
        CombatTracker tracker = new CombatTracker();
        tracker.applyDamageToCeiling(PLAYER, 100.0, 60.0, 30.0, 100.0);   // 上限 70

        // 半血 50 时再挨 40 点：只有超出 30 额度的那 10 点是永久伤 -> 上限 70-10=60
        assertEquals(60.0, tracker.applyDamageToCeiling(PLAYER, 50.0, 40.0, 30.0, 100.0), 1.0E-9);
    }

    @Test
    void ceilingNeverExceedsMaxHealthEvenIfConfigSaysSo() {
        CombatTracker tracker = new CombatTracker();
        // maxRecoverable 配成 999：上限仍然只能到 100
        assertEquals(100.0, tracker.applyDamageToCeiling(PLAYER, 100.0, 20.0, 999.0, 100.0), 1.0E-9);
        // 直接写超界值也一样被钳住
        tracker.setRegenCeiling(PLAYER, 500.0, 100.0);
        assertEquals(100.0, tracker.regenCeiling(PLAYER, 100.0), 1.0E-9);
        tracker.setRegenCeiling(PLAYER, -5.0, 100.0);
        assertEquals(0.0, tracker.regenCeiling(PLAYER, 100.0), 1.0E-9);
    }

    @Test
    void maxHealthDropPullsTheCeilingDown() {
        CombatTracker tracker = new CombatTracker();
        tracker.regenCeiling(PLAYER, 100.0);
        // 离局摘掉 100 血修饰器后上限变成 20，回血上限不能悬在 100
        assertEquals(20.0, tracker.regenCeiling(PLAYER, 20.0), 1.0E-9);
    }

    @Test
    void respawnResetsTheCeilingToMaxHealth() {
        CombatTracker tracker = new CombatTracker();
        tracker.applyDamageToCeiling(PLAYER, 100.0, 60.0, 30.0, 100.0);
        assertEquals(70.0, tracker.regenCeiling(PLAYER, 100.0), 1.0E-9);

        tracker.markRespawn(PLAYER, 500, 100.0);
        assertEquals(100.0, tracker.regenCeiling(PLAYER, 100.0), 1.0E-9);
    }

    @Test
    void deathClearsTheCeilingWithTheRestOfTheState() {
        CombatTracker tracker = new CombatTracker();
        tracker.applyDamageToCeiling(PLAYER, 100.0, 60.0, 30.0, 100.0);
        tracker.clear(PLAYER);

        // 重开一条命：上限按最大生命值重新初始化，不会继承上一命的 70
        assertEquals(100.0, tracker.regenCeiling(PLAYER, 100.0), 1.0E-9);
    }
}
