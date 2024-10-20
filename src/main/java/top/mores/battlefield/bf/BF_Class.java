package top.mores.battlefield.bf;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;

public class BF_Class {

    private final String name;
    private final ItemStack[] items;
    private final ItemStack offHand;
    private final ItemStack[] armors;

    private static final Map<String, BF_Class> globals = new HashMap<>();
    private static final List<Material> OTHER_HELMET_LIST = asList(Material.PUMPKIN, Material.JACK_O_LANTERN, Material.PLAYER_HEAD);

    public BF_Class(String name, ItemStack[] items, ItemStack offHand, ItemStack[] armors) {
        this.name = name;
        this.items = items;
        this.offHand = offHand;
        this.armors = armors;
    }

    public String getName() {
        return name;
    }

    public ItemStack[] getItems() {
        return items;
    }

    public static boolean whetherHelmetItem(Material material) {
        return material.name().endsWith("_HELMET") || material.name().endsWith("_WOOL") ||
                material.name().endsWith("_HEAD") || OTHER_HELMET_LIST.contains(material);
    }

    private static boolean whetherChestplateItem(Material material) {
        return material.name().endsWith("_CHESTPLATE") || material == Material.ELYTRA;
    }

    private static boolean whetherLeggingsItem(Material material) {
        return material.name().endsWith("_LEGGINGS");
    }

    private static boolean whetherBootsItem(Material material) {
        return material.name().endsWith("_BOOTS");
    }

    private static boolean whetherArmorItem(Material material) {
        return whetherBootsItem(material) || whetherLeggingsItem(material) || whetherChestplateItem(material) || whetherHelmetItem(material);
    }

    //装备护甲
    private static void equipArmor(final ItemStack stack, final PlayerInventory inv) {
        final Material type = stack.getType();
        if (whetherHelmetItem(type)) {
            if (inv.getHelmet() != null && inv.getHelmet().getType() != Material.AIR) {
                inv.addItem(stack);
            } else {
                inv.setHelmet(stack);
            }
        } else if (whetherChestplateItem(type)) {
            if (inv.getChestplate() != null && inv.getChestplate().getType() != Material.AIR) {
                inv.addItem(stack);
            } else {
                inv.setChestplate(stack);
            }
        } else if (whetherLeggingsItem(type)) {
            if (inv.getLeggings() != null && inv.getLeggings().getType() != Material.AIR) {
                inv.addItem(stack);
            } else {
                inv.setLeggings(stack);
            }
        } else if (whetherBootsItem(type)) {
            if (inv.getBoots() != null && inv.getBoots().getType() != Material.AIR) {
                inv.addItem(stack);
            } else {
                inv.setBoots(stack);
            }
        }
    }

    public void equip(final Player player) {
        for (ItemStack item : this.armors) {
            if (item != null) {
                equipArmor(item, player.getInventory());
            }
        }
        for (ItemStack item : this.items) {
            if (item == null) {
                continue;
            }
            if (whetherArmorItem(item.getType())) {
                equipArmor(item, player.getInventory());
            } else {
                player.getInventory().addItem(item);
            }
        }
        player.getInventory().setItemInOffHand(this.offHand);
    }
}
