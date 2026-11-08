package top.mores.battlefield.bridge;

import com.google.gson.JsonObject;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.protocol.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static top.mores.battlefield.protocol.BridgeProtocol.*;

/** Forge side. No Bukkit/implementation types in signatures or linkage. */
public final class BFBackpackServiceBridge implements AutoCloseable {
    private record Connection(Object plugin, Object scheduler, Method runTask, long generation) {}
    private volatile Connection connection;
    private volatile boolean probing, closed;
    private volatile String health = "NOT_READY: bridge not installed/initializing";
    private final Set<CompletableFuture<String>> pending = ConcurrentHashMap.newKeySet();
    private final Function<String, CompletableFuture<String>> validator;
    public BFBackpackServiceBridge(Function<String, CompletableFuture<String>> validator) { this.validator = validator; }
    public String health() { return health; }
    public boolean ready() { return connection != null && !closed && health.startsWith("OK:"); }
    public void probe() {
        if (closed || probing) return; probing = true;
        try {
            Class<?> bukkit = Class.forName("org.bukkit.Bukkit");
            Object manager = bukkit.getMethod("getPluginManager").invoke(null);
            Object plugin = manager.getClass().getMethod("getPlugin", String.class).invoke(manager, "BattlefieldBridge");
            if (plugin == null) { disconnected("NOT_READY: BattlefieldBridge not installed"); probing = false; return; }
            Object scheduler = bukkit.getMethod("getScheduler").invoke(null);
            Class<?> pluginType = Class.forName("org.bukkit.plugin.Plugin", false, bukkit.getClassLoader());
            Method runTask = scheduler.getClass().getMethod("runTask", pluginType, Runnable.class);
            runTask.invoke(scheduler, plugin, (Runnable) () -> {
                String step = "reading bridge health";
                try {
                    if (closed) return;
                    Connection current = connection;
                    if (current != null && current.plugin != plugin) {
                        disconnected("NOT_READY: Bukkit bridge plugin replaced");
                        current = null;
                    }
                    Method healthMethod = plugin.getClass().getMethod("health");
                    JsonObject status = parse((String) healthMethod.invoke(plugin));
                    if (!compatible(status)) return;
                    long reportedGeneration = number(status, "generation");
                    if (current != null && reportedGeneration != current.generation
                            && status.has("connected") && flag(status, "connected")) {
                        unavailable("NOT_READY: Forge connection replaced");
                        return;
                    }
                    if (current == null || reportedGeneration != current.generation) {
                        step = "connecting Forge validator";
                        long generation = (Long) plugin.getClass().getMethod("connect", int.class, Function.class)
                                .invoke(plugin, VERSION, validator);
                        current = new Connection(plugin, scheduler, runTask, generation);
                        connection = current;
                        step = "confirming bridge health";
                        status = parse((String) healthMethod.invoke(plugin));
                        if (!compatible(status)) return;
                        if (number(status, "generation") != current.generation) {
                            unavailable("NOT_READY: Forge connection changed during handshake");
                            return;
                        }
                    }
                    health = flag(status, "ready") ? "OK: API=3 protocol=2 bridge=" + VERSION : "NOT_READY: backend initializing/unregistered";
                    // A replaced API is rebound by the Bukkit service listener, without retaining its old loader.
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { probeFailed(step, e); }
                finally { probing = false; }
            });
        } catch (ClassNotFoundException e) { disconnected("NOT_READY: Bukkit unavailable (Forge-only server)"); probing = false; }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            probeFailed("finding Bukkit bridge or scheduling handshake", e);
            probing = false;
        }
    }
    private boolean compatible(JsonObject status) {
        long api = number(status, "apiVersion");
        long backendProtocol = number(status, "backendProtocol");
        long protocol = number(status, "bridgeProtocol");
        try {
            requireVersions(api, backendProtocol, protocol);
            return true;
        } catch (IllegalArgumentException incompatible) {
            unavailable("API_INCOMPATIBLE");
            return false;
        }
    }
    private void probeFailed(String step, Throwable error) {
        Throwable cause = error instanceof InvocationTargetException wrapped && wrapped.getCause() != null
                ? wrapped.getCause() : error;
        String reason = "NOT_READY: " + step + " (" + cause.getClass().getSimpleName() + ")";
        if (!reason.equals(health)) Battlefield.LOGGER.warn("BFBackpack bridge failed while {}", step, cause);
        unavailable(reason);
    }
    public CompletableFuture<String> request(UUID player, SoldierClass type, String operation) {
        JsonObject input = new JsonObject(); input.addProperty("bridgeProtocol", VERSION); input.addProperty("player", player.toString());
        input.addProperty("soldierClass", type.name()); input.addProperty("operation", operation); return request(input);
    }
    public CompletableFuture<String> request(JsonObject input) {
        Connection accepted = connection;
        if (accepted == null || !ready()) return CompletableFuture.completedFuture(failure(health.equals("API_INCOMPATIBLE") ? "API_INCOMPATIBLE" : "NOT_READY", health));
        if (pending.size() >= 128) return CompletableFuture.completedFuture(failure("VALIDATION_BUSY", "Bridge capacity"));
        CompletableFuture<String> result = new CompletableFuture<>(); pending.add(result);
        result.orTimeout(6, TimeUnit.SECONDS).whenComplete((v, e) -> pending.remove(result));
        try {
            JsonObject authenticated = input.deepCopy(); authenticated.addProperty("connectionGeneration", accepted.generation);
            String request = JSON.toJson(authenticated); parse(request);
            accepted.runTask.invoke(accepted.scheduler, accepted.plugin, (Runnable) () -> {
                if (result.isDone()) return;
                if (!ready() || connection != accepted) { result.complete(failure("NOT_READY", "Bridge changed")); return; }
                try {
                    Object returned = accepted.plugin.getClass().getMethod("request", String.class).invoke(accepted.plugin, request);
                    if (!(returned instanceof CompletableFuture<?> future)) throw new IllegalStateException("Invalid bridge result");
                    future.whenComplete((value, error) -> {
                        if (closed || connection != accepted) result.complete(failure("NOT_READY", "Bridge changed"));
                        else if (error != null || !(value instanceof String)) result.complete(failure(error instanceof TimeoutException ? "VALIDATION_TIMEOUT" : "PROVIDER_UNAVAILABLE", "Bridge failed"));
                        else { try { parse((String) value); result.complete((String) value); } catch (RuntimeException e) { result.complete(failure("INVALID_INPUT", "Malformed bridge response")); } }
                    });
                } catch (ReflectiveOperationException | RuntimeException e) { result.complete(failure("NOT_READY", "Bridge unavailable")); }
            });
        } catch (ReflectiveOperationException | RuntimeException e) { result.complete(failure("NOT_READY", "Bridge unavailable")); }
        return result.exceptionally(e -> failure("VALIDATION_TIMEOUT", "Bridge request expired"));
    }
    private void disconnected(String reason) {
        connection = null;
        unavailable(reason);
    }
    private void unavailable(String reason) {
        health = reason;
        pending.forEach(f -> f.complete(failure("NOT_READY", reason)));
    }
    @Override public void close() {
        closed = true; Connection old = connection; disconnected("NOT_READY: server stopping");
        if (old != null) try {
            old.runTask.invoke(old.scheduler, old.plugin, (Runnable) () -> {
                try { old.plugin.getClass().getMethod("disconnect", long.class).invoke(old.plugin, old.generation); }
                catch (ReflectiveOperationException | RuntimeException e) { Battlefield.LOGGER.debug("Bridge already stopped", e); }
            });
        } catch (ReflectiveOperationException | RuntimeException e) { Battlefield.LOGGER.debug("Bukkit scheduler already stopped", e); }
    }
}
