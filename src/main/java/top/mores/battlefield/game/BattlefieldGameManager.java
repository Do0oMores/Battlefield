package top.mores.battlefield.game;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.block.ModBlocks;
import top.mores.battlefield.block.TaczAmmoStationBlock;
import top.mores.battlefield.breakthrough.CapturePoint;
import top.mores.battlefield.config.BattlefieldServerConfig;
import top.mores.battlefield.config.TaczAmmoCapConfig;
import top.mores.battlefield.breakthrough.Sector;
import top.mores.battlefield.config.SectorConfigLoader;
import top.mores.battlefield.net.BattlefieldNet;
import top.mores.battlefield.net.S2CGameStatePacket;
import top.mores.battlefield.net.S2COpenDeployScreenPacket;
import top.mores.battlefield.net.S2CSectorAreaPacket;
import top.mores.battlefield.server.BattleInstanceWorldService;
import top.mores.battlefield.server.BackpackBridge;
import top.mores.battlefield.server.BombardmentManager;
import top.mores.battlefield.server.MohistTeleport;
import top.mores.battlefield.team.SquadManager;
import top.mores.battlefield.team.TeamId;
import top.mores.battlefield.team.TeamManager;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Mod.EventBusSubscriber(modid = Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BattlefieldGameManager {
    private BattlefieldGameManager() {
    }

    public enum Phase {WAITING, COUNTDOWN, RUNNING, ENDING}

    private static final String SCOREBOARD_TEAM_ATTACKERS = "battlefield_atk";
    private static final String SCOREBOARD_TEAM_DEFENDERS = "battlefield_def";

    /**
     * 客户端状态包发送间隔（tick）。原先每 tick 全量发送，32 人局等于 640 包/秒。
     * 阶段切换时额外补发一次，避免 HUD 出现明显滞后。
     */
    private static final int STATE_SYNC_INTERVAL_TICKS = 2;
    /** 名牌队伍同步间隔（tick）。原先每 tick 重建计分板队伍，32 人局等于 640 次/秒。 */
    private static final int NAME_TAG_SYNC_INTERVAL_TICKS = 10;
    /** 冻结阶段允许的位置漂移上限（格²）。超过才回拉，避免每 tick 全量传送。 */
    private static final double FROZEN_MAX_DRIFT_SQR = 0.5 * 0.5;

    private static final Map<String, MatchContext> MATCHES = new HashMap<>();
    private static final Map<UUID, String> PLAYER_MATCH = new HashMap<>();
    /** 阵亡玩家在部署界面选择的出生点索引，复活时消费一次 */
    private static final Map<UUID, Integer> PENDING_SPAWN_INDEX = new HashMap<>();
    private static final Map<UUID, PlacedAmmoStation> PLAYER_AMMO_STATIONS = new HashMap<>();

    private static SectorConfigLoader.SectorConfig config;

    private static final class MatchContext {
        final String arenaId;
        SectorConfigLoader.ArenaConfig arena;
        ServerLevel templateLevel;
        ServerLevel battleLevel;
        final SectorManager sectorManager = new SectorManager();
        final Map<UUID, Integer> outsideAreaTicks = new HashMap<>();
        final Set<UUID> participants = new HashSet<>();

        GameSession session;
        Phase phase = Phase.WAITING;
        TeamId winner = TeamId.SPECTATOR;
        TeamId pendingEndWinner;
        int tickCounter = 0;
        int countdownTicks = 0;
        int endingTicks = 0;
        /** 上一次已同步给客户端的阶段序号，用于阶段切换时强制补发状态包 */
        int lastSyncedPhase = -1;

        MatchContext(String arenaId, SectorConfigLoader.ArenaConfig arena, ServerLevel templateLevel, ServerLevel battleLevel) {
            this.arenaId = arenaId;
            this.arena = arena;
            this.templateLevel = templateLevel;
            this.battleLevel = battleLevel;
        }
    }

    private record PlacedAmmoStation(ResourceKey<net.minecraft.world.level.Level> dimension, BlockPos pos) {}

    public static void loadConfig(ServerLevel defaultLevel) {
        Path cfgDir = FMLPaths.CONFIGDIR.get().resolve("battlefield");
        config = SectorConfigLoader.loadConfig(cfgDir);

        Map<String, MatchContext> next = new HashMap<>();
        for (var arena : config.arenas.values()) {
            ServerLevel templateLevel = resolveBattleLevel(defaultLevel, arena.templateWorld);
            if (templateLevel == null) continue;
            MatchContext old = MATCHES.get(arena.areaName);
            MatchContext ctx = old != null
                    ? old
                    : new MatchContext(arena.areaName, arena, templateLevel, templateLevel);
            ctx.arena = arena;
            ctx.templateLevel = templateLevel;
            if (ctx.session == null || !ctx.arena.useTemporaryWorld) {
                ctx.battleLevel = templateLevel;
            }
            syncSessionWithConfig(ctx);
            next.put(arena.areaName, ctx);
        }
        MATCHES.clear();
        MATCHES.putAll(next);
    }

    private static void syncSessionWithConfig(MatchContext ctx) {
        if (ctx.session == null) return;
        ctx.session.setSectors(copySectors(ctx.arena.sectors));
        ctx.session.addMilitary = Math.max(0, ctx.arena.addMilitary);

        int nextDurationTicks = Math.max(1, ctx.arena.timeMinutes) * 60 * 20;
        int passedTicks = (int) Math.max(0L, ctx.battleLevel.getGameTime() - ctx.session.startGameTick);
        ctx.session.matchDurationTicks = Math.max(nextDurationTicks, passedTicks + 1);
    }

    public static TeamId joinBattle(ServerPlayer player) {
        ensureConfig(player.serverLevel());
        return joinBattle(player, config.defaultAreaName());
    }

    public static TeamId joinBattle(ServerPlayer player, String arenaId) {
        ensureConfig(player.serverLevel());

        if (PLAYER_MATCH.containsKey(player.getUUID())) {
            return TeamManager.getTeam(player);
        }

        var arena = config.getArena(arenaId);
        if (arena == null) {
            player.sendSystemMessage(Component.literal("未知对局ID: " + arenaId));
            return TeamId.SPECTATOR;
        }

        MatchContext ctx = getOrCreateMatch(player.serverLevel(), arena.areaName);
        if (ctx == null) {
            player.sendSystemMessage(Component.literal("无法创建对局: " + arena.areaName));
            return TeamId.SPECTATOR;
        }

        if (ctx.participants.size() >= arena.maxPlayerNumber) {
            player.sendSystemMessage(Component.literal("对局 " + arena.areaName + " 已满"));
            return TeamId.SPECTATOR;
        }

        // 结算阶段不接受新玩家：此时参与者正在被清背包/传送回大厅，中途插入只会拿到一份错乱的状态
        if (ctx.phase == Phase.ENDING) {
            player.sendSystemMessage(Component.literal("对局 " + arena.areaName + " 正在结算，请稍后再加入"));
            return TeamId.SPECTATOR;
        }

        TeamId team = autoAssignWithLimit(ctx, player);
        if (team == TeamId.SPECTATOR) {
            player.sendSystemMessage(Component.literal("对局 " + arena.areaName + " 已满"));
            return team;
        }

        ctx.participants.add(player.getUUID());
        PLAYER_MATCH.put(player.getUUID(), arena.areaName);
        SquadManager.autoAssignSquad(player);
        // 入场前先把背包/游戏模式存一份，结算清空后原样还回去
        PlayerSnapshotStore.capture(player);

        if (ctx.phase == Phase.WAITING) {
            // 未开局：先在等待区集合，倒计时开始时统一传送到本方出生点
            teleportTo(player, arena.wait);
        } else {
            // 中途加入（倒计时/进行中）：直接落本方主出生点。
            // 原实现一律传送到 wait 区，wait 可能落在可活动区域之外，开局后会被 enforcement 直接判死。
            teleportToTeamSpawn(ctx, player, team);
        }
        setRespawn(player, arena.lobby, ctx.battleLevel);

        // 中途加入者拿不到开局时整维度广播的区域包，这里单独补一份，
        // 否则客户端没有区域框、也没有出界警告，但服务端照样按矩形计时击杀。
        if (ctx.phase != Phase.WAITING && ctx.session != null) {
            Sector cur = ctx.session.currentSector();
            if (cur != null) {
                BattlefieldNet.sendSectorAreasTo(player, ctx.session.currentSectorIndex,
                        cur.attackerAreas, cur.defenderAreas);
            }
        }

        if (ctx.phase == Phase.WAITING && ctx.participants.size() >= arena.minPlayerNumber) {
            beginCountdown(ctx);
        }
        return team;
    }

    public static void leaveBattle(ServerPlayer player) {
        String arenaId = PLAYER_MATCH.get(player.getUUID());
        if (arenaId == null) {
            sendClientReset(player);
            return;
        }

        MatchContext ctx = MATCHES.get(arenaId);
        if (ctx == null) {
            PLAYER_MATCH.remove(player.getUUID());
            PENDING_SPAWN_INDEX.remove(player.getUUID());
            TeamManager.clearTeam(player);
            ScoreManager.clearPlayer(player.getUUID());
            PlayerSnapshotStore.restore(player);
            sendClientReset(player);
            return;
        }

        sendClientReset(player);
        ctx.participants.remove(player.getUUID());
        ctx.outsideAreaTicks.remove(player.getUUID());
        PLAYER_MATCH.remove(player.getUUID());
        PENDING_SPAWN_INDEX.remove(player.getUUID());
        BombardmentManager.stop(player);
        TeamManager.clearTeam(player);
        ScoreManager.clearPlayer(player.getUUID());
        PlayerSnapshotStore.restore(player);
        teleportTo(player, ctx.arena.lobby);
        setRespawn(player, ctx.arena.lobby, ctx.battleLevel);

        if (ctx.participants.isEmpty()) {
            resetMatch(ctx);
            return;
        }

        if ((ctx.phase == Phase.COUNTDOWN || ctx.phase == Phase.WAITING) && ctx.participants.size() < ctx.arena.minPlayerNumber) {
            ctx.phase = Phase.WAITING;
            ctx.countdownTicks = 0;
        }
    }

    /**
     * 部署请求的处理结果，用于给客户端回显失败原因。
     */
    public record DeployResult(boolean ok, String message) {
        static final DeployResult OK = new DeployResult(true, "");

        static DeployResult fail(String message) {
            return new DeployResult(false, message);
        }
    }

    /** 解析出来的部署目标：维度 + 坐标 + 朝向。 */
    private record DeployTarget(ServerLevel level, double x, double y, double z, float yaw) {
    }

    /**
     * 处理一次重新部署。所有校验都在服务端完成，客户端只提供一个索引。
     * <p>
     * 原实现直接把玩家传送到 {@code getSharedSpawnPos()}，有两个问题：
     * 一是部署点根本没按 spawnIndex 选，二是任何人都能靠发这个包白嫖一次回城传送。
     *
     * @param spawnIndex   0 = 队伍主出生点；1..N = 第 N 个可用小队信标
     * @param backpackSlot 1..9 配装预设槽位
     */
    public static DeployResult deploy(ServerPlayer player, int spawnIndex, int backpackSlot) {
        if (backpackSlot < 1 || backpackSlot > 9) {
            return DeployResult.fail("无效的配装槽位。");
        }

        MatchContext ctx = findContext(player.getUUID());
        if (ctx == null) {
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

        DeployTarget target = resolveDeployTarget(player, ctx, team, spawnIndex);
        if (target == null) {
            return DeployResult.fail("该部署点不可用。");
        }

        // 阵亡状态下只记录选择，等 onPlayerRespawn 时再解析一次：
        // 期间信标可能被打掉，必须用复活那一刻的状态重新判定。
        if (!player.isAlive()) {
            PENDING_SPAWN_INDEX.put(player.getUUID(), spawnIndex);
            return DeployResult.OK;
        }

        player.setDeltaMovement(0, 0, 0);
        player.fallDistance = 0.0f;
        player.teleportTo(target.level(), target.x(), target.y(), target.z(), target.yaw(), 0.0f);
        player.setRespawnPosition(target.level().dimension(),
                BlockPos.containing(target.x(), target.y(), target.z()), target.yaw(), true, false);

        BackpackBridge.applyBackpackPreset(player, backpackSlot);
        return DeployResult.OK;
    }

    @javax.annotation.Nullable
    private static DeployTarget resolveDeployTarget(ServerPlayer player, MatchContext ctx, TeamId team, int spawnIndex) {
        if (spawnIndex <= 0) {
            SectorConfigLoader.Position pos = (team == TeamId.ATTACKERS)
                    ? ctx.arena.firstAttackSpawnPoint
                    : ctx.arena.firstDefendSpawnPoint;
            if (pos == null) return null;
            ServerLevel level = pos.resolveLevel(player.getServer(), ctx.battleLevel);
            if (level == null) return null;
            return new DeployTarget(level, pos.x(), pos.y(), pos.z(), player.getYRot());
        }

        List<BeaconSpawnCandidate> beacons = BeaconRespawnLogic.collect(player);
        int index = spawnIndex - 1;
        if (index >= beacons.size()) return null;

        BeaconSpawnCandidate candidate = beacons.get(index);
        if (candidate.level() == null) return null;
        Vec3 p = candidate.pos();
        return new DeployTarget(candidate.level(), p.x, p.y, p.z, candidate.yaw());
    }

    @SubscribeEvent
    public static void onLogout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (!Battlefield.isEnabled()) return;
        if (event.getEntity() instanceof ServerPlayer sp && PLAYER_MATCH.containsKey(sp.getUUID())) {
            leaveBattle(sp);
        }
    }

    /**
     * 服务器异常退出时快照还留在存档里，玩家重新登录后补发，
     * 避免"上一局背包被清空、却没来得及还原"造成装备丢失。
     */
    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!Battlefield.isEnabled()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (PlayerSnapshotStore.hasSnapshot(sp)) {
            PlayerSnapshotStore.restore(sp);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        MATCHES.values().forEach(BattlefieldGameManager::resetMatch);
        MATCHES.clear();
        PLAYER_MATCH.clear();
        PENDING_SPAWN_INDEX.clear();
        PLAYER_AMMO_STATIONS.clear();
        config = null;
        BombardmentManager.clearAll();
        BattleInstanceWorldService.releaseAll();
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

    @SubscribeEvent
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
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!Battlefield.isEnabled()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        MatchContext ctx = findContext(sp.getUUID());
        if (ctx == null) return;

        TeamId team = TeamManager.getTeam(sp);
        if (team == TeamId.SPECTATOR) return;

        // ① 阵亡前在部署界面明确选过的出生点优先（此时再解析一次，信标可能已被摧毁）
        Integer pendingIndex = PENDING_SPAWN_INDEX.remove(sp.getUUID());
        if (pendingIndex != null) {
            DeployTarget pending = resolveDeployTarget(sp, ctx, team, pendingIndex);
            if (pending != null) {
                sp.teleportTo(pending.level(), pending.x(), pending.y(), pending.z(), pending.yaw(), 0.0f);
                return;
            }
            sp.displayClientMessage(Component.literal("所选部署点已失效，改用默认出生点。")
                    .withStyle(ChatFormatting.YELLOW), true);
        }

        // ② 其次在小队复活信标处重生（离死亡点最近且可站立的那个）
        if (BeaconRespawnLogic.tryAutoRespawn(sp)) {
            return;
        }

        // ③ 没有信标 / 信标被摧毁 / 被方块堵住 → 回落到队伍主出生点
        if (team == TeamId.ATTACKERS) {
            teleportTo(sp, ctx.arena.firstAttackSpawnPoint);
        } else if (team == TeamId.DEFENDERS) {
            teleportTo(sp, ctx.arena.firstDefendSpawnPoint);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (!Battlefield.isEnabled()) return;
        if (e.phase != TickEvent.Phase.END) return;
        for (MatchContext ctx : MATCHES.values()) {
            tickMatch(ctx);
        }
    }

    private static void tickMatch(MatchContext ctx) {
        ctx.tickCounter++;
        forEachParticipant(ctx, BattlefieldGameManager::keepPlayerFed);

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
                ctx.sectorManager.tick(ctx.session);
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

    private static void enforceFrozenPhaseMovement(MatchContext ctx) {
        for (ServerPlayer sp : participants(ctx)) {
            sp.setDeltaMovement(0, 0, 0);

            // 原先只要坐标与上一 tick 不等就 teleportTo，等价于每 tick 对每名玩家全量传送：
            // 32 人 × 20tps = 640 次/秒，每次都会下发位置包并触发客户端插值回拉（橡皮筋）。
            // 客户端已有 BattlefieldClientMovementBlocker 清零输入，这里只兜底被爆炸/作弊推开的情况。
            double dx = sp.getX() - sp.xo;
            double dy = sp.getY() - sp.yo;
            double dz = sp.getZ() - sp.zo;
            if (dx * dx + dy * dy + dz * dz > FROZEN_MAX_DRIFT_SQR) {
                sp.teleportTo(sp.serverLevel(), sp.xo, sp.yo, sp.zo, sp.getYRot(), sp.getXRot());
            }
        }
    }

    private static void syncNameTagTeams(MatchContext ctx) {
        if (ctx.battleLevel.getServer() == null) return;

        Scoreboard scoreboard = ctx.battleLevel.getServer().getScoreboard();
        String suffix = "_" + ctx.arenaId;
        PlayerTeam atkTeam = ensureNameTagTeam(scoreboard, SCOREBOARD_TEAM_ATTACKERS + suffix, Component.literal("Battlefield Attackers " + ctx.arenaId));
        PlayerTeam defTeam = ensureNameTagTeam(scoreboard, SCOREBOARD_TEAM_DEFENDERS + suffix, Component.literal("Battlefield Defenders " + ctx.arenaId));

        Set<String> activeAtk = new HashSet<>();
        Set<String> activeDef = new HashSet<>();
        forEachParticipant(ctx, sp -> {
            TeamId t = TeamManager.getTeam(sp);
            String name = sp.getScoreboardName();
            if (t == TeamId.ATTACKERS) {
                activeAtk.add(name);
                scoreboard.addPlayerToTeam(name, atkTeam);
            } else if (t == TeamId.DEFENDERS) {
                activeDef.add(name);
                scoreboard.addPlayerToTeam(name, defTeam);
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

    private static void sendWaitingActionbar(MatchContext ctx) {
        String text = "【对局 " + ctx.arenaId + " 需要 " + ctx.participants.size() + "/" + ctx.arena.minPlayerNumber + " 来开始】";
        Component message = Component.literal(text);
        forEachParticipant(ctx, sp -> sp.displayClientMessage(message, true));
    }

    private static void beginCountdown(MatchContext ctx) {
        ctx.phase = Phase.COUNTDOWN;
        ctx.countdownTicks = BattlefieldServerConfig.get().gameCountdownSeconds * 20;
        prepareBattleWorld(ctx);
        ensureSession(ctx);
        forEachParticipant(ctx, sp -> teleportToTeamSpawn(ctx, sp, TeamManager.getTeam(sp)));
    }

    private static void startRunningMatch(MatchContext ctx) {
        ctx.phase = Phase.RUNNING;
        ctx.pendingEndWinner = null;
    }

    private static void startEnding(MatchContext ctx, TeamId winner) {
        ctx.phase = Phase.ENDING;
        ctx.winner = winner;
        ctx.endingTicks = BattlefieldServerConfig.get().gameEndingSeconds * 20;

        forEachParticipant(ctx, sp -> {
            sp.getInventory().clearContent();
            sp.setGameMode(GameType.ADVENTURE);
            sp.setDeltaMovement(0, 0, 0);
        });
    }

    private static void finishAndReset(MatchContext ctx) {
        executeResultCommands(ctx);
        forEachParticipant(ctx, sp -> {
            sendClientReset(sp);
            // 必须先还原再传送：startEnding() 清过背包，这里不还原就等于装备被吞
            PlayerSnapshotStore.restore(sp);
            teleportTo(sp, ctx.arena.lobby);
            setRespawn(sp, ctx.arena.lobby, ctx.battleLevel);
            BombardmentManager.stop(sp);
            TeamManager.clearTeam(sp);
            ScoreManager.clearPlayer(sp.getUUID());
            PLAYER_MATCH.remove(sp.getUUID());
            PENDING_SPAWN_INDEX.remove(sp.getUUID());
        });
        resetMatch(ctx);
    }

    private static void resetMatch(MatchContext ctx) {
        BattleInstanceWorldService.releaseInstance(ctx.battleLevel, ctx.arena);
        ctx.session = null;
        ctx.phase = Phase.WAITING;
        ctx.winner = TeamId.SPECTATOR;
        ctx.countdownTicks = 0;
        ctx.endingTicks = 0;
        ctx.lastSyncedPhase = -1;
        ctx.outsideAreaTicks.clear();
        // 只清本局参与者。原实现在这里调用全局 ScoreManager.reset()，
        // 多个 arena 并行时任意一局结束都会把其他局的分数一起抹掉。
        ctx.participants.forEach(ScoreManager::clearPlayer);
        ctx.participants.forEach(PLAYER_MATCH::remove);
        ctx.participants.clear();
        ctx.pendingEndWinner = null;
        ctx.battleLevel = ctx.templateLevel;
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
    }

    private static void ensureSession(MatchContext ctx) {
        if (ctx.session != null) return;
        GameSession s = new GameSession(ctx.battleLevel, copySectors(ctx.arena.sectors), ctx.arena.military, ctx.arena.addMilitary, ctx.arena.timeMinutes);
        ctx.session = s;
        Sector cur = s.currentSector();
        if (cur != null) {
            BattlefieldNet.sendSectorAreas(ctx.session.level, ctx.session.currentSectorIndex, cur.attackerAreas, cur.defenderAreas);
        }
    }

    private static List<Sector> copySectors(List<Sector> sectors) {
        return sectors.stream().map(s -> {
            List<CapturePoint> points = s.points.stream()
                    .map(p -> new CapturePoint(p.id, p.x, p.y, p.z, p.radius))
                    .toList();
            return new Sector(s.id, points, List.copyOf(s.attackerAreas), List.copyOf(s.defenderAreas));
        }).toList();
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
            overlayTitle = "对局 " + ctx.arenaId + " 即将开始";
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

    private static TeamId autoAssignWithLimit(MatchContext ctx, ServerPlayer player) {
        long atk = countParticipantsInTeam(ctx, TeamId.ATTACKERS);
        long def = countParticipantsInTeam(ctx, TeamId.DEFENDERS);

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

    private static long countParticipantsInTeam(MatchContext ctx, TeamId teamId) {
        return ctx.participants.stream()
                .map(ctx.battleLevel::getPlayerByUUID)
                .filter(Objects::nonNull)
                .filter(sp -> TeamManager.getTeam((ServerPlayer) sp) == teamId)
                .count();
    }

    private static byte toClientTeamCode(TeamId teamId) {
        if (teamId == TeamId.ATTACKERS) return 0;
        if (teamId == TeamId.DEFENDERS) return 1;
        return 2;
    }

    private static void teleportToTeamSpawn(MatchContext ctx, ServerPlayer player, TeamId teamId) {
        if (teamId == TeamId.ATTACKERS) {
            teleportToBattleLevel(player, ctx.arena.firstAttackSpawnPoint, ctx.battleLevel);
            setRespawnToBattleLevel(player, ctx.arena.firstAttackSpawnPoint, ctx.battleLevel);
        } else if (teamId == TeamId.DEFENDERS) {
            teleportToBattleLevel(player, ctx.arena.firstDefendSpawnPoint, ctx.battleLevel);
            setRespawnToBattleLevel(player, ctx.arena.firstDefendSpawnPoint, ctx.battleLevel);
        }
    }

    private static void ensureConfig(ServerLevel defaultLevel) {
        if (config == null || MATCHES.isEmpty()) {
            loadConfig(defaultLevel);
        }
    }

    private static ServerLevel resolveBattleLevel(ServerLevel defaultLevel, String world) {
        var id = net.minecraft.resources.ResourceLocation.tryParse(world);
        if (id == null) return defaultLevel;
        ServerLevel level = defaultLevel.getServer().getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id));
        return level != null ? level : defaultLevel;
    }

    /**
     * 传送统一入口。解析顺序：
     * <ol>
     *   <li>原版维度已加载 —— 直接 NMS 传送，最快且不受插件干扰；</li>
     *   <li>维度未加载 / 只存在于 Bukkit 侧 —— 按世界名解析后交给 Bukkit（会触发世界加载）；</li>
     *   <li>都解析不到 —— 告警并取消。</li>
     * </ol>
     * 原实现在第 2 步失败时会退回 fallbackLevel，把玩家静默丢进错误的维度
     * （sectors.json 写成 minecraft:world 时就靠这个"兜底"掩盖了配置错误）。
     */
    private static void teleportTo(ServerPlayer player, SectorConfigLoader.Position pos) {
        if (pos == null) return;

        var server = player.getServer();
        if (server == null) return;

        ServerLevel level = pos.resolveLevel(server, null);
        if (level != null) {
            player.teleportTo(level, pos.x(), pos.y(), pos.z(), player.getYRot(), player.getXRot());
            return;
        }

        if (MohistTeleport.teleportToWorld(player, pos.world(), pos.x(), pos.y(), pos.z(),
                player.getYRot(), player.getXRot())) {
            return;
        }

        Battlefield.LOGGER.warn("[Battlefield] 世界 '{}' 无法解析，已取消传送（玩家 {}）",
                pos.world(), player.getGameProfile().getName());
    }

    private static void setRespawn(ServerPlayer player, SectorConfigLoader.Position pos, ServerLevel fallbackLevel) {
        if (pos == null) return;
        ServerLevel level = pos.resolveLevel(player.getServer(), fallbackLevel != null ? fallbackLevel : player.serverLevel());
        player.setRespawnPosition(level.dimension(), net.minecraft.core.BlockPos.containing(pos.toVec3()), 0, true, false);
    }

    private static void teleportToBattleLevel(ServerPlayer player, SectorConfigLoader.Position pos, ServerLevel battleLevel) {
        if (pos == null || battleLevel == null) return;
        player.teleportTo(battleLevel, pos.x(), pos.y(), pos.z(), player.getYRot(), player.getXRot());
    }

    private static void setRespawnToBattleLevel(ServerPlayer player, SectorConfigLoader.Position pos, ServerLevel battleLevel) {
        if (pos == null || battleLevel == null) return;
        player.setRespawnPosition(battleLevel.dimension(), net.minecraft.core.BlockPos.containing(pos.toVec3()), 0, true, false);
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

    private static void keepPlayerFed(ServerPlayer sp) {
        var food = sp.getFoodData();
        food.setFoodLevel(20);
        food.setSaturation(20.0F);
        food.setExhaustion(0.0F);
    }

    private static String participantNames(MatchContext ctx, TeamId teamId) {
        if (ctx.battleLevel.getServer() == null) return "";
        return ctx.participants.stream()
                .map(id -> ctx.battleLevel.getServer().getPlayerList().getPlayer(id))
                .filter(Objects::nonNull)
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

    private static MatchContext findContext(UUID playerId) {
        String arenaId = PLAYER_MATCH.get(playerId);
        return arenaId == null ? null : MATCHES.get(arenaId);
    }

    private static MatchContext getOrCreateMatch(ServerLevel defaultLevel, String arenaId) {
        MatchContext existing = MATCHES.get(arenaId);
        if (existing != null) return existing;

        var arena = config.getArena(arenaId);
        if (arena == null) return null;
        ServerLevel templateLevel = resolveBattleLevel(defaultLevel, arena.templateWorld);
        if (templateLevel == null) return null;

        MatchContext created = new MatchContext(arena.areaName, arena, templateLevel, templateLevel);
        MATCHES.put(arena.areaName, created);
        return created;
    }

    private static void prepareBattleWorld(MatchContext ctx) {
        if (ctx.session != null) return;
        if (!ctx.arena.useTemporaryWorld) {
            ctx.battleLevel = ctx.templateLevel;
            BattleInstanceWorldService.preloadChunks(ctx.battleLevel, ctx.arena);
            return;
        }

        ServerLevel instance = BattleInstanceWorldService.createInstance(ctx.templateLevel, ctx.arenaId, ctx.arena);
        if (instance != null) {
            ctx.battleLevel = instance;
        } else {
            ctx.battleLevel = ctx.templateLevel;
        }
        BattleInstanceWorldService.preloadChunks(ctx.battleLevel, ctx.arena);
    }

    public static int reloadSectors(ServerLevel level) {
        loadConfig(level);
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

    public static String getPlayerAreaName(UUID playerId) {
        return PLAYER_MATCH.get(playerId);
    }

    @SubscribeEvent
    public static void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModBlocks.TACZ_AMMO_STATION_ITEM.get());
        }
    }
}
