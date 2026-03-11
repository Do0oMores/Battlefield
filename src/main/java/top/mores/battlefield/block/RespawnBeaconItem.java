package top.mores.battlefield.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;
import top.mores.battlefield.game.RespawnBeaconManager;
import top.mores.battlefield.game.RespawnBeaconPlacement;

import java.util.Optional;
import java.util.UUID;

public class RespawnBeaconItem extends Item {
    public RespawnBeaconItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getPlayer() instanceof ServerPlayer sp)) {
            return InteractionResult.SUCCESS;
        }

        if (!GameSessionManager.isPlaying(sp)) { // TODO 替换为你的战局判断
            sp.displayClientMessage(Component.literal("当前不在战局中，无法部署复活信标"), true);
            return InteractionResult.FAIL;
        }

        UUID squadId = SquadManager.getSquadUuid(sp); // TODO 替换为你的小队方法
        if (squadId == null) {
            sp.displayClientMessage(Component.literal("你当前没有小队，无法部署复活信标"), true);
            return InteractionResult.FAIL;
        }

        BlockPos target = context.getClickedPos().relative(context.getClickedFace());
        Optional<Vec3> placePos = RespawnBeaconPlacement.findPlacePos(sp.serverLevel(), target);

        if (placePos.isEmpty()) {
            sp.displayClientMessage(Component.literal("此处无法部署复活信标"), true);
            return InteractionResult.FAIL;
        }

        RespawnBeaconManager.placeOrReplace(sp, placePos.get(), sp.getYRot());

        if (!sp.getAbilities().instabuild) {
            context.getItemInHand().shrink(1);
        }

        sp.displayClientMessage(Component.literal("复活信标已部署"), true);
        return InteractionResult.CONSUME;
    }
}
