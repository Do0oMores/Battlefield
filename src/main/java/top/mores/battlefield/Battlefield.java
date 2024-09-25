package top.mores.battlefield;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import top.mores.battlefield.commands.MainCommand;

import java.io.File;
import java.util.Objects;

public final class Battlefield extends JavaPlugin {

    private static Battlefield instance;
    private YamlConfiguration data;
    private File dataFile;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        reloadConfig();
        setupDataFile();
        Objects.requireNonNull(getCommand("bf")).setExecutor(new MainCommand());
        getLogger().info("Battlefield is enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("Battlefield is disabled!");
    }

    // 初始化 data.yml 文件
    public void setupDataFile() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            saveResource("data.yml", false);
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
    }

    // 重载 data.yml 文件
    public void reloadDataFile() {
        if (dataFile == null) {
            dataFile = new File(getDataFolder(), "data.yml");
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
        getLogger().info("Data.yml has been reloaded!");
    }

    // 获取 data.yml 中的值
    public YamlConfiguration getData() {
        if (data == null) {
            reloadDataFile();
        }
        return data;
    }

    public static Battlefield getInstance() {
        return instance;
    }
}