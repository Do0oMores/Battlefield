package top.mores.battlefield.client.armory;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.resource.index.ClientAttachmentIndex;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import top.mores.battlefield.armory.ArmoryView.Entry;

/** Renders client resource textures and local display stacks only. */
final class ArmoryIcons {
    private ArmoryIcons() {}

    static void draw(GuiGraphics g, Entry entry, int x, int y, int width, int height) {
        ResourceLocation id = ResourceLocation.tryParse(entry.resource());
        if (id == null) return;
        switch (entry.provider()) {
            case "TACZ", "TACZ_GUN" -> TimelessAPI.getClientGunIndex(id)
                    .map(index -> index.getDefaultDisplay()).map(GunDisplayInstance::getSlotTexture)
                    .ifPresent(texture -> drawTexture(g, texture, x, y, width, height, true));
            case "ATTACHMENT" -> TimelessAPI.getClientAttachmentIndex(id).map(ClientAttachmentIndex::getSlotTexture)
                    .ifPresent(texture -> drawTexture(g, texture, x, y, width, height, false));
            case "MINECRAFT_ITEM", "BATTLEFIELD_ITEM" -> {
                var item = ForgeRegistries.ITEMS.getValue(id);
                if (item != null) g.renderItem(new ItemStack(item), x + (width - 16) / 2, y + (height - 16) / 2);
            }
            default -> { }
        }
    }

    static Component stats(Entry entry) {
        ResourceLocation id = ResourceLocation.tryParse(entry.resource());
        if (id == null || !(entry.provider().equals("TACZ") || entry.provider().equals("TACZ_GUN"))) return Component.empty();
        return TimelessAPI.getClientGunIndex(id).map(index -> index.getGunData())
                .<Component>map(gun -> ArmoryTheme.label("stats", gun.getAmmoAmount(), gun.getRoundsPerMinute()))
                .orElse(Component.empty());
    }

    private static void drawTexture(GuiGraphics g, ResourceLocation texture, int x, int y, int width, int height, boolean wide) {
        int w = Math.min(width, wide ? height * 2 : height);
        int h = wide ? w / 2 : w;
        if (w > 0 && h > 0) g.blit(texture, x + (width - w) / 2, y + (height - h) / 2, 0, 0, w, h, w, h);
    }
}
