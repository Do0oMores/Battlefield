package top.mores.battlefield.armory;

import top.mores.battlefield.protocol.EquipmentSlot;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Short-lived server projection of BFBackpack data. IDs are catalog IDs unless labeled resource. */
public record ArmoryView(long revision, Map<EquipmentSlot, String> equipped, Map<EquipmentSlot, String> issues,
                         Map<String, String> installed, List<Entry> entries, String deployment) {
    public ArmoryView {
        equipped = Map.copyOf(equipped);
        issues = Map.copyOf(issues);
        installed = Map.copyOf(installed);
        entries = List.copyOf(entries);
    }

    public record Entry(String id, String name, String category, String provider, String resource,
                        Set<EquipmentSlot> slots, Set<String> tags, boolean customizable, boolean unlocked, String status) {
        public Entry {
            slots = Set.copyOf(slots);
            tags = Set.copyOf(tags);
        }
        public boolean selectable() { return status.equals("OK"); }
    }
}
