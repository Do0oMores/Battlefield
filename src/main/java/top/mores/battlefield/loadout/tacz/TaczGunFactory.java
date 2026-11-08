package top.mores.battlefield.loadout.tacz;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.*;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import top.mores.battlefield.protocol.LoadoutDto;
import top.mores.battlefield.protocol.BridgeFailure;
import java.util.*;

/** Uses actual TaCZ APIs; callers must be on Forge server thread. Never constructs NBT or forceBuild. */
public final class TaczGunFactory {
    public ItemStack create(LoadoutDto.Equipment equipment) {
        ResourceLocation id = new ResourceLocation(equipment.weapon().resource());
        var index = TimelessAPI.getCommonGunIndex(id).orElseThrow(() -> new BridgeFailure("RESOURCE_NOT_FOUND", "Missing gun resource: " + id));
        if (!equipment.weapon().attachmentsEnabled() && !equipment.attachments().isEmpty()) throw new BridgeFailure("ATTACHMENT_INVALID", "Attachment rejected: ");
        ItemStack stack = GunItemBuilder.create().setId(id).setCount(1).setFireMode(index.getGunData().getFireModeSet().stream().findFirst().orElseThrow()).build();
        IGun gun = IGun.getIGunOrNull(stack);
        if (stack.isEmpty() || gun == null || !id.equals(gun.getGunId(stack))) throw new BridgeFailure("ITEM_CONSTRUCTION_FAILED", "Gun builder returned invalid item: " + id);
        // Sort for deterministic construction; one DTO key per actual type prevents duplicate installation.
        for (var selected : new TreeMap<>(equipment.attachments()).entrySet()) {
            AttachmentType type = AttachmentType.valueOf(selected.getKey());
            ResourceLocation attachmentId = new ResourceLocation(selected.getValue().resource());
            var definition = TimelessAPI.getCommonAttachmentIndex(attachmentId).orElseThrow(() -> new BridgeFailure("RESOURCE_NOT_FOUND", "Missing attachment resource: " + attachmentId));
            if (definition.getType() != type || !gun.allowAttachmentType(stack, type)) throw new BridgeFailure("ATTACHMENT_INVALID", "Attachment rejected: type");
            ItemStack attachment = AttachmentItemBuilder.create().setId(attachmentId).setCount(1).build();
            IAttachment actual = IAttachment.getIAttachmentOrNull(attachment);
            if (attachment.isEmpty() || actual == null || actual.getType(attachment) != type || !gun.allowAttachment(stack, attachment)) throw new BridgeFailure("ATTACHMENT_INVALID", "Attachment rejected: incompatible");
            gun.installAttachment(stack, attachment);
            if (!attachmentId.equals(gun.getAttachmentId(stack, type))) throw new BridgeFailure("ATTACHMENT_INVALID", "Attachment rejected: installation failed");
        }
        // Re-check the complete installed combination. Unselected built-ins stay owned by TaCZ.
        for (var selected : equipment.attachments().entrySet()) {
            AttachmentType type = AttachmentType.valueOf(selected.getKey()); ItemStack attachment = gun.getAttachment(stack, type);
            if (!new ResourceLocation(selected.getValue().resource()).equals(gun.getAttachmentId(stack, type)) || !gun.allowAttachment(stack, attachment)) throw new BridgeFailure("ATTACHMENT_INVALID", "Attachment rejected: combination");
        }
        return stack;
    }
}
