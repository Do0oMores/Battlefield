package top.mores.battlefield.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import top.mores.battlefield.armory.ArmoryView;
import top.mores.battlefield.protocol.EquipmentSlot;
import top.mores.battlefield.protocol.SoldierClass;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static top.mores.battlefield.net.C2SArmoryRequest.Action.*;

class ArmoryPacketsTest {
    @Test void roundTripSelectionAndConfirmedSnapshot() {
        UUID id = UUID.randomUUID();
        var request = new C2SArmoryRequest(id, true, SoldierClass.RECON, SELECT_ATTACHMENT,
                "battlefield:rifle", "SCOPE", "battlefield:optic", 123L);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            C2SArmoryRequest.encode(request, buffer);
            assertEquals(request, C2SArmoryRequest.decode(buffer));
            var entry = new ArmoryView.Entry("battlefield:optic", "Optic", "SCOPE", "ATTACHMENT",
                    "tacz:optic", Set.of(), Set.of(), false, true, "OK");
            var view = new ArmoryView(124, Map.of(EquipmentSlot.PRIMARY, "battlefield:rifle"), Map.of(),
                    Map.of("SCOPE", entry.id()), List.of(entry), "OK");
            var response = new S2CArmoryResult(id, "SAVED", view);
            S2CArmoryResult.encode(response, buffer);
            assertEquals(response, S2CArmoryResult.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test void invalidFieldsFailBeforeServerDispatch() {
        var id = UUID.randomUUID();
        var type = SoldierClass.ASSAULT;
        assertThrows(IllegalArgumentException.class, () -> new C2SArmoryRequest(id, false, type, SELECT_EQUIPMENT, "", "HELMET", "test:gun", 0));
        assertThrows(IllegalArgumentException.class, () -> new C2SArmoryRequest(id, false, type, SELECT_EQUIPMENT, "", "PRIMARY", "test:../UPPER", 0));
        assertThrows(IllegalArgumentException.class, () -> new C2SArmoryRequest(id, false, type, SELECT_EQUIPMENT, "", "PRIMARY", "test:" + "x".repeat(128), 0));
        assertThrows(IllegalArgumentException.class, () -> new C2SArmoryRequest(id, false, type, SELECT_ATTACHMENT, "test:gun", "bad slot", "test:optic", 1));
        assertThrows(IllegalArgumentException.class, () -> new C2SArmoryRequest(id, false, type, REMOVE_ATTACHMENT, "test:gun", "SCOPE", "test:optic", 1));
        assertThrows(IllegalArgumentException.class, () -> new C2SArmoryRequest(id, false, type, LOADOUT, "", "", "", -1));
    }

    @Test void decodeRejectsForgedClassAndOverlongField() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeUUID(UUID.randomUUID()).writeBoolean(false);
            buffer.writeUtf("ADMIN");
            assertThrows(IllegalArgumentException.class, () -> C2SArmoryRequest.decode(buffer));
            buffer.clear();
            buffer.writeUUID(UUID.randomUUID()).writeBoolean(false);
            buffer.writeUtf("ASSAULT");
            buffer.writeUtf("CUSTOMIZATION");
            buffer.writeUtf("test:" + "x".repeat(128));
            assertThrows(RuntimeException.class, () -> C2SArmoryRequest.decode(buffer));
        } finally {
            buffer.release();
        }
    }
}
