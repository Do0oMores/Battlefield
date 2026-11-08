package top.mores.battlefield.loadout;

import java.util.concurrent.*;
import java.util.function.*;

/** Thread-testable two-read transaction boundary; all construction and commit run on the game executor. */
public final class AsyncDeploymentFlow {
    private AsyncDeploymentFlow() {}
    public record Prepared<P, E>(P first, P confirmed, E equipment) {}
    public static <P, E> CompletableFuture<Void> run(Executor game, Supplier<CompletableFuture<P>> read,
            Runnable live, Function<P, E> construct, Consumer<Prepared<P, E>> commit) {
        try {
            return read.get().thenComposeAsync(first -> {
                live.run(); E equipment = construct.apply(first);
                return read.get().thenApply(confirmed -> new Prepared<>(first, confirmed, equipment));
            }, game).thenAcceptAsync(prepared -> { live.run(); commit.accept(prepared); }, game);
        } catch (RuntimeException e) { return CompletableFuture.failedFuture(e); }
    }
}
