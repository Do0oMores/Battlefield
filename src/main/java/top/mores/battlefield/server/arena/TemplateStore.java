package top.mores.battlefield.server.arena;

import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraftforge.fml.loading.FMLPaths;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.config.SectorConfigLoader;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * 模板读取与缓存。
 * <p>
 * 模板目录 {@code config/battlefield/templates/<id>/} 下放 {@code region/} 与（可选）{@code entities/}，
 * 就是从任意存档里原样拷出来的 .mca 文件。这个目录<b>永远不会被当成世界加载</b>，
 * 服务端只用自己的只读解析器读它——不会占用 session.lock，也不会被自动保存写回。
 * <p>
 * 所有磁盘 IO、解压、NBT 解析、DataFixer 升级都在单独的后台线程完成，
 * 主线程只拿到一个已经构建好的 {@link ArenaTemplate}。
 */
public final class TemplateStore {
    private TemplateStore() {
    }

    private static final Codec<PalettedContainer<BlockState>> BLOCK_STATE_CODEC = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES,
            Blocks.AIR.defaultBlockState());

    /** 1.20.1 主世界高度：-64..319，共 24 个区段。战场维度的 dimension_type 与之一致。 */
    private static final int MIN_SECTION_Y = -4;
    private static final int SECTION_COUNT = 24;

    private static ExecutorService io;
    /** 仅主线程访问。键：模板 id + 范围。 */
    private static final Map<String, CompletableFuture<ArenaTemplate>> CACHE = new HashMap<>();

    public static Path templatesDir() {
        return FMLPaths.CONFIGDIR.get().resolve("battlefield").resolve("templates");
    }

    /** 取模板（必要时在后台读取）。只能在主线程调用。 */
    public static CompletableFuture<ArenaTemplate> get(MinecraftServer server, SectorConfigLoader.ArenaConfig arena) {
        String key = arena.template + "@" + arena.templateBounds;
        CompletableFuture<ArenaTemplate> cached = CACHE.get(key);
        if (cached != null && !cached.isCompletedExceptionally()) return cached;

        Registry<Biome> biomes = server.registryAccess().registryOrThrow(Registries.BIOME);
        String id = arena.template;
        SectorConfigLoader.Bounds bounds = arena.templateBounds;
        CompletableFuture<ArenaTemplate> future = CompletableFuture.supplyAsync(() -> load(id, bounds, biomes), executor());
        CACHE.put(key, future);
        return future;
    }

    /** /bt reload 时调用：模板文件可能被替换了，下次使用时重新读取并重新计算指纹。 */
    public static void invalidateAll() {
        CACHE.clear();
    }

    public static void shutdown() {
        CACHE.clear();
        if (io != null) {
            io.shutdownNow();
            io = null;
        }
    }

    private static synchronized ExecutorService executor() {
        if (io == null) {
            io = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Battlefield-Template-IO");
                t.setDaemon(true);
                return t;
            });
        }
        return io;
    }

    // ===================== 后台线程 =====================

    private static ArenaTemplate load(String id, SectorConfigLoader.Bounds bounds, Registry<Biome> biomeRegistry) {
        long started = System.nanoTime();
        Path dir = templatesDir().resolve(id);
        Path regionDir = dir.resolve("region");
        Path entityDir = dir.resolve("entities");
        if (!Files.isDirectory(regionDir)) {
            throw new IllegalStateException("模板目录不存在或缺少 region/: " + dir.toAbsolutePath());
        }

        Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec = PalettedContainer.codecRO(
                biomeRegistry.asHolderIdMap(), biomeRegistry.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES, biomeRegistry.getHolderOrThrow(Biomes.PLAINS));
        DataFixer fixer = DataFixers.getDataFixer();
        int currentVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();

        Long2ObjectOpenHashMap<ArenaTemplate.Chunk> chunks = new Long2ObjectOpenHashMap<>();
        int skipped = 0;

        for (int rx = bounds.minChunkX() >> 5; rx <= bounds.maxChunkX() >> 5; rx++) {
            for (int rz = bounds.minChunkZ() >> 5; rz <= bounds.maxChunkZ() >> 5; rz++) {
                Path regionFile = regionDir.resolve("r." + rx + "." + rz + ".mca");
                Path entityFile = entityDir.resolve("r." + rx + "." + rz + ".mca");
                if (!Files.isRegularFile(regionFile)) continue;

                try (RegionReader blocks = new RegionReader(regionFile);
                     RegionReader ents = Files.isRegularFile(entityFile) ? new RegionReader(entityFile) : null) {
                    for (int lx = 0; lx < 32; lx++) {
                        for (int lz = 0; lz < 32; lz++) {
                            int cx = (rx << 5) + lx;
                            int cz = (rz << 5) + lz;
                            if (cx < bounds.minChunkX() || cx > bounds.maxChunkX()
                                    || cz < bounds.minChunkZ() || cz > bounds.maxChunkZ()) continue;

                            CompoundTag tag = blocks.read(lx, lz, cx, cz);
                            if (tag == null) continue;
                            tag = upgrade(fixer, DataFixTypes.CHUNK, tag, currentVersion);

                            String status = tag.getString("Status");
                            if (!status.equals("minecraft:full") && !status.equals("full")) {
                                skipped++;
                                continue;
                            }

                            List<CompoundTag> entities = List.of();
                            if (ents != null) {
                                CompoundTag entTag = ents.read(lx, lz, cx, cz);
                                if (entTag != null) {
                                    entTag = upgrade(fixer, DataFixTypes.ENTITY_CHUNK, entTag, currentVersion);
                                    entities = compounds(entTag.getList("Entities", Tag.TAG_COMPOUND));
                                }
                            }

                            chunks.put(net.minecraft.world.level.ChunkPos.asLong(cx, cz),
                                    parseChunk(tag, biomeCodec, entities));
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("读取模板区域文件失败: " + regionFile, e);
                }
            }
        }

        String fingerprint = fingerprint(dir);
        Battlefield.LOGGER.info("[Battlefield] 模板 '{}' 读取完成：{} 个区块（跳过未生成完毕 {} 个），指纹 {}，耗时 {} ms（后台线程）",
                id, chunks.size(), skipped, fingerprint, (System.nanoTime() - started) / 1_000_000);
        return new ArenaTemplate(id, fingerprint, bounds, MIN_SECTION_Y, chunks);
    }

    private static CompoundTag upgrade(DataFixer fixer, DataFixTypes type, CompoundTag tag, int currentVersion) {
        int version = tag.contains("DataVersion", Tag.TAG_ANY_NUMERIC) ? tag.getInt("DataVersion") : 1343;
        if (version >= currentVersion) return tag;
        return type.updateToCurrentVersion(fixer, tag, version);
    }

    @SuppressWarnings("unchecked")
    private static ArenaTemplate.Chunk parseChunk(CompoundTag tag,
                                                  Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec,
                                                  List<CompoundTag> entities) {
        PalettedContainer<BlockState>[] states = new PalettedContainer[SECTION_COUNT];
        PalettedContainerRO<Holder<Biome>>[] biomes = new PalettedContainerRO[SECTION_COUNT];

        ListTag sections = tag.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i);
            int index = section.getByte("Y") - MIN_SECTION_Y;
            if (index < 0 || index >= SECTION_COUNT) continue;

            if (section.contains("block_states", Tag.TAG_COMPOUND)) {
                PalettedContainer<BlockState> container = BLOCK_STATE_CODEC
                        .parse(NbtOps.INSTANCE, section.getCompound("block_states"))
                        .result().orElse(null);
                // 整段空气按 null 存，主线程比对时可以整段跳过
                if (container != null && container.maybeHas(s -> !s.isAir())) {
                    states[index] = container;
                }
            }
            if (section.contains("biomes", Tag.TAG_COMPOUND)) {
                biomes[index] = biomeCodec.parse(NbtOps.INSTANCE, section.getCompound("biomes")).result().orElse(null);
            }
        }

        return new ArenaTemplate.Chunk(states, biomes,
                compounds(tag.getList("block_entities", Tag.TAG_COMPOUND)), entities);
    }

    private static List<CompoundTag> compounds(ListTag list) {
        if (list.isEmpty()) return List.of();
        List<CompoundTag> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) out.add(list.getCompound(i));
        return List.copyOf(out);
    }

    private static String fingerprint(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            files.filter(Files::isRegularFile).sorted().forEach(p -> {
                try {
                    md.update(dir.relativize(p).toString().replace('\\', '/').getBytes());
                    md.update(Long.toString(Files.size(p)).getBytes());
                    md.update(Long.toString(Files.getLastModifiedTime(p).toMillis()).getBytes());
                } catch (IOException ignored) {
                }
            });
            return HexFormat.of().formatHex(md.digest()).substring(0, 12);
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 只读的 Anvil 区域文件解析器。
     * <p>
     * 不用原版 {@code RegionFile}：它以读写方式打开文件，文件头不完整时还会主动写入，
     * 模板目录被设成只读（推荐做法）时会直接打开失败。
     */
    private static final class RegionReader implements Closeable {
        private final FileChannel channel;
        private final Path file;
        private final IntBufferView offsets;

        RegionReader(Path file) throws IOException {
            this.file = file;
            this.channel = FileChannel.open(file, StandardOpenOption.READ);
            ByteBuffer header = ByteBuffer.allocate(4096);
            while (header.hasRemaining() && channel.read(header, header.position()) > 0) {
                // 读满文件头
            }
            header.flip();
            this.offsets = new IntBufferView(header);
        }

        CompoundTag read(int localX, int localZ, int chunkX, int chunkZ) throws IOException {
            int packed = offsets.get(localX + localZ * 32);
            if (packed == 0) return null;
            long sectorOffset = (long) (packed >>> 8) * 4096L;

            ByteBuffer head = ByteBuffer.allocate(5);
            if (channel.read(head, sectorOffset) < 5) return null;
            head.flip();
            int length = head.getInt();
            byte compression = head.get();
            if (length <= 1) return null;

            if ((compression & 0x80) != 0) {
                // 超大区块存在外部 .mcc 文件里
                Path external = file.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc");
                if (!Files.isRegularFile(external)) return null;
                try (InputStream in = Files.newInputStream(external)) {
                    return decode((byte) (compression & 0x7F), in);
                }
            }

            ByteBuffer data = ByteBuffer.allocate(length - 1);
            long pos = sectorOffset + 5;
            while (data.hasRemaining()) {
                int n = channel.read(data, pos + data.position());
                if (n < 0) return null;
            }
            return decode(compression, new ByteArrayInputStream(data.array()));
        }

        private static CompoundTag decode(byte compression, InputStream raw) throws IOException {
            InputStream in = switch (compression) {
                case 1 -> new GZIPInputStream(raw);
                case 2 -> new InflaterInputStream(raw);
                case 3 -> raw;
                default -> throw new IOException("未知的区块压缩格式: " + compression);
            };
            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(in))) {
                return NbtIo.read(dis);
            }
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    private record IntBufferView(ByteBuffer buffer) {
        int get(int index) {
            return buffer.getInt(index * 4);
        }
    }
}
