package top.mores.battlefield.loadout;

import top.mores.battlefield.protocol.EquipmentSlot;
import java.util.*;

/** Six equipment slots in hotbar, reserves in main inventory; armor/offhand explicitly cleared. */
public record DeploymentEquipmentLayout(Map<EquipmentSlot, Integer> slots) {
    public DeploymentEquipmentLayout {
        slots = Map.copyOf(slots);
        if (slots.size() != 6 || new HashSet<>(slots.values()).size() != 6 || slots.values().stream().anyMatch(i -> i < 0 || i > 8)) throw new IllegalArgumentException("Six unique hotbar slots required");
    }
    public static DeploymentEquipmentLayout defaults() {
        return new DeploymentEquipmentLayout(Map.of(EquipmentSlot.PRIMARY, 0, EquipmentSlot.SECONDARY, 1, EquipmentSlot.GADGET_1, 2, EquipmentSlot.GADGET_2, 3, EquipmentSlot.GRENADE, 4, EquipmentSlot.MELEE, 5));
    }
}
