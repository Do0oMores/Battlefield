package top.mores.battlefield.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 战斗参数的解析。
 * <p>
 * 两个重点：
 * <ul>
 *   <li>{@link CombatConfig#fraction} 不能复用 {@code positiveDouble} —— 后者把 0 判成非法，
 *       而 {@code suppressionRateMultiplier: 0}（压制期间完全不回血）是合法配置；</li>
 *   <li>JSON 字段名到配置对象字段是一层纯手工映射，写错一处就是「改了没反应」，
 *       所以这里用一批互不相同的数值把每个键都钉一遍。</li>
 * </ul>
 * 注意：这个测试只碰 {@code Root} 与 {@code CombatConfig}，不会触发 {@code BattlefieldServerConfig}
 * 的静态初始化（那里要读 FMLPaths，单元测试环境里加载不了）。
 */
class CombatConfigTest {

    private static CombatConfig fromJson(String json) {
        BattlefieldServerConfig.Root root = new Gson().fromJson(json, BattlefieldServerConfig.Root.class);
        return CombatConfig.from(root);
    }

    @Test
    void fractionAcceptsZeroAndClampsToOne() {
        assertEquals(0.0, CombatConfig.fraction(0.0, 1.0), 1.0E-9, "0 是合法的「不回血」，不能被当成非法值");
        assertEquals(0.0, CombatConfig.fraction(-0.2, 1.0), 1.0E-9, "负数钳到 0，而不是回退默认值");
        assertEquals(1.0, CombatConfig.fraction(2.5, 0.5), 1.0E-9, "大于 1 钳到 1");
        assertEquals(0.35, CombatConfig.fraction(0.35, 1.0), 1.0E-9);
    }

    @Test
    void fractionFallsBackOnMissingOrNaN() {
        assertEquals(0.25, CombatConfig.fraction(null, 0.25), 1.0E-9);
        assertEquals(0.25, CombatConfig.fraction(Double.NaN, 0.25), 1.0E-9);
    }

    @Test
    void missingRootGivesDefaults() {
        CombatConfig cfg = CombatConfig.from(null);
        assertEquals(100.0, cfg.maxHealth, 1.0E-9);
        assertEquals(120, cfg.regenDelayTicks);
        // 1 点 / 0.1 秒：效率 10 HP/s，但 HUD 上是一点一点往上跳
        assertEquals(2, cfg.regenIntervalTicks);
        assertEquals(1.0, cfg.regenHealPerInterval, 1.0E-9);
        assertEquals(10.0, cfg.regenHealPerSecond(), 1.0E-9);
        assertTrue(cfg.regenEnabled);
        assertFalse(cfg.shootingBreaksRegen);
        assertFalse(cfg.regenOutOfArea);
        assertEquals(1.0, cfg.gunDamageScale, 1.0E-9);
        // 动态回血上限：默认开启，单次最多 30 点可自然恢复（100 血标尺 = 30% 最大生命）
        assertTrue(cfg.enableRegenCeiling);
        assertEquals(30.0, cfg.maxRecoverableDamage, 1.0E-9);
        assertTrue(cfg.fallbacks().isEmpty());
    }

    @Test
    void emptyJsonAlsoGivesDefaults() {
        CombatConfig cfg = fromJson("{}");
        assertEquals(120, cfg.regenDelayTicks);
        assertEquals(2, cfg.regenIntervalTicks);
        assertTrue(cfg.fallbacks().isEmpty(), "字段没写不算错，不该报警");
    }

    @Test
    void everyJsonKeyLandsOnItsOwnField() {
        // 每个键给一个互不相同的值：位置参数写反了这里立刻炸
        CombatConfig cfg = fromJson("""
                {
                  "combatRegenEnabled": false,
                  "maxHealth": 150.0,
                  "regenDelayTicks": 77,
                  "regenIntervalTicks": 7,
                  "regenHealPerInterval": 3.5,
                  "regenMaxHealthFraction": 0.8,
                  "suppressionDelayBonusTicks": 33,
                  "suppressionRateMultiplier": 0.4,
                  "suppressionDecayPerSecond": 0.9,
                  "shootingBreaksRegen": true,
                  "gunDamageScale": 2.5,
                  "regenOutOfArea": true,
                  "enableRegenCeiling": false,
                  "maxRecoverableDamage": 6.0
                }
                """);

        assertFalse(cfg.regenEnabled);
        assertEquals(150.0, cfg.maxHealth, 1.0E-9);
        assertEquals(77, cfg.regenDelayTicks);
        assertEquals(7, cfg.regenIntervalTicks);
        assertEquals(3.5, cfg.regenHealPerInterval, 1.0E-9);
        assertEquals(0.8, cfg.maxHealthFraction, 1.0E-9);
        assertEquals(33, cfg.suppressionDelayBonusTicks);
        assertEquals(0.4, cfg.suppressionRateMultiplier, 1.0E-9);
        assertEquals(0.9, cfg.suppressionDecayPerSecond, 1.0E-9);
        assertTrue(cfg.shootingBreaksRegen);
        assertEquals(2.5, cfg.gunDamageScale, 1.0E-9);
        assertTrue(cfg.regenOutOfArea);
        assertFalse(cfg.enableRegenCeiling);
        assertEquals(6.0, cfg.maxRecoverableDamage, 1.0E-9);
        assertTrue(cfg.fallbacks().isEmpty());
        // 3.5 血 / 0.35 秒 = 10 HP/s
        assertEquals(10.0, cfg.regenHealPerSecond(), 1.0E-9);
    }

    @Test
    void outOfRangeValuesFallBackAndGetReported() {
        CombatConfig cfg = fromJson("""
                {
                  "maxHealth": 0.0,
                  "regenDelayTicks": 0,
                  "regenHealPerInterval": -5.0,
                  "gunDamageScale": 0.0,
                  "suppressionDelayBonusTicks": -60
                }
                """);

        assertEquals(100.0, cfg.maxHealth, 1.0E-9);
        assertEquals(120, cfg.regenDelayTicks);
        assertEquals(1.0, cfg.regenHealPerInterval, 1.0E-9);
        assertEquals(1.0, cfg.gunDamageScale, 1.0E-9);
        assertEquals(60, cfg.suppressionDelayBonusTicks);
        assertEquals(5, cfg.fallbacks().size(), "越界的字段要能报出来，否则调参时只会以为配置没生效");
        assertTrue(cfg.fallbacks().contains("regenDelayTicks"));
        assertTrue(cfg.fallbacks().contains("gunDamageScale"));
    }

    @Test
    void intervalZeroIsTreatedAsIllegalNotDivisionByZero() {
        CombatConfig cfg = fromJson("{\"regenIntervalTicks\": 0}");
        assertEquals(2, cfg.regenIntervalTicks);
        assertEquals(10.0, cfg.regenHealPerSecond(), 1.0E-9);
    }

    @Test
    void zeroRecoverableDamageIsALegalConfiguration() {
        // 0 的意思是「自然回血一点都补不回来，只能靠医疗包」，不能被当成非法值退回 30
        CombatConfig cfg = fromJson("{\"maxRecoverableDamage\": 0}");
        assertEquals(0.0, cfg.maxRecoverableDamage, 1.0E-9);
        assertTrue(cfg.fallbacks().isEmpty());
    }

    @Test
    void negativeRecoverableDamageFallsBackAndIsReported() {
        CombatConfig cfg = fromJson("{\"maxRecoverableDamage\": -1}");
        assertEquals(30.0, cfg.maxRecoverableDamage, 1.0E-9);
        assertTrue(cfg.fallbacks().contains("maxRecoverableDamage"));
    }
}
