package top.mores.battlefield.client;

import net.minecraft.world.item.ItemStack;
import top.mores.battlefield.protocol.SoldierClass;
import java.util.*;

public final class DeployPreviewClientState {
    private DeployPreviewClientState() {}
    private static final Map<SoldierClass,List<ItemStack>> PREVIEWS = new EnumMap<>(SoldierClass.class);
    private static final Map<SoldierClass,String> STATUS = new EnumMap<>(SoldierClass.class);
    public static void putPreview(SoldierClass type, List<ItemStack> items, String status) {
        PREVIEWS.put(type, items.stream().map(ItemStack::copy).toList()); STATUS.put(type,status);
    }
    public static List<ItemStack> getPreview(SoldierClass type) { return PREVIEWS.getOrDefault(type,List.of()); }
    public static String status(SoldierClass type) { return STATUS.getOrDefault(type,"正在请求配装"); }
    public static void clear() { PREVIEWS.clear(); STATUS.clear(); }
}