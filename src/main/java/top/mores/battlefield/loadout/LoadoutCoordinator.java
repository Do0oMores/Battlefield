package top.mores.battlefield.loadout;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.*;
import net.minecraftforge.event.server.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.bridge.BFBackpackServiceBridge;
import top.mores.battlefield.game.BattlefieldGameManager;
import top.mores.battlefield.loadout.tacz.TaczResourceValidator;
import top.mores.battlefield.net.*;
import top.mores.battlefield.protocol.*;
import java.util.*;
import java.util.concurrent.*;

/** Prepare/confirm/commit, exclusively scheduling mutations on Forge main. Never waits on a Future. */
@Mod.EventBusSubscriber(modid = Battlefield.MODID)
public final class LoadoutCoordinator implements AutoCloseable {
    private static volatile LoadoutCoordinator instance;
    private final MinecraftServer server;
    private final DeploymentSettings settings;
    private final EquipmentFactory factory;
    private final TaczResourceValidator resources;
    private final BFBackpackServiceBridge bridge;
    private final DeploymentLedger ledger = new DeploymentLedger();
    private final Set<DeploymentLedger.Attempt> started = new HashSet<>();
    private final Set<UUID> committing = new HashSet<>();
    private LoadoutCoordinator(MinecraftServer server) {
        this.server = server; settings = DeploymentSettings.load(); factory = new EquipmentFactory(settings);
        resources = new TaczResourceValidator(server, factory); resources.refresh(); bridge = new BFBackpackServiceBridge(resources::validate); bridge.probe();
    }
    public static LoadoutCoordinator get() { return instance; }
    public String health() { return bridge.health() + "; tacz=" + resources.ready() + "; resourceGeneration=" + resources.generation(); }
    public BFBackpackServiceBridge bridge() { return bridge; }
    public static boolean committing(UUID player) { return instance != null && instance.committing.contains(player); }
    public static void cancel(UUID player, String reason) { if (instance != null) instance.ledger.forget(player, reason); }
    public static void failedCommit(UUID player, RuntimeException error) {
        if (instance != null) instance.ledger.fail(player, DeploymentLedger.Outcome.fail(error instanceof BridgeFailure typed ? typed.code() : "EQUIPMENT_APPLY_FAILED", error.getMessage()));
    }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void start(ServerStartedEvent e) { instance = new LoadoutCoordinator(e.getServer()); }
    @SubscribeEvent public static void stop(ServerStoppingEvent e) { LoadoutCoordinator old = instance; instance = null; if (old != null) old.close(); }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent e) { if (e.phase == TickEvent.Phase.END && instance != null && e.getServer().getTickCount() % 20 == 0) instance.bridge.probe(); }
    @SubscribeEvent public static void reload(AddReloadListenerEvent e) { if (instance != null) instance.resources.invalidate(); }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void synced(OnDatapackSyncEvent e) {
        if (instance != null && e.getPlayer() == null) { LoadoutCoordinator service = instance; service.server.execute(service.resources::refresh); }
    }
    public CompletableFuture<DeploymentLedger.Outcome> deploy(ServerPlayer player, String option, SoldierClass type, UUID request) {
        if (!server.isSameThread()) throw new IllegalStateException("Forge main required");
        BattlefieldGameManager.LoadoutPermit permit;
        DeploymentLedger.Attempt attempt;
        try {
            var prior = ledger.find(player.getUUID(), request, option + "/" + type.name());
            if (prior != null) return prior.result;
            permit = BattlefieldGameManager.prepareLoadout(player, option, type);
            attempt = ledger.begin(player.getUUID(), request, permit.matchId(), permit.life(), option + "/" + type.name());
        } catch (RuntimeException e) { return CompletableFuture.completedFuture(DeploymentLedger.Outcome.fail(e instanceof BridgeFailure typed ? typed.code() : "DEPLOYMENT_DENIED", e.getMessage())); }
        if (attempt.result.isDone() || !started.add(attempt)) return attempt.result;
        AsyncDeploymentFlow.run(server,
                () -> bridge.request(attempt.player, type, "deployment").thenApply(json -> LoadoutDtoAdapter.deployment(json, attempt.player, type)),
                () -> requireActive(attempt, permit),
                first -> {
                    if (!resources.ready()) throw new BridgeFailure("PROVIDER_UNAVAILABLE", "TaCZ resource index unavailable");
                    return new Staged(Map.copyOf(factory.create(first)), resources.generation());
                },
                prepared -> {
                    long age = System.currentTimeMillis() - prepared.confirmed().permissionCapturedAt();
                    if (!prepared.first().samePlan(prepared.confirmed())) throw new BridgeFailure("REVISION_CONFLICT", "配装准备期间已修改");
                    if (age < 0 || age > settings.maxPermissionAgeMs()) throw new BridgeFailure("PERMISSION_STALE", "最终权限快照已过期");
                    if (!resources.ready() || prepared.equipment().resourceEpoch() != resources.generation()) throw new BridgeFailure("PROVIDER_UNAVAILABLE", "TaCZ resources reloaded");
                    if (!ledger.claim(attempt)) throw new IllegalStateException("DEPLOYMENT_DUPLICATE");
                    committing.add(attempt.player);
                    try {
                        BattlefieldGameManager.commitLoadout(permit, prepared.equipment().items(), new EquipmentApplier());
                        ledger.complete(attempt, DeploymentLedger.Outcome.success());
                    } finally { committing.remove(attempt.player); }
                }).whenComplete((ignored, error) -> {
                    if (error != null) {
                        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                        Battlefield.LOGGER.warn("Deployment {} player={} match={} failed", attempt.request, attempt.player, attempt.match, cause);
                        ledger.complete(attempt, DeploymentLedger.Outcome.fail(cause instanceof BridgeFailure typed ? typed.code() : "DEPLOYMENT_FAILED", cause.getMessage() == null ? "部署失败" : cause.getMessage()));
                    }
                    server.execute(() -> started.remove(attempt));
                });
        return attempt.result;
    }
    private record Staged(Map<Integer, ItemStack> items, long resourceEpoch) {}
    private void requireActive(DeploymentLedger.Attempt attempt, BattlefieldGameManager.LoadoutPermit permit) {
        if (!ledger.active(attempt) || instance != this) throw new IllegalStateException("CANCELLED");
        BattlefieldGameManager.checkLoadoutPermit(permit);
    }
    public CompletableFuture<List<ItemStack>> preview(ServerPlayer player, SoldierClass type) {
        UUID id = player.getUUID(); String match = BattlefieldGameManager.getPlayerMatchId(id);
        if (match == null) return CompletableFuture.failedFuture(new IllegalStateException("NOT_IN_MATCH"));
        return bridge.request(id, type, "deployment").thenApply(json -> LoadoutDtoAdapter.deployment(json, id, type)).thenApplyAsync(dto -> {
            ServerPlayer current = server.getPlayerList().getPlayer(id);
            if (instance != this || current != player || !match.equals(BattlefieldGameManager.getPlayerMatchId(id))) throw new IllegalStateException("Preview session changed");
            List<ItemStack> result = new ArrayList<>(); for (EquipmentSlot slot : EquipmentSlot.values()) result.add(factory.createEquipment(dto.equipment().get(slot))); return result;
        }, server);
    }
    @Override public void close() { ledger.close(); bridge.close(); resources.close(); started.clear(); committing.clear(); }
}
