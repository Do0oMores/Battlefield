package top.mores.battlefield;

import org.bukkit.plugin.java.JavaPlugin;

public final class Battlefield extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("Battlefield is enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("Battlefield is disabled!");
    }
}
