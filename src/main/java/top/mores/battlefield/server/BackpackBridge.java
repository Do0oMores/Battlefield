package top.mores.battlefield.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class BackpackBridge {
    private static volatile boolean initTried = false;
    private static volatile boolean available = false;

    private static final String BACKPACK_PLUGIN_NAME = "Backpack";

    private static final String[] SINGLE_BACKPACK_CLASS_CANDIDATES = new String[]{
            "top.mores.backpack.GUI.SingleBackpack",
            "top.mores.backpack.gui.SingleBackpack"
    };

    private static Class<?> CLS_BUKKIT;
    private static Method M_BUKKIT_getPluginManager;
    private static Method M_BUKKIT_isPrimaryThread;
    private static Method M_BUKKIT_getScheduler;
    private static Method M_BUKKIT_getServer;

    private static Method M_PM_getPlugin;
    private static Method M_SCHED_runTask;

    private static Object backpackPlugin; // JavaPlugin instance

    private static Class<?> CLS_SINGLE_BACKPACK;
    private static Constructor<?> CTOR_SINGLE_BACKPACK;
    private static Method M_SYNC_SINGLE_BACKPACK;      // SyncSingleBackpack(Player,int)
    private static Method M_SINGLE_BACKPACK_ITEMS;     // SingleBackpackItems(UUID,int)

    // CraftItemStack 反射
    private static Class<?> CLS_CRAFT_ITEM_STACK;
    private static Method M_CRAFT_asNMSCopy;

    private BackpackBridge() {
    }

    /**
     * 在战地部署成功后调用：给玩家应用某个背包槽位
     */
    public static boolean applyBackpackPreset(ServerPlayer serverPlayer, int slot) {
        try {
            if (!ensureInit()) return false;

            Object bukkitPlayer = toBukkitPlayer(serverPlayer);
            if (bukkitPlayer == null) return false;

            if (isPrimaryThread()) {
                invokeSyncSingleBackpack(bukkitPlayer, slot);
            } else {
                Object scheduler = M_BUKKIT_getScheduler.invoke(null);
                Runnable task = () -> {
                    try {
                        invokeSyncSingleBackpack(bukkitPlayer, slot);
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                };
                M_SCHED_runTask.invoke(scheduler, backpackPlugin, task);
            }

            return true;
        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        }
    }

    /**
     * 读取某个背包槽位的预览物品，返回 Forge/NMS ItemStack 列表给客户端显示
     */
    public static List<ItemStack> getBackpackPreview(ServerPlayer player, int bpSlot) {
        List<ItemStack> result = new ArrayList<>();
        try {
            if (!ensureInit()) return result;
            if (M_SINGLE_BACKPACK_ITEMS == null || CTOR_SINGLE_BACKPACK == null) return result;
            Object single = CTOR_SINGLE_BACKPACK.newInstance();
            Object raw = M_SINGLE_BACKPACK_ITEMS.invoke(single, player.getUUID(), bpSlot);
            if (raw == null) return result;

            List<Object> bukkitItems = new ArrayList<>();
            // 支持 ItemStack[] / List<ItemStack> 两种返回形式
            if (raw.getClass().isArray()) {
                int len = Array.getLength(raw);
                for (int i = 0; i < len; i++) {
                    bukkitItems.add(Array.get(raw, i));
                }
            } else if (raw instanceof Iterable<?>) {
                for (Object o : (Iterable<?>) raw) {
                    bukkitItems.add(o);
                }
            } else {
                return result;
            }
            for (Object bukkitStack : bukkitItems) {
                if (bukkitStack == null) continue;
                if (isBukkitAir(bukkitStack)) continue;

                ItemStack mcStack = bukkitToMc(bukkitStack);
                if (mcStack == null || mcStack.isEmpty()) continue;

                result.add(mcStack);

                // 预览最多发 7 个非空物品
                if (result.size() >= 7) {
                    break;
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }

        return result;
    }

    // -------------------- internal --------------------

    private static void invokeSyncSingleBackpack(Object bukkitPlayer, int slot) throws Throwable {
        if (CTOR_SINGLE_BACKPACK == null || M_SYNC_SINGLE_BACKPACK == null) return;
        Object single = CTOR_SINGLE_BACKPACK.newInstance();
        M_SYNC_SINGLE_BACKPACK.invoke(single, bukkitPlayer, slot);
    }

    private static Object toBukkitPlayer(ServerPlayer serverPlayer) {
        try {
            Method m = serverPlayer.getClass().getMethod("getBukkitEntity");
            return m.invoke(serverPlayer);
        } catch (Throwable ignored) {
        }

        try {
            for (Method m : serverPlayer.getClass().getMethods()) {
                if (m.getName().equals("getBukkitEntity") && m.getParameterCount() == 0) {
                    return m.invoke(serverPlayer);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isPrimaryThread() {
        try {
            Object r = M_BUKKIT_isPrimaryThread.invoke(null);
            return (r instanceof Boolean) && (Boolean) r;
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean ensureInit() {
        if (initTried) return available;

        synchronized (BackpackBridge.class) {
            if (initTried) return available;
            initTried = true;

            try {
                // ===== Bukkit 基础 =====
                CLS_BUKKIT = Class.forName("org.bukkit.Bukkit");
                M_BUKKIT_getPluginManager = CLS_BUKKIT.getMethod("getPluginManager");
                M_BUKKIT_isPrimaryThread = CLS_BUKKIT.getMethod("isPrimaryThread");
                M_BUKKIT_getScheduler = CLS_BUKKIT.getMethod("getScheduler");
                M_BUKKIT_getServer = CLS_BUKKIT.getMethod("getServer");

                Object pm = M_BUKKIT_getPluginManager.invoke(null);
                M_PM_getPlugin = pm.getClass().getMethod("getPlugin", String.class);

                backpackPlugin = M_PM_getPlugin.invoke(pm, BACKPACK_PLUGIN_NAME);
                if (backpackPlugin == null) {
                    available = false;
                    return false;
                }

                Object scheduler = M_BUKKIT_getScheduler.invoke(null);
                for (Method m : scheduler.getClass().getMethods()) {
                    if (!m.getName().equals("runTask")) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 2 && Runnable.class.isAssignableFrom(p[1])) {
                        M_SCHED_runTask = m;
                        break;
                    }
                }
                if (M_SCHED_runTask == null) {
                    available = false;
                    return false;
                }

                // ===== 反射加载插件中的 SingleBackpack =====
                ClassLoader pluginCl = backpackPlugin.getClass().getClassLoader();

                CLS_SINGLE_BACKPACK = null;
                for (String name : SINGLE_BACKPACK_CLASS_CANDIDATES) {
                    try {
                        CLS_SINGLE_BACKPACK = Class.forName(name, true, pluginCl);
                        break;
                    } catch (Throwable ignored) {
                    }
                }

                if (CLS_SINGLE_BACKPACK == null) {
                    available = false;
                    return false;
                }

                CTOR_SINGLE_BACKPACK = CLS_SINGLE_BACKPACK.getDeclaredConstructor();
                CTOR_SINGLE_BACKPACK.setAccessible(true);

                // 找 SyncSingleBackpack(Player, int)
                for (Method m : CLS_SINGLE_BACKPACK.getMethods()) {
                    if (!m.getName().equals("SyncSingleBackpack")) continue;
                    if (m.getParameterCount() != 2) continue;
                    if (m.getParameterTypes()[1] != int.class) continue;
                    M_SYNC_SINGLE_BACKPACK = m;
                    break;
                }

                // 找 SingleBackpackItems(UUID, int) 或 getBackpackItems(UUID, int)
                for (Method m : CLS_SINGLE_BACKPACK.getMethods()) {
                    if (!(m.getName().equals("SingleBackpackItems") || m.getName().equals("getBackpackItems"))) {
                        continue;
                    }
                    if (m.getParameterCount() != 2) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p[0] == UUID.class && p[1] == int.class) {
                        M_SINGLE_BACKPACK_ITEMS = m;
                        break;
                    }
                }

                // 预览功能需要这个方法
                if (M_SINGLE_BACKPACK_ITEMS == null) {
                    available = false;
                    return false;
                }

                available = true;
                return true;
            } catch (Throwable t) {
                t.printStackTrace();
                available = false;
                return false;
            }
        }
    }

    private static boolean isBukkitAir(Object bukkitStack) {
        try {
            Method getType = bukkitStack.getClass().getMethod("getType");
            Object material = getType.invoke(bukkitStack);
            if (material == null) return true;

            try {
                Method isAir = material.getClass().getMethod("isAir");
                Object r = isAir.invoke(material);
                if (r instanceof Boolean) {
                    return (Boolean) r;
                }
            } catch (Throwable ignored) {
            }

            return "AIR".equalsIgnoreCase(String.valueOf(material));
        } catch (Throwable t) {
            return false;
        }
    }

    private static ItemStack bukkitToMc(Object bukkitStack) {
        try {
            ensureCraftItemStackInit();
            if (M_CRAFT_asNMSCopy == null) {
                return ItemStack.EMPTY;
            }

            Object mc = M_CRAFT_asNMSCopy.invoke(null, bukkitStack);
            if (mc instanceof ItemStack) {
                return ((ItemStack) mc).copy();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return ItemStack.EMPTY;
    }

    private static void ensureCraftItemStackInit() {
        if (M_CRAFT_asNMSCopy != null) return;

        synchronized (BackpackBridge.class) {
            if (M_CRAFT_asNMSCopy != null) return;

            try {
                // 先根据 Bukkit 服务端真实包名推导
                Object server = M_BUKKIT_getServer.invoke(null);
                String serverPkg = server.getClass().getPackage().getName();
                String craftItemStackName;

                if (serverPkg.endsWith(".CraftServer")) {
                    craftItemStackName = serverPkg.substring(0, serverPkg.length() - ".CraftServer".length())
                            + ".inventory.CraftItemStack";
                } else {
                    craftItemStackName = "org.bukkit.craftbukkit.inventory.CraftItemStack";
                }

                try {
                    CLS_CRAFT_ITEM_STACK = Class.forName(craftItemStackName);
                } catch (Throwable ignored) {
                    // 再兜底几个常见路径
                    try {
                        CLS_CRAFT_ITEM_STACK = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack");
                    } catch (Throwable ignored2) {
                        CLS_CRAFT_ITEM_STACK = Class.forName("org.bukkit.craftbukkit.v1_20_R1.inventory.CraftItemStack");
                    }
                }

                Method found = null;
                for (Method m : CLS_CRAFT_ITEM_STACK.getMethods()) {
                    if (!m.getName().equals("asNMSCopy")) continue;
                    if (m.getParameterCount() != 1) continue;
                    found = m;
                    break;
                }

                M_CRAFT_asNMSCopy = found;
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }
}