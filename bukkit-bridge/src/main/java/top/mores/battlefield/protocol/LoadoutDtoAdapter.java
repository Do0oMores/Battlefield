package top.mores.battlefield.protocol;

import com.google.gson.*;
import java.util.*;
import static top.mores.battlefield.protocol.BridgeProtocol.*;

/** Validates identity and all references in an already-authorized backend envelope. */
public final class LoadoutDtoAdapter {
    private LoadoutDtoAdapter() {}
    public static LoadoutDto deployment(String json, UUID player, SoldierClass type) {
        JsonObject envelope = parse(json); String code = text(envelope, "code");
        if (!code.equals("OK")) throw new BridgeFailure(code, text(envelope, "message"));
        JsonObject value = object(envelope, "value"), binding = object(value, "binding");
        if (number(value, "protocolVersion") != BACKEND_PROTOCOL || !flag(value, "deployable")
                || !text(value, "status").equals("OK") || !flag(binding, "stillCurrent")) throw new IllegalArgumentException("Unverified deployment");
        if (!UUID.fromString(text(binding, "playerId")).equals(player) || !text(binding, "soldierClass").equals(type.name())) throw new IllegalArgumentException("Identity mismatch");
        if (!text(binding, "providerId").equals("battlefield-bridge") || number(binding, "providerGeneration") == 0) throw new IllegalArgumentException("Wrong validator provider");
        JsonObject snapshot = object(value, "loadout"), saved = object(snapshot, "saved");
        if (!text(saved, "playerId").equals(player.toString()) || !text(saved, "soldierClass").equals(type.name())
                || number(saved, "revision") != number(binding, "loadoutRevision") || number(snapshot, "catalogRevision") != number(binding, "catalogRevision")) throw new IllegalArgumentException("Snapshot binding mismatch");
        JsonObject effective = object(snapshot, "effectiveEquipment");
        JsonObject entries = object(value, "equipment");
        if (entries.size() != EquipmentSlot.values().length) throw new IllegalArgumentException("Incomplete equipment");
        Map<EquipmentSlot, LoadoutDto.Equipment> equipment = new EnumMap<>(EquipmentSlot.class);
        for (var entry : entries.entrySet()) {
            EquipmentSlot slot = EquipmentSlot.valueOf(entry.getKey()); JsonObject e = entry.getValue().getAsJsonObject();
            JsonObject compatibility = object(e, "compatibility");
            if (!text(compatibility, "state").equals("VERIFIED") || !text(compatibility, "detail").equals("OK")) throw new IllegalArgumentException("Unverified slot");
            JsonObject definition = object(e, "definition");
            var weapon = weapon(definition); JsonObject custom = object(e, "customization");
            if (!flag(definition, "enabled") || !text(effective, slot.name()).equals(weapon.id())
                    || !java.util.stream.StreamSupport.stream(definition.getAsJsonArray("slots").spliterator(), false).anyMatch(s -> s.getAsString().equals(slot.name()))) throw new IllegalArgumentException("Disabled or mismatched equipment slot");
            if (!text(custom, "weaponId").equals(weapon.id()) || !UUID.fromString(text(custom, "playerId")).equals(player) || !text(custom, "skinId").isEmpty()) throw new IllegalArgumentException("Customization identity/skin mismatch");
            Map<String, LoadoutDto.Attachment> attachments = attachments(object(custom, "attachments"), object(e, "attachmentDefinitions"));
            equipment.put(slot, new LoadoutDto.Equipment(weapon, number(custom, "revision"), attachments));
        }
        return new LoadoutDto(player, type, number(binding, "loadoutRevision"), number(binding, "catalogRevision"),
                number(binding, "sessionGeneration"), number(binding, "permissionCapturedAt"), number(binding, "providerGeneration"), equipment);
    }
    public static LoadoutDto.Weapon weapon(JsonObject o) {
        String provider = text(o, "provider");
        if (!provider.matches("[A-Z][A-Z0-9_]{0,31}")) throw new IllegalArgumentException("Invalid provider");
        return new LoadoutDto.Weapon(id(text(o, "id")), provider, id(text(o, "gunId")), flag(o, "attachmentsEnabled"));
    }
    public static Map<String, LoadoutDto.Attachment> attachments(JsonObject selected, JsonObject definitions) {
        if (selected.size() > 6 || selected.size() != definitions.size()) throw new IllegalArgumentException("Attachment definition mismatch");
        Map<String, LoadoutDto.Attachment> result = new HashMap<>();
        for (var entry : selected.entrySet()) {
            String type = entry.getKey(); if (!Set.of("SCOPE", "MUZZLE", "STOCK", "GRIP", "LASER", "EXTENDED_MAG").contains(type)) throw new IllegalArgumentException("Unknown attachment type");
            JsonObject def = object(definitions, type); String id = id(text(def, "id"));
            if (!id.equals(entry.getValue().getAsString()) || !text(def, "type").equals(type)) throw new IllegalArgumentException("Attachment identity/type mismatch");
            result.put(type, new LoadoutDto.Attachment(id, id(text(def, "providerId")), type));
        }
        return Map.copyOf(result);
    }
}
