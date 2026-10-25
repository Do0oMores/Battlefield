package top.mores.battlefield.game.combat;

import com.tacz.guns.api.event.common.GunShootEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.config.BattlefieldServerConfig;
import top.mores.battlefield.config.CombatConfig;
import top.mores.battlefield.game.BattlefieldGameManager;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 脱战回血结算。
 * <p>
 * 设计不变式（任何一条被破坏都算 bug）：
 * <ol>
 *   <li>回血速率必须低于交火有效 DPS 的 1/5 —— 否则贴脸对枪可以靠「边打边回」抵消伤害，TTK 失去意义；</li>
 *   <li>回血的前提是「脱离敌方火力」，不是「停止开火」（{@code shootingBreaksRegen} 默认关）；</li>
 *   <li>所有数值从 {@link CombatConfig} 现取，{@code /bt reload} 后对进行中的对局立即生效。</li>
 * </ol>
 * 结算方式：每 {@code regenIntervalTicks} 加一次血，不用 {@code MobEffects.REGENERATION}
 * （按秒加整数点、与饱和/饥饿耦合、表达不了延迟和上限百分比）；也不每 tick 调 heal ——
 * 默认 2 tick 结算 1 点（10 HP/s）已经足够让 HUD 的数字连续往上跳，每 tick 结算只是白白翻倍同步包。
 * <p>
 * 「回血过程中一受伤立刻停」不是单独做的一个开关，而是 {@code lastDamageTick} 的直接后果：
 * 挨打当 tick 就把脱战时钟清零，下一次结算的延迟判定过不了，回血自然中断；
 * 客户端那边掉血是<b>立即</b>对齐的（见 {@code HealthHudOverlay}），所以视觉上不会有"还在涨"的错觉。
 * <p>
 * 监听器放在这个类而不是 {@link CombatTracker}，是因为 tracker 必须保持零 Minecraft 依赖才能单元测试。
 */
@Mod.EventBusSubscriber(modid = Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CombatRegenService {
    /** 血量比较的容差：float 血的累加会留下 1e-5 量级的尾巴，不留容差会多结算一次。 */
    private static final double HEALTH_EPSILON = 1.0E-4;

    private static final CombatTracker TRACKER = new CombatTracker();

    /** 兵种系统的注入点；null = 用配置里的基础档。 */
    private static volatile RegenProfileProvider profileProvider;

    private CombatRegenService() {
    }

    public static void setProfileProvider(@Nullable RegenProfileProvider provider) {
        profileProvider = provider;
    }

    // ===================================================================================
    // 生命周期
    // ===================================================================================

    /** 死亡：脱战计时与压制一起丢掉，免得下次复活还带着死亡前的状态。 */
    public static void onDeath(UUID playerId) {
        TRACKER.clear(playerId);
    }

    /** 复活 / 重新部署：延迟从复活这一刻重新起算，回血上限回到最大生命值。 */
    public static void onRespawn(ServerPlayer player) {
        TRACKER.markRespawn(player.getUUID(), player.server.getTickCount(), player.getMaxHealth());
    }

    // ===================================================================================
    // 动态回血上限（对外入口）
    // ===================================================================================

    /** 当前的自然回血上限。没记录时等同于最大生命值。 */
    public static double regenCeiling(ServerPlayer player) {
        return TRACKER.regenCeiling(player.getUUID(), player.getMaxHealth());
    }

    /** 写上限，会被钳进 [0, maxHealth]。 */
    public static void setRegenCeiling(ServerPlayer player, double ceiling) {
        TRACKER.setRegenCeiling(player.getUUID(), ceiling, player.getMaxHealth());
    }

    /** 重置成最大生命值（完整医疗包 / 管理员满血）。 */
    public static void resetRegenCeiling(ServerPlayer player) {
        setRegenCeiling(player, player.getMaxHealth());
    }

    /** 退出对局 / 掉线。 */
    public static void onLeave(UUID playerId) {
        TRACKER.clear(playerId);
    }

    public static void reset() {
        TRACKER.clearAll();
    }

    // ===================================================================================
    // 事件
    // ===================================================================================

    /**
     * 记录「最后一次实际受到伤害」。
     * <p>
     * 优先级 LOW：友伤取消发生在 {@code FriendlyFireHandler}（默认 NORMAL），LOW 在其之后执行，
     * 因此这里能拿到最终结果 —— 被取消的友伤不会重置脱战计时。
     * 自伤（摔落 / 出界 / {@code /damage}）算受伤，摔下去再爬上来不该白送一次满血。
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!Battlefield.isEnabled()) return;
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof ServerPlayer victim)) return;
        if (BattlefieldGameManager.getPlayerMatchId(victim.getUUID()) == null) return;
        TRACKER.markDamaged(victim.getUUID(), victim.server.getTickCount());
    }

    /**
     * 记录「这一次真实掉了多少血」，据此收紧回血上限。
     * <p>
     * <b>为什么挂在 {@link LivingDamageEvent} 而不是 {@link LivingHurtEvent}</b>：
     * {@code LivingHurtEvent} 里的数值还没过护甲、附魔、抗性、吸收盾，直接拿来算会把伤害算多；
     * {@code LivingDamageEvent} 是在 {@code LivingEntity#hurt} 里「护甲减伤 → 附魔减伤 → 扣吸收盾」
     * <b>全部结算完之后</b>、真正扣血之前触发的，它的 {@code amount} 就是这一下实际要掉的血。
     * 另有两点：它不可取消（不用担心友伤/无敌取消造成误记）；优先级取 LOWEST，
     * 别的模组在 NORMAL 里 {@code setAmount} 的结果我们已经看到了。
     * <p>
     * 唯一需要对不上的地方是「溢出伤害」：100 血挨 300 点，事件里是 300、实际损失只有 100，
     * 所以 {@link CombatTracker#applyDamageToCeiling} 里会按当前血量钳一次。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDamage(LivingDamageEvent event) {
        if (!Battlefield.isEnabled()) return;
        CombatConfig cfg = BattlefieldServerConfig.get().combat;
        if (!cfg.enableRegenCeiling) return;
        if (!(event.getEntity() instanceof ServerPlayer victim)) return;
        if (BattlefieldGameManager.getPlayerMatchId(victim.getUUID()) == null) return;

        TRACKER.applyDamageToCeiling(victim.getUUID(), victim.getHealth(), event.getAmount(),
                cfg.maxRecoverableDamage, victim.getMaxHealth());
    }

    /**
     * 可选：自己开火也重置脱战计时。默认关闭 —— 机枪手持续压制时若不能回血，支援兵会变成自虐兵种。
     * <p>
     * 恒订阅、内部早退，而不是按配置在启动时决定要不要注册：TACZ 的射击事件每发一颗子弹才一次，
     * 一次 volatile 读的代价远小于「改配置要重开服」。
     */
    @SubscribeEvent
    public static void onGunShoot(GunShootEvent event) {
        if (!BattlefieldServerConfig.get().combat.shootingBreaksRegen) return;
        if (event.getLogicalSide() != LogicalSide.SERVER) return;
        if (!(event.getShooter() instanceof ServerPlayer shooter)) return;
        TRACKER.markDamaged(shooter.getUUID(), shooter.server.getTickCount());
    }

    // ===================================================================================
    // 结算
    // ===================================================================================

    /**
     * 每 tick 调一次。
     *
     * @param participants 本局在线的参与者（复用外面已经算好的那一份列表，不要在这里再分配）
     * @param gameTime     {@code server.getTickCount()}
     * @param outsideArea  该玩家是否正在出界击杀倒计时里
     */
    public static void tick(List<ServerPlayer> participants, long gameTime, Predicate<UUID> outsideArea) {
        if (participants.isEmpty()) return;

        // 配置在循环外取一次快照：/bt reload 换的是整个 volatile 对象，
        // 循环里再读的话同一次结算可能跨越两份配置，算出来的血量会是两个档位拼起来的
        CombatConfig cfg = BattlefieldServerConfig.get().combat;
        if (!cfg.regenEnabled) return;

        RegenProfile base = baseProfile(cfg);
        RegenProfileProvider custom = profileProvider;
        boolean allowOutOfArea = cfg.regenOutOfArea;

        for (ServerPlayer sp : participants) {
            UUID id = sp.getUUID();

            // 压制先衰减再判定：这一 tick 刚好衰减完的玩家，本 tick 就该按「未被压制」结算
            double suppression = TRACKER.decaySuppression(id, gameTime, cfg.suppressionDecayPerSecond);
            boolean suppressed = suppression > 0.0;

            if (!sp.isAlive() || sp.isSpectator()) continue;
            if (!allowOutOfArea && outsideArea.test(id)) continue;

            RegenProfile profile = base;
            if (custom != null) {
                RegenProfile p = custom.profileFor(sp);
                if (p != null) profile = p;
            }

            float maxHealth = sp.getMaxHealth();
            if (maxHealth <= 0.0F) continue;
            // 两个上限取小：一个是兵种/状态档位允许回复到的百分比，一个是「这次掉的血能不能补回来」。
            // 后者才是战地式的伤情限制，前者留给兵种系统，两者可以同时生效。
            double ceiling = cfg.enableRegenCeiling ? TRACKER.regenCeiling(id, maxHealth) : maxHealth;
            double cap = Math.min(profile.capFor(maxHealth), ceiling);
            float health = sp.getHealth();
            if (health >= cap - HEALTH_EPSILON) continue;

            if (!TRACKER.isOutOfCombat(id, gameTime, profile.delayTicksFor(suppressed))) continue;

            CombatTracker.State state = TRACKER.state(id);
            if (gameTime < state.nextRegenTick()) continue;

            // 速率 = 单次量 × 倍率；调速率优先改「单次量」而不是「间隔」，
            // 拉长间隔会让血条跳变、缩短间隔只是更顺滑但同步包更密
            double heal = profile.healPerInterval() * (suppressed ? profile.suppressionRateMultiplier() : 1.0);
            if (!(heal > 0.0)) continue;

            // 用 setHealth 而不是 heal()：heal() 只在 maxHealth 处截断，
            // 而这里要截断的是「本档位允许回复的上限」（regenMaxHealthFraction），两者上限不是一个数
            float target = (float) Math.min(health + heal, cap);
            if (target <= health) continue;
            sp.setHealth(target);
            TRACKER.markRegen(id, gameTime + profile.intervalTicks());
        }
    }

    /** 配置 → 基础档位。每 tick 造一个 record，代价远小于一次 HashMap 查找。 */
    private static RegenProfile baseProfile(CombatConfig cfg) {
        return new RegenProfile(cfg.regenDelayTicks, cfg.regenIntervalTicks, cfg.regenHealPerInterval,
                cfg.maxHealthFraction, cfg.suppressionRateMultiplier, cfg.suppressionDelayBonusTicks);
    }
}
