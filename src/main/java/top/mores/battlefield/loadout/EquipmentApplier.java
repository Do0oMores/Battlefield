package top.mores.battlefield.loadout;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import top.mores.battlefield.game.PlayerSnapshotStore;
import java.util.*;

/** Forge-only inventory application. Never drops overflow, and rolls back a failed complete update. */
public final class EquipmentApplier {
    public void apply(ServerPlayer player, Map<Integer, ItemStack> staged) {
        if (!player.server.isSameThread() || player.server.getPlayerList().getPlayer(player.getUUID()) != player || !PlayerSnapshotStore.hasSnapshot(player)) throw new IllegalStateException("PLAYER_INSTANCE_INVALID");
        var inventory = player.getInventory(); List<ItemStack> before = new ArrayList<>(); int selected = inventory.selected;
        for (int i = 0; i < inventory.getContainerSize(); i++) before.add(inventory.getItem(i).copy());
        try {
            if (staged.keySet().stream().anyMatch(i -> i < 0 || i >= 36) || staged.values().stream().anyMatch(ItemStack::isEmpty)) throw new IllegalArgumentException("Invalid inventory layout");
            inventory.clearContent(); // battle snapshot protects original armor and offhand too
            staged.forEach((slot, item) -> inventory.setItem(slot, item.copy()));
            inventory.selected = 0; inventory.setChanged(); player.inventoryMenu.broadcastChanges();
        } catch (RuntimeException error) {
            try {
                inventory.clearContent(); for (int i = 0; i < before.size(); i++) inventory.setItem(i, before.get(i));
                inventory.selected = selected; player.inventoryMenu.broadcastChanges();
            } catch (RuntimeException rollback) { error.addSuppressed(rollback); }
            throw error;
        }
    }
}
