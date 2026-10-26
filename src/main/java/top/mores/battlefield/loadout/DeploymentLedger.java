package top.mores.battlefield.loadout;

import java.util.*;
import java.util.concurrent.*;

/** Identity-based idempotency, independent of cooldowns. Cancellation makes late callbacks inert. */
public final class DeploymentLedger {
    public record Outcome(boolean ok, String code, String message) {
        public static Outcome fail(String code, String message) { return new Outcome(false, code, message); }
        public static Outcome success() { return new Outcome(true, "OK", "部署成功"); }
    }
    public static final class Attempt {
        public final UUID player, request, life;
        public final String match, choice;
        public final CompletableFuture<Outcome> result = new CompletableFuture<>();
        private boolean claimed;
        Attempt(UUID player, UUID request, String match, UUID life, String choice) { this.player = player; this.request = request; this.match = match; this.life = life; this.choice = choice; }
    }
    private final Map<UUID, Attempt> active = new HashMap<>();
    private final Map<UUID, LinkedHashMap<UUID, Attempt>> history = new HashMap<>();
    public synchronized Attempt find(UUID player, UUID request, String choice) {
        Attempt prior = history.getOrDefault(player, new LinkedHashMap<>()).get(request);
        if (prior != null && !prior.choice.equals(choice)) throw new IllegalArgumentException("Deployment ID reused for different request");
        return prior;
    }
    public synchronized void fail(UUID player, Outcome outcome) {
        Attempt attempt = active.get(player); if (attempt != null) complete(attempt, outcome);
    }
    public synchronized Attempt begin(UUID player, UUID request, String match, UUID life, String choice) {
        var records = history.computeIfAbsent(player, p -> new LinkedHashMap<>());
        Attempt prior = records.get(request);
        if (prior != null) {
            if (!prior.match.equals(match) || !prior.choice.equals(choice)) throw new IllegalArgumentException("Deployment ID reused for different request");
            return prior;
        }
        if (active.containsKey(player)) throw new IllegalStateException("Deployment already pending");
        if (active.size() >= 128) throw new IllegalStateException("Deployment capacity exceeded");
        Attempt attempt = new Attempt(player, request, match, life, choice); active.put(player, attempt); records.put(request, attempt);
        while (records.size() > 16) records.remove(records.keySet().iterator().next());
        return attempt;
    }
    public synchronized boolean active(Attempt attempt) { return active.get(attempt.player) == attempt && !attempt.result.isDone(); }
    public synchronized boolean claim(Attempt attempt) { if (!active(attempt) || attempt.claimed) return false; attempt.claimed = true; return true; }
    public synchronized void complete(Attempt attempt, Outcome outcome) { if (active.get(attempt.player) == attempt) active.remove(attempt.player); attempt.result.complete(outcome); }
    public synchronized void cancel(UUID player, String reason) {
        Attempt attempt = active.remove(player); if (attempt != null) attempt.result.complete(Outcome.fail("CANCELLED", reason));
    }
    public synchronized void forget(UUID player, String reason) { cancel(player, reason); history.remove(player); }
    public synchronized void close() { for (UUID player : List.copyOf(active.keySet())) cancel(player, "服务器停用"); history.clear(); }
}
