package top.mores.battlefield.client.armory;

import org.junit.jupiter.api.Test;
import top.mores.battlefield.armory.ArmoryView;
import top.mores.battlefield.net.C2SArmoryRequest;
import top.mores.battlefield.net.S2CArmoryResult;
import top.mores.battlefield.protocol.SoldierClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static top.mores.battlefield.net.C2SArmoryRequest.Action.*;

class ArmoryClientStateTest {
    private final List<C2SArmoryRequest> sent = new ArrayList<>();
    private final ArmoryClientState state = new ArmoryClientState(true, sent::add);

    @Test void classSwitchDuringSaveKeepsTheConfirmationOnItsOriginalClass() {
        var assault = focus(SoldierClass.ASSAULT, "");
        state.accept(result(assault, "OK", 3));
        tick(3);
        assertTrue(state.select(SELECT_EQUIPMENT, "PRIMARY", "tacz:ak47"));
        var save = last();
        assertEquals(3, save.revision());
        assertEquals("SAVING", state.status());
        assertFalse(state.readyToDeploy());
        state.focus(SoldierClass.MEDIC, "");
        state.focus(SoldierClass.SUPPORT, "");
        state.focus(SoldierClass.RECON, "");
        state.accept(result(save, "SAVED", 4));
        assertEquals(4, state.view(new ArmoryClientState.Key(SoldierClass.ASSAULT, "")).revision());
        assertNull(state.view());
        tick(3);
        var recon = last();
        assertEquals(SoldierClass.RECON, recon.soldierClass());
        state.accept(result(assault, "OK", 1));
        assertNull(state.view());
        state.accept(result(recon, "OK", 10));
        assertEquals(10, state.view().revision());
    }

    @Test void attachmentConflictRefreshesOnlyThatWeaponAndNeverRetriesTheWrite() {
        var assault = focus(SoldierClass.ASSAULT, "");
        state.accept(result(assault, "OK", 10));
        var weapon = focus(SoldierClass.ASSAULT, "tacz:ak47");
        state.accept(result(weapon, "OK", 1));
        tick(3);
        assertTrue(state.select(SELECT_ATTACHMENT, "SCOPE", "tacz:optic"));
        var save = last();
        assertEquals(1, save.revision());
        state.accept(result(save, "REVISION_CONFLICT", 2));
        tick(5);
        assertSame(save, last());
        assertEquals(2, state.view().revision());
        assertEquals("REVISION_CONFLICT", state.status());
        assertEquals("tacz:optic", state.intent());
        assertNull(state.view(new ArmoryClientState.Key(SoldierClass.ASSAULT, "")));
    }

    @Test void failedSaveAndLateResponsesCannotConfirmANewlyOpenedEditor() {
        var read = focus(SoldierClass.MEDIC, "");
        state.accept(result(read, "OK", 0));
        tick(3);
        assertTrue(state.select(SELECT_EQUIPMENT, "SECONDARY", "tacz:pistol"));
        var save = last();
        state.accept(result(save, "DATABASE_ERROR", 0));
        assertEquals("DATABASE_ERROR", state.status());
        assertEquals("tacz:pistol", state.intent());

        var reopened = new ArmoryClientState(false, sent::add);
        reopened.focus(SoldierClass.MEDIC, "");
        reopened.tick();
        reopened.accept(result(save, "SAVED", 1));
        assertNull(reopened.view());
        var fresh = last();
        reopened.accept(result(fresh, "OK", 1));
        assertEquals(1, reopened.view().revision());
    }

    @Test void timedOutReadCannotOverwriteARefresh() {
        var read = focus(SoldierClass.SUPPORT, "");
        tick(800);
        assertEquals("VALIDATION_TIMEOUT", state.status());
        state.refresh();
        state.tick();
        var retry = last();
        state.accept(result(read, "OK", 5));
        assertNull(state.view());
        state.accept(result(retry, "OK", 6));
        assertEquals(6, state.view().revision());
    }

    private C2SArmoryRequest focus(SoldierClass type, String weapon) {
        state.focus(type, weapon);
        tick(3);
        return last();
    }

    private C2SArmoryRequest last() { return sent.get(sent.size() - 1); }
    private void tick(int count) { for (int i = 0; i < count; i++) state.tick(); }

    private static S2CArmoryResult result(C2SArmoryRequest request, String code, long revision) {
        var view = new ArmoryView(revision, Map.of(), Map.of(), Map.of(), List.of(), "OK");
        return new S2CArmoryResult(request.requestId(), code, view);
    }
}
