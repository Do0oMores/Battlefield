package top.mores.battlefield.protocol;

import java.util.*;

/** Independent wire DTOs, never cast to BFBackpack's separately loaded records. */
public record LoadoutDto(UUID player, SoldierClass soldierClass, long revision, long catalogRevision,
        long sessionGeneration, long permissionCapturedAt, long providerGeneration, Map<EquipmentSlot, Equipment> equipment) {
    public LoadoutDto { equipment = Map.copyOf(equipment); }
    public record Weapon(String id, String provider, String resource, boolean attachmentsEnabled) {}
    public record Attachment(String id, String resource, String type) {}
    public record Equipment(Weapon weapon, long customizationRevision, Map<String, Attachment> attachments) {
        public Equipment { attachments = Map.copyOf(attachments); }
    }
    public boolean samePlan(LoadoutDto other) {
        return player.equals(other.player) && soldierClass == other.soldierClass && revision == other.revision
                && catalogRevision == other.catalogRevision && sessionGeneration == other.sessionGeneration
                && providerGeneration == other.providerGeneration && equipment.equals(other.equipment);
    }
}
