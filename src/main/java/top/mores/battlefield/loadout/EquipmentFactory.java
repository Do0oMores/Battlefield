package top.mores.battlefield.loadout;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import com.tacz.guns.util.AttachmentDataUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraftforge.registries.ForgeRegistries;
import top.mores.battlefield.config.TaczAmmoCapConfig;
import top.mores.battlefield.protocol.*;
import top.mores.battlefield.loadout.tacz.TaczGunFactory;
import java.util.*;

/** Stages all six items and ammunition before touching a player's inventory. */
public final class EquipmentFactory {
    public interface EquipmentProvider {
        boolean supports(String provider);
        ItemStack create(LoadoutDto.Equipment equipment);
    }
    private final List<EquipmentProvider> providers;
    private final DeploymentSettings settings;
    public EquipmentFactory(DeploymentSettings settings) {
        this.settings = settings;
        providers = List.of(new EquipmentProvider() {
            public boolean supports(String p) { return p.equals("TACZ") || p.equals("TACZ_GUN"); }
            public ItemStack create(LoadoutDto.Equipment e) { return new TaczGunFactory().create(e); }
        }, new EquipmentProvider() {
            public boolean supports(String p) { return p.equals("TACZ_AMMO"); }
            public ItemStack create(LoadoutDto.Equipment e) {
                ResourceLocation id = new ResourceLocation(e.weapon().resource());
                if (TimelessAPI.getCommonAmmoIndex(id).isEmpty()) throw new BridgeFailure("RESOURCE_NOT_FOUND", "ammo");
                return AmmoItemBuilder.create().setId(id).setCount(1).build();
            }
        }, new EquipmentProvider() {
            public boolean supports(String p) { return p.equals("MINECRAFT_ITEM") || p.equals("BATTLEFIELD_ITEM"); }
            public ItemStack create(LoadoutDto.Equipment e) {
                ResourceLocation id = new ResourceLocation(e.weapon().resource());
                String expected = e.weapon().provider().equals("MINECRAFT_ITEM") ? "minecraft" : "battlefield";
                if (!id.getNamespace().equals(expected) || !e.attachments().isEmpty()) throw new BridgeFailure("PROVIDER_UNAVAILABLE", "item namespace/customization");
                Item item = ForgeRegistries.ITEMS.getValue(id);
                if (item == null || item == Items.AIR) throw new BridgeFailure("RESOURCE_NOT_FOUND", "Missing item resource: " + id);
                return new ItemStack(item);
            }
        });
    }
    public ItemStack createEquipment(LoadoutDto.Equipment equipment) {
        ItemStack result = providers.stream().filter(p -> p.supports(equipment.weapon().provider())).findFirst()
                .orElseThrow(() -> new BridgeFailure("PROVIDER_UNAVAILABLE", "Unsupported provider: " + equipment.weapon().provider())).create(equipment);
        if (result == null || result.isEmpty()) throw new BridgeFailure("ITEM_CONSTRUCTION_FAILED", "ITEM_CONSTRUCTION_FAILED");
        return result;
    }
    public Map<Integer, ItemStack> create(LoadoutDto loadout) {
        Map<Integer, ItemStack> staged = new LinkedHashMap<>(); Map<ResourceLocation, Integer> reserves = new LinkedHashMap<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            var equipment = loadout.equipment().get(slot); if (equipment == null) throw new BridgeFailure("LOADOUT_INVALID", "LOADOUT_INVALID");
            ItemStack stack = createEquipment(equipment); IGun gun = IGun.getIGunOrNull(stack);
            if (gun != null) {
                var data = TimelessAPI.getCommonGunIndex(gun.getGunId(stack)).orElseThrow().getGunData();
                ResourceLocation ammo = data.getAmmoId();
                if (ammo == null || TimelessAPI.getCommonAmmoIndex(ammo).isEmpty()) throw new BridgeFailure("RESOURCE_NOT_FOUND", "gun ammunition");
                int capacity = AttachmentDataUtils.getAmmoCountWithAttachment(stack, data);
                if (capacity < 1 || capacity > 10000) throw new IllegalArgumentException("Invalid magazine capacity");
                int cap = TaczAmmoCapConfig.getOrCreate(gun.getGunId(stack), Math.max(1, data.getAmmoAmount()) * 3);
                var allocation = DeploymentAmmoPolicy.plan(capacity, cap, settings.fullMagazine(), settings.reserveMagazines());
                gun.setCurrentAmmoCount(stack, allocation.loaded()); gun.setBulletInBarrel(stack, false);
                int reserve = allocation.reserve();
                // Shared ammo must respect every selected gun's total carry cap, rather than double-counting reserves.
                reserves.merge(ammo, reserve, Math::min);
            }
            staged.put(settings.layout().slots().get(slot), stack);
        }
        int inventorySlot = 9;
        for (var reserve : reserves.entrySet()) {
            int remaining = reserve.getValue();
            int limit = Math.max(1, Math.min(64, TimelessAPI.getCommonAmmoIndex(reserve.getKey()).orElseThrow().getStackSize()));
            while (remaining > 0) {
                if (inventorySlot >= 36) throw new BridgeFailure("INVENTORY_FULL", "staged ammunition");
                int count = Math.min(remaining, limit);
                ItemStack ammo = AmmoItemBuilder.create().setId(reserve.getKey()).setCount(count).build();
                if (ammo.isEmpty() || ammo.getCount() != count) throw new BridgeFailure("ITEM_CONSTRUCTION_FAILED", "ammunition");
                staged.put(inventorySlot++, ammo); remaining -= count;
            }
        }
        return staged;
    }
}
