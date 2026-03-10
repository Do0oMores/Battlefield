package top.mores.battlefield.block;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;
import top.mores.battlefield.config.TaczAmmoCapConfig;
import com.tacz.guns.resource.index.CommonAmmoIndex;

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
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        // 这里只作为“非枪状态下的普通右键兜底”
        return trySupply(level, pos, state, player)
                ? InteractionResult.CONSUME
                : InteractionResult.PASS;
    }

    public static boolean trySupply(Level level, BlockPos pos, BlockState state, Player player) {
        if (level.isClientSide) {
            return true;
        }

        if (!(state.getBlock() instanceof TaczAmmoStationBlock)) {
            return false;
        }

        ItemStack gunStack = player.getMainHandItem();
        IGun iGun = IGun.getIGunOrNull(gunStack);
        if (iGun == null) {
            player.sendSystemMessage(Component.literal("主手物品不为枪械，无法补充备弹"));
            return true;
        }

        ResourceLocation gunId = iGun.getGunId(gunStack);
        int totalCarryCap = TaczAmmoCapConfig.getOrCreate(gunId, resolveDefaultTotalCarryCap(gunId));

        int currentMag = Math.max(0, iGun.getCurrentAmmoCount(gunStack));
        int reserveNow = getCurrentReserveAmmo(player, gunStack, iGun);

        if (reserveNow == Integer.MAX_VALUE) {
            player.sendSystemMessage(Component.literal("已有无限子弹，无需补给"));
            return true;
        }

        int need = Math.max(0, totalCarryCap - currentMag - reserveNow);
        if (need == 0) {
            player.sendSystemMessage(Component.literal("弹药已满"));
            return true;
        }

        boolean ok;
        if (iGun.useDummyAmmo(gunStack)) {
            ok = refillDummyAmmo(gunStack, iGun, totalCarryCap, currentMag, need);
        } else {
            ok = giveInventoryAmmo(player, gunStack, need);
        }

        if (!ok) {
            player.sendSystemMessage(Component.literal("未找到对应的弹药类型"));
            return true;
        }

        player.sendSystemMessage(Component.literal("已补给 " + need + " 发备弹"));
        return true;
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
                                           int currentMag,
                                           int need) {
        int targetReserve = Math.max(0, totalCarryCap - currentMag);
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
        if (need <= 0) return true;

        int maxPerStack = TimelessAPI.getCommonAmmoIndex(ammoId)
                .map(CommonAmmoIndex::getStackSize)
                .filter(v -> v > 0)
                .orElse(1);

        int remaining = need;
        while (remaining > 0) {
            int give = Math.min(remaining, maxPerStack);

            ItemStack ammoStack = AmmoItemBuilder.create()
                    .setId(ammoId)
                    .setCount(give)
                    .build();

            boolean addedAll = player.addItem(ammoStack);
            if (!addedAll && !ammoStack.isEmpty()) {
                player.drop(ammoStack, false);
            }

            remaining -= give;
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