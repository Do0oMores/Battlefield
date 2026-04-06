package top.mores.battlefield.block;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import com.tacz.guns.resource.index.CommonAmmoIndex;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
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
import top.mores.battlefield.net.BattlefieldNet;
import top.mores.battlefield.net.S2CAmmoStationCooldownPacket;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TaczAmmoStationBlock extends Block {

    private static final long COOLDOWN_TICKS = 15L * 20L;
    private static final Map<CooldownKey, Long> COOLDOWN_MAP = new HashMap<>();

    public TaczAmmoStationBlock(Properties properties) {
        super(properties);
    }

    @Override
    public @NotNull InteractionResult use(@NotNull BlockState state,
                                          @NotNull Level level,
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

        long now = level.getGameTime();
        CooldownKey cooldownKey = new CooldownKey(level.dimension(), pos.immutable(), player.getUUID());
        long remainTicks = getRemainingCooldown(now, cooldownKey);
        if (remainTicks > 0) {
            int remainSeconds = (int) Math.ceil(remainTicks / 20.0D);

            syncCooldownToClient(player, pos, remainTicks);

            player.displayClientMessage(
                    Component.literal("【冷却中，" + remainSeconds + "秒后可用】")
                            .withStyle(ChatFormatting.RED),
                    true
            );
            return true;
        }

        ItemStack gunStack = player.getMainHandItem();
        IGun iGun = IGun.getIGunOrNull(gunStack);
        if (iGun == null) {
            player.displayClientMessage(
                    Component.literal("主手物品不为枪械，无法补充备弹")
                            .withStyle(ChatFormatting.RED),
                    true
            );
            return true;
        }

        ResourceLocation gunId = iGun.getGunId(gunStack);
        int totalCarryCap = TaczAmmoCapConfig.getOrCreate(gunId, resolveDefaultTotalCarryCap(gunId));

        int currentMag = Math.max(0, iGun.getCurrentAmmoCount(gunStack));
        int reserveNow = getCurrentReserveAmmo(player, gunStack, iGun);

        if (reserveNow == Integer.MAX_VALUE) {
            player.displayClientMessage(
                    Component.literal("已有无限子弹，无需补给")
                            .withStyle(ChatFormatting.YELLOW),
                    true
            );
            return true;
        }

        int need = Math.max(0, totalCarryCap - currentMag - reserveNow);
        if (need == 0) {
            player.displayClientMessage(
                    Component.literal("弹药已满")
                            .withStyle(ChatFormatting.RED),
                    true
            );
            return true;
        }

        boolean ok;
        if (iGun.useDummyAmmo(gunStack)) {
            ok = refillDummyAmmo(gunStack, iGun, totalCarryCap, currentMag, need);
        } else {
            ok = giveInventoryAmmo(player, gunStack, need);
        }

        if (!ok) {
            player.displayClientMessage(
                    Component.literal("未找到对应的弹药类型")
                            .withStyle(ChatFormatting.RED),
                    true
            );
            return true;
        }

        COOLDOWN_MAP.put(cooldownKey, now + COOLDOWN_TICKS);
        syncCooldownToClient(player, pos, COOLDOWN_TICKS);

        player.displayClientMessage(
                Component.literal("已补给 " + need + " 发备弹")
                        .withStyle(ChatFormatting.GREEN),
                true
        );
        return true;
    }

    private static long getRemainingCooldown(long now, CooldownKey key) {
        Long endTick = COOLDOWN_MAP.get(key);
        if (endTick == null) {
            return 0L;
        }

        long remain = endTick - now;
        if (remain <= 0L) {
            COOLDOWN_MAP.remove(key);
            return 0L;
        }
        return remain;
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

    private static void syncCooldownToClient(Player player, BlockPos pos, long remainingTicks) {
        if (player instanceof ServerPlayer serverPlayer) {
            BattlefieldNet.sendToPlayer(serverPlayer,
                    new S2CAmmoStationCooldownPacket(pos, (int) Math.max(0L, remainingTicks)));
        }
    }

    private record CooldownKey(ResourceKey<Level> dimension, BlockPos pos, UUID playerId) {
    }

    public static void cleanupExpiredCooldowns(Level level) {
        if (level.isClientSide) {
            return;
        }

        long now = level.getGameTime();
        COOLDOWN_MAP.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    public static void removePlayerCooldowns(UUID playerId) {
        COOLDOWN_MAP.entrySet().removeIf(entry -> entry.getKey().playerId().equals(playerId));
    }
}