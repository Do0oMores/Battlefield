package top.mores.battlefield.server;

import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public final class BackpackBridge {
    private static volatile boolean initTried=false;
    private static volatile boolean available=false;

    private static Class<?> CLS_BUKKIT;
    private static Method M_BUKKIT_getPluginManager;
    private static Method M_PM_getPlugin;
    private static Method M_BUKKIT_isPrimaryThread;
    private static Method M_BUKKIT_getScheduler;
    private static Method M_SCHED_runTask;

    // Backpack plugin
    private static Object backpackPlugin; // JavaPlugin instance
    private static String backpackPluginName = "Backpack";

    // SingleBackpack
    private static Class<?> CLS_SINGLE_BACKPACK;
    private static Constructor<?> CTOR_SINGLE_BACKPACK;
    private static Method M_SYNC_SINGLE_BACKPACK;

    private BackpackBridge() {}

    /**
     * 在战地部署成功后调用：给玩家应用某个背包槽位
     */
    public static boolean applyBackpackPreset(ServerPlayer serverPlayer, int slot) {
        try {
            if (!ensureInit()) return false;

            Object bukkitPlayer = toBukkitPlayer(serverPlayer);
            if (bukkitPlayer == null) return false;

            // Bukkit 主线程执行
            if (isPrimaryThread()) {
                invokeSyncSingleBackpack(bukkitPlayer, slot);
            } else {
                // scheduler.runTask(plugin, Runnable)
                Object scheduler = M_BUKKIT_getScheduler.invoke(null);
                Runnable task = () -> {
                    try {
                        invokeSyncSingleBackpack(bukkitPlayer, slot);
                    } catch (Throwable t) {
                        // swallow to avoid crashing server tick
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

    // -------------------- internal --------------------

    private static void invokeSyncSingleBackpack(Object bukkitPlayer, int slot) throws Throwable {
        Object single = CTOR_SINGLE_BACKPACK.newInstance();
        // SyncSingleBackpack(Player player, int slot)
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
                // ===== Bukkit =====
                CLS_BUKKIT = Class.forName("org.bukkit.Bukkit");
                M_BUKKIT_getPluginManager = CLS_BUKKIT.getMethod("getPluginManager");
                M_BUKKIT_isPrimaryThread = CLS_BUKKIT.getMethod("isPrimaryThread");
                M_BUKKIT_getScheduler = CLS_BUKKIT.getMethod("getScheduler");

                Object pm = M_BUKKIT_getPluginManager.invoke(null);

                // PluginManager#getPlugin(String)
                M_PM_getPlugin = pm.getClass().getMethod("getPlugin", String.class);

                backpackPlugin = M_PM_getPlugin.invoke(pm, backpackPluginName);
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

                ClassLoader pluginCl = backpackPlugin.getClass().getClassLoader();

                CLS_SINGLE_BACKPACK = Class.forName("top.mores.backpack.GUI.SingleBackpack", true, pluginCl);

                CTOR_SINGLE_BACKPACK = CLS_SINGLE_BACKPACK.getDeclaredConstructor();
                CTOR_SINGLE_BACKPACK.setAccessible(true);

                Method found = null;
                for (Method m : CLS_SINGLE_BACKPACK.getMethods()) {
                    if (!m.getName().equals("SyncSingleBackpack")) continue;
                    if (m.getParameterCount() != 2) continue;
                    if (m.getParameterTypes()[1] != int.class) continue;
                    found = m;
                    break;
                }
                M_SYNC_SINGLE_BACKPACK = found;

                if (M_SYNC_SINGLE_BACKPACK == null) {
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
}
