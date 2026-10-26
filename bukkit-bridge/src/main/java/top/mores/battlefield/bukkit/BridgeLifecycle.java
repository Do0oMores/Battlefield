package top.mores.battlefield.bukkit;

import com.google.gson.*;
import org.bukkit.Bukkit;
import org.bukkit.event.*;
import org.bukkit.event.server.*;
import org.bukkit.plugin.java.JavaPlugin;
import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.backpack.BackpackType;
import top.mores.battlefield.protocol.BridgeProtocol;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static top.mores.battlefield.protocol.BridgeProtocol.*;

/** Bukkit-owned API loader. The Forge endpoint crosses the boundary using JDK Function/Future/String. */
public final class BridgeLifecycle extends JavaPlugin implements Listener {
    private volatile BFBackpackAPI api;
    private volatile Function<String, CompletableFuture<String>> endpoint;
    private volatile ValidationRegistration registration;
    private volatile long generation;
    private final Set<CompletableFuture<String>> pending = ConcurrentHashMap.newKeySet();
    @Override public void onEnable() {
        try {
            requireVersions(BFBackpackAPI.class.getField("API_VERSION").getInt(null), BFBackpackAPI.class.getField("PROTOCOL_VERSION").getInt(null), VERSION);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("API_INCOMPATIBLE", e); }
        getServer().getPluginManager().registerEvents(this, this); discover();
    }
    private void discover() {
        BFBackpackAPI found = getServer().getServicesManager().load(BFBackpackAPI.class);
        api = found != null && found.apiVersion() == API ? found : null;
        register();
    }
    @EventHandler public void registered(ServiceRegisterEvent e) { if (e.getProvider().getService() == BFBackpackAPI.class) discover(); }
    @EventHandler public void unregistered(ServiceUnregisterEvent e) {
        if (e.getProvider().getService() == BFBackpackAPI.class && e.getProvider().getProvider() == api) {
            release(); api = null; failPending("NOT_READY", "BFBackpack service unregistered");
        }
    }
    /** Only a trusted in-process server endpoint can supply this JDK callback. Must run on Bukkit main. */
    public long connect(int bridgeProtocol, Function<String, CompletableFuture<String>> validator) {
        main(); if (bridgeProtocol != VERSION) throw new IllegalArgumentException("API_INCOMPATIBLE");
        release(); endpoint = Objects.requireNonNull(validator); generation++; register(); return generation;
    }
    public void disconnect(long expectedGeneration) {
        main(); if (generation != expectedGeneration) return;
        release(); endpoint = null; generation++; failPending("NOT_READY", "Forge endpoint disconnected");
    }
    private void register() {
        BFBackpackAPI backend = api; Function<String, CompletableFuture<String>> target = endpoint;
        if (backend == null || target == null) return;
        release(); registration = backend.registerValidators("battlefield-bridge", new Provider(backend, target));
    }
    public String health() {
        JsonObject state = new JsonObject(); state.addProperty("bridgeProtocol", VERSION); state.addProperty("apiVersion", API);
        state.addProperty("backendProtocol", BACKEND_PROTOCOL); state.addProperty("generation", generation);
        state.addProperty("ready", api != null && registration != null && registration.active());
        return JSON.toJson(state);
    }
    /** Trusted server RPC allowlist. Never exposes grant/revoke or SQL. */
    public CompletableFuture<String> request(String json) {
        main(); BFBackpackAPI backend = api; long acceptedGeneration = generation;
        ValidationRegistration acceptedRegistration = registration;
        if (backend == null || registration == null || !registration.active()) return CompletableFuture.completedFuture(failure("NOT_READY", "Bridge/backend not ready"));
        if (pending.size() >= 128) return CompletableFuture.completedFuture(failure("VALIDATION_BUSY", "Bridge request limit"));
        try {
            JsonObject input = parse(json);
            if (number(input, "bridgeProtocol") != VERSION) return CompletableFuture.completedFuture(failure("API_INCOMPATIBLE", "Bridge protocol mismatch"));
            if (number(input, "connectionGeneration") != acceptedGeneration) return CompletableFuture.completedFuture(failure("NOT_READY", "Forge connection changed"));
            UUID player = UUID.fromString(text(input, "player")); String op = text(input, "operation");
            CompletableFuture<? extends LoadoutResult<?>> task;
            switch (op) {
                case "deployment" -> task = backend.getDeploymentLoadout(player, BackpackType.valueOf(text(input, "soldierClass")));
                case "loadout" -> task = backend.getLoadout(player, BackpackType.valueOf(text(input, "soldierClass")));
                case "catalog" -> task = backend.getCatalog(player, BackpackType.valueOf(text(input, "soldierClass")));
                case "attachments" -> task = backend.getAttachmentCatalog(player, id(text(input, "weaponId")));
                case "customization" -> task = backend.getWeaponCustomization(player, id(text(input, "weaponId")));
                case "selectEquipment" -> task = backend.selectEquipment(player, BackpackType.valueOf(text(input, "soldierClass")), LoadoutSlot.valueOf(text(input, "slot")), id(text(input, "itemId")), number(input, "revision"));
                case "selectAttachment" -> task = backend.selectAttachment(player, id(text(input, "weaponId")), text(input, "slot"), id(text(input, "itemId")), number(input, "revision"));
                case "removeAttachment" -> task = backend.removeAttachment(player, id(text(input, "weaponId")), text(input, "slot"), number(input, "revision"));
                default -> { return CompletableFuture.completedFuture(failure("INVALID_INPUT", "Operation is not exposed")); }
            }
            CompletableFuture<String> result = new CompletableFuture<>(); pending.add(result);
            result.orTimeout(5, TimeUnit.SECONDS).whenComplete((v, e) -> pending.remove(result));
            task.whenComplete((value, error) -> {
                if (acceptedGeneration != generation || backend != api || acceptedRegistration != registration || !acceptedRegistration.active()) result.complete(failure("NOT_READY", "Bridge generation changed"));
                else if (error != null) { getLogger().log(java.util.logging.Level.WARNING, "Bridge request failed", error); result.complete(failure("PROVIDER_UNAVAILABLE", "Bridge request failed")); }
                else result.complete(JSON.toJson(value));
            });
            return result.exceptionally(e -> failure("VALIDATION_TIMEOUT", "Bridge request timed out/cancelled"));
        } catch (RuntimeException e) { return CompletableFuture.completedFuture(failure("INVALID_INPUT", "Invalid bridge input")); }
    }
    private static void main() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Bukkit main thread required"); }
    private void release() { var old = registration; registration = null; if (old != null) old.close(); }
    private void failPending(String code, String message) { pending.forEach(f -> f.complete(failure(code, message))); pending.clear(); }
    @Override public void onDisable() { release(); api = null; endpoint = null; generation++; failPending("NOT_READY", "Bridge disabled"); }

    private final class Provider implements ValidationProvider {
        private final BFBackpackAPI backend;
        private final Function<String, CompletableFuture<String>> target;
        Provider(BFBackpackAPI backend, Function<String, CompletableFuture<String>> target) { this.backend = backend; this.target = target; }
        @Override public CompletableFuture<CompatibilityInfo> validateWeapon(WeaponDefinition weapon) { return validate("weapon", weapon, null, Map.of()); }
        @Override public CompletableFuture<CompatibilityInfo> validateAttachments(WeaponDefinition weapon, WeaponCustomization plan) {
            return backend.getAttachmentCatalog(plan.playerId(), weapon.id()).thenCompose(catalog -> {
                if (!catalog.successful()) return CompletableFuture.completedFuture(CompatibilityInfo.rejected(catalog.code(), "Attachment catalog unavailable"));
                Map<String, AttachmentDefinition> definitions = new HashMap<>();
                for (var selected : plan.attachments().entrySet()) {
                    AttachmentCatalogEntry entry = catalog.value().stream().filter(a -> a.attachment().id().equals(selected.getValue())).findFirst().orElse(null);
                    if (entry == null) return CompletableFuture.completedFuture(CompatibilityInfo.rejected(ResultCode.RESOURCE_NOT_FOUND, "Attachment absent"));
                    if (!entry.unlock().unlocked()) return CompletableFuture.completedFuture(CompatibilityInfo.rejected(ResultCode.LOCKED, "Attachment locked"));
                    definitions.put(selected.getKey(), entry.attachment());
                }
                return validate("attachments", weapon, plan, definitions);
            });
        }
        private CompletableFuture<CompatibilityInfo> validate(String operation, WeaponDefinition weapon, WeaponCustomization plan, Map<String, AttachmentDefinition> definitions) {
            JsonObject request = new JsonObject(); request.addProperty("bridgeProtocol", VERSION); request.addProperty("operation", operation);
            request.add("weapon", JSON.toJsonTree(weapon));
            if (plan != null) { request.add("customization", JSON.toJsonTree(plan)); request.add("attachmentDefinitions", JSON.toJsonTree(definitions)); }
            try {
                return target.apply(JSON.toJson(request)).thenApply(json -> {
                    JsonObject info = parse(json);
                    return new CompatibilityInfo(CompatibilityInfo.State.valueOf(text(info, "state")), ResultCode.valueOf(text(info, "detail")), text(info, "reason"));
                }).exceptionally(e -> CompatibilityInfo.unverified(ResultCode.PROVIDER_UNAVAILABLE, "Forge endpoint unavailable"));
            } catch (RuntimeException e) { return CompletableFuture.completedFuture(CompatibilityInfo.unverified(ResultCode.PROVIDER_UNAVAILABLE, "Forge endpoint unavailable")); }
        }
    }
}
