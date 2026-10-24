package top.mores.battlefield.server.arena;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.config.BattlefieldServerConfig;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 槽位还原任务调度。所有任务共享同一份每 tick 预算（{@code restoreBudgetMillis}），
 * 并发还原再多也不会把单个 tick 拖长超过预算。只在主线程访问。
 */
@Mod.EventBusSubscriber(modid = Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SlotRestoreService {
    private SlotRestoreService() {
    }

    static final TicketType<ChunkPos> TICKET = TicketType.create("battlefield_restore", Comparator.comparingLong(ChunkPos::toLong));

    /** 槽位号 → 进行中的任务 */
    private static final Map<Integer, SlotRestoreJob> JOBS = new LinkedHashMap<>();

    /**
     * 安排还原。同一槽位已有任务时直接返回它（不重复排队）。
     */
    public static SlotRestoreJob schedule(ServerLevel level, SlotRegistry.Slot slot, SlotGeometry geometry,
                                          CompletableFuture<ArenaTemplate> template) {
        SlotRestoreJob existing = JOBS.get(slot.index);
        if (existing != null && !existing.finished()) return existing;

        SlotRegistry.get(level.getServer()).markDirty(slot);
        SlotRestoreJob job = new SlotRestoreJob(level, slot, geometry, template);
        JOBS.put(slot.index, job);
        return job;
    }

    @Nullable
    public static SlotRestoreJob jobFor(int slotIndex) {
        SlotRestoreJob job = JOBS.get(slotIndex);
        return job == null || job.finished() ? null : job;
    }

    public static int activeJobs() {
        int n = 0;
        for (SlotRestoreJob job : JOBS.values()) if (!job.finished()) n++;
        return n;
    }

    public static void cancelAll() {
        for (SlotRestoreJob job : JOBS.values()) job.cancel();
        JOBS.clear();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || JOBS.isEmpty()) return;

        long deadline = System.nanoTime() + BattlefieldServerConfig.get().restoreBudgetMillis * 1_000_000L;
        // 轮转：每 tick 从不同任务开始，避免某个大任务长期饿死后面的
        List<SlotRestoreJob> order = new ArrayList<>(JOBS.values());
        Collections.rotate(order, -(int) (System.nanoTime() & 0xFF) % Math.max(1, order.size()));
        for (SlotRestoreJob job : order) {
            if (System.nanoTime() >= deadline) break;
            try {
                job.step(deadline);
            } catch (Exception ex) {
                Battlefield.LOGGER.error("[Battlefield] 槽位 #{} 还原异常，任务已中止（槽位保持为脏，下次使用前会重试）",
                        job.slot.index, ex);
                job.cancel();
            }
        }
        JOBS.values().removeIf(SlotRestoreJob::finished);
    }
}
