package top.mores.battlefield.protocol;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static top.mores.battlefield.protocol.BridgeProtocol.*;

class BridgeProtocolTest {
    @Test void abiAndWireVersionsAreCheckedWithoutIntegerTruncation() {
        requireVersions(3,2,1);
        assertThrows(IllegalArgumentException.class,()->requireVersions(4,2,1));
        assertThrows(IllegalArgumentException.class,()->requireVersions(3,1,1));
        assertThrows(IllegalArgumentException.class,()->requireVersions(3L+(1L<<32),2,1));
    }
    final UUID player = UUID.randomUUID();
    JsonObject deployment() {
        JsonObject envelope = new JsonObject(), value = new JsonObject(), binding = new JsonObject(), snapshot = new JsonObject(), saved = new JsonObject();
        envelope.addProperty("code","OK"); envelope.add("value",value);
        value.addProperty("protocolVersion",2); value.addProperty("deployable",true); value.addProperty("status","OK"); value.add("binding",binding);
        binding.addProperty("playerId",player.toString()); binding.addProperty("soldierClass","ASSAULT"); binding.addProperty("loadoutRevision",1);
        binding.addProperty("catalogRevision",2); binding.addProperty("sessionGeneration",3); binding.addProperty("permissionCapturedAt",System.currentTimeMillis());
        binding.addProperty("providerId","battlefield-bridge"); binding.addProperty("providerGeneration",4); binding.addProperty("stillCurrent",true);
        value.add("loadout",snapshot); snapshot.add("saved",saved); snapshot.addProperty("catalogRevision",2);
        saved.addProperty("playerId",player.toString()); saved.addProperty("soldierClass","ASSAULT"); saved.addProperty("revision",1);
        JsonObject effective = new JsonObject(), entries = new JsonObject(); snapshot.add("effectiveEquipment",effective); value.add("equipment",entries);
        for (var slot : EquipmentSlot.values()) {
            String id="battlefield:"+slot.name().toLowerCase(Locale.ROOT); effective.addProperty(slot.name(),id);
            JsonObject equipment=new JsonObject(),def=new JsonObject(),custom=new JsonObject(),info=new JsonObject(); entries.add(slot.name(),equipment);
            equipment.add("definition",def); equipment.add("customization",custom); equipment.add("compatibility",info); equipment.add("attachmentDefinitions",new JsonObject());
            def.addProperty("id",id); def.addProperty("provider","MINECRAFT_ITEM"); def.addProperty("gunId","minecraft:stick"); def.addProperty("attachmentsEnabled",false); def.addProperty("enabled",true);
            JsonArray slots=new JsonArray(); slots.add(slot.name()); def.add("slots",slots);
            custom.addProperty("playerId",player.toString()); custom.addProperty("weaponId",id); custom.addProperty("skinId",""); custom.addProperty("revision",0); custom.add("attachments",new JsonObject());
            info.addProperty("state","VERIFIED"); info.addProperty("detail","OK");
        }
        return envelope;
    }
    LoadoutDto decode(JsonObject json) { return LoadoutDtoAdapter.deployment(JSON.toJson(json),player,SoldierClass.ASSAULT); }
    @Test void fullDescriptionKeepsReferencesAndImmutableData() {
        var dto=decode(deployment()); assertEquals(6,dto.equipment().size()); assertEquals(1,dto.revision());
        assertThrows(UnsupportedOperationException.class,()->dto.equipment().clear());
    }
    @Test void uuidAndClassCannotBeTakenFromUntrustedPayload() {
        var json=deployment(); json.getAsJsonObject("value").getAsJsonObject("binding").addProperty("playerId",UUID.randomUUID().toString());
        assertThrows(IllegalArgumentException.class,()->decode(json));
        assertThrows(IllegalArgumentException.class,()->LoadoutDtoAdapter.deployment(JSON.toJson(deployment()),player,SoldierClass.RECON));
    }
    @Test void incompatibleProtocolAndUnverifiedStatusFailClosed() {
        var json=deployment(); json.getAsJsonObject("value").addProperty("protocolVersion",1); assertThrows(IllegalArgumentException.class,()->decode(json));
        var invalid=deployment(); invalid.getAsJsonObject("value").addProperty("deployable",false); assertThrows(IllegalArgumentException.class,()->decode(invalid));
    }
    @Test void incompleteKitDisabledDefinitionAndWrongSnapshotAreRejected() {
        var json=deployment(); json.getAsJsonObject("value").getAsJsonObject("equipment").remove("MELEE"); assertThrows(IllegalArgumentException.class,()->decode(json));
        var disabled=deployment(); disabled.getAsJsonObject("value").getAsJsonObject("equipment").getAsJsonObject("PRIMARY").getAsJsonObject("definition").addProperty("enabled",false);
        assertThrows(IllegalArgumentException.class,()->decode(disabled));
        var stale=deployment(); stale.getAsJsonObject("value").getAsJsonObject("loadout").getAsJsonObject("saved").addProperty("revision",99); assertThrows(IllegalArgumentException.class,()->decode(stale));
    }
    @Test void duplicateKeysAndArbitraryTypeTreesCannotSlipThroughDeserializer() {
        assertThrows(IllegalArgumentException.class,()->parse("{\"a\":1,\"a\":2}"));
        assertThrows(IllegalArgumentException.class,()->decode(parse("{\"@class\":\"java.lang.Runtime\"}")));
    }
    @Test void sizeDepthStringsArraysAndFractionalNumbersAreBounded() {
        assertThrows(IllegalArgumentException.class,()->parse(" ".repeat(MAX_JSON+1)));
        assertThrows(IllegalArgumentException.class,()->parse("{\"a\":"+"[".repeat(30)+"0"+"]".repeat(30)+"}"));
        assertThrows(IllegalArgumentException.class,()->parse("{\"a\":\""+"x".repeat(MAX_STRING+1)+"\"}"));
        assertThrows(IllegalArgumentException.class,()->parse("{\"a\":["+"0,".repeat(MAX_ENTRIES)+"0]}"));
        assertThrows(IllegalArgumentException.class,()->parse("{\"revision\":1.5}"));
    }
    @Test void snapshotRevisionAndCustomizationChangesAreDetected() {
        var first=decode(deployment()); var json=deployment(); json.getAsJsonObject("value").getAsJsonObject("equipment").getAsJsonObject("PRIMARY").getAsJsonObject("customization").addProperty("revision",1);
        assertFalse(first.samePlan(decode(json)));
    }
    @Test void attachmentIdTypeAndDefinitionsMustMatchExactly() {
        var selected=parse("{\"SCOPE\":\"battlefield:scope\"}");
        var definitions=parse("{\"SCOPE\":{\"id\":\"battlefield:scope\",\"providerId\":\"tacz:scope\",\"type\":\"MUZZLE\"}}");
        assertThrows(IllegalArgumentException.class,()->LoadoutDtoAdapter.attachments(selected,definitions));
        assertThrows(IllegalArgumentException.class,()->LoadoutDtoAdapter.attachments(selected,new JsonObject()));
    }
    @Test void freshPermissionTimestampDoesNotHideChangedSessionOrPlan() {
        var first=decode(deployment()); var json=deployment(); json.getAsJsonObject("value").getAsJsonObject("binding").addProperty("sessionGeneration",99);
        assertFalse(first.samePlan(decode(json)));
    }
}
