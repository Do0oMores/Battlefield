package top.mores.battlefield.client;

import net.minecraft.world.item.ItemStack;

import java.util.*;

public final class DeployPreviewClientState {
    private DeployPreviewClientState() {}

    private static final Map<Integer, List<ItemStack>> PREVIEW_MAP = new HashMap<>();

    public static void putPreview(int bpSlot, List<ItemStack> items) {
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack stack : items) {
            copy.add(stack.copy());
        }
        PREVIEW_MAP.put(bpSlot, copy);
    }

    public static List<ItemStack> getPreview(int bpSlot) {
        return PREVIEW_MAP.getOrDefault(bpSlot, Collections.emptyList());
    }

    public static void clear() {
        PREVIEW_MAP.clear();
    }
}
