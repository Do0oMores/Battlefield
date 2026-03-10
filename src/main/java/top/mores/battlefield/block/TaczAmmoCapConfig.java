package top.mores.battlefield.block;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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

    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Integer>>() {
    }.getType();

    /**
     * 配置文件：
     * config/battlefield/tacz_ammo_caps.json
     */
    private static final Path FILE = FMLPaths.CONFIGDIR.get()
            .resolve(Battlefield.MODID)
            .resolve("tacz_ammo_caps.json");

    /**
     * gunId -> 总携弹上限
     * 例如：
     * {
     * "tacz:ak47": 90,
     * "tacz:m4a1": 120
     * }
     */
    private static final Map<String, Integer> CAPS = new LinkedHashMap<>();

    private static boolean loaded = false;

    private TaczAmmoCapConfig() {
    }

    /**
     * 初始化加载。建议服务端启动时调一次。
     */
    public static synchronized void init() {
        loadIfNeeded();
    }

    /**
     * 如未加载则加载。
     */
    private static void loadIfNeeded() {
        if (!loaded) {
            loadInternal();
        }
    }

    /**
     * 强制重载配置文件。
     */
    public static synchronized void reload() {
        loaded = false;
        CAPS.clear();
        loadInternal();
    }

    /**
     * 获取某把枪的总携弹上限。
     * 若不存在则返回 null。
     */
    public static synchronized Integer get(ResourceLocation gunId) {
        loadIfNeeded();
        if (gunId == null) return null;
        return CAPS.get(gunId.toString());
    }

    /**
     * 获取某把枪的总携弹上限。
     * 若不存在则写入默认值并保存。
     */
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

    /**
     * 设置某把枪的总携弹上限。
     */
    public static synchronized void set(ResourceLocation gunId, int value) {
        loadIfNeeded();
        if (gunId == null) return;

        CAPS.put(gunId.toString(), sanitize(value));
        saveInternal();
    }

    /**
     * 按字符串 gunId 设置，方便命令或外部调用。
     */
    public static synchronized void set(String gunId, int value) {
        loadIfNeeded();
        if (gunId == null || gunId.isBlank()) return;

        CAPS.put(gunId, sanitize(value));
        saveInternal();
    }

    /**
     * 删除某把枪配置。
     */
    public static synchronized boolean remove(ResourceLocation gunId) {
        loadIfNeeded();
        if (gunId == null) return false;

        boolean removed = CAPS.remove(gunId.toString()) != null;
        if (removed) {
            saveInternal();
        }
        return removed;
    }

    /**
     * 是否存在该枪配置。
     */
    public static synchronized boolean contains(ResourceLocation gunId) {
        loadIfNeeded();
        if (gunId == null) return false;
        return CAPS.containsKey(gunId.toString());
    }

    /**
     * 返回全部配置的只读副本。
     */
    public static synchronized Map<String, Integer> getAll() {
        loadIfNeeded();
        return Collections.unmodifiableMap(new LinkedHashMap<>(CAPS));
    }

    /**
     * 手动保存。
     */
    public static synchronized void save() {
        loadIfNeeded();
        saveInternal();
    }

    private static void loadInternal() {
        loaded = true;

        try {
            Files.createDirectories(FILE.getParent());

            if (!Files.exists(FILE)) {
                saveInternal();
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

            Battlefield.LOGGER.info("已加载 TACZ 总携弹上限配置，共 {} 条：{}", CAPS.size(), FILE);
        } catch (Exception e) {
            Battlefield.LOGGER.error("加载 TACZ 总携弹上限配置失败：{}", FILE, e);
            CAPS.clear();
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

    /**
     * 总携弹上限最小限制为 1。
     * 你也可以改成更高，比如最小 10。
     */
    private static int sanitize(int value) {
        return Math.max(1, value);
    }

    private static boolean isValidResourceLocation(String s) {
        try {
            return ResourceLocation.tryParse(s) != null;
        } catch (Exception e) {
            return false;
        }
    }
}
