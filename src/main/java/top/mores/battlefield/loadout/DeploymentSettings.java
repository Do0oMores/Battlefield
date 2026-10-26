package top.mores.battlefield.loadout;

import com.google.gson.*;
import net.minecraftforge.fml.loading.FMLPaths;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.protocol.EquipmentSlot;
import java.nio.file.*;
import java.util.*;

/** Server-owned deployment policy; combat never writes back to loadout templates. */
public record DeploymentSettings(boolean fullMagazine, int reserveMagazines, long maxPermissionAgeMs, DeploymentEquipmentLayout layout) {
    public static DeploymentSettings load() {
        Path file = FMLPaths.CONFIGDIR.get().resolve("battlefield/deployment_loadout.json");
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) Files.writeString(file, "{\n  \"magazinePolicy\": \"FULL\",\n  \"reserveMagazines\": 3,\n  \"maxPermissionAgeMs\": 1000,\n  \"slots\": {\"PRIMARY\":0,\"SECONDARY\":1,\"GADGET_1\":2,\"GADGET_2\":3,\"GRENADE\":4,\"MELEE\":5}\n}\n");
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            String policy = root.get("magazinePolicy").getAsString();
            if (!Set.of("FULL", "EMPTY").contains(policy)) throw new IllegalArgumentException("Unknown magazinePolicy");
            int reserve = root.get("reserveMagazines").getAsInt(); long age = root.get("maxPermissionAgeMs").getAsLong();
            if (reserve < 0 || reserve > 10 || age < 50 || age > 2000) throw new IllegalArgumentException("Deployment policy outside limits");
            Map<EquipmentSlot, Integer> slots = new EnumMap<>(EquipmentSlot.class);
            root.getAsJsonObject("slots").entrySet().forEach(e -> slots.put(EquipmentSlot.valueOf(e.getKey()), e.getValue().getAsInt()));
            return new DeploymentSettings(policy.equals("FULL"), reserve, age, new DeploymentEquipmentLayout(slots));
        } catch (Exception e) { Battlefield.LOGGER.error("Invalid deployment_loadout.json; using safe defaults", e); return new DeploymentSettings(true, 3, 1000, DeploymentEquipmentLayout.defaults()); }
    }
}
