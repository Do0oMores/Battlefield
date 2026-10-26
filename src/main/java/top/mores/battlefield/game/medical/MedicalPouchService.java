package top.mores.battlefield.game.medical;

import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ReloadState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.config.BattlefieldServerConfig;
import top.mores.battlefield.game.BattlefieldGameManager;
import top.mores.battlefield.game.combat.CombatRegenService;
import top.mores.battlefield.game.combat.PlayerHealthService;
import top.mores.battlefield.net.BattlefieldNet;
import top.mores.battlefield.net.S2CMedicalState;
import top.mores.battlefield.server.entity.MedicalPouchEntity;
import top.mores.battlefield.team.TeamManager;
import top.mores.battlefield.team.TeamSavedData;
import java.util.*;

@Mod.EventBusSubscriber(modid = Battlefield.MODID)
public final class MedicalPouchService {
    private MedicalPouchService() {}
    // Only temporary action/equipment data; authoritative reserves live in TeamSavedData.
    private record Equipment(int slot, ItemStack actual, ItemStack offActual, ItemStack main, ItemStack off, String dimension) {
        static Equipment capture(ServerPlayer p) {
            return new Equipment(p.getInventory().selected, p.getMainHandItem(), p.getOffhandItem(), p.getMainHandItem().copy(),
                    p.getOffhandItem().copy(), p.level().dimension().location().toString());
        }
        private static boolean sameWeapon(ItemStack snapshot, ItemStack current) {
            if (snapshot.getItem() != current.getItem()) return false;
            var gun = com.tacz.guns.api.item.IGun.getIGunOrNull(current);
            // Heat/cooldown/ammo NBT may legitimately change without the player changing equipment.
            return gun == null || (Objects.equals(gun.getGunId(snapshot),gun.getGunId(current))
                    && Objects.equals(gun.getGunDisplayId(snapshot),gun.getGunDisplayId(current)));
        }
        boolean matches(ServerPlayer p) {
            return slot == p.getInventory().selected && actual == p.getMainHandItem()
                    && offActual == p.getOffhandItem()
                    && sameWeapon(main, p.getMainHandItem()) && sameWeapon(off, p.getOffhandItem())
                    && dimension.equals(p.level().dimension().location().toString());
        }
    }
    private static final Map<UUID, Equipment> EQUIPMENT = new HashMap<>();
    private record Flight(UUID entity, long nextThrow) {}
    private static final Map<UUID, Flight> FLIGHTS = new HashMap<>();
    /** 正在逐步回血的窗口。临时数据，和动作、装备快照一样不进存档。 */
    private record Drip(UUID life, long end, float perTick) {}
    private static final Map<UUID, Drip> DRIPS = new HashMap<>();

    public static MedicalState state(ServerPlayer p) { return TeamSavedData.get(p.serverLevel()).medical(p.getUUID()); }
    public static boolean eligible(ServerPlayer p) {
        if (!Battlefield.isEnabled() || !p.isAlive() || p.isSpectator()
                || !BattlefieldGameManager.isMedicalDeploymentValid(p)) return false;
        var s = state(p);
        return s.deployed && s.match.equals(BattlefieldGameManager.getPlayerMatchId(p.getUUID()))
                && s.team.equals(TeamManager.getTeam(p).name());
    }
    public static boolean locked(LivingEntity entity) {
        return entity instanceof ServerPlayer p && state(p).action() != null;
    }
    public static void deployed(ServerPlayer p, MedicalState.Role role) {
        destroyFlights(p);
        cancel(p);
        state(p).deploy(role, BattlefieldGameManager.getPlayerMatchId(p.getUUID()), TeamManager.getTeam(p).name());
        sync(p);
    }
    public static void invalidate(ServerPlayer p) {
        destroyFlights(p); DRIPS.remove(p.getUUID());
        cancel(p); state(p).invalidate(); sync(p);
    }
    public static void cancel(ServerPlayer p) {
        var s = state(p);
        EQUIPMENT.remove(p.getUUID());
        if (s.action() != null) { s.cancel(); sync(p); }
    }
    public static void sync(ServerPlayer p) {
        var s = state(p); var a = s.action();
        TeamSavedData.get(p.serverLevel()).setDirty();
        BattlefieldNet.sendToPlayer(p, new S2CMedicalState(s.life, s.revision(), eligible(p), s.medic(), s.reserve(),
                a == null ? 0 : a.id(), a == null ? 0 : (int)(p.server.getTickCount() - a.start()),
                a == null ? 0 : (int)(a.end() - a.start())));
    }
    public static boolean needsCare(ServerPlayer p) {
        return p.getHealth() < p.getMaxHealth() - 1.0E-4
                || CombatRegenService.regenCeiling(p) < p.getMaxHealth() - 1.0E-4;
    }
    private static boolean busy(ServerPlayer p) {
        var op = IGunOperator.fromLivingEntity(p);
        var data = op.getDataHolder();
        return data.reloadStateType != ReloadState.StateType.NOT_RELOADING
                || op.getSynReloadState().getStateType() != ReloadState.StateType.NOT_RELOADING
                || data.isBolting || op.getSynIsBolting()
                || new com.tacz.guns.entity.shooter.LivingEntityDrawGun(p,data).getDrawCoolDown() > 0
                || op.getSynDrawCoolDown() > 0 || op.getSynMeleeCoolDown() > 0
                || data.meleePrepTickCount > 0 || p.isUsingItem() || p.isSleeping();
    }
    public static void use(ServerPlayer p) {
        if (!eligible(p) || busy(p)) return;
        var a = state(p).begin(p.server.getTickCount(), BattlefieldServerConfig.get().medical.useTicks(), needsCare(p));
        if (a == null) return;
        EQUIPMENT.put(p.getUUID(), Equipment.capture(p));
        IGunOperator.fromLivingEntity(p).aim(false);
        sync(p);
    }
    /** END/LOWEST: world damage and the existing regen pass precede the settlement. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        for (var p : e.getServer().getPlayerList().getPlayers()) {
            var s = state(p); var a = s.action();
            if (s.deployed && (!s.match.equals(BattlefieldGameManager.getPlayerMatchId(p.getUUID()))
                    || !s.team.equals(TeamManager.getTeam(p).name()))) { invalidate(p); continue; }
            // 逐步回血要在「动作已结束」之后继续跑，所以必须放在 action == null 的提前 return 之前
            tickDrip(p, s);
            if (a == null) continue;
            var equipment = EQUIPMENT.get(p.getUUID());
            if (!eligible(p) || !s.life.equals(a.life()) || equipment == null || !equipment.matches(p)) { cancel(p); continue; }
            if (e.getServer().getTickCount() < a.end()) continue;
            boolean heal = s.complete(a, e.getServer().getTickCount(), eligible(p) && !busy(p), needsCare(p));
            EQUIPMENT.remove(p.getUUID());
            // 只结算「消耗 + 开始回血」，血量本身留到后面几秒慢慢补
            if (heal) startDrip(p);
            sync(p);
        }
        FLIGHTS.entrySet().removeIf(entry -> {
            var f = entry.getValue();
            if (e.getServer().getTickCount() < f.nextThrow) return false;
            for (var level : e.getServer().getAllLevels()) if (level.getEntity(f.entity) != null) return false;
            return true;
        });
    }

    /**
     * 起一个逐步回血的窗口：缺口在 {@code medicalHealTicks} 内匀速补满。
     * <p>
     * 为什么不直接在结算时 {@code healPlayer(max, true)}：那样血量是一次跳变，
     * 看不出来「正在被治疗」，也没法和自然回血、伤害反馈共存。
     * 速率按最大生命值算（100 血 / 40 tick = 2.5 血/tick ≈ 50 HP/s），缺口小就先补满再提前收尾。
     * <p>
     * 每 tick 走 {@code healPlayer(step, false)}（普通治疗），它会把回血上限抬到当时的血量 ——
     * 于是自然回血在窗口内被自己的上限挡住（{@code health >= cap}），不会出现两套回血叠加；
     * 上限只在窗口收尾时用 {@code resetRegenCeiling} 一次性解开。
     */
    private static void startDrip(ServerPlayer p) {
        if (p.getMaxHealth() - p.getHealth() <= 1.0E-4F) {
            // 满血但带着永久伤：没有血要补，直接把上限解开就好
            CombatRegenService.resetRegenCeiling(p);
            return;
        }
        int ticks = Math.max(1, BattlefieldServerConfig.get().medical.healTicks());
        DRIPS.put(p.getUUID(), new Drip(state(p).life, p.server.getTickCount() + ticks, p.getMaxHealth() / ticks));
    }

    private static void tickDrip(ServerPlayer p, MedicalState s) {
        var d = DRIPS.get(p.getUUID());
        if (d == null) return;
        // 换命 / 死亡 / 离局：直接停，不补上限 —— 这条命已经轮不到这个医疗包了
        if (!d.life().equals(s.life) || !p.isAlive() || !eligible(p)) { DRIPS.remove(p.getUUID()); return; }

        float max = p.getMaxHealth();
        float missing = max - p.getHealth();
        if (missing > 1.0E-4F) PlayerHealthService.healPlayer(p, Math.min(d.perTick(), missing), false);

        if (p.getHealth() >= max - 1.0E-4F || p.server.getTickCount() >= d.end()) {
            DRIPS.remove(p.getUUID());
            CombatRegenService.resetRegenCeiling(p);
        }
    }
    public static boolean receiver(ServerPlayer sender, ServerPlayer target) {
        return sender != target && eligible(sender) && state(sender).medic() && eligible(target)
                && !state(target).medic() && state(target).reserve() == 0
                && sender.level() == target.level() && TeamManager.isSameTeam(sender, target);
    }
    public static boolean loadedSegment(net.minecraft.world.level.Level level, Vec3 from, Vec3 to) {
        int minX = net.minecraft.util.Mth.floor(Math.min(from.x,to.x)) >> 4;
        int maxX = net.minecraft.util.Mth.floor(Math.max(from.x,to.x)) >> 4;
        int minZ = net.minecraft.util.Mth.floor(Math.min(from.z,to.z)) >> 4;
        int maxZ = net.minecraft.util.Mth.floor(Math.max(from.z,to.z)) >> 4;
        for (int x=minX;x<=maxX;x++) for (int z=minZ;z<=maxZ;z++) if (!level.hasChunk(x,z)) return false;
        return true;
    }
    public static boolean clearPath(ServerPlayer p, Vec3 from, Vec3 to) {
        if (!loadedSegment(p.level(),from,to)) return false;
        return p.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() == HitResult.Type.MISS;
    }
    public static void distribute(ServerPlayer p) {
        if (!eligible(p) || !state(p).medic() || locked(p) || busy(p)) return;
        long now = p.server.getTickCount(); var existing = FLIGHTS.get(p.getUUID());
        if (existing != null) {
            if (now < existing.nextThrow) return;
            for (var level : p.server.getAllLevels()) if (level.getEntity(existing.entity) != null) return;
        }
        var cfg = BattlefieldServerConfig.get().medical;
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        Vec3 start = eye.add(look.scale(0.45)).add(0, -0.15, 0);
        if (!clearPath(p, eye, start)) return;
        double minDot = Math.cos(Math.toRadians(cfg.aimDegrees()));
        var targets = new ArrayList<ServerPlayer>();
        for (var target : BattlefieldGameManager.participantsOf(p)) {
            Vec3 delta = target.getBoundingBox().getCenter().subtract(eye);
            if (receiver(p, target) && delta.lengthSqr() <= cfg.range()*cfg.range() && delta.lengthSqr() > 0.01
                    && look.dot(delta.normalize()) >= minDot && clearPath(p, start, target.getBoundingBox().getCenter())) targets.add(target);
        }
        targets.sort(Comparator.<ServerPlayer>comparingDouble(t -> -look.dot(t.getBoundingBox().getCenter().subtract(eye).normalize()))
                .thenComparingDouble(p::distanceToSqr).thenComparing(t -> t.getUUID().toString()));
        if (targets.isEmpty()) return;
        var entity = new MedicalPouchEntity(p.serverLevel(), p, targets.get(0), start);
        if (p.serverLevel().addFreshEntity(entity)) FLIGHTS.put(p.getUUID(), new Flight(entity.getUUID(), now + cfg.throwCooldownTicks()));
    }
    public static boolean supply(ServerPlayer sender, ServerPlayer target, UUID senderLife, UUID targetLife) {
        if (!receiver(sender, target) || !state(sender).life.equals(senderLife)) return false;
        boolean received = state(target).receive(targetLife);
        if (received) sync(target);
        return received;
    }
    public static void destroyFlights(ServerPlayer p) {
        for (var level : p.server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (entity instanceof MedicalPouchEntity pouch && pouch.involves(p.getUUID())) pouch.discard();
            }
        }
    }
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) { destroyFlights(p); DRIPS.remove(p.getUUID()); cancel(p); sync(p); }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) { destroyFlights(p); DRIPS.remove(p.getUUID()); cancel(p); }
    }
    @SubscribeEvent public static void clonePlayer(PlayerEvent.Clone e) {
        if (e.getEntity() instanceof ServerPlayer p) { if (e.isWasDeath()) invalidate(p); else { destroyFlights(p); cancel(p); } }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void death(LivingDeathEvent e) {
        if (!e.isCanceled() && e.getEntity() instanceof ServerPlayer p) invalidate(p);
    }
    @SubscribeEvent public static void attack(AttackEntityEvent e) { if (locked(e.getEntity())) e.setCanceled(true); }
    @SubscribeEvent public static void useItem(PlayerInteractEvent.RightClickItem e) { if (locked(e.getEntity())) e.setCanceled(true); }
    @SubscribeEvent public static void useBlock(PlayerInteractEvent.RightClickBlock e) { if (locked(e.getEntity())) e.setCanceled(true); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent e) { EQUIPMENT.clear(); FLIGHTS.clear(); DRIPS.clear(); }
}
