package top.mores.battlefield.game;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.mores.battlefield.Battlefield;
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

    private static final Map<UUID, Integer> SCORES = new HashMap<>();
    private static final Map<UUID, Integer> LAST_BONUS = new HashMap<>();
    private static final Map<UUID, Long> LAST_BONUS_TICK = new HashMap<>();
    private static final Map<UUID, Long> LAST_KILL_TICK = new HashMap<>();
    private static final Map<UUID, Map<String, KillWindow>> STREAK_SQUAD_KILLS = new HashMap<>();

    private record KillWindow(Set<UUID> killedVictims) {
    }

    public static void reset() {
        SCORES.clear();
        LAST_BONUS.clear();
        LAST_BONUS_TICK.clear();
        LAST_KILL_TICK.clear();
        STREAK_SQUAD_KILLS.clear();
    }

    public static int getScore(UUID playerId) {
        return SCORES.getOrDefault(playerId, 0);
    }

    public static int getLastBonus(UUID playerId, long nowTick) {
        long t = LAST_BONUS_TICK.getOrDefault(playerId, -9999L);
        if (nowTick - t > 40) return 0;
        return LAST_BONUS.getOrDefault(playerId, 0);
    }

    public static void addCaptureScore(ServerPlayer player, int score) {
        addScore(player.getUUID(), score, player.serverLevel().getGameTime());
    }

    private static void addScore(UUID playerId, int score, long nowTick) {
        if (score <= 0) return;
        SCORES.put(playerId, SCORES.getOrDefault(playerId, 0) + score);
        LAST_BONUS.put(playerId, score);
        LAST_BONUS_TICK.put(playerId, nowTick);
    }

    public static void onBombDamage(ServerLevel level, UUID owner, UUID victim, float damage) {
        if (damage <= 0) return;
        if (owner.equals(victim)) return;

        ServerPlayer ownerPlayer = level.getPlayerByUUID(owner);
        ServerPlayer victimPlayer = level.getPlayerByUUID(victim);
        if (ownerPlayer == null || victimPlayer == null) return;
        if (TeamManager.isSameTeam(ownerPlayer, victimPlayer)) return;

        int rounded = Math.max(0, Math.round(damage));
        addScore(owner, rounded, level.getGameTime());
    }

    public static void onBombKill(ServerLevel level, UUID owner, UUID victim) {
        ServerPlayer ownerPlayer = level.getPlayerByUUID(owner);
        ServerPlayer victimPlayer = level.getPlayerByUUID(victim);
        if (ownerPlayer == null || victimPlayer == null) return;
        if (TeamManager.isSameTeam(ownerPlayer, victimPlayer)) return;

        onKill(ownerPlayer, victimPlayer);
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer attacker)) return;
        if (TeamManager.isSameTeam(attacker, victim)) return;

        int rounded = Math.max(0, Math.round(event.getAmount()));
        addScore(attacker.getUUID(), rounded, attacker.serverLevel().getGameTime());
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

        long lastKill = LAST_KILL_TICK.getOrDefault(attackerId, Long.MIN_VALUE / 4);
        if (now - lastKill <= STREAK_WINDOW_TICKS) {
            addScore(attackerId, STREAK_KILL_BONUS, now);
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
            addScore(attackerId, SQUAD_WIPE_BONUS, now);
            map.remove(key);
        }
    }
}
