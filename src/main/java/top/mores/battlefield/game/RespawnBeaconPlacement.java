package top.mores.battlefield.game;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

public final class RespawnBeaconPlacement {
    private RespawnBeaconPlacement() {
    }

    public static Optional<Vec3> findPlacePos(ServerLevel level, BlockPos clicked) {
        // 向下吸附地面，避免玩家点到半空
        for (int dy = 2; dy >= -4; dy--) {
            BlockPos feet = clicked.offset(0, dy, 0);
            if (canPlaceBeaconAt(level, feet)) {
                return Optional.of(new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5));
            }
        }
        return Optional.empty();
    }

    public static Optional<Vec3> findStandablePosNearBeacon(ServerLevel level, Vec3 center) {
        BlockPos origin = BlockPos.containing(center);
        int[][] offsets = {
                {0, 0},
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
                {2, 0}, {-2, 0}, {0, 2}, {0, -2}
        };

        for (int[] off : offsets) {
            BlockPos feet = origin.offset(off[0], 0, off[1]);
            if (canStand(level, feet)) {
                return Optional.of(new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5));
            }
        }
        return Optional.empty();
    }

    private static boolean canPlaceBeaconAt(ServerLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        BlockState belowState = level.getBlockState(below);

        if (belowState.getCollisionShape(level, below).isEmpty()) return false;
        if (!level.getBlockState(feet).canBeReplaced()) return false;
        if (!level.getBlockState(feet.above()).canBeReplaced()) return false;

        AABB box = new AABB(
                feet.getX() + 0.05, feet.getY(), feet.getZ() + 0.05,
                feet.getX() + 0.95, feet.getY() + 1.20, feet.getZ() + 0.95
        );
        return level.noCollision(box);
    }

    private static boolean canStand(ServerLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        BlockState belowState = level.getBlockState(below);

        if (belowState.getCollisionShape(level, below).isEmpty()) return false;

        AABB playerBox = new AABB(
                feet.getX() + 0.2, feet.getY(), feet.getZ() + 0.2,
                feet.getX() + 0.8, feet.getY() + 1.8, feet.getZ() + 0.8
        );

        if (!level.noCollision(playerBox)) return false;
        if (level.containsAnyLiquid(playerBox)) return false;

        return true;
    }
}
