package top.mores.battlefield.loadout;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class AsyncDeploymentFlowTest {
    static class Game implements Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>(); boolean executing;
        public void execute(Runnable task) { tasks.add(task); }
        void drain() { executing = true; try { while (!tasks.isEmpty()) tasks.remove().run(); } finally { executing = false; } }
    }
    @Test void neverWaitsForEitherReadAndOnlyConstructsAndCommitsOnGameExecutor() {
        Game game = new Game(); var first = new CompletableFuture<Integer>(); var last = new CompletableFuture<Integer>();
        AtomicInteger reads = new AtomicInteger(), constructed = new AtomicInteger(), committed = new AtomicInteger();
        var flow = AsyncDeploymentFlow.run(game, () -> reads.getAndIncrement() == 0 ? first : last, () -> assertTrue(game.executing),
                value -> { assertTrue(game.executing); constructed.incrementAndGet(); return "kit"; }, prepared -> { assertTrue(game.executing); committed.incrementAndGet(); });
        assertFalse(flow.isDone()); assertEquals(0, constructed.get());
        first.complete(1); assertEquals(0, constructed.get()); game.drain(); assertEquals(1, constructed.get()); assertFalse(flow.isDone());
        last.complete(1); assertEquals(0, committed.get()); game.drain(); assertTrue(flow.isDone()); assertEquals(1, committed.get());
    }
    @Test void permissionRevocationAtFinalReadStopsApplication() {
        Game game = new Game(); AtomicInteger calls = new AtomicInteger(), committed = new AtomicInteger();
        var flow = AsyncDeploymentFlow.run(game, () -> calls.getAndIncrement() == 0 ? CompletableFuture.completedFuture(1)
                : CompletableFuture.failedFuture(new IllegalStateException("PERMISSION_DENIED")), () -> {}, n -> "kit", prepared -> committed.incrementAndGet());
        game.drain(); assertTrue(flow.isCompletedExceptionally()); assertEquals(0, committed.get());
    }
    @Test void changedRevisionAtConfirmationCannotApplyPreparedKit() {
        Game game = new Game(); AtomicInteger reads = new AtomicInteger(), committed = new AtomicInteger();
        var flow = AsyncDeploymentFlow.run(game, () -> CompletableFuture.completedFuture(reads.incrementAndGet()), () -> {}, n -> "kit", prepared -> {
            if (!prepared.first().equals(prepared.confirmed())) throw new IllegalStateException("REVISION_CONFLICT"); committed.incrementAndGet();
        }); game.drain(); assertTrue(flow.isCompletedExceptionally()); assertEquals(0, committed.get());
    }
    @Test void quitWhileWaitingPreventsConstruction() { assertCancelledBeforeConstruct("QUIT"); }
    @Test void matchEndingWhileWaitingPreventsConstruction() { assertCancelledBeforeConstruct("MATCH_ENDED"); }
    private void assertCancelledBeforeConstruct(String reason) {
        Game game = new Game(); var future = new CompletableFuture<Integer>(); AtomicBoolean live = new AtomicBoolean(true); AtomicInteger constructed = new AtomicInteger();
        var flow = AsyncDeploymentFlow.run(game, () -> future, () -> { if (!live.get()) throw new IllegalStateException(reason); }, n -> constructed.incrementAndGet(), p -> fail("late commit"));
        live.set(false); future.complete(1); game.drain(); assertTrue(flow.isCompletedExceptionally()); assertEquals(0, constructed.get());
    }
    @Test void replacedPlayerOrInvalidPointBeforeCommitCannotApply() {
        Game game = new Game(); AtomicBoolean live = new AtomicBoolean(true); AtomicInteger reads = new AtomicInteger(); var confirmation = new CompletableFuture<Integer>();
        var flow = AsyncDeploymentFlow.run(game, () -> reads.getAndIncrement() == 0 ? CompletableFuture.completedFuture(1) : confirmation,
                () -> { if (!live.get()) throw new IllegalStateException("PLAYER_OR_POINT_CHANGED"); }, n -> "kit", p -> fail("late commit"));
        game.drain(); live.set(false); confirmation.complete(1); game.drain(); assertTrue(flow.isCompletedExceptionally());
    }
    @Test void constructionFailureNeverStartsConfirmationOrCommit() {
        Game game = new Game(); AtomicInteger reads = new AtomicInteger();
        var flow = AsyncDeploymentFlow.run(game, () -> { reads.incrementAndGet(); return CompletableFuture.completedFuture(1); }, () -> {}, n -> { throw new IllegalStateException("RESOURCE_NOT_FOUND"); }, p -> fail());
        game.drain(); assertTrue(flow.isCompletedExceptionally()); assertEquals(1, reads.get());
    }
    @Test void commitFailureIsNeverReportedAsSuccess() {
        Game game = new Game();
        var flow = AsyncDeploymentFlow.run(game, () -> CompletableFuture.completedFuture(1), () -> {}, n -> "kit", p -> { throw new IllegalStateException("APPLY_FAILED"); });
        game.drain(); assertTrue(flow.isCompletedExceptionally());
    }
    @Test void oneHungPlayerDoesNotBlockOtherPlayersOrTheGameExecutor() {
        Game game = new Game(); AtomicInteger commits = new AtomicInteger();
        var hung = AsyncDeploymentFlow.run(game, () -> new CompletableFuture<Integer>(), () -> {}, n -> "kit", p -> fail());
        var healthy = AsyncDeploymentFlow.run(game, () -> CompletableFuture.completedFuture(1), () -> {}, n -> "kit", p -> commits.incrementAndGet());
        game.drain(); assertFalse(hung.isDone()); assertTrue(healthy.isDone()); assertEquals(1, commits.get());
    }
    @Test void timeoutFailureDoesNotScheduleAnApplication() {
        Game game = new Game(); var response = new CompletableFuture<Integer>();
        var flow = AsyncDeploymentFlow.run(game, () -> response, () -> {}, n -> "kit", p -> fail());
        response.completeExceptionally(new TimeoutException("controlled deadline")); game.drain(); assertTrue(flow.isCompletedExceptionally());
    }
}
