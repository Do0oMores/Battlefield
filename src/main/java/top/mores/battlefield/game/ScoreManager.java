package top.mores.battlefield.game;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.net.BattlefieldNet;
import top.mores.battlefield.net.S2CScoreToastPacket;
import top.mores.battlefield.team.SquadManager;
import top.mores.battlefield.team.TeamId;
import top.mores.battlefield.team.TeamManager;

import java.util.*;

@Mod.EventBusSubscriber(modid = Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ScoreManager {
    private ScoreManager() {
    }

    private static final int STREAK_WINDOW_TICKS = 15 * 20;
    private static final int STREAK_KILL_BONUS = 50;
    private static final int SQUAD_WIPE_BONUS = 200;

    // ===== 分数与旧HUD兼容字段 =====
    private static final Map<UUID, Integer> SCORES = new HashMap<>();
    private static final Map<UUID, Integer> LAST_BONUS = new HashMap<>();
    private static final Map<UUID, Long> LAST_BONUS_TICK = new HashMap<>();

    // ===== 连杀/团灭判定 =====
    private static final Map<UUID, Long> LAST_KILL_TICK = new HashMap<>();
    private static final Map<UUID, Map<String, KillWindow>> STREAK_SQUAD_KILLS = new HashMap<>();

    private record KillWindow(Set<UUID> killedVictims) {
    }

    // ===== Toast 并行合并 =====
    private static final int TOAST_MERGE_WINDOW_TICKS = 6;   // 同 reason 6 tick 内合并
    private static final int TOAST_FLUSH_AFTER_TICKS = 12;
    private static final Map<UUID, Float> PRE_HURT_HEALTH = new HashMap<>();
    private static final Map<UUID, Long> PRE_HURT_TICK = new HashMap<>();

    private static final Map<UUID, EnumMap<ScoreReason, PendingToast>> PENDING_TOASTS = new HashMap<>();

    private static final class PendingToast {
        int amount;
        long lastTick;

        PendingToast(int amount, long lastTick) {
            this.amount = amount;
            this.lastTick = lastTick;
        }
    }

    public static void reset() {
        SCORES.clear();
        LAST_BONUS.clear();
        LAST_BONUS_TICK.clear();
        LAST_KILL_TICK.clear();
        STREAK_SQUAD_KILLS.clear();
        PENDING_TOASTS.clear();
    }


    public static void clearPlayer(UUID playerId) {
        SCORES.remove(playerId);
        LAST_BONUS.remove(playerId);
        LAST_BONUS_TICK.remove(playerId);
        LAST_KILL_TICK.remove(playerId);
        STREAK_SQUAD_KILLS.remove(playerId);
        PENDING_TOASTS.remove(playerId);
    }

    public static int getScore(UUID playerId) {
        return SCORES.getOrDefault(playerId, 0);
    }

    /**
     * 旧HUD用：最近 40 tick 内最后一次加分
     */
    public static int getLastBonus(UUID playerId, long nowTick) {
        long t = LAST_BONUS_TICK.getOrDefault(playerId, -9999L);
        if (nowTick - t > 40) return 0;
        return LAST_BONUS.getOrDefault(playerId, 0);
    }

    public static void addCaptureScore(ServerPlayer player, int score) {
        addScore(player, score, ScoreReason.CAPTURE);
    }

    public static void addDefendScore(ServerPlayer player, int score) {
        addScore(player, score, ScoreReason.DEFEND);
    }

    public static void onBombDamage(ServerLevel level, UUID owner, UUID victim, float damage) {
        if (damage <= 0) return;
        if (owner.equals(victim)) return;

        ServerPlayer ownerPlayer = level.getServer().getPlayerList().getPlayer(owner);
        ServerPlayer victimPlayer = level.getServer().getPlayerList().getPlayer(victim);
        if (ownerPlayer == null || victimPlayer == null) return;
        if (TeamManager.isSameTeam(ownerPlayer, victimPlayer)) return;

        int rounded = Math.max(0, Math.round(damage));
        addScore(ownerPlayer, rounded, ScoreReason.DAMAGE);
    }

    public static void onBombKill(ServerLevel level, UUID owner, UUID victim) {
        ServerPlayer ownerPlayer = level.getServer().getPlayerList().getPlayer(owner);
        ServerPlayer victimPlayer = level.getServer().getPlayerList().getPlayer(victim);
        if (ownerPlayer == null || victimPlayer == null) return;
        if (TeamManager.isSameTeam(ownerPlayer, victimPlayer)) return;

        onKill(ownerPlayer, victimPlayer);
    }

    @SubscribeEvent
    public static void onLivingDamage(net.minecraftforge.event.entity.living.LivingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer attacker)) return;
        if (TeamManager.isSameTeam(attacker, victim)) return;

        long now = victim.serverLevel().getGameTime();
        UUID vid = victim.getUUID();

        // 获取玩家的快照时间和生命值，只需要在事件触发时获取一次
        Float previousHealth = PRE_HURT_HEALTH.get(vid);
        Long lastHurtTick = PRE_HURT_TICK.get(vid);

        // 检查是否是同tick的快照，如果不是则跳过
        if (lastHurtTick == null || lastHurtTick != now) {
            PRE_HURT_TICK.put(vid, now);
            PRE_HURT_HEALTH.put(vid, victim.getHealth());
            return; // 不是同一个tick，不进行计算
        }

        // 获取实际的伤害量和玩家当前剩余生命值
        float realDamage = event.getAmount();
        float remainingHealth = victim.getHealth();

        // 如果伤害小于等于0，直接跳过
        if (realDamage <= 0.0f) return;

        // 最后一次伤害处理：如果伤害大于玩家剩余生命，则将伤害值设置为玩家剩余生命
        if (realDamage > remainingHealth) {
            realDamage = remainingHealth; // 将伤害值修正为剩余生命值
        }

        // 防止任何异常导致超大
        float maxHp = victim.getMaxHealth();
        int realDamageScore = Math.min(Math.round(realDamage), Math.round(maxHp));

        // 清理缓存并加分
        PRE_HURT_HEALTH.remove(vid);
        PRE_HURT_TICK.remove(vid);
        addScore(attacker, realDamageScore, ScoreReason.DAMAGE);
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer attacker)) return;
        if (TeamManager.isSameTeam(attacker, victim)) return;

        onKill(attacker, victim);
    }

    private static void onKill(ServerPlayer attacker, ServerPlayer victim) {
        long now = attacker.serverLevel().getGameTime();
        UUID attackerId = attacker.getUUID();

        // 击杀基础分（你可以按需调整）
        addScore(attacker, 100, ScoreReason.KILL);

        long lastKill = LAST_KILL_TICK.getOrDefault(attackerId, Long.MIN_VALUE / 4);
        if (now - lastKill <= STREAK_WINDOW_TICKS) {
            addScore(attacker, STREAK_KILL_BONUS, ScoreReason.STREAK);
        } else {
            STREAK_SQUAD_KILLS.remove(attackerId);
        }
        LAST_KILL_TICK.put(attackerId, now);

        TeamId victimTeam = TeamManager.getTeam(victim);
        int victimSquad = SquadManager.getSquad(victim);
        if (victimTeam == TeamId.SPECTATOR || victimSquad <= 0) return;

        String key = victimTeam.name() + "-" + victimSquad;
        Map<String, KillWindow> map = STREAK_SQUAD_KILLS.computeIfAbsent(attackerId, k -> new HashMap<>());
        KillWindow win = map.computeIfAbsent(key, k -> new KillWindow(new HashSet<>()));
        win.killedVictims().add(victim.getUUID());

        if (win.killedVictims().size() >= SquadManager.SQUAD_CAP) {
            addScore(attacker, SQUAD_WIPE_BONUS, ScoreReason.SQUAD_WIPE);
            map.remove(key);
        }
    }

    /**
     * 核心：加分 + 并行合并 toast（不立刻发，交给 tick flush）
     */
    private static void addScore(ServerPlayer player, int score, ScoreReason reason) {
        if (score <= 0) return;

        ServerLevel level = player.serverLevel();
        long nowTick = level.getGameTime();
        UUID playerId = player.getUUID();

        // 1) 总分累计
        SCORES.put(playerId, SCORES.getOrDefault(playerId, 0) + score);

        // 2) 旧HUD兼容：记录“最后一次加分”
        LAST_BONUS.put(playerId, score);
        LAST_BONUS_TICK.put(playerId, nowTick);

        // 3) 并行 pending：UUID -> EnumMap<reason, PendingToast>
        EnumMap<ScoreReason, PendingToast> map =
                PENDING_TOASTS.computeIfAbsent(playerId, k -> new EnumMap<>(ScoreReason.class));

        PendingToast p = map.get(reason);
        if (p != null && (nowTick - p.lastTick) <= TOAST_MERGE_WINDOW_TICKS) {
            p.amount += score;
            p.lastTick = nowTick;
        } else {
            // 如果同 reason 存在但超窗：先把旧的发掉，再开新的
            if (p != null) {
                sendToast(level, player, p.amount, reason);
            }
            map.put(reason, new PendingToast(score, nowTick));
        }
    }

    /**
     * 定时 flush：超过阈值未更新就发给客户端
     */
    @SubscribeEvent
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent e) {
        if (e.phase != net.minecraftforge.event.TickEvent.Phase.END) return;

        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long nowTick = server.getTickCount();

        Iterator<Map.Entry<UUID, EnumMap<ScoreReason, PendingToast>>> it = PENDING_TOASTS.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            UUID playerId = entry.getKey();
            EnumMap<ScoreReason, PendingToast> map = entry.getValue();

            ServerPlayer sp = server.getPlayerList().getPlayer(playerId);
            if (sp == null) {
                it.remove();
                continue;
            }

            ServerLevel level = sp.serverLevel();
            Iterator<Map.Entry<ScoreReason, PendingToast>> it2 = map.entrySet().iterator();
            while (it2.hasNext()) {
                var e2 = it2.next();
                ScoreReason reason = e2.getKey();
                PendingToast p = e2.getValue();

                // 注意：pending 里 lastTick 用的是 level.getGameTime()；这里用 serverTickCount
                // 两者都每tick+1（同一 server），差一个常数不影响 dt 判断
                if ((nowTick - p.lastTick) >= TOAST_FLUSH_AFTER_TICKS) {
                    sendToast(level, sp, p.amount, reason);
                    it2.remove();
                }
            }

            if (map.isEmpty()) it.remove();
        }
    }

    private static void sendToast(ServerLevel level, ServerPlayer sp, int amount, ScoreReason reason) {
        if (amount <= 0) return;
        BattlefieldNet.sendToPlayer(sp, new S2CScoreToastPacket(amount, reason.name()));
    }
}
