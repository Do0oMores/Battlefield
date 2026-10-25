package top.mores.battlefield.game.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回血档位的单位换算。
 * <p>
 * 这套参数全靠人肉推导（tick ↔ 秒、单次量 ↔ 速率），一旦换算错一个 20，
 * 表现是「回血比设计快 20 倍」或者「6 秒变 0.3 秒」，而游戏里根本看不出来是算错了还是配错了。
 */
class RegenProfileTest {

    @Test
    void baseProfileIsTenHpPerSecond() {
        // 1 点 / 0.1 秒 = 10 HP/s，满血 100 需要 10 秒
        assertEquals(10.0, RegenProfile.BASE.healPerSecond(), 1.0E-9);
        assertEquals(100.0 / 10.0, 100.0 / RegenProfile.BASE.healPerSecond(), 1.0E-9);
    }

    @Test
    void baseProfileTicksOnePointAtATime() {
        // 「平滑上涨」的落点：粒度必须是 1 点 / 0.1 秒。
        // 如果谁把间隔改回 10 tick、单次改回 5 点，效率一样但 HUD 会变回整段跳变 —— 这个断言就是拦它的。
        assertEquals(1.0, RegenProfile.BASE.healPerInterval(), 1.0E-9);
        assertEquals(2, RegenProfile.BASE.intervalTicks());
        assertEquals(10.0, RegenProfile.BASE.healPerSecond(), 1.0E-9);
    }

    @Test
    void suppressionCutsRateToAQuarterAndAddsThreeSeconds() {
        assertEquals(2.5, RegenProfile.BASE.suppressedHealPerSecond(), 1.0E-9);
        // 120 tick = 6 秒；压制再加 60 tick = 9 秒
        assertEquals(120L, RegenProfile.BASE.delayTicksFor(false));
        assertEquals(180L, RegenProfile.BASE.delayTicksFor(true));
    }

    @Test
    void assaultRolePresetMatchesTheDesignDoc() {
        // 兵种预留档：突击兵 delay ×0.6、速率 ×1.4 → 3.6 秒 / 14 HP/s，满血 7.1 秒
        RegenProfile assault = RegenProfile.BASE.scaled(0.6, 1.4);
        assertEquals(72, assault.delayTicks());
        assertEquals(3.6, assault.delayTicks() / 20.0, 1.0E-9);
        assertEquals(14.0, assault.healPerSecond(), 1.0E-9);
        assertEquals(7.14, 100.0 / assault.healPerSecond(), 0.01);
    }

    @Test
    void capFollowsMaxHealthFraction() {
        assertEquals(100.0, RegenProfile.BASE.capFor(100.0), 1.0E-9);
        RegenProfile capped = new RegenProfile(120, 10, 5.0, 0.7, 0.25, 60);
        assertEquals(70.0, capped.capFor(100.0), 1.0E-9);
    }

    @Test
    void illegalValuesAreClampedInsteadOfPoisoningTheTick() {
        RegenProfile broken = new RegenProfile(0, 0, -5.0, 2.0, -1.0, -3);
        assertTrue(broken.delayTicks() >= 1);
        assertTrue(broken.intervalTicks() >= 1);
        assertEquals(0.0, broken.healPerInterval(), 1.0E-9);
        assertEquals(1.0, broken.maxFraction(), 1.0E-9);
        assertEquals(0.0, broken.suppressionRateMultiplier(), 1.0E-9);
        assertEquals(0, broken.suppressionDelayBonusTicks());
        // 除零被钳掉了，换算不会变成 Infinity
        assertEquals(0.0, broken.healPerSecond(), 1.0E-9);
    }

    @Test
    void nanFallsBackToZeroRate() {
        RegenProfile nan = new RegenProfile(120, 10, Double.NaN, Double.NaN, Double.NaN, 60);
        assertEquals(0.0, nan.healPerInterval(), 1.0E-9);
        assertEquals(0.0, nan.healPerSecond(), 1.0E-9);
    }
}
