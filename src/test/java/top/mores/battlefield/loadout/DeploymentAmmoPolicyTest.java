package top.mores.battlefield.loadout;

import org.junit.jupiter.api.Test;
import top.mores.battlefield.protocol.EquipmentSlot;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentAmmoPolicyTest {
    @Test void stationCapClampsFullMagazineAndReserveTogether() { assertEquals(new DeploymentAmmoPolicy(30,60), DeploymentAmmoPolicy.plan(30,90,true,3)); }
    @Test void smallCapCannotGrantOverfilledMagazine() { assertEquals(new DeploymentAmmoPolicy(10,0), DeploymentAmmoPolicy.plan(30,10,true,3)); }
    @Test void emptyMagazineDoesNotConsumeReserveAllowance() { assertEquals(new DeploymentAmmoPolicy(0,90), DeploymentAmmoPolicy.plan(30,90,false,3)); }
    @Test void zeroReserveIsSupported() { assertEquals(new DeploymentAmmoPolicy(30,0), DeploymentAmmoPolicy.plan(30,90,true,0)); }
    @Test void rejectsInvalidPolicyAndOverlappingSlots() {
        assertThrows(IllegalArgumentException.class, () -> DeploymentAmmoPolicy.plan(0,90,true,3));
        Map<EquipmentSlot,Integer> slots = new EnumMap<>(EquipmentSlot.class); for (var slot : EquipmentSlot.values()) slots.put(slot, 0);
        assertThrows(IllegalArgumentException.class, () -> new DeploymentEquipmentLayout(slots));
    }
}
