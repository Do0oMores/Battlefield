package top.mores.battlefield.game.medical;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MedicalStateTest {
    private MedicalState deployed(MedicalState.Role role) {
        var s = new MedicalState(); s.deploy(role,"match#1","ATTACKERS"); return s;
    }
    @Test void threeOrdinaryRolesDeployWithExactlyOne() {
        for (var role : new MedicalState.Role[]{MedicalState.Role.ASSAULT,MedicalState.Role.SUPPORT,MedicalState.Role.RECON}) {
            var s=deployed(role); assertEquals(1,s.reserve()); assertFalse(s.medic());
        }
    }
    @Test void medicInfiniteIsACapabilityAndNeverAnInventorySentinel() {
        var s=deployed(MedicalState.Role.MEDIC);
        for(int n=0;n<3;n++) { var a=s.begin(n*14,14,true); assertTrue(s.complete(a,n*14+14,true,true)); assertEquals(0,s.reserve()); assertTrue(s.available()); }
        assertFalse(s.receive(s.life));
    }
    @Test void noTreatmentThroughTickThirteenThenExactlyOneConsumption() {
        var s=deployed(MedicalState.Role.SUPPORT); var a=s.begin(100,14,true);
        for(int t=100;t<114;t++) { assertFalse(s.complete(a,t,true,true)); assertEquals(1,s.reserve()); assertSame(a,s.action()); }
        assertTrue(s.complete(a,114,true,true)); assertEquals(0,s.reserve());
        assertFalse(s.complete(a,114,true,true)); assertFalse(s.complete(a,115,true,true));
    }
    @Test void fullHealthWithoutCeilingRestrictionDoesNotStart() {
        var s=deployed(MedicalState.Role.ASSAULT); assertNull(s.begin(0,14,false)); assertEquals(1,s.reserve());
    }
    @Test void damageOrCeilingRestrictionAllowsStart() {
        assertNotNull(deployed(MedicalState.Role.ASSAULT).begin(0,14,true));
    }
    @Test void repeatedCDoesNotReplaceOrExtendTheAttempt() {
        var s=deployed(MedicalState.Role.ASSAULT); var a=s.begin(0,14,true);
        for(int t=1;t<14;t++) assertNull(s.begin(t,14,true));
        assertSame(a,s.action()); assertEquals(14,s.action().end());
    }
    @Test void cancelPreservesStockAndMakesDelayedCompletionHarmless() {
        var s=deployed(MedicalState.Role.ASSAULT); var a=s.begin(0,14,true); s.cancel();
        assertFalse(s.complete(a,14,true,true)); assertEquals(1,s.reserve()); assertNull(s.action());
    }
    @Test void staleAttemptCannotCompleteANewerAttempt() {
        var s=deployed(MedicalState.Role.ASSAULT); var old=s.begin(0,14,true); s.cancel(); var next=s.begin(2,14,true);
        assertFalse(s.complete(old,20,true,true)); assertSame(next,s.action()); assertTrue(s.complete(next,20,true,true));
    }
    @Test void otherHealingBeforeCompletionDoesNotWasteStock() {
        var s=deployed(MedicalState.Role.ASSAULT); var a=s.begin(0,14,true);
        assertFalse(s.complete(a,14,true,false)); assertEquals(1,s.reserve()); assertNull(s.action());
    }
    @Test void lossOfEligibilityAtCompletionCannotHealOrConsume() {
        var s=deployed(MedicalState.Role.ASSAULT); var a=s.begin(0,14,true);
        assertFalse(s.complete(a,14,false,true)); assertEquals(1,s.reserve()); assertNull(s.action());
    }
    @Test void deathInvalidatesBothTreatmentAndIncomingOldLifeSupply() {
        var s=deployed(MedicalState.Role.ASSAULT); var a=s.begin(0,14,true); var old=s.life; s.invalidate();
        assertFalse(s.available()); assertFalse(s.receive(old)); assertNull(s.begin(1,14,true)); assertFalse(s.complete(a,14,true,true));
    }
    @Test void redeployChangesLifeAndOnlyThenRestoresStock() {
        var s=deployed(MedicalState.Role.ASSAULT); var a=s.begin(0,14,true); var old=s.life; s.complete(a,14,true,true);
        assertNull(s.begin(15,14,true)); s.deploy(MedicalState.Role.RECON,"match#1","ATTACKERS");
        assertNotEquals(old,s.life); assertEquals(1,s.reserve()); assertFalse(s.receive(old)); assertFalse(s.complete(a,28,true,true));
    }
    @Test void concurrentDeliveriesCanOnlyFillZeroToOneOnce() {
        var s=deployed(MedicalState.Role.ASSAULT); var a=s.begin(0,14,true); s.complete(a,14,true,true);
        assertTrue(s.receive(s.life)); assertFalse(s.receive(s.life)); assertEquals(1,s.reserve());
    }
    @Test void restoredLifePreservesZeroAndDoesNotRestoreAnAction() {
        var s=deployed(MedicalState.Role.ASSAULT); var a=s.begin(0,14,true); s.complete(a,14,true,true);
        var restored=new MedicalState(); restored.restore(s.role,s.life,s.match,s.team,s.deployed,s.reserve());
        assertEquals(0,restored.reserve()); assertEquals(s.life,restored.life); assertNull(restored.action()); assertFalse(restored.available());
    }
    @Test void storageClampsCorruptStockRatherThanCreatingInfinity() {
        var s=deployed(MedicalState.Role.ASSAULT);
        for(int stock:new int[]{-1,2,Integer.MAX_VALUE}) { s.restore(s.role,s.life,s.match,s.team,true,stock); assertEquals(0,s.reserve()); }
    }
}
