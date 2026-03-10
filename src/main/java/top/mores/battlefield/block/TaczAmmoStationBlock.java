package top.mores.battlefield.block;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.api.item.IGun;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;

public class TaczAmmoStationBlock extends Block {

    public TaczAmmoStationBlock(Properties properties) {
        super(properties);
    }

    @Override
    public @NotNull InteractionResult use(@NotNull BlockState state,
                                          Level level,
                                          @NotNull BlockPos pos,
                                          @NotNull Player player,
                                          @NotNull InteractionHand hand,
                                          @NotNull BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        ItemStack gunStack = player.getMainHandItem();
        IGun iGun = IGun.getIGunOrNull(gunStack);
        if (iGun == null) {
            player.sendSystemMessage(Component.literal("主手物品不为枪械，无法补充备弹"));
            return InteractionResult.CONSUME;
        }

        ResourceLocation gunId = iGun.getGunId(gunStack);
        int totalCarryCap = TaczAmmoCapConfig.getOrCreate(gunId, resolveDefaultTotalCarryCap(gunId));

        int currentMag = Math.max(0, iGun.getCurrentAmmoCount(gunStack));
        int reserveNow = getCurrentReserveAmmo(player, gunStack, iGun);

        if (reserveNow == Integer.MAX_VALUE) {
            player.sendSystemMessage(Component.literal("已有无限子弹，无需补给"));
            return InteractionResult.CONSUME;
        }

        int need = Math.max(0, totalCarryCap - currentMag - reserveNow);
        if (need == 0) {
            player.sendSystemMessage(Component.literal("弹药已满"));
            return InteractionResult.CONSUME;
        }

        boolean ok;
        if (iGun.useDummyAmmo(gunStack)) {
            ok = refillDummyAmmo(gunStack, iGun, totalCarryCap, currentMag, need);
        } else {
            ok = giveInventoryAmmo(player, gunStack, need);
        }

        if (!ok) {
            player.sendSystemMessage(Component.literal("未找到对应的弹药类型"));
            return InteractionResult.CONSUME;
        }

        player.sendSystemMessage(Component.literal("已补给 " + need + " 发备弹"));
        return InteractionResult.CONSUME;
    }

    private static int getCurrentReserveAmmo(Player player, ItemStack gunStack, IGun iGun) {
        if (iGun.useDummyAmmo(gunStack)) {
            return Math.max(0, iGun.getDummyAmmoAmount(gunStack));
        }
        return countInventoryAmmo(player, gunStack);
    }

    private static boolean refillDummyAmmo(ItemStack gunStack,
                                           IGun iGun,
                                           int totalCarryCap,
                                           int currentMeg,
                                           int need) {
        int targetReserve = Math.max(0, totalCarryCap - currentMeg);
        if (iGun.hasMaxDummyAmmo(gunStack)) {
            iGun.setMaxDummyAmmoAmount(gunStack, targetReserve);
        }
        iGun.addDummyAmmoAmount(gunStack, need);
        return true;
    }

    private static int countInventoryAmmo(Player player, ItemStack gunStack) {
        Inventory inventory = player.getInventory();
        int total = 0;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;

            IAmmo iAmmo = IAmmo.getIAmmoOrNull(stack);
            if (iAmmo != null) {
                if (iAmmo.isAmmoOfGun(gunStack, stack)) {
                    total += stack.getCount();
                }
                continue;
            }

            if (stack.getItem() instanceof IAmmoBox iAmmoBox) {
                if (!iAmmoBox.isAmmoBoxOfGun(gunStack, stack)) continue;

                if (iAmmoBox.isCreative(stack) || iAmmoBox.isAllTypeCreative(stack)) {
                    return Integer.MAX_VALUE;
                }
                total += Math.max(0, iAmmoBox.getAmmoCount(stack));
            }
        }
        return total;
    }

    private static boolean giveInventoryAmmo(Player player, ItemStack gunStack, int need) {
        ResourceLocation ammoId = getAmmoIdForGun(gunStack);
        if (ammoId == null) return false;

        Item ammoBoxItem = ForgeRegistries.ITEMS.getValue(new ResourceLocation("tacz", "ammo_box"));
        if (ammoBoxItem == null) return false;

        ItemStack ammoBox = new ItemStack(ammoBoxItem);
        if (!(ammoBox.getItem() instanceof IAmmoBox iAmmoBox)) {
            return false;
        }

        iAmmoBox.setAmmoId(ammoBox, ammoId);
        iAmmoBox.setAmmoCount(ammoBox, need);

        boolean added = player.addItem(ammoBox);
        if (!added) {
            player.drop(ammoBox, false);
        }
        return true;
    }

    @Nullable
    private static ResourceLocation getAmmoIdForGun(ItemStack gunStack) {
        IGun iGun = IGun.getIGunOrNull(gunStack);
        if (iGun == null) return null;

        ResourceLocation gunId = iGun.getGunId(gunStack);

        return TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getGunData().getAmmoId())
                .orElse(null);
    }

    private static int resolveDefaultTotalCarryCap(ResourceLocation gunId) {
        int magSize = TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getGunData().getAmmoAmount())
                .filter(v -> v > 0)
                .orElse(30);

        return magSize * 3;
    }
}
