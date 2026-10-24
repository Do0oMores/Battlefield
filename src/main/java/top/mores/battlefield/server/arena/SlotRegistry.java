package top.mores.battlefield.server.arena;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;
import top.mores.battlefield.config.BattlefieldServerConfig;

import javax.annotation.Nullable;
import java.util.*;

/**
 * 战场维度的槽位登记表（锚定主世界，跨重启保留）。
 * <p>
 * 一个槽位一旦分给某个 arena 就固定归它（不同模板之间不互相覆盖），记录：
 * <ul>
 *   <li>槽位里现在装的模板指纹（模板文件更新后能发现需要重新还原）；</li>
 *   <li>是否干净（对局开始置脏，还原完成置干净）。</li>
 * </ul>
 * 另有一个 {@code running} 标记：开服时置 true、正常关服时置 false。
 * 开服时发现它仍为 true，说明上次是崩服/强杀，此时所有槽位一律视为脏，重新还原一遍——
 * 因为「槽位已干净」这条记录可能先于区块本身落盘。
 */
public class SlotRegistry extends SavedData {
    private static final String DATA_NAME = "battlefield_arena_slots";

    public static final class Slot {
        public final int index;
        public final String arena;
        String fingerprint = "";
        boolean clean = false;
        /** 上次还原时模板放置的位置（区块坐标），槽位配置改动后能识别出来。 */
        long installedAt = Long.MIN_VALUE;

        Slot(int index, String arena) {
            this.index = index;
            this.arena = arena;
        }

        public boolean isClean(String expectedFingerprint, SlotGeometry geometry) {
            return clean && fingerprint.equals(expectedFingerprint) && installedAt == geometry.originKey();
        }

        public String fingerprint() {
            return fingerprint;
        }

        public boolean clean() {
            return clean;
        }
    }

    private final Map<Integer, Slot> slots = new TreeMap<>();
    private boolean running;

    public static SlotRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(SlotRegistry::load, SlotRegistry::new, DATA_NAME);
    }

    /**
     * 开服时调用一次。
     *
     * @return 上次是否异常退出
     */
    public boolean markRunning() {
        boolean crashed = running;
        if (crashed) {
            for (Slot s : slots.values()) s.clean = false;
        }
        running = true;
        setDirty();
        return crashed;
    }

    /** 正常关服（ServerStopping）时调用，随最后一次存档落盘。 */
    public void markStopped() {
        running = false;
        setDirty();
    }

    public List<Slot> slotsOf(String arena) {
        List<Slot> list = new ArrayList<>();
        for (Slot s : slots.values()) {
            if (s.arena.equals(arena)) list.add(s);
        }
        return list;
    }

    public Collection<Slot> all() {
        return Collections.unmodifiableCollection(slots.values());
    }

    @Nullable
    public Slot byIndex(int index) {
        return slots.get(index);
    }

    /** 给 arena 新分配一个槽位；全服槽位已满时返回 null。 */
    @Nullable
    public Slot allocate(String arena) {
        int max = BattlefieldServerConfig.get().maxSlots;
        for (int i = 0; i < max; i++) {
            if (!slots.containsKey(i)) {
                Slot slot = new Slot(i, arena);
                slots.put(i, slot);
                setDirty();
                return slot;
            }
        }
        return null;
    }

    public void markDirty(Slot slot) {
        if (!slot.clean) return;
        slot.clean = false;
        setDirty();
    }

    public void markClean(Slot slot, String fingerprint, SlotGeometry geometry) {
        slot.clean = true;
        slot.fingerprint = fingerprint;
        slot.installedAt = geometry.originKey();
        setDirty();
    }

    public static SlotRegistry load(CompoundTag tag) {
        SlotRegistry data = new SlotRegistry();
        data.running = tag.getBoolean("running");
        ListTag list = tag.getList("slots", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            Slot slot = new Slot(e.getInt("index"), e.getString("arena"));
            slot.fingerprint = e.getString("fingerprint");
            slot.clean = e.getBoolean("clean");
            slot.installedAt = e.contains("installedAt") ? e.getLong("installedAt") : Long.MIN_VALUE;
            data.slots.put(slot.index, slot);
        }
        return data;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag) {
        tag.putBoolean("running", running);
        ListTag list = new ListTag();
        for (Slot s : slots.values()) {
            CompoundTag e = new CompoundTag();
            e.putInt("index", s.index);
            e.putString("arena", s.arena);
            e.putString("fingerprint", s.fingerprint);
            e.putBoolean("clean", s.clean);
            e.putLong("installedAt", s.installedAt);
            list.add(e);
        }
        tag.put("slots", list);
        return tag;
    }
}
