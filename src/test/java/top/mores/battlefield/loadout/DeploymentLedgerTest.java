package top.mores.battlefield.loadout;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentLedgerTest {
    final DeploymentLedger ledger = new DeploymentLedger();
    final UUID player = UUID.randomUUID(), request = UUID.randomUUID(), life = UUID.randomUUID();
    DeploymentLedger.Attempt start() { return ledger.begin(player, request, "match#1", life, "base/ASSAULT"); }
    @Test void repeatedRequestReturnsSameAttemptAndCanOnlyCommitOnce() {
        var first = start(); assertSame(first, start()); assertTrue(ledger.claim(first)); assertFalse(ledger.claim(first));
        ledger.complete(first, DeploymentLedger.Outcome.success()); assertSame(first, start()); assertTrue(first.result.join().ok()); assertFalse(ledger.claim(first));
    }
    @Test void reuseWithDifferentPayloadIsRejected() {
        start(); assertThrows(IllegalArgumentException.class, () -> ledger.begin(player, request, "match#1", life, "base/RECON"));
    }
    @Test void concurrentDifferentRequestForSamePlayerIsRejected() {
        start(); assertThrows(IllegalStateException.class, () -> ledger.begin(player, UUID.randomUUID(), "match#1", life, "base/MEDIC"));
    }
    @Test void lateCompletionAfterQuitCannotBecomeSuccess() {
        var first = start(); ledger.forget(player, "quit"); ledger.complete(first, DeploymentLedger.Outcome.success());
        assertFalse(first.result.join().ok()); assertFalse(ledger.claim(first));
    }
    @Test void shutdownCompletesPendingAndMakesLateCommitInert() {
        var first = start(); ledger.close(); assertFalse(first.result.join().ok()); assertFalse(ledger.active(first));
    }
    @Test void newLifeAfterDeathCanDeployWithoutReusingOldAttempt() {
        var first = start(); ledger.complete(first, DeploymentLedger.Outcome.success());
        var next = ledger.begin(player, UUID.randomUUID(), "match#1", UUID.randomUUID(), "base/MEDIC");
        assertTrue(ledger.claim(next)); assertNotEquals(first.life, next.life);
    }
}
