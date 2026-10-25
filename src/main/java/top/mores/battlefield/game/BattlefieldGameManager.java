package top.mores.battlefield.game;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.block.ModBlocks;
import top.mores.battlefield.breakthrough.CapturePoint;
import top.mores.battlefield.breakthrough.Sector;
import top.mores.battlefield.config.BattlefieldServerConfig;
import top.mores.battlefield.config.SectorConfigLoader;
import top.mores.battlefield.config.TaczAmmoCapConfig;
import top.mores.battlefield.deploy.DeploymentContext;
import top.mores.battlefield.deploy.DeploymentData;
import top.mores.battlefield.deploy.DeploymentOption;
import top.mores.battlefield.deploy.DeploymentRules;
import top.mores.battlefield.deploy.DeploymentTarget;
import top.mores.battlefield.deploy.map.BakedDeployMap;
import top.mores.battlefield.deploy.map.DeployMapService;
import top.mores.battlefield.net.BattlefieldNet;
import top.mores.battlefield.net.S2CDeployDataPacket;
import top.mores.battlefield.net.S2CDeployMapPacket;
import top.mores.battlefield.net.S2CGameStatePacket;
import top.mores.battlefield.net.S2CSectorAreaPacket;
import top.mores.battlefield.server.BackpackBridge;
import top.mores.battlefield.server.BattleTeleport;
import top.mores.battlefield.server.BombardmentManager;
import top.mores.battlefield.server.arena.*;
import top.mores.battlefield.team.SquadManager;
import top.mores.battlefield.team.TeamId;
import top.mores.battlefield.team.TeamManager;
import top.mores.battlefield.team.TeamSavedData;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 对局总控。
 * <p>
 * 一个 {@link MatchContext} 就是一局，键是对局实例 ID（{@code area_alpha#3}），不再是地图名——
 * 同一张图可以同时开多局。每局要么跑在固定世界里（STATIC），要么独占战场维度里的一个槽位（INSTANCED），
 * 槽位开局前从模板还原、结束后再还原一次。所有对局状态（参与者、阵营、小队、分数、信标、名牌队伍、
 * 区域包、聊天播报）都按局隔离，不再有任何「按维度广播」或「全服清零」。
 */
@Mod.EventBusSubscriber(modid = Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BattlefieldGameManager {
    private BattlefieldGameManager() {
    }

    public enum Phase {WAITING, COUNTDOWN, RUNNING, ENDING}

    private static final String SCOREBOARD_TEAM_PREFIX = "bf";

    /**
     * 客户端状态包发送间隔（tick）。原先每 tick 全量发送，32 人局等于 640 包/秒。
     * 阶段切换时额外补发一次，避免 HUD 出现明显滞后。
     */
    private static final int STATE_SYNC_INTERVAL_TICKS = 2;
    /** 名牌队伍同步间隔（tick）。原先每 tick 重建计分板队伍，32 人局等于 640 次/秒。 */
    private static final int NAME_TAG_SYNC_INTERVAL_TICKS = 10;
    /** 冻结阶段允许偏离冻结起点的距离上限（格²）。超过才回拉，避免每 tick 全量传送。 */
    private static final double FROZEN_MAX_DRIFT_SQR = 0.5 * 0.5;

    private static final Map<String, MatchContext> MATCHES = new LinkedHashMap<>();
    private static final Map<UUID, String> PLAYER_MATCH = new HashMap<>();
    /**
     * 已经由服务端确认、尚未消费的部署选择。
     * <p>
     * 存选项 id 而不是坐标：复活那一刻要用当时的状态重新解析一次——期间据点可能易主、信标可能被打掉。
     */
    private static final Map<UUID, PendingDeploy> PENDING_DEPLOY = new HashMap<>();
    /** 阵亡后部署界面的推送计划：{@code openAt} 打开界面，{@code readyAt} 放开部署按钮。 */
    private static final Map<UUID, DeployWindow> DEPLOY_WINDOWS = new HashMap<>();
    /** 部署请求节流时间戳，防止连点导致重复复活/重复发放装备。 */
    private static final Map<UUID, Integer> DEPLOY_REQUEST_TICK = new HashMap<>();
    private static final Map<UUID, PlacedAmmoStation> PLAYER_AMMO_STATIONS = new HashMap<>();
    /** 正被某局占用（含准备中）的槽位号 */
    private static final Set<Integer> SLOTS_IN_USE = new HashSet<>();
    /** 开服预热：等模板读完后判断槽位是否需要还原 */
    private static final List<PendingPrewarm> PENDING_PREWARM = new ArrayList<>();

    private static SectorConfigLoader.SectorConfig config;
    private static int matchSerial;

    private static final class MatchContext {
        final String matchId;
        final int serial;
        final SectorConfigLoader.ArenaConfig arena;
        final ServerLevel battleLevel;
        @Nullable
        final SlotRegistry.Slot slot;
        @Nullable
        final SlotGeometry geometry;
        @Nullable
        CompletableFuture<ArenaTemplate> template;
        @Nullable
        SlotRestoreJob restoreJob;
        /** 地图已就绪（静态世界恒为 true；槽位要等还原完成） */
        boolean ready;
        /** 战区底图烘焙任务与结果。静态世界没有模板，两者都为 null，界面退回坐标示意图。 */
        @Nullable
        CompletableFuture<BakedDeployMap> mapJob;
        @Nullable
        volatile BakedDeployMap bakedMap;
        /** 底图编码后的 PNG，只编一次，之后按需发给客户端。 */
        @Nullable
        byte[] mapPng;
        boolean released;
        /** 曾有玩家进入过对局世界：结束后槽位必须还原 */
        boolean everEntered;

        final SectorManager sectorManager = new SectorManager();
        final Map<UUID, Integer> outsideAreaTicks = new HashMap<>();
        final Set<UUID> participants = new LinkedHashSet<>();
        /** 已经进入对局世界（拍过快照、清过背包）的参与者 */
        final Set<UUID> entered = new HashSet<>();
        /** 冻结阶段每名玩家的锚点 */
        final Map<UUID, Vec3> frozenAnchors = new HashMap<>();

        GameSession session;
        Phase phase = Phase.WAITING;
        TeamId winner = TeamId.SPECTATOR;
        TeamId pendingEndWinner;
        int tickCounter = 0;
        int countdownTicks = 0;
        int endingTicks = 0;
        /** 上一次已同步给客户端的阶段序号，用于阶段切换时强制补发状态包 */
        int lastSyncedPhase = -1;

        MatchContext(String matchId, int serial, SectorConfigLoader.ArenaConfig arena, ServerLevel battleLevel,
                     @Nullable SlotRegistry.Slot slot, @Nullable SlotGeometry geometry) {
            this.matchId = matchId;
            this.serial = serial;
            this.arena = arena;
            this.battleLevel = battleLevel;
            this.slot = slot;
            this.geometry = geometry;
        }

        double dx() {
            return geometry == null ? 0 : geometry.dx();
        }

        double dz() {
            return geometry == null ? 0 : geometry.dz();
        }

        /** 对局内坐标：实例化时把模板坐标平移到槽位、维度换成战场维度；静态世界原样返回。 */
        SectorConfigLoader.Position inBattle(SectorConfigLoader.Position p) {
            if (p == null || geometry == null) return p;
            return new SectorConfigLoader.Position(battleLevel.dimension().location().toString(),
                    p.x() + dx(), p.y(), p.z() + dz());
        }

        SectorConfigLoader.Position spawnOf(TeamId team) {
            return inBattle(team == TeamId.ATTACKERS ? arena.firstAttackSpawnPoint : arena.firstDefendSpawnPoint);
        }

        ServerLevel levelOf(SectorConfigLoader.Position p) {
            if (geometry != null) return battleLevel;
            return p.resolveLevel(battleLevel.getServer(), battleLevel);
        }

        String teamName(TeamId team) {
            return SCOREBOARD_TEAM_PREFIX + serial + (team == TeamId.ATTACKERS ? "_atk" : "_def");
        }
    }

    private record PlacedAmmoStation(ResourceKey<net.minecraft.world.level.Level> dimension, BlockPos pos) {
    }

    private record PendingPrewarm(ServerLevel level, SlotRegistry.Slot slot, SlotGeometry geometry,
                                  CompletableFuture<ArenaTemplate> template) {
    }

    private record PendingDeploy(String optionId, int backpackSlot) {
    }

    private record DeployWindow(int openAt, int readyAt) {
    }

    // ===================================================================================
    // 配置
    // ===================================================================================

    /**
     * 重新读取 sectors.json。解析失败时保留旧配置。
     * 进行中的对局各自持有创建时的那份 arena 配置，不受重载影响（原实现会把进行中对局的据点进度全部重置）。
     *
     * @return 是否成功
     */
    public static boolean loadConfig(MinecraftServer server) {
        Path cfgDir = FMLPaths.CONFIGDIR.get().resolve("battlefield");
        SectorConfigLoader.SectorConfig next;
        try {
            next = SectorConfigLoader.loadConfig(cfgDir);
        } catch (RuntimeException e) {
            Battlefield.LOGGER.error("[Battlefield] sectors.json 解析失败，继续使用旧配置", e);
            if (config == null) config = new SectorConfigLoader.SectorConfig(new LinkedHashMap<>());
            return false;
        }

        for (var arena : next.arenas.values()) {
            if (!arena.instanced()) continue;
            ServerLevel battle = BattleWorlds.resolve(server, arena.battleWorld);
            if (battle == null) {
                Battlefield.LOGGER.error("[Battlefield] arena '{}' 的战场维度 '{}' 未加载，该 arena 无法开局", arena.areaName, arena.battleWorld);
            } else {
                BattleWorlds.registerBattleLevel(battle.dimension());
            }
            if (!Files.isDirectory(TemplateStore.templatesDir().resolve(arena.template).resolve("region"))) {
                Battlefield.LOGGER.error("[Battlefield] arena '{}' 的模板目录不存在: {}", arena.areaName,
                        TemplateStore.templatesDir().resolve(arena.template).toAbsolutePath());
            }
            SlotGeometry probe = SlotGeometry.of(0, arena.templateBounds);
            if (!probe.fitsSpacing()) {
                Battlefield.LOGGER.warn("[Battlefield] arena '{}' 模板宽 {} 格，超过槽位间距 {} - 512，相邻对局可能互相看得见",
                        arena.areaName, arena.templateBounds.widthChunks() * 16, BattlefieldServerConfig.get().slotSpacingBlocks);
            }
        }
        config = next;
        TemplateStore.invalidateAll();
        return true;
    }

    private static void ensureConfig(MinecraftServer server) {
        if (config == null) {
            loadConfig(server);
        }
    }

    // ===================================================================================
    // 加入 / 离开
    // ===================================================================================

    public static TeamId joinBattle(ServerPlayer player) {
        ensureConfig(player.server);
        return joinBattle(player, config.defaultAreaName());
    }

    public static TeamId joinBattle(ServerPlayer player, String arenaId) {
        ensureConfig(player.server);

        String current = PLAYER_MATCH.get(player.getUUID());
        if (current != null) {
            player.sendSystemMessage(Component.literal("你已在对局 " + current + " 中，请先 /bt leave"));
            return TeamManager.getTeam(player);
        }

        var arena = config.getArena(arenaId);
        if (arena == null) {
            player.sendSystemMessage(Component.literal("未知对局ID: " + arenaId));
            return TeamId.SPECTATOR;
        }

        MatchContext ctx = findJoinableMatch(arena);
        if (ctx == null) {
            ctx = createMatch(player, arena);
            if (ctx == null) return TeamId.SPECTATOR;
        }

        // 先登记对局再分队：分队会触发自动分小队，小队是按对局分桶的
        PLAYER_MATCH.put(player.getUUID(), ctx.matchId);
        ctx.participants.add(player.getUUID());

        TeamId team = autoAssignWithLimit(ctx, player);
        if (team == TeamId.SPECTATOR) {
            PLAYER_MATCH.remove(player.getUUID());
            ctx.participants.remove(player.getUUID());
            player.sendSystemMessage(Component.literal("对局 " + ctx.matchId + " 已满"));
            if (ctx.participants.isEmpty()) releaseMatch(ctx);
            return team;
        }
        SquadManager.autoAssignSquad(player);

        if (ctx.ready) {
            placeJoiner(ctx, player, team);
            if (ctx.phase == Phase.WAITING && ctx.participants.size() >= ctx.arena.minPlayerNumber) {
                beginCountdown(ctx);
            }
        } else {
            player.sendSystemMessage(Component.literal("对局 " + ctx.matchId + " 的地图正在准备，完成后会自动传送。")
                    .withStyle(ChatFormatting.GRAY));
        }
        return team;
    }

    /** 同一张图里优先进人最多、还在等待的那局；其次倒计时；再次进行中（中途加入）。 */
    @Nullable
    private static MatchContext findJoinableMatch(SectorConfigLoader.ArenaConfig arena) {
        MatchContext best = null;
        for (MatchContext ctx : MATCHES.values()) {
            if (ctx.released || !ctx.arena.areaName.equals(arena.areaName)) continue;
            // 结算阶段不接受新玩家：此时参与者正在被清背包/传送回大厅，中途插入只会拿到一份错乱的状态
            if (ctx.phase == Phase.ENDING) continue;
            if (!hasRoomForJoiner(ctx)) continue;
            if (best == null || rank(ctx) > rank(best)) best = ctx;
        }
        return best;
    }

    /**
     * 这一局还能不能再进一个人。
     * <p>
     * 只看 {@code maxPlayerNumber} 不够：真正卡人的是两队各自的容量。攻守两队各 1 人时，
     * 总人数上限配成 1 也能让第二个人无处可去——他会被判定为「对局已满」而另开一局，
     * 而新的一局同样只装得下一个人，{@code minPlayerNumber} 于是永远凑不齐，谁都开不了局。
     * 所以这里要求「总人数没满」且「至少一队还有空位」，与 {@link #autoAssignWithLimit} 的判定保持一致。
     */
    private static boolean hasRoomForJoiner(MatchContext ctx) {
        int[] counts = teamCounts(ctx, null);
        return MatchCapacity.hasRoomForJoiner(ctx.participants.size(), ctx.arena.maxPlayerNumber,
                counts[0], ctx.arena.attackNumber, counts[1], ctx.arena.defendNumber);
    }

    /**
     * 本局在册参与者按阵营计数。
     * <p>
     * 按 {@code ctx.participants} 而不是在线玩家列表统计：掉线但没结算的人仍然占着阵营位置，
     * 漏算会让分边逻辑把超出容量的玩家塞进同一队。
     */
    private static int[] teamCounts(MatchContext ctx, @Nullable UUID skip) {
        TeamSavedData data = TeamSavedData.get(ctx.battleLevel);
        int attackers = 0;
        int defenders = 0;
        for (UUID id : ctx.participants) {
            if (id.equals(skip)) continue;
            TeamId team = data.getTeam(id);
            if (team == TeamId.ATTACKERS) attackers++;
            else if (team == TeamId.DEFENDERS) defenders++;
        }
        return new int[]{attackers, defenders};
    }

    private static int rank(MatchContext ctx) {
        int phaseRank = switch (ctx.phase) {
            case WAITING -> 3;
            case COUNTDOWN -> 2;
            case RUNNING -> 1;
            case ENDING -> 0;
        };
        return phaseRank * 10_000 + ctx.participants.size();
    }

    @Nullable
    private static MatchContext createMatch(ServerPlayer requester, SectorConfigLoader.ArenaConfig arena) {
        MinecraftServer server = requester.server;
        long live = MATCHES.values().stream().filter(c -> !c.released && c.arena.areaName.equals(arena.areaName)).count();
        if (live >= arena.maxConcurrentMatches) {
            requester.sendSystemMessage(Component.literal("对局 " + arena.areaName + " 的所有并行对局都已满员或正在结算，请稍后再试"));
            return null;
        }

        int serial = ++matchSerial;
        String matchId = arena.areaName + "#" + serial;
        MatchContext ctx;

        if (!arena.instanced()) {
            ServerLevel level = BattleWorlds.resolve(server, arena.world);
            if (level == null) {
                requester.sendSystemMessage(Component.literal("对局世界 " + arena.world + " 未加载，无法开局"));
                return null;
            }
            ctx = new MatchContext(matchId, serial, arena, level, null, null);
            ctx.ready = true;
        } else {
            ServerLevel level = BattleWorlds.resolve(server, arena.battleWorld);
            if (level == null) {
                requester.sendSystemMessage(Component.literal("战场维度 " + arena.battleWorld + " 未加载，无法开局"));
                return null;
            }
            SlotRegistry registry = SlotRegistry.get(server);
            SlotRegistry.Slot slot = acquireSlot(registry, arena);
            if (slot == null) {
                requester.sendSystemMessage(Component.literal("当前没有空闲的战场槽位，请稍后再试"));
                return null;
            }
            SlotGeometry geometry = SlotGeometry.of(slot.index, arena.templateBounds);
            ctx = new MatchContext(matchId, serial, arena, level, slot, geometry);
            ctx.template = TemplateStore.get(server, arena);
            SLOTS_IN_USE.add(slot.index);
        }

        MATCHES.put(matchId, ctx);
        Battlefield.LOGGER.info("[Battlefield] 创建对局 {}（模式 {}，{}）", matchId, arena.mode,
                ctx.slot == null ? "世界 " + arena.world : "槽位 #" + ctx.slot.index + " 原点区块 ("
                        + ctx.geometry.originChunkX() + ", " + ctx.geometry.originChunkZ() + ")");
        return ctx;
    }

    /** 优先用本 arena 已有的、没人占用的干净槽位；其次没人占用的脏槽位（需要还原）；最后新分配一个。 */
    @Nullable
    private static SlotRegistry.Slot acquireSlot(SlotRegistry registry, SectorConfigLoader.ArenaConfig arena) {
        SlotRegistry.Slot best = null;
        for (SlotRegistry.Slot s : registry.slotsOf(arena.areaName)) {
            if (SLOTS_IN_USE.contains(s.index)) continue;
            if (best == null || (s.clean() && !best.clean())) best = s;
        }
        return best != null ? best : registry.allocate(arena.areaName);
    }

    /** 地图就绪后把玩家放进对局：等待中去等待点，否则直接落本方主出生点。 */
    private static void placeJoiner(MatchContext ctx, ServerPlayer player, TeamId team) {
        if (ctx.phase == Phase.WAITING) {
            enterMatchWorld(ctx, player, ctx.inBattle(ctx.arena.wait));
        } else {
            // 中途加入（倒计时/进行中）：直接落本方主出生点。
            // 原实现一律传送到 wait 区，wait 可能落在可活动区域之外，开局后会被 enforcement 直接判死。
            enterMatchWorld(ctx, player, ctx.spawnOf(team));
        }
        setRespawnToTeamSpawn(ctx, player, team);

        // 中途加入者拿不到开局时下发的区域包，这里单独补一份，
        // 否则客户端没有区域框、也没有出界警告，但服务端照样按矩形计时击杀。
        if (ctx.phase != Phase.WAITING && ctx.session != null) {
            Sector cur = ctx.session.currentSector();
            if (cur != null) {
                BattlefieldNet.sendSectorAreasTo(player, ctx.session.currentSectorIndex,
                        cur.attackerAreas, cur.defenderAreas);
            }
        }
    }

    /** 第一次进入对局世界时拍快照并清空背包，然后传送。 */
    private static void enterMatchWorld(MatchContext ctx, ServerPlayer player, SectorConfigLoader.Position pos) {
        if (ctx.entered.add(player.getUUID())) {
            PlayerSnapshotStore.captureAndClear(player);
            if (ctx.slot != null && !ctx.everEntered) {
                SlotRegistry.get(player.server).markDirty(ctx.slot);
            }
            ctx.everEntered = true;
        }
        // 部署流程依赖「死亡界面」把玩家留在阵亡状态直到他确认落点。
        // 战场世界若开着 doImmediateRespawn，客户端会在死亡瞬间自动复活，界面根本没有出现的机会；
        // 这个开关只存在于登录包里，运行时只能靠这条原版游戏事件同步给客户端（0=显示死亡界面）。
        syncDeathScreen(player, true);
        teleportInMatch(ctx, player, pos);
    }

    private static void syncDeathScreen(ServerPlayer player, boolean enabled) {
        player.connection.send(new ClientboundGameEventPacket(
                ClientboundGameEventPacket.IMMEDIATE_RESPAWN, enabled ? 0.0F : 1.0F));
    }

    private static void teleportInMatch(MatchContext ctx, ServerPlayer player, SectorConfigLoader.Position pos) {
        if (pos == null) return;
        if (ctx.geometry != null) {
            BattleTeleport.toLevel(player, ctx.battleLevel, pos.x(), pos.y(), pos.z(), player.getYRot(), player.getXRot());
        } else {
            BattleTeleport.toPosition(player, pos, null);
        }
    }

    private static void setRespawnToTeamSpawn(MatchContext ctx, ServerPlayer player, TeamId team) {
        SectorConfigLoader.Position pos = ctx.spawnOf(team);
        if (pos == null || team == TeamId.SPECTATOR) return;
        player.setRespawnPosition(ctx.levelOf(pos).dimension(), BlockPos.containing(pos.toVec3()), 0, true, false);
    }

    public static void leaveBattle(ServerPlayer player) {
        String matchId = PLAYER_MATCH.get(player.getUUID());
        MatchContext ctx = matchId == null ? null : MATCHES.get(matchId);
        if (ctx == null) {
            PLAYER_MATCH.remove(player.getUUID());
            sendClientReset(player);
            // 不在任何对局里却还留着快照（例如上一局异常中断）：顺手还原
            if (PlayerSnapshotStore.hasSnapshot(player)) {
                BattleTeleport.sendHome(player, PlayerSnapshotStore.restore(player), defaultLobby());
            }
            return;
        }

        removeParticipant(ctx, player, false);
        afterParticipantLeft(ctx);
    }

    private static void afterParticipantLeft(MatchContext ctx) {
        if (ctx.participants.isEmpty()) {
            releaseMatch(ctx);
            return;
        }
        if ((ctx.phase == Phase.COUNTDOWN || ctx.phase == Phase.WAITING) && ctx.participants.size() < ctx.arena.minPlayerNumber) {
            ctx.phase = Phase.WAITING;
            ctx.countdownTicks = 0;
            ctx.frozenAnchors.clear();
        }
    }

    /**
     * 把一名玩家从对局里摘掉并清理他在各张表里的状态。
     *
     * @param loggingOut 掉线时只还原背包、不传送（登出事件里做跨维度传送不可靠），位置在下次登录时处理
     */
    private static void removeParticipant(MatchContext ctx, ServerPlayer player, boolean loggingOut) {
        UUID id = player.getUUID();
        sendClientReset(player);
        BombardmentManager.stop(player);
        RespawnBeaconManager.removeOwnerBeacon(player.server, id);
        removeFromNameTagTeams(ctx, player);
        // 先清阵营再摘对局：清阵营会把人从小队里移除，小队是按对局查的
        TeamManager.clearTeam(player);
        ScoreManager.clearPlayer(id);

        PLAYER_MATCH.remove(id);
        PENDING_DEPLOY.remove(id);
        DEPLOY_WINDOWS.remove(id);
        DEPLOY_REQUEST_TICK.remove(id);
        PLAYER_AMMO_STATIONS.remove(id);
        ctx.participants.remove(id);
        ctx.outsideAreaTicks.remove(id);
        ctx.frozenAnchors.remove(id);

        if (ctx.entered.remove(id)) {
            if (loggingOut) {
                PlayerSnapshotStore.restoreInventoryKeepReturn(player);
            } else {
                BattleTeleport.sendHome(player, PlayerSnapshotStore.restore(player), ctx.arena.lobby);
            }
            // 离场后把死亡界面行为还给服务器自身的 doImmediateRespawn 设置
            syncDeathScreen(player, !player.serverLevel().getGameRules()
                    .getBoolean(GameRules.RULE_DO_IMMEDIATE_RESPAWN));
        }
    }

    /** 对局结束：释放所有局内状态，槽位交给还原服务。 */
    private static void releaseMatch(MatchContext ctx) {
        if (ctx.released) return;
        ctx.released = true;
        MATCHES.remove(ctx.matchId);

        for (UUID id : ctx.participants) {
            PLAYER_MATCH.remove(id);
            PENDING_DEPLOY.remove(id);
            DEPLOY_WINDOWS.remove(id);
            DEPLOY_REQUEST_TICK.remove(id);
            PLAYER_AMMO_STATIONS.remove(id);
            ScoreManager.clearPlayer(id);
        }
        removeNameTagTeams(ctx);
        RespawnBeaconManager.clearMatch(ctx.matchId, ctx.battleLevel, ctx.participants);
        SquadManager.clearMatch(ctx.matchId);
        ctx.participants.clear();

        if (ctx.slot != null) {
            SLOTS_IN_USE.remove(ctx.slot.index);
            // 有人进去过就立刻还原，下一局拿到的就是干净槽位；准备阶段的还原任务（若还没跑完）会继续跑
            if (ctx.everEntered) {
                MinecraftServer server = ctx.battleLevel.getServer();
                SlotRestoreService.schedule(ctx.battleLevel, ctx.slot, ctx.geometry, TemplateStore.get(server, ctx.arena));
            }
        }
        Battlefield.LOGGER.info("[Battlefield] 对局 {} 已释放{}", ctx.matchId,
                ctx.slot != null && ctx.everEntered ? "，槽位 #" + ctx.slot.index + " 开始还原" : "");
    }

    private static void abortMatch(MatchContext ctx, String reason) {
        Battlefield.LOGGER.error("[Battlefield] 对局 {} 已取消：{}", ctx.matchId, reason);
        for (ServerPlayer sp : participants(ctx)) {
            sp.sendSystemMessage(Component.literal("对局 " + ctx.matchId + " 已取消：" + reason).withStyle(ChatFormatting.RED));
            removeParticipant(ctx, sp, false);
        }
        releaseMatch(ctx);
    }

    // ===================================================================================
    // 部署
    // ===================================================================================

    /**
     * 部署请求的处理结果，用于给客户端回显失败原因。
     */
    public record DeployResult(boolean ok, String message) {
        static final DeployResult OK = new DeployResult(true, "");

        static DeployResult fail(String message) {
            return new DeployResult(false, message);
        }
    }

    /**
     * 客户端请求一份部署数据（按下打开界面的按键、界面定时刷新、部署被拒后刷新）。
     * <p>
     * 只有"确实合法打开界面"时才让客户端弹窗；否则只回一份带原因的关闭态数据，
     * 客户端会把它显示成一条 actionbar，而不是弹出一个点了也没用的空界面。
     */
    public static void requestDeploymentData(ServerPlayer player) {
        sendDeploymentData(player, canOpenDeployScreen(player));
    }

    private static boolean canOpenDeployScreen(ServerPlayer player) {
        MatchContext ctx = findContext(player.getUUID());
        if (ctx == null || !ctx.entered.contains(player.getUUID())) return false;
        if (ctx.phase == Phase.WAITING || ctx.phase == Phase.ENDING) return false;
        return TeamManager.getTeam(player) != TeamId.SPECTATOR;
    }

    private static void sendDeploymentData(ServerPlayer player, boolean showScreen) {
        if (player == null) return;
        BattlefieldNet.sendToPlayer(player, new S2CDeployDataPacket(showScreen, buildDeploymentData(player)));
    }

    /** 与 {@link #sendDeploymentData} 相同，但把数据回传给调用方（用于日志，避免重复构建）。 */
    private static DeploymentData sendDeploymentDataAndGet(ServerPlayer player) {
        DeploymentData data = buildDeploymentData(player);
        BattlefieldNet.sendToPlayer(player, new S2CDeployDataPacket(true, data));
        return data;
    }

    /**
     * 组装一份部署界面数据。完全由服务端当前状态推导，没有任何客户端入参。
     */
    public static DeploymentData buildDeploymentData(ServerPlayer player) {
        MatchContext ctx = findContext(player.getUUID());
        if (ctx == null || !ctx.entered.contains(player.getUUID())) {
            return new DeploymentData("", "", "", "", "", (byte) 2, false, "你当前不在对局中。", 0, List.of());
        }

        TeamId team = TeamManager.getTeam(player);
        DeploymentRules rules = DeploymentRules.of(ctx.arena.gameMode);
        List<DeploymentOption> options = new ArrayList<>();
        for (DeploymentTarget target : rules.targets(buildDeployContext(player, ctx, team))) {
            options.add(target.option());
        }

        String deny = null;
        if (team == TeamId.SPECTATOR) {
            deny = "观战者无法部署。";
        } else if (ctx.phase == Phase.WAITING) {
            deny = "对局尚未开始。";
        } else if (ctx.phase == Phase.ENDING) {
            deny = "对局已结束。";
        } else if (ctx.phase == Phase.RUNNING && player.isAlive()) {
            deny = "战斗中无法重新部署。";
        }

        int wait = waitTicks(player.getUUID(), player.server.getTickCount());
        if (deny == null && wait > 0) {
            deny = "阵亡等待中，剩余 " + seconds(wait) + " 秒";
        }

        BakedDeployMap map = ctx.bakedMap;
        return new DeploymentData(rules.modeId(), rules.displayName(), ctx.arena.areaName,
                map == null ? "" : map.mapId(), sectorLabel(ctx),
                toClientTeamCode(team), deny == null, deny == null ? "" : deny, wait, options);
    }

    private static int waitTicks(UUID playerId, int now) {
        DeployWindow window = DEPLOY_WINDOWS.get(playerId);
        return window == null ? 0 : Math.max(0, window.readyAt() - now);
    }

    private static String seconds(int ticks) {
        return String.valueOf((ticks + 19) / 20);
    }

    private static String sectorLabel(MatchContext ctx) {
        if (ctx.session == null) return "";
        Sector sector = ctx.session.currentSector();
        if (sector == null) return "";
        return "战线 " + sector.id + "（" + (ctx.session.currentSectorIndex + 1) + "/"
                + Math.max(1, ctx.session.sectors.size()) + "）";
    }

    /** 把对局状态翻译成模式规则需要的只读快照。 */
    private static DeploymentContext buildDeployContext(ServerPlayer player, MatchContext ctx, TeamId team) {
        List<DeploymentContext.PointState> points = new ArrayList<>();
        Sector sector = ctx.session == null ? null : ctx.session.currentSector();
        if (sector != null) {
            for (CapturePoint point : sector.points) {
                points.add(new DeploymentContext.PointState(point.id, point.x, point.y, point.z, point.radius,
                        point.owner, point.getProgress(), point.lastAttackersIn, point.lastDefendersIn));
            }
        }

        List<DeploymentContext.BeaconState> beacons = new ArrayList<>();
        for (BeaconSpawnCandidate candidate : BeaconRespawnLogic.collect(player)) {
            boolean blocked = candidate.level() == null
                    || RespawnBeaconPlacement.findStandablePosNearBeacon(candidate.level(), candidate.pos()).isEmpty();
            beacons.add(new DeploymentContext.BeaconState(candidate.beaconUuid().toString(), candidate.label(),
                    candidate.pos().x, candidate.pos().y, candidate.pos().z, blocked));
        }

        SectorConfigLoader.Position base = team == TeamId.SPECTATOR ? null : ctx.spawnOf(team);
        return new DeploymentContext(team,
                ctx.session == null ? 0 : ctx.session.currentSectorIndex,
                ctx.session == null ? 0 : ctx.session.sectors.size(),
                sector == null ? "" : sector.id,
                points, beacons,
                base != null,
                base == null ? 0 : base.x(),
                base == null ? 0 : base.y(),
                base == null ? 0 : base.z());
    }

    /**
     * 处理一次重新部署。所有校验都在服务端完成，客户端只提供一个稳定标识。
     *
     * @param optionId     {@code base} / {@code point:A} / {@code beacon:<uuid>}
     * @param backpackSlot 1..9 配装预设槽位
     */
    public static DeployResult deploy(ServerPlayer player, String optionId, int backpackSlot) {
        if (backpackSlot < 1 || backpackSlot > 9) {
            return DeployResult.fail("无效的配装槽位。");
        }
        if (optionId == null || optionId.isBlank()) {
            return DeployResult.fail("请先选择部署点。");
        }

        MatchContext ctx = findContext(player.getUUID());
        if (ctx == null || !ctx.entered.contains(player.getUUID())) {
            return DeployResult.fail("你当前不在对局中。");
        }
        if (ctx.phase == Phase.WAITING) {
            return DeployResult.fail("对局尚未开始。");
        }
        if (ctx.phase == Phase.ENDING) {
            return DeployResult.fail("对局已结束。");
        }

        TeamId team = TeamManager.getTeam(player);
        if (team == TeamId.SPECTATOR) {
            return DeployResult.fail("观战者无法部署。");
        }

        // 战斗中且仍然存活时不允许"重新部署"，否则等于一次免费回城
        if (ctx.phase == Phase.RUNNING && player.isAlive()) {
            return DeployResult.fail("战斗中无法重新部署。");
        }

        int now = player.server.getTickCount();
        int wait = waitTicks(player.getUUID(), now);
        if (wait > 0) {
            return DeployResult.fail("阵亡等待中，剩余 " + seconds(wait) + " 秒。");
        }

        // 连点保护：两次请求至少间隔 10 tick。真正防止重复出生的是"已经复活后不再是阵亡状态"，
        // 这里只是让连点不会打出两串日志/两次配装下发。
        Integer last = DEPLOY_REQUEST_TICK.get(player.getUUID());
        if (last != null && now - last < 10) {
            return DeployResult.fail("部署请求过于频繁，请稍候。");
        }

        DeploymentTarget target = resolveDeployTarget(player, ctx, team, optionId);
        if (target == null) {
            return DeployResult.fail("该部署点已失效。");
        }

        DEPLOY_REQUEST_TICK.put(player.getUUID(), now);
        DEPLOY_WINDOWS.remove(player.getUUID());

        // 阵亡状态：记录选择后立刻走原版复活流程。
        // ServerGamePacketListenerImpl 处理 PERFORM_RESPAWN 时用的也是 PlayerList#respawn，
        // 因此这里的事件、维度切换、背包处理与玩家手点死亡界面完全一致；
        // 真正的落点由 onPlayerRespawn 按那一刻的状态再解析一次。
        if (!player.isAlive()) {
            PENDING_DEPLOY.put(player.getUUID(), new PendingDeploy(optionId, backpackSlot));
            try {
                player.server.getPlayerList().respawn(player, false);
            } catch (Throwable t) {
                PENDING_DEPLOY.remove(player.getUUID());
                Battlefield.LOGGER.error("[Battlefield] 玩家 {} 部署时强制复活失败",
                        player.getGameProfile().getName(), t);
                return DeployResult.fail("部署失败，请重试。");
            }
            return DeployResult.OK;
        }

        BattleTeleport.toLevel(player, ctx.battleLevel, target.x(), target.y(), target.z(), target.yaw(), 0.0f);
        player.setRespawnPosition(ctx.battleLevel.dimension(),
                BlockPos.containing(target.x(), target.y(), target.z()), target.yaw(), true, false);

        BackpackBridge.applyBackpackPreset(player, backpackSlot);
        return DeployResult.OK;
    }

    @Nullable
    private static DeploymentTarget resolveDeployTarget(ServerPlayer player, MatchContext ctx, TeamId team, String optionId) {
        DeploymentRules rules = DeploymentRules.of(ctx.arena.gameMode);
        // 重新算一遍而不是信任客户端传来的旧数据：据点易主 / 信标消失 / 战线推进都会让旧 id 匹配不上
        return DeploymentRules.find(rules.targets(buildDeployContext(player, ctx, team)), optionId);
    }

    // ===================================================================================
    // 事件
    // ===================================================================================

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!Battlefield.isEnabled()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        MatchContext ctx = findContext(sp.getUUID());
        if (ctx == null) return;
        removeParticipant(ctx, sp, true);
        afterParticipantLeft(ctx);
    }

    /**
     * 登录时处理上一次留下的状态：
     * <ul>
     *   <li>掉线：背包已在登出时还原，这里把人从战场维度送回进场前的位置；</li>
     *   <li>崩服：快照还在、背包还是对局里的，这里一并还原；</li>
     *   <li>残留阵营（对局不会跨重启存活）一律清掉。</li>
     * </ul>
     */
    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!Battlefield.isEnabled()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (PLAYER_MATCH.containsKey(sp.getUUID())) return;

        if (TeamManager.getTeam(sp) != TeamId.SPECTATOR) {
            TeamManager.clearTeam(sp);
        }
        if (PlayerSnapshotStore.hasSnapshot(sp)) {
            BattleTeleport.sendHome(sp, PlayerSnapshotStore.restore(sp), defaultLobby());
            Battlefield.LOGGER.info("[Battlefield] 玩家 {} 登录时补还原了上次对局的快照", sp.getGameProfile().getName());
        } else if (BattleWorlds.isBattleLevel(sp.serverLevel())) {
            BattleTeleport.sendHome(sp, null, defaultLobby());
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        int stale = TeamManager.clearAllStale(server.overworld());
        if (stale > 0) {
            Battlefield.LOGGER.info("[Battlefield] 清理了 {} 条残留阵营记录", stale);
        }
        loadConfig(server);

        SlotRegistry registry = SlotRegistry.get(server);
        if (registry.markRunning()) {
            Battlefield.LOGGER.warn("[Battlefield] 检测到上次异常退出，所有战场槽位将重新校验");
        }

        // 开服预热：每个实例化 arena 先准备好 prewarmSlots 个干净槽位，第一局不用等
        for (var arena : config.arenas.values()) {
            if (!arena.instanced() || arena.prewarmSlots <= 0) continue;
            ServerLevel level = BattleWorlds.resolve(server, arena.battleWorld);
            if (level == null) continue;
            List<SlotRegistry.Slot> slots = new ArrayList<>(registry.slotsOf(arena.areaName));
            while (slots.size() < arena.prewarmSlots) {
                SlotRegistry.Slot s = registry.allocate(arena.areaName);
                if (s == null) break;
                slots.add(s);
            }
            CompletableFuture<ArenaTemplate> template = TemplateStore.get(server, arena);
            for (int i = 0; i < Math.min(arena.prewarmSlots, slots.size()); i++) {
                SlotRegistry.Slot s = slots.get(i);
                PENDING_PREWARM.add(new PendingPrewarm(level, s, SlotGeometry.of(s.index, arena.templateBounds), template));
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        // 正常关服标记：随后的最终存档会带上它。仍在进行的对局所在槽位已经是脏的，下次开服会还原
        SlotRegistry.get(event.getServer()).markStopped();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        MATCHES.clear();
        PLAYER_MATCH.clear();
        PENDING_DEPLOY.clear();
        DEPLOY_WINDOWS.clear();
        DEPLOY_REQUEST_TICK.clear();
        PLAYER_AMMO_STATIONS.clear();
        SLOTS_IN_USE.clear();
        PENDING_PREWARM.clear();
        config = null;
        matchSerial = 0;
        BombardmentManager.clearAll();
        RespawnBeaconManager.clearAll();
        SquadManager.clearAll();
        SlotRestoreService.cancelAll();
        TemplateStore.shutdown();
        // 弹药上限配置改为延迟落盘，正常停服时补一次，避免丢掉最后一次 flush 之后的变更
        TaczAmmoCapConfig.flushIfDirty();
    }

    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!event.getPlacedBlock().is(ModBlocks.TACZ_AMMO_STATION.get())) return;

        UUID playerId = player.getUUID();
        ServerLevel level = (ServerLevel) event.getLevel();
        BlockPos newPos = event.getPos().immutable();

        PlacedAmmoStation previous = PLAYER_AMMO_STATIONS.get(playerId);
        if (previous != null) {
            ServerLevel previousLevel = player.getServer().getLevel(previous.dimension());
            if (previousLevel != null && !previous.pos().equals(newPos) && previousLevel.getBlockState(previous.pos()).is(ModBlocks.TACZ_AMMO_STATION.get())) {
                previousLevel.removeBlock(previous.pos(), false);
            }
        }

        PLAYER_AMMO_STATIONS.put(playerId, new PlacedAmmoStation(level.dimension(), newPos));
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (!event.getState().is(ModBlocks.TACZ_AMMO_STATION.get())) return;

        PlacedAmmoStation station = PLAYER_AMMO_STATIONS.get(player.getUUID());
        if (station == null) return;

        if (station.dimension().equals(player.serverLevel().dimension()) && station.pos().equals(event.getPos())) {
            PLAYER_AMMO_STATIONS.remove(player.getUUID());
        }
    }

    /** 最低优先级：前面的监听者（图腾类 mod）取消了死亡事件时不再扣票。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!Battlefield.isEnabled()) return;
        if (!(event.getEntity() instanceof ServerPlayer dead)) return;
        MatchContext ctx = findContext(dead.getUUID());
        if (ctx == null || ctx.phase != Phase.RUNNING || ctx.session == null) return;

        TeamId team = TeamManager.getTeam(dead);
        if (team == TeamId.ATTACKERS) {
            ctx.session.attackerTickets = Math.max(0, ctx.session.attackerTickets - 1);
            if (ctx.session.attackerTickets == 0 && !isSpecialLastPointCapturing(ctx)) {
                ctx.pendingEndWinner = TeamId.DEFENDERS;
            }
        }

        // 阵亡后打开部署界面：死亡事件比 ClientboundPlayerCombatKillPacket 更早触发，
        // 所以不能在事件里直接发包（会被原版死亡界面盖住），而是排到下一 tick。
        // readyAt 到点后再推一次，把「阵亡等待」的按钮放开——等待时长由服务端权威状态决定。
        int now = dead.server.getTickCount();
        int delay = Math.max(0, BattlefieldServerConfig.get().deployOpenDelayTicks);
        DEPLOY_WINDOWS.put(dead.getUUID(), new DeployWindow(now + 1, now + delay + 1));
        // 客户端一旦处于「立即重生」状态，死亡界面会被它自己收掉并直接发重生包，部署界面就没机会打开。
        // 每次阵亡都显式把状态拨回「显示死亡界面」：死亡事件早于 CombatKill 包，这个包一定先到。
        syncDeathScreen(dead, true);
        Battlefield.LOGGER.info("[Battlefield] {} 阵亡，{} tick 后开放部署（对局 {}）",
                dead.getGameProfile().getName(), delay, ctx.matchId);
    }

    /**
     * 参与者死亡不掉落：装备来自部署配装，结算时还原快照。掉在地上只会变成可被他人捡走的复制源。
     */
    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp && isEnteredParticipant(sp)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onExperienceDrop(LivingExperienceDropEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp && isEnteredParticipant(sp)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!Battlefield.isEnabled()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        MatchContext ctx = findContext(sp.getUUID());
        if (ctx == null || !ctx.entered.contains(sp.getUUID())) return;

        // PlayerList#respawn 会按服务端 doImmediateRespawn 再下发一次「立即重生」游戏事件，
        // 客户端随之把 showDeathScreen 重置——于是第二次阵亡起，客户端会自己重生掉，部署界面根本没机会打开。
        // 每复活一次就把状态拨回「显示死亡界面」，保证下一次阵亡仍由部署界面接管。
        syncDeathScreen(sp, true);

        TeamId team = TeamManager.getTeam(sp);
        if (team == TeamId.SPECTATOR) return;

        // ① 部署界面确认过的位置优先（此时再按复活这一刻的状态解析一次，信标可能已被摧毁、据点可能易主）
        PendingDeploy pending = PENDING_DEPLOY.remove(sp.getUUID());
        Battlefield.LOGGER.info("[Battlefield] {} 触发复活事件，pending={}",
                sp.getGameProfile().getName(), pending == null ? "无" : pending.optionId());
        if (pending != null) {
            DeploymentTarget target = resolveDeployTarget(sp, ctx, team, pending.optionId());
            if (target != null) {
                BattleTeleport.toLevel(sp, ctx.battleLevel, target.x(), target.y(), target.z(), target.yaw(), 0.0f);
                sp.setRespawnPosition(ctx.battleLevel.dimension(),
                        BlockPos.containing(target.x(), target.y(), target.z()), target.yaw(), true, false);
                // 装备来源就是部署配装：死亡掉落已被拦截，这里把选中的预设重新装上
                BackpackBridge.applyBackpackPreset(sp, pending.backpackSlot());
                sp.displayClientMessage(Component.literal("已部署至 " + target.option().name())
                        .withStyle(ChatFormatting.GREEN), true);
                return;
            }
            sp.displayClientMessage(Component.literal("所选部署点已失效，改用默认出生点。")
                    .withStyle(ChatFormatting.YELLOW), true);
        }

        // ② 其次在小队复活信标处重生（离死亡点最近且可站立的那个）
        if (BeaconRespawnLogic.tryAutoRespawn(sp)) {
            if (pending != null) BackpackBridge.applyBackpackPreset(sp, pending.backpackSlot());
            return;
        }

        // ③ 没有信标 / 信标被摧毁 / 被方块堵住 → 回落到队伍主出生点
        teleportInMatch(ctx, sp, ctx.spawnOf(team));
        if (pending != null) BackpackBridge.applyBackpackPreset(sp, pending.backpackSlot());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (!Battlefield.isEnabled()) return;
        if (e.phase != TickEvent.Phase.END) return;
        if (!PENDING_PREWARM.isEmpty()) pollPrewarm();
        if (!DEPLOY_WINDOWS.isEmpty()) tickDeployWindows(e.getServer());
        if (MATCHES.isEmpty()) return;
        // 拷贝一份：结算会把对局从表里摘掉
        for (MatchContext ctx : new ArrayList<>(MATCHES.values())) {
            tickMatch(ctx);
        }
    }

    /**
     * 阵亡后按窗口推送部署数据。
     * <p>
     * 一次阵亡会推两次：第一次在死亡后 1 tick（打开界面、带阵亡等待倒计时），
     * 第二次在等待结束时（把按钮放开）。玩家在这之前自己点了原版复活按钮的，直接丢弃。
     */
    private static void tickDeployWindows(MinecraftServer server) {
        int now = server.getTickCount();
        Iterator<Map.Entry<UUID, DeployWindow>> it = DEPLOY_WINDOWS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, DeployWindow> entry = it.next();
            DeployWindow window = entry.getValue();
            if (now < window.openAt()) continue;

            ServerPlayer sp = server.getPlayerList().getPlayer(entry.getKey());
            MatchContext ctx = findContext(entry.getKey());
            if (sp == null || sp.isAlive() || ctx == null || ctx.phase != Phase.RUNNING
                    || !ctx.entered.contains(entry.getKey())) {
                // 最常见的两种：玩家已经自己复活（原版按钮 / 第三方插件接管），或对局已结束
                Battlefield.LOGGER.info("[Battlefield] 跳过部署界面推送：{}（alive={} phase={}）",
                        entry.getKey(), sp != null && sp.isAlive(), ctx == null ? "无对局" : ctx.phase);
                it.remove();
                continue;
            }

            DeploymentData pushed = sendDeploymentDataAndGet(sp);
            Battlefield.LOGGER.info("[Battlefield] 已向 {} 推送部署数据（{} 个可选点，等待 {} tick）",
                    sp.getGameProfile().getName(), pushed.options().size(), waitTicks(sp.getUUID(), now));
            if (now >= window.readyAt()) {
                it.remove();
            } else {
                entry.setValue(new DeployWindow(window.readyAt(), window.readyAt()));
            }
        }
    }

    private static void pollPrewarm() {
        Iterator<PendingPrewarm> it = PENDING_PREWARM.iterator();
        while (it.hasNext()) {
            PendingPrewarm p = it.next();
            if (!p.template().isDone()) continue;
            it.remove();
            if (p.template().isCompletedExceptionally()) {
                Battlefield.LOGGER.error("[Battlefield] 槽位 #{} 预热失败：模板读取异常", p.slot().index);
                continue;
            }
            ArenaTemplate t = p.template().join();
            if (!p.slot().isClean(t.fingerprint, p.geometry()) && !SLOTS_IN_USE.contains(p.slot().index)) {
                SlotRestoreService.schedule(p.level(), p.slot(), p.geometry(), p.template());
            }
        }
    }

    // ===================================================================================
    // 对局循环
    // ===================================================================================

    private static void tickMatch(MatchContext ctx) {
        ctx.tickCounter++;

        if (!ctx.ready) {
            pollPreparation(ctx);
            if (!ctx.ready) {
                if (ctx.tickCounter % 10 == 0) sendPreparingActionbar(ctx);
                return;
            }
        }

        pollMapBake(ctx);

        forEachEntered(ctx, BattlefieldGameManager::keepPlayerFed);

        if (ctx.phase == Phase.COUNTDOWN) {
            enforceFrozenPhaseMovement(ctx);
            ctx.countdownTicks--;
            if (ctx.countdownTicks <= 0) {
                startRunningMatch(ctx);
            }
        } else if (ctx.phase == Phase.ENDING) {
            enforceFrozenPhaseMovement(ctx);
            ctx.endingTicks--;
            if (ctx.endingTicks <= 0) {
                finishAndReset(ctx);
                return;
            }
        }

        if (ctx.phase == Phase.RUNNING && ctx.session != null && ctx.session.running) {
            if (ctx.session.getRemainingTicks() <= 0) {
                startEnding(ctx, TeamId.DEFENDERS);
                return;
            }
            enforceMovableArea(ctx);

            if (ctx.tickCounter % BattlefieldServerConfig.get().captureIntervalTicks == 0) {
                ctx.sectorManager.tick(ctx.session, participants(ctx));
                checkWinConditionBySector(ctx);
            }

            if (ctx.pendingEndWinner != null) {
                startEnding(ctx, ctx.pendingEndWinner);
                ctx.pendingEndWinner = null;
            }
        }

        if (ctx.phase == Phase.WAITING && ctx.tickCounter % 10 == 0) {
            sendWaitingActionbar(ctx);
        }

        syncPeriodicViews(ctx);
    }

    /** 等模板读完、槽位还原完。全部异步进行，这里只轮询状态。 */
    private static void pollPreparation(MatchContext ctx) {
        if (ctx.template == null || ctx.slot == null) {
            ctx.ready = true;
            onMatchReady(ctx);
            return;
        }
        if (!ctx.template.isDone()) return;

        ArenaTemplate template;
        try {
            template = ctx.template.join();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            abortMatch(ctx, "地图模板读取失败（" + cause.getMessage() + "）");
            return;
        }

        // 模板里的区块数据是只读的，正好可以在后台线程直接采样烘焙成底图：
        // 不加载世界、不占区块票据、不阻塞主线程，也不影响槽位还原。
        if (ctx.mapJob == null && BattlefieldServerConfig.get().deployMapBakeEnabled) {
            ctx.mapJob = DeployMapService.bakeAsync(template, DeployMapService.rasterFor(ctx.arena, template));
        }

        if (ctx.restoreJob == null) {
            boolean busy = SlotRestoreService.jobFor(ctx.slot.index) != null;
            if (!busy && ctx.slot.isClean(template.fingerprint, ctx.geometry)) {
                ctx.ready = true;
                onMatchReady(ctx);
                return;
            }
            ctx.restoreJob = SlotRestoreService.schedule(ctx.battleLevel, ctx.slot, ctx.geometry, ctx.template);
        }

        switch (ctx.restoreJob.state()) {
            case DONE -> {
                ctx.ready = true;
                onMatchReady(ctx);
            }
            case FAILED, CANCELLED -> abortMatch(ctx, "地图还原失败");
            default -> {
            }
        }
    }

    /**
     * 底图烘焙完成后的发布：把模板坐标平移成槽位坐标，编码一次 PNG，然后发给本局参与者。
     * 客户端按 mapId 缓存，之后的死亡只走请求-应答，不会重复传输。
     */
    private static void pollMapBake(MatchContext ctx) {
        if (ctx.mapJob == null || !ctx.mapJob.isDone()) return;

        CompletableFuture<BakedDeployMap> job = ctx.mapJob;
        ctx.mapJob = null;

        BakedDeployMap map;
        try {
            map = job.join();
        } catch (Exception e) {
            Battlefield.LOGGER.warn("[Battlefield] 对局 {} 底图烘焙失败，界面将使用坐标示意图",
                    ctx.matchId, e.getCause() != null ? e.getCause() : e);
            return;
        }

        int dx = ctx.geometry == null ? 0 : ctx.geometry.blockDx();
        int dz = ctx.geometry == null ? 0 : ctx.geometry.blockDz();
        BakedDeployMap placed = map.offset(dx, dz);

        byte[] png;
        try {
            png = placed.toPng();
        } catch (RuntimeException e) {
            Battlefield.LOGGER.warn("[Battlefield] 对局 {} 底图编码失败，界面将使用坐标示意图", ctx.matchId, e);
            return;
        }

        ctx.bakedMap = placed;
        ctx.mapPng = png;
        for (ServerPlayer sp : participants(ctx)) {
            sendDeployMap(sp, placed, png);
        }
        Battlefield.LOGGER.info("[Battlefield] 对局 {} 底图就绪：{}x{}，{} 格/像素，PNG {} KB",
                ctx.matchId, placed.width(), placed.height(), placed.blocksPerPixel(), png.length / 1024);
    }

    private static void sendDeployMap(ServerPlayer player, BakedDeployMap map, byte[] png) {
        BattlefieldNet.sendToPlayer(player, new S2CDeployMapPacket(map.mapId(),
                map.minX(), map.minZ(), map.maxX(), map.maxZ(), map.blocksPerPixel(), png));
    }

    /** 客户端发现手里的 mapId 过期时主动要一次；不在对局或还没烘好就什么都不发。 */
    public static void requestDeployMap(ServerPlayer player) {
        MatchContext ctx = findContext(player.getUUID());
        if (ctx == null || !ctx.entered.contains(player.getUUID())) return;
        BakedDeployMap map = ctx.bakedMap;
        byte[] png = ctx.mapPng;
        if (map == null || png == null) return;
        sendDeployMap(player, map, png);
    }

    private static void onMatchReady(MatchContext ctx) {
        Battlefield.LOGGER.info("[Battlefield] 对局 {} 地图就绪，{} 名玩家进场", ctx.matchId, ctx.participants.size());
        for (ServerPlayer sp : participants(ctx)) {
            placeJoiner(ctx, sp, TeamManager.getTeam(sp));
        }
        if (ctx.phase == Phase.WAITING && ctx.participants.size() >= ctx.arena.minPlayerNumber) {
            beginCountdown(ctx);
        }
    }

    /**
     * 客户端视图同步。状态包与名牌队伍都做了降频：原先两者都是每 tick 全量执行，
     * 32 人局下分别是 640 包/秒与 640 次计分板写入/秒。阶段切换时补发一次。
     */
    private static void syncPeriodicViews(MatchContext ctx) {
        boolean phaseChanged = ctx.phase.ordinal() != ctx.lastSyncedPhase;
        if (phaseChanged) {
            ctx.lastSyncedPhase = ctx.phase.ordinal();
        }

        if (phaseChanged || ctx.tickCounter % NAME_TAG_SYNC_INTERVAL_TICKS == 0) {
            syncNameTagTeams(ctx);
        }
        if (phaseChanged || ctx.tickCounter % STATE_SYNC_INTERVAL_TICKS == 0) {
            sendState(ctx);
        }
    }

    /**
     * 冻结阶段防移动。按「冻结起点」判定：原实现比较的是上一 tick 的位置（xo/yo/zo），
     * 每 tick 走 0.2 格永远不触发回拉，改过的客户端倒计时期间可以自由走动。
     */
    private static void enforceFrozenPhaseMovement(MatchContext ctx) {
        for (ServerPlayer sp : participants(ctx)) {
            if (!ctx.entered.contains(sp.getUUID())) continue;
            sp.setDeltaMovement(0, 0, 0);

            Vec3 anchor = ctx.frozenAnchors.computeIfAbsent(sp.getUUID(), k -> sp.position());
            // 客户端已有 BattlefieldClientMovementBlocker 清零输入，这里只兜底被爆炸/作弊推开的情况
            if (sp.position().distanceToSqr(anchor) > FROZEN_MAX_DRIFT_SQR) {
                sp.teleportTo(sp.serverLevel(), anchor.x, anchor.y, anchor.z, sp.getYRot(), sp.getXRot());
            }
        }
    }

    private static void syncNameTagTeams(MatchContext ctx) {
        if (ctx.battleLevel.getServer() == null) return;

        Scoreboard scoreboard = ctx.battleLevel.getServer().getScoreboard();
        PlayerTeam atkTeam = ensureNameTagTeam(scoreboard, ctx.teamName(TeamId.ATTACKERS), Component.literal("Battlefield Attackers " + ctx.matchId));
        PlayerTeam defTeam = ensureNameTagTeam(scoreboard, ctx.teamName(TeamId.DEFENDERS), Component.literal("Battlefield Defenders " + ctx.matchId));

        Set<String> activeAtk = new HashSet<>();
        Set<String> activeDef = new HashSet<>();
        forEachParticipant(ctx, sp -> {
            TeamId t = TeamManager.getTeam(sp);
            String name = sp.getScoreboardName();
            if (t == TeamId.ATTACKERS) {
                activeAtk.add(name);
                if (scoreboard.getPlayersTeam(name) != atkTeam) scoreboard.addPlayerToTeam(name, atkTeam);
            } else if (t == TeamId.DEFENDERS) {
                activeDef.add(name);
                if (scoreboard.getPlayersTeam(name) != defTeam) scoreboard.addPlayerToTeam(name, defTeam);
            }
        });

        pruneTeamMembers(scoreboard, atkTeam, activeAtk);
        pruneTeamMembers(scoreboard, defTeam, activeDef);
    }

    private static PlayerTeam ensureNameTagTeam(Scoreboard scoreboard, String teamName, Component displayName) {
        PlayerTeam team = scoreboard.getPlayerTeam(teamName);
        if (team == null) {
            team = scoreboard.addPlayerTeam(teamName);
            // 只在创建时写一次属性。原实现每次调用都 setDisplayName/setColor/setNameTagVisibility，
            // 等于每 tick 覆盖一次队伍属性，既浪费又把管理员的自定义改回去。
            team.setDisplayName(displayName);
            team.setColor(ChatFormatting.BLUE);
            team.setNameTagVisibility(Team.Visibility.HIDE_FOR_OTHER_TEAMS);
        }
        return team;
    }

    private static void pruneTeamMembers(Scoreboard scoreboard, PlayerTeam team, Set<String> actives) {
        Collection<String> members = team.getPlayers();
        // 常见情况是"没有变化"，此时直接返回，避免每次构造一遍副本
        if (members.size() == actives.size() && actives.containsAll(members)) {
            return;
        }
        for (String member : new ArrayList<>(members)) {
            if (!actives.contains(member)) {
                scoreboard.removePlayerFromTeam(member, team);
            }
        }
    }

    private static void removeFromNameTagTeams(MatchContext ctx, ServerPlayer sp) {
        Scoreboard scoreboard = sp.server.getScoreboard();
        PlayerTeam team = scoreboard.getPlayersTeam(sp.getScoreboardName());
        if (team != null && (team.getName().equals(ctx.teamName(TeamId.ATTACKERS)) || team.getName().equals(ctx.teamName(TeamId.DEFENDERS)))) {
            scoreboard.removePlayerFromTeam(sp.getScoreboardName(), team);
        }
    }

    /** 局结束删掉本局的两个计分板队伍。原实现从不删除，队伍随 scoreboard.dat 永久堆积。 */
    private static void removeNameTagTeams(MatchContext ctx) {
        Scoreboard scoreboard = ctx.battleLevel.getServer().getScoreboard();
        for (TeamId t : new TeamId[]{TeamId.ATTACKERS, TeamId.DEFENDERS}) {
            PlayerTeam team = scoreboard.getPlayerTeam(ctx.teamName(t));
            if (team != null) scoreboard.removePlayerTeam(team);
        }
    }

    private static void sendWaitingActionbar(MatchContext ctx) {
        String text = "【对局 " + ctx.matchId + " 需要 " + ctx.participants.size() + "/" + ctx.arena.minPlayerNumber + " 来开始】";
        Component message = Component.literal(text);
        forEachParticipant(ctx, sp -> sp.displayClientMessage(message, true));
    }

    private static void sendPreparingActionbar(MatchContext ctx) {
        int pct = ctx.restoreJob == null ? 0 : Math.round(ctx.restoreJob.progress() * 100);
        Component message = Component.literal("【对局 " + ctx.matchId + " 地图准备中 " + pct + "%】");
        forEachParticipant(ctx, sp -> sp.displayClientMessage(message, true));
    }

    private static void beginCountdown(MatchContext ctx) {
        ctx.phase = Phase.COUNTDOWN;
        ctx.countdownTicks = BattlefieldServerConfig.get().gameCountdownSeconds * 20;
        ctx.frozenAnchors.clear();
        ensureSession(ctx);
        forEachParticipant(ctx, sp -> {
            TeamId team = TeamManager.getTeam(sp);
            enterMatchWorld(ctx, sp, ctx.spawnOf(team));
            setRespawnToTeamSpawn(ctx, sp, team);
        });
    }

    private static void startRunningMatch(MatchContext ctx) {
        ctx.phase = Phase.RUNNING;
        ctx.pendingEndWinner = null;
        ctx.frozenAnchors.clear();
    }

    private static void startEnding(MatchContext ctx, TeamId winner) {
        ctx.phase = Phase.ENDING;
        ctx.winner = winner;
        ctx.endingTicks = BattlefieldServerConfig.get().gameEndingSeconds * 20;
        ctx.frozenAnchors.clear();

        forEachEntered(ctx, sp -> {
            sp.getInventory().clearContent();
            sp.setGameMode(GameType.ADVENTURE);
            sp.setDeltaMovement(0, 0, 0);
        });
    }

    private static void finishAndReset(MatchContext ctx) {
        executeResultCommands(ctx);
        // 必须先还原再传送：startEnding() 清过背包，这里不还原就等于装备被吞（removeParticipant 内部处理）
        for (ServerPlayer sp : participants(ctx)) {
            removeParticipant(ctx, sp, false);
        }
        releaseMatch(ctx);
    }

    private static void sendClientReset(ServerPlayer sp) {
        if (sp == null) return;

        BattlefieldNet.sendToPlayer(sp, new S2CGameStatePacket(
                false, (byte) 2,
                0, 0,
                0, 0, 0,
                Collections.emptyList(), Collections.emptyList(), 0,
                Collections.emptyList(), Phase.WAITING.ordinal(), "", "", 0
        ));
        BattlefieldNet.sendToPlayer(sp, new S2CSectorAreaPacket(0, Collections.emptyList(), Collections.emptyList()));
        // 关掉可能还开着的部署界面：离局/结算后旧界面不能继续用来发包
        BattlefieldNet.sendToPlayer(sp, new S2CDeployDataPacket(false, DeploymentData.CLOSED));
    }

    private static void ensureSession(MatchContext ctx) {
        if (ctx.session != null) return;
        GameSession s = new GameSession(ctx.battleLevel, copySectors(ctx.arena.sectors, ctx.dx(), ctx.dz()),
                ctx.arena.military, ctx.arena.addMilitary, ctx.arena.timeMinutes);
        ctx.session = s;
        Sector cur = s.currentSector();
        if (cur != null) {
            BattlefieldNet.sendSectorAreas(participants(ctx), ctx.session.currentSectorIndex, cur.attackerAreas, cur.defenderAreas);
        }
    }

    /** 每局一份独立的据点对象；实例化对局顺带把模板坐标平移到槽位。 */
    private static List<Sector> copySectors(List<Sector> sectors, double dx, double dz) {
        return sectors.stream().map(s -> {
            List<CapturePoint> points = s.points.stream()
                    .map(p -> new CapturePoint(p.id, p.x + dx, p.y, p.z + dz, p.radius))
                    .toList();
            return new Sector(s.id, points, shift(s.attackerAreas, dx, dz), shift(s.defenderAreas, dx, dz));
        }).toList();
    }

    private static List<Sector.AreaRect> shift(List<Sector.AreaRect> rects, double dx, double dz) {
        return rects.stream().map(r -> new Sector.AreaRect(r.x1() + dx, r.z1() + dz, r.x2() + dx, r.z2() + dz)).toList();
    }

    private static void checkWinConditionBySector(MatchContext ctx) {
        if (ctx.session == null) return;

        if (ctx.session.currentSector() == null) {
            ctx.pendingEndWinner = TeamId.ATTACKERS;
            return;
        }

        if (ctx.session.attackerTickets <= 0 && !isSpecialLastPointCapturing(ctx)) {
            ctx.pendingEndWinner = TeamId.DEFENDERS;
        }
    }

    private static boolean isSpecialLastPointCapturing(MatchContext ctx) {
        if (ctx.session == null || ctx.session.sectors.isEmpty()) return false;
        if (ctx.session.currentSectorIndex != ctx.session.sectors.size() - 1) return false;

        Sector lastSector = ctx.session.currentSector();
        if (lastSector == null || lastSector.points.isEmpty()) return false;
        CapturePoint lastPoint = lastSector.points.get(lastSector.points.size() - 1);

        if (lastPoint.getProgress() >= 100) return true;
        if (lastPoint.lastAttackersIn > 0 && lastPoint.lastDefendersIn == 0) return true;

        for (CapturePoint p : lastSector.points) {
            if (p.owner == TeamId.DEFENDERS) return false;
        }
        return false;
    }

    private static void sendState(MatchContext ctx) {
        if (ctx.battleLevel.getServer() == null) return;

        List<ServerPlayer> online = participants(ctx);
        if (online.isEmpty()) return;

        // 票数 / 据点 / 阶段文案在同一 tick 内对所有玩家完全一致，只构建一次即可。
        // 原实现把它们放在逐玩家的循环里，32 人局每 tick 会重复构建 32 次（含 32 次 ArrayList 分配）。
        int remain = ctx.session != null ? ctx.session.getRemainingTicks() : 0;
        int atk = ctx.session != null ? ctx.session.attackerTickets : 0;
        int def = ctx.session != null ? ctx.session.defenderTickets : 0;
        List<S2CGameStatePacket.PointInfo> pointInfos = buildPoints(ctx);

        String overlayTitle = "";
        String overlaySub = "";
        int overlayTicks = 0;
        if (ctx.phase == Phase.COUNTDOWN) {
            overlayTitle = "对局 " + ctx.matchId + " 即将开始";
            overlaySub = "倒计时";
            overlayTicks = ctx.countdownTicks;
        } else if (ctx.phase == Phase.ENDING) {
            overlayTitle = (ctx.winner == TeamId.ATTACKERS ? "进攻方" : "防守方") + "赢得了这场战斗的胜利";
            overlaySub = participantNames(ctx, ctx.winner);
            overlayTicks = ctx.endingTicks;
        }

        boolean inBattle = ctx.phase != Phase.WAITING;
        int phaseOrdinal = ctx.phase.ordinal();

        // 小队快照按 (阵营, 小队号) 缓存：同一小队成员拿到的内容完全相同，
        // 原实现为每名玩家重算一次 O(P) 并做一次 UUID 字符串排序，32 人局即 O(P²)。
        Map<Long, SquadSnapshot> squadCache = new HashMap<>();

        for (ServerPlayer sp : online) {
            TeamId t = TeamManager.getTeam(sp);
            SquadSnapshot squadSnapshot = SquadSnapshot.EMPTY;
            if (t != TeamId.SPECTATOR) {
                int squadId = SquadManager.getSquad(sp);
                long key = ((long) t.ordinal() << 32) | (squadId & 0xFFFFFFFFL);
                squadSnapshot = squadCache.computeIfAbsent(key, k -> buildSquadSnapshot(online, t, squadId));
            }

            var pkt = new S2CGameStatePacket(inBattle, toClientTeamCode(t),
                    atk, def,
                    remain, ScoreManager.getScore(sp.getUUID()), ScoreManager.getLastBonus(sp.getUUID(), sp.serverLevel().getGameTime()),
                    squadSnapshot.memberNames(), squadSnapshot.memberScores(), squadSnapshot.totalScore(), pointInfos,
                    phaseOrdinal, overlayTitle, overlaySub, overlayTicks);
            BattlefieldNet.sendToPlayer(sp, pkt);
        }
    }

    private static SquadSnapshot buildSquadSnapshot(List<ServerPlayer> participants, TeamId teamId, int squadId) {
        List<ServerPlayer> members = new ArrayList<>(4);
        for (ServerPlayer sp : participants) {
            if (TeamManager.getTeam(sp) == teamId && SquadManager.getSquad(sp) == squadId) {
                members.add(sp);
            }
        }
        // 按名字排序保证 HUD 行序稳定。Comparator.comparing(UUID::toString) 会在每次比较时
        // 重新拼一个 36 字符的 UUID 字符串（n log n 次分配），getScoreboardName 返回的是缓存串。
        members.sort(Comparator.comparing(ServerPlayer::getScoreboardName));

        List<String> memberNames = new ArrayList<>(members.size());
        List<Integer> memberScores = new ArrayList<>(members.size());
        int totalScore = 0;
        for (ServerPlayer member : members) {
            memberNames.add(member.getScoreboardName());
            int score = ScoreManager.getScore(member.getUUID());
            memberScores.add(score);
            totalScore += score;
        }
        return new SquadSnapshot(memberNames, memberScores, totalScore);
    }

    private record SquadSnapshot(List<String> memberNames, List<Integer> memberScores, int totalScore) {
        private static final SquadSnapshot EMPTY = new SquadSnapshot(Collections.emptyList(), Collections.emptyList(), 0);
    }

    private static List<S2CGameStatePacket.PointInfo> buildPoints(MatchContext ctx) {
        if (ctx.session == null || ctx.session.currentSector() == null) return Collections.emptyList();
        List<S2CGameStatePacket.PointInfo> list = new ArrayList<>();
        for (var p : ctx.session.currentSector().points) {
            list.add(new S2CGameStatePacket.PointInfo(
                    p.id, p.x, p.y, p.z, (float) p.radius,
                    p.getProgress(),
                    (byte) (p.owner == TeamId.ATTACKERS ? 0 : (p.owner == TeamId.DEFENDERS ? 1 : 2)),
                    p.lastAttackersIn,
                    p.lastDefendersIn
            ));
        }
        return list;
    }

    private static void executeResultCommands(MatchContext ctx) {
        List<String> winnerCommands = ctx.arena.winCommand;
        List<String> loserCommands = ctx.arena.loseCommand;
        forEachParticipant(ctx, sp -> {
            TeamId t = TeamManager.getTeam(sp);
            boolean isWinner = t == ctx.winner;
            List<String> list = isWinner ? winnerCommands : loserCommands;
            for (String raw : list) {
                String cmd = raw.replace("{player}", sp.getGameProfile().getName());
                ctx.battleLevel.getServer().getCommands().performPrefixedCommand(
                        ctx.battleLevel.getServer().createCommandSourceStack().withSuppressedOutput(), cmd);
            }
        });
    }

    /** 按本局在册参与者的阵营计数分边：人数少的一队优先，两队都满则观战。 */
    private static TeamId autoAssignWithLimit(MatchContext ctx, ServerPlayer player) {
        int[] counts = teamCounts(ctx, player.getUUID());
        int atk = counts[0];
        int def = counts[1];

        TeamId pick;
        if (atk >= ctx.arena.attackNumber && def >= ctx.arena.defendNumber) {
            pick = TeamId.SPECTATOR;
        } else if (atk >= ctx.arena.attackNumber) {
            pick = TeamId.DEFENDERS;
        } else if (def >= ctx.arena.defendNumber) {
            pick = TeamId.ATTACKERS;
        } else {
            pick = atk <= def ? TeamId.ATTACKERS : TeamId.DEFENDERS;
        }
        TeamManager.setTeam(player, pick);
        return pick;
    }

    private static byte toClientTeamCode(TeamId teamId) {
        if (teamId == TeamId.ATTACKERS) return 0;
        if (teamId == TeamId.DEFENDERS) return 1;
        return 2;
    }

    /** 把 ctx.participants 解析成在线 ServerPlayer 列表，避免各处重复写一遍 stream 管道。 */
    private static List<ServerPlayer> participants(MatchContext ctx) {
        var server = ctx.battleLevel.getServer();
        if (server == null || ctx.participants.isEmpty()) return Collections.emptyList();
        List<ServerPlayer> players = new ArrayList<>(ctx.participants.size());
        for (UUID id : ctx.participants) {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp != null) players.add(sp);
        }
        return players;
    }

    private static void forEachParticipant(MatchContext ctx, java.util.function.Consumer<ServerPlayer> action) {
        for (ServerPlayer sp : participants(ctx)) {
            action.accept(sp);
        }
    }

    private static void forEachEntered(MatchContext ctx, java.util.function.Consumer<ServerPlayer> action) {
        for (ServerPlayer sp : participants(ctx)) {
            if (ctx.entered.contains(sp.getUUID())) action.accept(sp);
        }
    }

    private static void keepPlayerFed(ServerPlayer sp) {
        var food = sp.getFoodData();
        food.setFoodLevel(20);
        food.setSaturation(20.0F);
        food.setExhaustion(0.0F);
    }

    private static String participantNames(MatchContext ctx, TeamId teamId) {
        return participants(ctx).stream()
                .filter(sp -> TeamManager.getTeam(sp) == teamId)
                .map(Player::getScoreboardName)
                .collect(Collectors.joining(", "));
    }

    private static void enforceMovableArea(MatchContext ctx) {
        var s = ctx.session;
        var sector = s.currentSector();
        if (sector == null) {
            ctx.outsideAreaTicks.clear();
            return;
        }

        // 只遍历本局参与者。原实现遍历全服在线玩家再用 participants.contains 过滤，
        // 多局并行时是 O(局数 × 全服人数) / tick。
        for (ServerPlayer sp : participants(ctx)) {
            if (sp.serverLevel() != s.level || sp.isCreative() || sp.isSpectator()) continue;

            TeamId t = TeamManager.getTeam(sp);
            byte myTeam = toClientTeamCode(t);
            if (myTeam != 0 && myTeam != 1) {
                ctx.outsideAreaTicks.remove(sp.getUUID());
                continue;
            }

            List<Sector.AreaRect> myAreas =
                    (myTeam == 0) ? sector.attackerAreas : sector.defenderAreas;

            boolean inside = BattlefieldAreaRules.isInsideAreas2D(sp.getX(), sp.getZ(), myAreas);

            if (inside) {
                ctx.outsideAreaTicks.remove(sp.getUUID());
                continue;
            }

            int ticks = ctx.outsideAreaTicks.getOrDefault(sp.getUUID(), 0) + 1;
            if (ticks >= BattlefieldServerConfig.get().outsideAreaKillTicks) {
                ctx.outsideAreaTicks.remove(sp.getUUID());
                sp.kill();
                continue;
            }
            ctx.outsideAreaTicks.put(sp.getUUID(), ticks);
        }

        ctx.outsideAreaTicks.keySet().removeIf(id -> s.level.getPlayerByUUID(id) == null);
    }

    @Nullable
    private static MatchContext findContext(UUID playerId) {
        String matchId = PLAYER_MATCH.get(playerId);
        return matchId == null ? null : MATCHES.get(matchId);
    }

    @Nullable
    private static SectorConfigLoader.Position defaultLobby() {
        if (config == null) return null;
        var arena = config.getArena(null);
        return arena == null ? null : arena.lobby;
    }

    // ===================================================================================
    // 对外查询（供计分、友伤、小队、信标、包处理使用）
    // ===================================================================================

    @Nullable
    public static String getPlayerMatchId(UUID playerId) {
        return PLAYER_MATCH.get(playerId);
    }

    public static boolean sameMatch(ServerPlayer a, ServerPlayer b) {
        String ma = PLAYER_MATCH.get(a.getUUID());
        return ma != null && ma.equals(PLAYER_MATCH.get(b.getUUID()));
    }

    /** 攻击者与受害者同属一局、该局正在进行、两人都已进场。 */
    public static boolean isCombatScored(ServerPlayer attacker, ServerPlayer victim) {
        if (!sameMatch(attacker, victim)) return false;
        MatchContext ctx = findContext(attacker.getUUID());
        return ctx != null && ctx.phase == Phase.RUNNING
                && ctx.entered.contains(attacker.getUUID()) && ctx.entered.contains(victim.getUUID());
    }

    public static boolean isEnteredParticipant(ServerPlayer player) {
        MatchContext ctx = findContext(player.getUUID());
        return ctx != null && ctx.entered.contains(player.getUUID());
    }

    /** 玩家在自己对局的世界里（信标只允许在这里放）。 */
    public static boolean isInOwnMatchWorld(ServerPlayer player) {
        MatchContext ctx = findContext(player.getUUID());
        return ctx != null && ctx.entered.contains(player.getUUID()) && player.serverLevel() == ctx.battleLevel;
    }

    public static List<ServerPlayer> participantsOf(ServerPlayer player) {
        MatchContext ctx = findContext(player.getUUID());
        return ctx == null ? Collections.emptyList() : participants(ctx);
    }

    public static boolean canUseSquadActions(ServerPlayer player) {
        MatchContext ctx = findContext(player.getUUID());
        return ctx != null && ctx.phase != Phase.ENDING;
    }

    @Nullable
    public static ServerLevel matchLevel(String matchId) {
        MatchContext ctx = matchId == null ? null : MATCHES.get(matchId);
        return ctx == null ? null : ctx.battleLevel;
    }

    @Nullable
    public static ServerLevel matchLevelOfBeacon(UUID beaconUuid) {
        return matchLevel(RespawnBeaconManager.matchOfBeacon(beaconUuid));
    }

    // ===================================================================================
    // 命令
    // ===================================================================================

    /** @return 总战线数；解析失败返回 -1 */
    public static int reloadSectors(MinecraftServer server) {
        if (!loadConfig(server)) return -1;
        return config.arenas.values().stream().mapToInt(a -> a.sectors.size()).sum();
    }

    public static int arenaCount() {
        if (config == null) return 0;
        return config.arenas.size();
    }

    public static Set<String> arenaIds() {
        if (config == null) return Collections.emptySet();
        return Collections.unmodifiableSet(config.arenas.keySet());
    }

    public static Set<String> matchIds() {
        return Collections.unmodifiableSet(MATCHES.keySet());
    }

    public static List<String> statusLines(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        lines.add("对局 " + MATCHES.size() + " 个，还原任务 " + SlotRestoreService.activeJobs() + " 个");
        for (MatchContext ctx : MATCHES.values()) {
            String prep = ctx.ready ? "" : "（准备中 " + (ctx.restoreJob == null ? 0 : Math.round(ctx.restoreJob.progress() * 100)) + "%）";
            lines.add(" - " + ctx.matchId + " " + ctx.phase + prep + " 人数 " + ctx.participants.size()
                    + (ctx.slot == null ? " 世界 " + ctx.battleLevel.dimension().location()
                    : " 槽位 #" + ctx.slot.index + " @x=" + (ctx.geometry.originChunkX() << 4))
                    + (ctx.session == null ? "" : " 票数 " + ctx.session.attackerTickets + " 战线 " + ctx.session.currentSectorIndex));
            for (ServerPlayer sp : participants(ctx)) {
                lines.add("     " + sp.getScoreboardName() + " " + TeamManager.getTeam(sp) + " 小队" + SquadManager.getSquad(sp)
                        + " 分数 " + ScoreManager.getScore(sp.getUUID()) + " 小队分 " + SquadManager.getSquadScore(sp)
                        + (ctx.entered.contains(sp.getUUID()) ? " @" + sp.serverLevel().dimension().location()
                        + String.format(" (%.0f, %.0f, %.0f)", sp.getX(), sp.getY(), sp.getZ()) : " 未进场"));
            }
        }
        for (SlotRegistry.Slot s : SlotRegistry.get(server).all()) {
            SlotRestoreJob job = SlotRestoreService.jobFor(s.index);
            lines.add(" · 槽位 #" + s.index + " " + s.arena + (SLOTS_IN_USE.contains(s.index) ? " 使用中" : " 空闲")
                    + (s.clean() ? " 干净" : " 脏") + (job != null ? " 还原中 " + Math.round(job.progress() * 100) + "%" : ""));
        }
        return lines;
    }

    /** 管理员强制开局：跳过最少人数，地图必须已就绪。 */
    public static String forceStart(String matchId) {
        MatchContext ctx = MATCHES.get(matchId);
        if (ctx == null) return "未找到对局 " + matchId;
        if (!ctx.ready) return "对局 " + matchId + " 地图尚未就绪";
        if (ctx.phase != Phase.WAITING) return "对局 " + matchId + " 已在 " + ctx.phase + " 阶段";
        if (ctx.participants.isEmpty()) return "对局 " + matchId + " 没有参与者";
        beginCountdown(ctx);
        return "对局 " + matchId + " 已强制开始倒计时";
    }

    public static String forceEnd(String matchId, TeamId winner) {
        MatchContext ctx = MATCHES.get(matchId);
        if (ctx == null) return "未找到对局 " + matchId;
        if (ctx.phase == Phase.ENDING) return "对局 " + matchId + " 已在结算";
        if (ctx.phase != Phase.RUNNING) {
            abortMatch(ctx, "管理员结束");
            return "对局 " + matchId + " 未开打，已直接解散";
        }
        startEnding(ctx, winner);
        return "对局 " + matchId + " 已结束，胜方 " + winner;
    }

    public static String restoreSlot(MinecraftServer server, int index) {
        SlotRegistry registry = SlotRegistry.get(server);
        SlotRegistry.Slot slot = registry.byIndex(index);
        if (slot == null) return "槽位 #" + index + " 未分配";
        if (SLOTS_IN_USE.contains(index)) return "槽位 #" + index + " 正被对局使用";
        ensureConfig(server);
        var arena = config.getArena(slot.arena);
        if (arena == null || !arena.instanced()) return "槽位 #" + index + " 所属 arena '" + slot.arena + "' 已不在配置中";
        ServerLevel level = BattleWorlds.resolve(server, arena.battleWorld);
        if (level == null) return "战场维度未加载";
        SlotRestoreService.schedule(level, slot, SlotGeometry.of(index, arena.templateBounds), TemplateStore.get(server, arena));
        return "槽位 #" + index + " 已开始还原";
    }

    @SubscribeEvent
    public static void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModBlocks.TACZ_AMMO_STATION_ITEM.get());
        }
    }
}
