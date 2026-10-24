package top.mores.battlefield.server.arena;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import top.mores.battlefield.Battlefield;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 把一个槽位还原成模板的样子。
 * <p>
 * 做法是<b>逐方块比对</b>而不是「记录改动再撤销」：不管方块是被爆炸、玩家、火、活塞、
 * 还是别的 mod / 插件改掉的，只要和模板不一样就改回去，因此不存在漏网的改动来源。
 * 方块实体（箱子内容等）、群系、实体（掉落物、箭、TACZ 子弹、信标、盔甲架……）一并处理。
 * <p>
 * 全部工作在主线程分片执行，每 tick 只用 {@code restoreBudgetMillis} 毫秒，可在任意方块处暂停续跑。
 * 区块由「非 tick 级」票据（level 33 = FULL）异步加载，加载期间随机刻、流体、计划刻都不会运行，
 * 不会出现边还原边流水的情况。
 */
public final class SlotRestoreJob {

    public enum State {WAITING_TEMPLATE, RUNNING, DONE, FAILED, CANCELLED}

    private enum ChunkPhase {BLOCKS, BLOCK_ENTITIES, BIOMES, ENTITIES}

    /** 不通知邻居、不更新形状、不掉落：只把方块精确改成目标状态，并同步给客户端。 */
    private static final int SET_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    private static final int BUDGET_CHECK_EVERY = 512;
    /** 第一遍有改动时再完整比对一遍，兜住「放置时触发了活塞/重力方块」之类的连锁变化。 */
    private static final int MAX_PASSES = 2;

    /** Mohist 若改过 LevelChunkSection / 光照引擎的签名，整块安装会抛 LinkageError，此后全服退回逐方块比对。 */
    private static volatile boolean bulkUnsupported;

    final ServerLevel level;
    final SlotRegistry.Slot slot;
    final SlotGeometry geometry;
    private final CompletableFuture<ArenaTemplate> templateFuture;
    private final List<Runnable> onDone = new ArrayList<>();

    private State state = State.WAITING_TEMPLATE;
    private ArenaTemplate template;
    private final List<ChunkPos> chunks = new ArrayList<>();

    private int pass = 1;
    private int next;
    private int ticketHead;
    private ChunkPhase phase = ChunkPhase.BLOCKS;
    private int sectionIdx;
    private int blockIdx;

    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    // 统计
    private long changedBlocks;
    private long changedThisPass;
    private long bulkChunks;
    private long blockEntityUpdates;
    private long entitiesRemoved;
    private long entitiesSpawned;
    private long busyNanos;
    private final long createdAtTick;

    SlotRestoreJob(ServerLevel level, SlotRegistry.Slot slot, SlotGeometry geometry, CompletableFuture<ArenaTemplate> templateFuture) {
        this.level = level;
        this.slot = slot;
        this.geometry = geometry;
        this.templateFuture = templateFuture;
        this.createdAtTick = level.getServer().getTickCount();
    }

    public void whenDone(Runnable callback) {
        if (state == State.DONE) {
            callback.run();
        } else {
            onDone.add(callback);
        }
    }

    public State state() {
        return state;
    }

    public boolean finished() {
        return state == State.DONE || state == State.FAILED || state == State.CANCELLED;
    }

    /** 0..1，供等待界面显示。 */
    public float progress() {
        if (state == State.DONE) return 1f;
        if (chunks.isEmpty()) return 0f;
        float perPass = (next + (phase.ordinal() / (float) ChunkPhase.values().length)) / chunks.size();
        return Math.min(0.999f, pass == 1 ? perPass * 0.9f : 0.9f + perPass * 0.1f);
    }

    /**
     * 推进一点工作。
     *
     * @return true 表示本次确实做了工作（消耗了预算）；false 表示正在等待（模板未读完 / 区块未加载）
     */
    boolean step(long deadlineNanos) {
        if (finished()) return false;

        if (state == State.WAITING_TEMPLATE) {
            if (!templateFuture.isDone()) return false;
            try {
                template = templateFuture.join();
            } catch (Exception e) {
                Battlefield.LOGGER.error("[Battlefield] 槽位 #{} 还原失败：模板读取异常", slot.index, e);
                state = State.FAILED;
                return false;
            }
            buildChunkList();
            state = State.RUNNING;
            Battlefield.LOGGER.info("[Battlefield] 槽位 #{}（{}）开始还原：{} 个区块，原点区块 ({}, {})",
                    slot.index, slot.arena, chunks.size(), geometry.originChunkX(), geometry.originChunkZ());
        }

        long begin = System.nanoTime();
        boolean worked = false;
        try {
            while (System.nanoTime() < deadlineNanos) {
                if (next >= chunks.size()) {
                    if (pass < MAX_PASSES && changedThisPass > 0) {
                        pass++;
                        changedThisPass = 0;
                        next = 0;
                        ticketHead = 0;
                        continue;
                    }
                    finish();
                    return true;
                }

                ensureTickets();
                ChunkPos pos = chunks.get(next);
                LevelChunk live = level.getChunkSource().getChunkNow(pos.x, pos.z);
                if (live == null) return worked;

                if (!processChunk(live, pos, deadlineNanos)) {
                    return true;
                }
                worked = true;
                releaseTicket(pos);
                next++;
                phase = ChunkPhase.BLOCKS;
                sectionIdx = 0;
                blockIdx = 0;
            }
            return true;
        } finally {
            busyNanos += System.nanoTime() - begin;
        }
    }

    void cancel() {
        if (finished()) return;
        for (int i = next; i < ticketHead; i++) {
            releaseTicket(chunks.get(i));
        }
        state = State.CANCELLED;
    }

    private void buildChunkList() {
        var b = template.bounds;
        for (int cz = b.minChunkZ(); cz <= b.maxChunkZ(); cz++) {
            for (int cx = b.minChunkX(); cx <= b.maxChunkX(); cx++) {
                chunks.add(new ChunkPos(cx + geometry.offsetChunkX(), cz + geometry.offsetChunkZ()));
            }
        }
    }

    private void ensureTickets() {
        int window = top.mores.battlefield.config.BattlefieldServerConfig.get().restoreChunkWindow;
        while (ticketHead < chunks.size() && ticketHead < next + window) {
            ChunkPos pos = chunks.get(ticketHead++);
            level.getChunkSource().addRegionTicket(SlotRestoreService.TICKET, pos, 0, pos);
        }
    }

    private void releaseTicket(ChunkPos pos) {
        level.getChunkSource().removeRegionTicket(SlotRestoreService.TICKET, pos, 0, pos);
    }

    /** @return true 表示该区块处理完毕；false 表示预算用完，下次从游标处继续 */
    private boolean processChunk(LevelChunk live, ChunkPos pos, long deadlineNanos) {
        ArenaTemplate.Chunk source = template.chunk(pos.x - geometry.offsetChunkX(), pos.z - geometry.offsetChunkZ());

        if (phase == ChunkPhase.BLOCKS) {
            // 全新的空槽位（区块里一个方块都没有）：整段拷贝 + 走一遍原版的光照初始化，
            // 比逐方块 setBlock 快两到三个数量级。首次安装一张真实地形的地图动辄几千万个方块，逐个放要几十分钟。
            if (pass == 1 && sectionIdx == 0 && blockIdx == 0 && source != null && !bulkUnsupported && isAllAir(live)
                    && bulkInstall(live, pos, source)) {
                phase = ChunkPhase.ENTITIES;
            } else {
                if (!restoreBlocks(live, pos, source, deadlineNanos)) return false;
                phase = ChunkPhase.BLOCK_ENTITIES;
            }
        }
        if (phase == ChunkPhase.BLOCK_ENTITIES) {
            if (source != null) restoreBlockEntities(live, source);
            phase = ChunkPhase.BIOMES;
        }
        if (phase == ChunkPhase.BIOMES) {
            if (source != null && pass == 1) restoreBiomes(live, pos, source);
            phase = ChunkPhase.ENTITIES;
        }
        if (phase == ChunkPhase.ENTITIES) {
            if (pass > 1) return true;
            // 实体分区在区块到 FULL 后异步加载，没加载完就清理会漏掉旧实体
            if (!level.areEntitiesLoaded(pos.toLong())) return false;
            restoreEntities(pos, source);
        }
        return true;
    }

    private boolean restoreBlocks(LevelChunk live, ChunkPos pos, ArenaTemplate.Chunk source, long deadlineNanos) {
        int sections = live.getSectionsCount();
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();
        BlockState air = Blocks.AIR.defaultBlockState();

        for (; sectionIdx < sections; sectionIdx++, blockIdx = 0) {
            int sectionY = live.getSectionYFromSectionIndex(sectionIdx);
            LevelChunkSection liveSection = live.getSection(sectionIdx);
            PalettedContainer<BlockState> target = templateSection(source, sectionY);

            // 两边都是整段空气：最常见的情况（地表以上），整段跳过
            if (target == null && liveSection.hasOnlyAir()) continue;

            int baseY = SectionPos.sectionToBlockCoord(sectionY);
            for (; blockIdx < 4096; blockIdx++) {
                if ((blockIdx & (BUDGET_CHECK_EVERY - 1)) == 0 && blockIdx != 0 && System.nanoTime() >= deadlineNanos) {
                    return false;
                }
                int x = blockIdx & 15;
                int z = (blockIdx >> 4) & 15;
                int y = blockIdx >> 8;

                BlockState want = target == null ? air : target.get(x, y, z);
                BlockState have = liveSection.getBlockState(x, y, z);
                if (have == want) continue;

                cursor.set(baseX + x, baseY + y, baseZ + z);
                if (have.hasBlockEntity()) {
                    // 箱子等容器被替换时会把内容物撒一地，先清空
                    Clearable.tryClear(live.getBlockEntity(cursor));
                }
                level.setBlock(cursor, want, SET_FLAGS);
                changedBlocks++;
                changedThisPass++;
            }
        }
        return true;
    }

    private static boolean isAllAir(LevelChunk chunk) {
        for (LevelChunkSection s : chunk.getSections()) {
            if (!s.hasOnlyAir()) return false;
        }
        return true;
    }

    /**
     * 整块安装。只用于「目标区块完全是空气」的情况：此时区块没有方块实体、没有光照数据层，
     * 换掉区段对象后按原版生成流程（先关光照 → 标记非空区段 → 初始化光源 → 传播）重新算光照，
     * 结果与原版生成的区块一致（洞穴是暗的）。有内容的区块一律走逐方块比对。
     */
    private boolean bulkInstall(LevelChunk live, ChunkPos pos, ArenaTemplate.Chunk source) {
        try {
            ThreadedLevelLightEngine light = level.getChunkSource().getLightEngine();
            light.setLightEnabled(pos, false);

            int sections = live.getSectionsCount();
            boolean any = false;
            for (int liveIdx = 0; liveIdx < sections; liveIdx++) {
                int sectionY = live.getSectionYFromSectionIndex(liveIdx);
                int idx = sectionY - template.minSectionY;
                if (idx < 0 || idx >= source.states().length) continue;
                PalettedContainer<BlockState> states = source.states()[idx];
                PalettedContainerRO<Holder<Biome>> biomes = source.biomes()[idx];
                LevelChunkSection old = live.getSection(liveIdx);
                if (states == null && biomes == null) continue;

                LevelChunkSection fresh = new LevelChunkSection(
                        states != null ? states.copy() : old.getStates().copy(),
                        biomes != null ? biomes : old.getBiomes());
                live.getSections()[liveIdx] = fresh;
                if (states != null) {
                    light.updateSectionStatus(SectionPos.of(pos, sectionY), false);
                    any = true;
                }
            }

            if (any) {
                Set<Heightmap.Types> types = EnumSet.noneOf(Heightmap.Types.class);
                for (var e : live.getHeightmaps()) types.add(e.getKey());
                Heightmap.primeHeightmaps(live, types);
                live.initializeLightSources();

                for (CompoundTag raw : source.blockEntities()) {
                    CompoundTag tag = raw.copy();
                    tag.remove("keepPacked");
                    int x = tag.getInt("x") + geometry.blockDx();
                    int z = tag.getInt("z") + geometry.blockDz();
                    tag.putInt("x", x);
                    tag.putInt("z", z);
                    BlockPos bePos = new BlockPos(x, tag.getInt("y"), z);
                    BlockState state = live.getBlockState(bePos);
                    if (!state.hasBlockEntity()) continue;
                    BlockEntity be = BlockEntity.loadStatic(bePos, state, tag);
                    if (be != null) {
                        live.addAndRegisterBlockEntity(be);
                        blockEntityUpdates++;
                    }
                }
            }

            light.initializeLight(live, true);
            light.lightChunk(live, false);
            live.setUnsaved(true);
            bulkChunks++;
            return true;
        } catch (LinkageError | RuntimeException e) {
            bulkUnsupported = true;
            Battlefield.LOGGER.warn("[Battlefield] 整块安装不可用（{}），改为逐方块还原；首次安装大地图会明显变慢", e.toString());
            return false;
        }
    }

    private PalettedContainer<BlockState> templateSection(ArenaTemplate.Chunk source, int sectionY) {
        if (source == null) return null;
        int idx = sectionY - template.minSectionY;
        if (idx < 0 || idx >= source.states().length) return null;
        return source.states()[idx];
    }

    private void restoreBlockEntities(LevelChunk live, ArenaTemplate.Chunk source) {
        for (CompoundTag raw : source.blockEntities()) {
            CompoundTag want = raw.copy();
            want.remove("keepPacked");
            int x = want.getInt("x") + geometry.blockDx();
            int z = want.getInt("z") + geometry.blockDz();
            want.putInt("x", x);
            want.putInt("z", z);
            cursor.set(x, want.getInt("y"), z);

            BlockEntity be = live.getBlockEntity(cursor);
            if (be == null) continue;
            if (be.saveWithFullMetadata().equals(want)) continue;

            be.load(want);
            be.setChanged();
            BlockState state = live.getBlockState(cursor);
            level.sendBlockUpdated(cursor, state, state, Block.UPDATE_ALL);
            blockEntityUpdates++;
        }
    }

    private void restoreBiomes(LevelChunk live, ChunkPos pos, ArenaTemplate.Chunk source) {
        boolean changed = false;
        for (int idx = 0; idx < source.biomes().length; idx++) {
            PalettedContainerRO<Holder<Biome>> want = source.biomes()[idx];
            if (want == null) continue;
            int sectionY = template.minSectionY + idx;
            int liveIdx = live.getSectionIndexFromSectionY(sectionY);
            if (liveIdx < 0 || liveIdx >= live.getSectionsCount()) continue;
            LevelChunkSection section = live.getSection(liveIdx);

            if (sameBiomes(section.getBiomes(), want)) continue;
            int qx = SectionPos.sectionToBlockCoord(pos.x) >> 2;
            int qy = SectionPos.sectionToBlockCoord(sectionY) >> 2;
            int qz = SectionPos.sectionToBlockCoord(pos.z) >> 2;
            section.fillBiomesFromNoise((x, y, z, sampler) -> want.get(x & 3, y & 3, z & 3), null, qx, qy, qz);
            changed = true;
        }
        if (changed) {
            live.setUnsaved(true);
            level.getChunkSource().chunkMap.resendBiomesForChunks(List.of(live));
        }
    }

    private static boolean sameBiomes(PalettedContainerRO<Holder<Biome>> a, PalettedContainerRO<Holder<Biome>> b) {
        for (int y = 0; y < 4; y++)
            for (int z = 0; z < 4; z++)
                for (int x = 0; x < 4; x++)
                    if (!a.get(x, y, z).equals(b.get(x, y, z))) return false;
        return true;
    }

    private void restoreEntities(ChunkPos pos, ArenaTemplate.Chunk source) {
        AABB box = new AABB(pos.getMinBlockX(), level.getMinBuildHeight() - 64, pos.getMinBlockZ(),
                pos.getMaxBlockX() + 1, level.getMaxBuildHeight() + 64, pos.getMaxBlockZ() + 1);
        // 只清「位于本区块」的实体：AABB 会带上压在边界上的邻区块实体，邻区块可能已经还原过了
        for (Entity e : level.getEntities((Entity) null, box, e -> !(e instanceof Player)
                && SectionPos.blockToSectionCoord(e.getBlockX()) == pos.x
                && SectionPos.blockToSectionCoord(e.getBlockZ()) == pos.z)) {
            if (e.hasExactlyOnePlayerPassenger()) continue;
            e.discard();
            entitiesRemoved++;
        }

        if (source == null) return;
        for (CompoundTag raw : source.entities()) {
            CompoundTag tag = raw.copy();
            relocate(tag);
            Entity entity = EntityType.loadEntityRecursive(tag, level, en -> en);
            if (entity != null && level.tryAddFreshEntityWithPassengers(entity)) {
                entitiesSpawned++;
            }
        }
    }

    /** 平移坐标并去掉 UUID：同一模板在多个槽位并存时，实体 UUID 必须各不相同。 */
    private void relocate(CompoundTag tag) {
        tag.remove("UUID");
        if (tag.contains("Pos", Tag.TAG_LIST)) {
            ListTag p = tag.getList("Pos", Tag.TAG_DOUBLE);
            ListTag moved = new ListTag();
            moved.add(DoubleTag.valueOf(p.getDouble(0) + geometry.dx()));
            moved.add(DoubleTag.valueOf(p.getDouble(1)));
            moved.add(DoubleTag.valueOf(p.getDouble(2) + geometry.dz()));
            tag.put("Pos", moved);
        }
        if (tag.contains("TileX")) {
            tag.putInt("TileX", tag.getInt("TileX") + geometry.blockDx());
            tag.putInt("TileZ", tag.getInt("TileZ") + geometry.blockDz());
        }
        if (tag.contains("Passengers", Tag.TAG_LIST)) {
            ListTag passengers = tag.getList("Passengers", Tag.TAG_COMPOUND);
            for (int i = 0; i < passengers.size(); i++) relocate(passengers.getCompound(i));
        }
    }

    private void finish() {
        for (int i = next; i < ticketHead; i++) releaseTicket(chunks.get(i));
        state = State.DONE;
        SlotRegistry.get(level.getServer()).markClean(slot, template.fingerprint, geometry);
        Battlefield.LOGGER.info("[Battlefield] 槽位 #{}（{}）还原完成：区块 {}（整块安装 {}），逐方块改动 {}，方块实体 {}，"
                        + "移除实体 {}，生成实体 {}，比对 {} 遍，主线程累计 {} ms，历时 {} tick",
                slot.index, slot.arena, chunks.size(), bulkChunks, changedBlocks, blockEntityUpdates, entitiesRemoved, entitiesSpawned,
                pass, busyNanos / 1_000_000, level.getServer().getTickCount() - createdAtTick);
        for (Runnable r : onDone) {
            try {
                r.run();
            } catch (Exception e) {
                Battlefield.LOGGER.error("[Battlefield] 槽位 #{} 还原完成回调异常", slot.index, e);
            }
        }
        onDone.clear();
    }

    public long changedBlocks() {
        return changedBlocks;
    }
}
