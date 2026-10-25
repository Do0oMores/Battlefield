package top.mores.battlefield.game.combat;

import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.config.BattlefieldServerConfig;
import top.mores.battlefield.config.CombatConfig;
import top.mores.battlefield.game.BattlefieldGameManager;

import java.util.UUID;

/**
 * 对局内的生命上限与子弹伤害标定。
 * <p>
 * 100 血不是「把 HUD 的数字乘 5」，是真的改 {@link Attributes#MAX_HEALTH}：
 * 心形渲染、{@code /data get entity Health}、伤害计算、图腾判定全都读这个属性，
 * 只有属性真变了，后面所有回血数值才有意义。
 */
@Mod.EventBusSubscriber(modid = Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PlayerHealthService {
    /**
     * 固定 UUID：1.20.1 的修饰器仍按 UUID 去重（1.21 才换成 ResourceLocation）。
     * 复用它做「先摘再挂」，反复进对局也不会叠加成 180 血。
     */
    private static final UUID MAX_HEALTH_MODIFIER_ID = UUID.fromString("4c1d7a3e-9b25-4f68-8a01-6d3e2b7c5f90");
    private static final String MAX_HEALTH_MODIFIER_NAME = "battlefield.match_max_health";
    private static final double EPSILON = 1.0E-4;

    private PlayerHealthService() {
    }

    /**
     * 把玩家的生命上限顶到配置值。进对局、复活都调一次（复活走的是新的实体，属性虽然会被
     * {@code PlayerList#respawn} 带过去，但显式再挂一次成本只有一次属性查找，能兜住所有分支）。
     */
    public static void apply(ServerPlayer player) {
        AttributeInstance instance = player.getAttribute(Attributes.MAX_HEALTH);
        if (instance == null) return;

        double target = currentMaxHealth();
        // 先摘自己的再读当前值：delta 因此是相对「其他来源（装备/药水/别的模组）」算出来的，
        // 直接读 getValue() 再套固定 +80 会在有装备加成时算错
        instance.removeModifier(MAX_HEALTH_MODIFIER_ID);
        double delta = target - instance.getValue();
        if (Math.abs(delta) < EPSILON) return;

        instance.addPermanentModifier(new AttributeModifier(MAX_HEALTH_MODIFIER_ID,
                MAX_HEALTH_MODIFIER_NAME, delta, AttributeModifier.Operation.ADDITION));

        // 上限被调低时把当前血量钳回来，否则会出现 「95 / 20」这种客户端和服务端都算不出来的状态
        if (player.isAlive() && player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
    }

    /** 离开对局：摘掉修饰器，血量钳回原版上限（20）。 */
    public static void remove(ServerPlayer player) {
        AttributeInstance instance = player.getAttribute(Attributes.MAX_HEALTH);
        if (instance == null) return;
        if (instance.getModifier(MAX_HEALTH_MODIFIER_ID) == null) return;

        instance.removeModifier(MAX_HEALTH_MODIFIER_ID);
        if (player.isAlive() && player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
    }

    public static boolean isApplied(ServerPlayer player) {
        AttributeInstance instance = player.getAttribute(Attributes.MAX_HEALTH);
        return instance != null && instance.getModifier(MAX_HEALTH_MODIFIER_ID) != null;
    }

    // ===================================================================================
    // 治疗入口（医疗包 / 管理员指令 / 以后的任何治疗道具都走这里）
    // ===================================================================================

    /**
     * 服务端治疗入口。
     * <p>
     * 这一个方法就是「医疗」和「自然回血」的分界线：
     * <ul>
     *   <li>自然回血只能回到 {@code regenCeiling}（受伤越重上限越低）；</li>
     *   <li>走这里治疗可以突破那个上限。</li>
     * </ul>
     * 三条硬约束：
     * <ol>
     *   <li>不复活死亡玩家 —— 死亡由部署流程接管，这里直接返回 false；</li>
     *   <li>不超过最大生命值 —— 超出的治疗量直接丢弃，不做"溢出转护盾"；</li>
     *   <li>不改伤害、不改护甲，只加血。</li>
     * </ol>
     *
     * @param amount            治疗量（正数）
     * @param resetRegenCeiling {@code true} = 完整治疗（医疗包、管理员满血），把自然回血上限整个还给玩家；
     *                          {@code false} = 普通治疗，只保证上限不低于治疗后的血量
     * @return 是否真的回上了血（满血时调用返回 false，但上限该重置的仍然会重置）
     */
    public static boolean healPlayer(ServerPlayer player, float amount, boolean resetRegenCeiling) {
        if (player == null || !player.isAlive()) return false;
        if (!(amount > 0.0F)) return false;

        float maxHealth = player.getMaxHealth();
        float before = player.getHealth();
        float after = Math.min(before + amount, maxHealth);

        boolean healed = after > before + 1.0E-4F;
        if (healed) {
            player.setHealth(after);
        }

        if (resetRegenCeiling) {
            // 完整治疗：这一条命的伤情直接清空
            CombatRegenService.resetRegenCeiling(player);
        } else if (CombatRegenService.regenCeiling(player) < after) {
            // 普通治疗：把上限抬到治疗后的血量。
            // 不抬的话会出现「刚被治疗到 90，血条却卡在 70 不动」这种自相矛盾的状态。
            CombatRegenService.setRegenCeiling(player, after);
        }
        return healed;
    }

    /**
     * 子弹伤害倍率，用于把 100 血基线下的 TTK 拉回原版手感。
     * <p>
     * 只能挂在 {@link LivingHurtEvent} 上：TACZ 的 {@code EntityHurtByGunEvent} 只有 getter，
     * 没有 {@code setAmount}（已用 javap 核实 1.1.8 的类签名），改不了伤害。
     * <p>
     * 判定用 {@code getDirectEntity()} 而不是 {@code getEntity()}：TACZ 的子弹是
     * {@code EntityKineticBullet extends Projectile}，伤害源里 {@code getEntity()} 是射手，
     * {@code getDirectEntity()} 才是弹丸本身。
     * <p>
     * <b>副作用</b>：{@code ScoreManager} 用「掉的血量」算伤害分，倍率会把伤害分同比例放大。
     * 因此 {@code ScoreManager} 里用 {@link #damageScoreCompensation} 按同一倍率折回去，
     * 保证「打了多少血」和「得多少分」仍然是 1:1。
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!Battlefield.isEnabled()) return;
        // 友伤取消在前（NORMAL），这里在 LOW，取消的事件不该被缩放
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof ServerPlayer victim)) return;
        if (BattlefieldGameManager.getPlayerMatchId(victim.getUUID()) == null) return;

        double scale = BattlefieldServerConfig.get().combat.gunDamageScale;
        if (Math.abs(scale - 1.0) < EPSILON) return;
        if (!(event.getSource().getDirectEntity() instanceof EntityKineticBullet)) return;

        event.setAmount((float) (event.getAmount() * scale));
    }

    /**
     * 计分补偿：子弹伤害被 {@code gunDamageScale} 放大过，伤害分要按同一倍率折回原尺度。
     * 非子弹伤害、或倍率为 1.0 时返回 1.0。
     */
    public static float damageScoreCompensation(DamageSource source) {
        if (source == null) return 1.0F;
        double scale = BattlefieldServerConfig.get().combat.gunDamageScale;
        if (scale <= 0.0 || Math.abs(scale - 1.0) < EPSILON) return 1.0F;
        if (!(source.getDirectEntity() instanceof EntityKineticBullet)) return 1.0F;
        return (float) (1.0 / scale);
    }

    private static double currentMaxHealth() {
        double configured = BattlefieldServerConfig.get().combat.maxHealth;
        return configured > 0.0 ? configured : CombatConfig.DEFAULT_MAX_HEALTH;
    }
}
