package top.mores.battlefield.game.medical;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class FireReleaseGateTest {
    @Test void completionOrCancellationCannotResumeHeldFire() {
        for(boolean completed:new boolean[]{true,false}) {
            var g=new FireReleaseGate(); g.started(); g.observe(true);
            assertTrue(g.blocked(true)); assertTrue(g.blocked(false));
            g.observe(false); assertFalse(g.blocked(false)); g.observe(true); assertFalse(g.blocked(false));
        }
    }
    @Test void releaseDoesNotUnlockAnOngoingMedicalAction() {
        var g=new FireReleaseGate(); g.started(); g.observe(false); assertTrue(g.blocked(true)); assertFalse(g.blocked(false));
    }
}
