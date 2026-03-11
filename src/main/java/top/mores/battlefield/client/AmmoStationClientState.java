package top.mores.battlefield.client;

import net.minecraft.Util;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

public final class AmmoStationClientState {
    private AmmoStationClientState() {
    }

    /** 方块位置 -> 本地冷却结束时间(ms) */
    private static final Map<BlockPos, Long> COOLDOWN_END_MS = new HashMap<>();

    public static void setCooldown(BlockPos pos, int remainingTicks) {
        BlockPos key = pos.immutable();
        if (remainingTicks <= 0) {
            COOLDOWN_END_MS.remove(key);
            return;
        }
        long endMs = Util.getMillis() + remainingTicks * 50L;
        COOLDOWN_END_MS.put(key, endMs);
    }

    public static int getRemainingSeconds(BlockPos pos) {
        Long endMs = COOLDOWN_END_MS.get(pos);
        if (endMs == null) {
            return 0;
        }

        long remainMs = endMs - Util.getMillis();
        if (remainMs <= 0L) {
            COOLDOWN_END_MS.remove(pos);
            return 0;
        }

        return (int) Math.ceil(remainMs / 1000.0D);
    }

    public static boolean isCooling(BlockPos pos) {
        return getRemainingSeconds(pos) > 0;
    }

    public static void clear() {
        COOLDOWN_END_MS.clear();
    }

    public static void cleanupExpired() {
        long now = Util.getMillis();
        COOLDOWN_END_MS.entrySet().removeIf(e -> e.getValue() <= now);
    }
}