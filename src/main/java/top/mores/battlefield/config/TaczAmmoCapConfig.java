package top.mores.battlefield.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import top.mores.battlefield.Battlefield;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TaczAmmoCapConfig {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Integer>>() {}.getType();

    private static final Path FILE = FMLPaths.CONFIGDIR.get()
            .resolve(Battlefield.MODID)
            .resolve("tacz_ammo_caps.json");

    private static final Map<String, Integer> CAPS = new LinkedHashMap<>();
    private static boolean loaded = false;

    private TaczAmmoCapConfig() {
    }

    public static synchronized void init() {
        loadIfNeeded();
    }

    private static void loadIfNeeded() {
        if (!loaded) {
            loadInternal();
        }
    }

    public static synchronized void reload() {
        loaded = false;
        CAPS.clear();
        loadInternal();
    }

    public static synchronized Integer get(ResourceLocation gunId) {
        loadIfNeeded();
        if (gunId == null) return null;
        return CAPS.get(gunId.toString());
    }

    public static synchronized int getOrCreate(ResourceLocation gunId, int defaultValue) {
        loadIfNeeded();
        if (gunId == null) {
            return sanitize(defaultValue);
        }

        String key = gunId.toString();
        Integer value = CAPS.get(key);
        if (value != null) {
            return sanitize(value);
        }

        int sanitized = sanitize(defaultValue);
        CAPS.put(key, sanitized);
        saveInternal();
        return sanitized;
    }

    public static synchronized void set(ResourceLocation gunId, int value) {
        loadIfNeeded();
        if (gunId == null) return;

        CAPS.put(gunId.toString(), sanitize(value));
        saveInternal();
    }

    public static synchronized boolean remove(ResourceLocation gunId) {
        loadIfNeeded();
        if (gunId == null) return false;

        boolean removed = CAPS.remove(gunId.toString()) != null;
        if (removed) {
            saveInternal();
        }
        return removed;
    }

    public static synchronized boolean contains(ResourceLocation gunId) {
        loadIfNeeded();
        if (gunId == null) return false;
        return CAPS.containsKey(gunId.toString());
    }

    public static synchronized Map<String, Integer> getAll() {
        loadIfNeeded();
        return Collections.unmodifiableMap(new LinkedHashMap<>(CAPS));
    }

    public static synchronized void save() {
        loadIfNeeded();
        saveInternal();
    }

    private static void loadInternal() {
        try {
            Files.createDirectories(FILE.getParent());

            if (!Files.exists(FILE)) {
                CAPS.clear();
                saveInternal();
                loaded = true;
                Battlefield.LOGGER.info("已创建 TACZ 总携弹上限配置文件：{}", FILE);
                return;
            }

            try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                Map<String, Integer> data = GSON.fromJson(reader, MAP_TYPE);

                CAPS.clear();
                if (data != null) {
                    for (Map.Entry<String, Integer> entry : data.entrySet()) {
                        String gunId = entry.getKey();
                        Integer value = entry.getValue();

                        if (gunId == null || gunId.isBlank()) continue;
                        if (!isValidResourceLocation(gunId)) {
                            Battlefield.LOGGER.warn("忽略非法 gunId 配置：{}", gunId);
                            continue;
                        }

                        CAPS.put(gunId, sanitize(value == null ? 30 : value));
                    }
                }
            }

            loaded = true;
            Battlefield.LOGGER.info("已加载 TACZ 总携弹上限配置，共 {} 条：{}", CAPS.size(), FILE);
        } catch (Exception e) {
            loaded = false;
            CAPS.clear();
            Battlefield.LOGGER.error("加载 TACZ 总携弹上限配置失败：{}", FILE, e);
        }
    }

    private static void saveInternal() {
        try {
            Files.createDirectories(FILE.getParent());

            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(CAPS, MAP_TYPE, writer);
            }
        } catch (Exception e) {
            Battlefield.LOGGER.error("保存 TACZ 总携弹上限配置失败：{}", FILE, e);
        }
    }

    private static int sanitize(int value) {
        return Math.max(1, value);
    }

    private static boolean isValidResourceLocation(String s) {
        return ResourceLocation.tryParse(s) != null;
    }
}