package top.mores.battlefield.game;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.breakthrough.CapturePoint;
import top.mores.battlefield.breakthrough.Sector;
import top.mores.battlefield.config.SectorConfigLoader;
import top.mores.battlefield.net.BattlefieldNet;
import top.mores.battlefield.net.S2CGameStatePacket;
import top.mores.battlefield.net.S2CSectorAreaPacket;
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
    private static final int TICKS_PER_SECOND = 20;
    private static final int COUNTDOWN_SECONDS = 10;
    private static final int ENDING_SECONDS = 10;

    private BattlefieldGameManager() {
    }

    public enum Phase {WAITING, COUNTDOWN, RUNNING, ENDING}

    private static final String SCOREBOARD_TEAM_ATTACKERS = "battlefield_atk";
    private static final String SCOREBOARD_TEAM_DEFENDERS = "battlefield_def";

    private static final Map<String, MatchContext> MATCHES = new HashMap<>();
    private static final Map<UUID, String> PLAYER_MATCH = new HashMap<>();

    private static SectorConfigLoader.SectorConfig config;

    private static final class MatchContext {
        final String arenaId;
        final SectorConfigLoader.ArenaConfig arena;
        final ServerLevel battleLevel;
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

        MatchContext(String arenaId, SectorConfigLoader.ArenaConfig arena, ServerLevel battleLevel) {
            this.arenaId = arenaId;
            this.arena = arena;
            this.battleLevel = battleLevel;
        }
    }

    public static void loadConfig(ServerLevel defaultLevel) {
        if (!Battlefield.isEnabled()) return;
        Path cfgDir = FMLPaths.CONFIGDIR.get().resolve("battlefield");
        config = SectorConfigLoader.loadConfig(cfgDir);

        Map<String, MatchContext> next = new HashMap<>();
        for (var arena : config.arenas.values()) {
            ServerLevel level = resolveBattleLevel(defaultLevel, arena.world);
            if (level == null) continue;
            MatchContext old = MATCHES.get(arena.areaName);
            MatchContext ctx = old != null
                    ? old
                    : new MatchContext(arena.areaName, arena, level);
            next.put(arena.areaName, ctx);
        }
        MATCHES.clear();
        MATCHES.putAll(next);
    }

    public static TeamId joinBattle(ServerPlayer player) {
        if (!Battlefield.isEnabled()) {
            player.sendSystemMessage(Component.literal("[BT] 功能已禁用，请联系服主检查 license。"));
            return TeamId.SPECTATOR;
        }
        ensureConfig(player.serverLevel());
        return joinBattle(player, config.defaultAreaName());
    }

    public static TeamId joinBattle(ServerPlayer player, String arenaId) {
        if (!Battlefield.isEnabled()) {
            player.sendSystemMessage(Component.literal("[BT] 功能已禁用，请联系服主检查 license。"));
            return TeamId.SPECTATOR;
        }
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

        TeamId team = autoAssignWithLimit(ctx, player);
        if (team == TeamId.SPECTATOR) {
            player.sendSystemMessage(Component.literal("对局 " + arena.areaName + " 已满"));
            return team;
        }

        ctx.participants.add(player.getUUID());
        PLAYER_MATCH.put(player.getUUID(), arena.areaName);
        SquadManager.autoAssignSquad(player);
        teleportTo(player, arena.wait, ctx.battleLevel);
        setRespawn(player, arena.lobby, ctx.battleLevel);

        if (ctx.phase == Phase.WAITING && ctx.participants.size() >= arena.minPlayerNumber) {
            beginCountdown(ctx);
        }
        return team;
    }

    public static void leaveBattle(ServerPlayer player) {
        if (!Battlefield.isEnabled()) return;
        String arenaId = PLAYER_MATCH.get(player.getUUID());
        if (arenaId == null) {
            sendClientReset(player);
            return;
        }

        MatchContext ctx = MATCHES.get(arenaId);
        if (ctx == null) {
            PLAYER_MATCH.remove(player.getUUID());
            TeamManager.clearTeam(player);
            ScoreManager.clearPlayer(player.getUUID());
            sendClientReset(player);
            return;
        }

        sendClientReset(player);
        ctx.participants.remove(player.getUUID());
        ctx.outsideAreaTicks.remove(player.getUUID());
        PLAYER_MATCH.remove(player.getUUID());
        BombardmentManager.stop(player);
        TeamManager.clearTeam(player);
        ScoreManager.clearPlayer(player.getUUID());
        teleportTo(player, ctx.arena.lobby, ctx.battleLevel);
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

    @SubscribeEvent
    public static void onLogout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (!Battlefield.isEnabled()) return;
        if (event.getEntity() instanceof ServerPlayer sp && PLAYER_MATCH.containsKey(sp.getUUID())) {
            leaveBattle(sp);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        MATCHES.values().forEach(BattlefieldGameManager::resetMatch);
        MATCHES.clear();
        PLAYER_MATCH.clear();
        config = null;
        BombardmentManager.clearAll();
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
        if (team == TeamId.ATTACKERS) {
            teleportTo(sp, ctx.arena.firstAttackSpawnPoint, ctx.battleLevel);
        } else if (team == TeamId.DEFENDERS) {
            teleportTo(sp, ctx.arena.firstDefendSpawnPoint, ctx.battleLevel);
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

            if (ctx.tickCounter % SectorManager.CAPTURE_INTERVAL_TICKS == 0) {
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

        syncNameTagTeams(ctx);
        sendState(ctx);
    }

    private static void enforceFrozenPhaseMovement(MatchContext ctx) {
        forEachParticipant(ctx, sp -> {
            sp.setDeltaMovement(0, 0, 0);
            if (sp.getX() != sp.xo || sp.getY() != sp.yo || sp.getZ() != sp.zo) {
                sp.teleportTo(sp.serverLevel(), sp.xo, sp.yo, sp.zo, sp.getYRot(), sp.getXRot());
            }
        });
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
        }
        team.setDisplayName(displayName);
        team.setColor(ChatFormatting.BLUE);
        team.setNameTagVisibility(Team.Visibility.HIDE_FOR_OTHER_TEAMS);
        return team;
    }

    private static void pruneTeamMembers(Scoreboard scoreboard, PlayerTeam team, Set<String> actives) {
        Set<String> members = new HashSet<>(team.getPlayers());
        for (String member : members) {
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
        ctx.countdownTicks = COUNTDOWN_SECONDS * TICKS_PER_SECOND;
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
        ctx.endingTicks = ENDING_SECONDS * TICKS_PER_SECOND;

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
            teleportTo(sp, ctx.arena.lobby, ctx.battleLevel);
            setRespawn(sp, ctx.arena.lobby, ctx.battleLevel);
            BombardmentManager.stop(sp);
            TeamManager.clearTeam(sp);
            ScoreManager.clearPlayer(sp.getUUID());
            PLAYER_MATCH.remove(sp.getUUID());
        });
        resetMatch(ctx);
    }

    private static void resetMatch(MatchContext ctx) {
        ctx.session = null;
        ctx.phase = Phase.WAITING;
        ctx.winner = TeamId.SPECTATOR;
        ctx.countdownTicks = 0;
        ctx.endingTicks = 0;
        ctx.outsideAreaTicks.clear();
        ctx.participants.forEach(ScoreManager::clearPlayer);
        ctx.participants.forEach(PLAYER_MATCH::remove);
        ctx.participants.clear();
        ctx.pendingEndWinner = null;
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
        GameSession s = new GameSession(ctx.battleLevel, copySectors(ctx.arena.sectors), ctx.arena.military, ctx.arena.timeMinutes);
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

        forEachParticipant(ctx, sp -> {
            TeamId t = TeamManager.getTeam(sp);
            byte myTeam = toClientTeamCode(t);

            int remain = ctx.session != null ? ctx.session.getRemainingTicks() : 0;
            int atk = ctx.session != null ? ctx.session.attackerTickets : 0;
            int def = ctx.session != null ? ctx.session.defenderTickets : 0;
            List<S2CGameStatePacket.PointInfo> list = buildPoints(ctx);

            SquadSnapshot squadSnapshot = buildSquadSnapshot(ctx, sp, t);

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

            var pkt = new S2CGameStatePacket(ctx.phase != Phase.WAITING, myTeam,
                    atk, def,
                    remain, ScoreManager.getScore(sp.getUUID()), ScoreManager.getLastBonus(sp.getUUID(), sp.serverLevel().getGameTime()),
                    squadSnapshot.memberNames(), squadSnapshot.memberScores(), squadSnapshot.totalScore(), list,
                    ctx.phase.ordinal(), overlayTitle, overlaySub, overlayTicks);
            BattlefieldNet.sendToPlayer(sp, pkt);
        });
    }

    private static SquadSnapshot buildSquadSnapshot(MatchContext ctx, ServerPlayer player, TeamId teamId) {
        if (teamId == TeamId.SPECTATOR) {
            return SquadSnapshot.EMPTY;
        }

        int squadId = SquadManager.getSquad(player);
        List<UUID> members = ctx.participants.stream()
                .filter(id -> {
                    ServerPlayer sp = (ServerPlayer) player.serverLevel().getPlayerByUUID(id);
                    return sp != null && TeamManager.getTeam(sp) == teamId && SquadManager.getSquad(sp) == squadId;
                })
                .collect(Collectors.toCollection(ArrayList::new));
        members.sort(Comparator.comparing(UUID::toString));

        List<String> memberNames = new ArrayList<>(members.size());
        List<Integer> memberScores = new ArrayList<>(members.size());
        int totalScore = 0;
        for (UUID id : members) {
            var member = player.serverLevel().getPlayerByUUID(id);
            String display = member != null ? member.getGameProfile().getName() : id.toString().substring(0, 8);
            int score = ScoreManager.getScore(id);
            memberNames.add(display);
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
            teleportTo(player, ctx.arena.firstAttackSpawnPoint, ctx.battleLevel);
        } else if (teamId == TeamId.DEFENDERS) {
            teleportTo(player, ctx.arena.firstDefendSpawnPoint, ctx.battleLevel);
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

    private static void teleportTo(ServerPlayer player, SectorConfigLoader.Position pos, ServerLevel fallbackLevel) {
        if (pos == null) return;

        String targetWorld = pos.world();
        if (targetWorld != null && !targetWorld.isBlank()) {
            String curWorld = MohistTeleport.getCurrentWorldName(player);
            String normTarget = MohistTeleport.normalizeWorldName(targetWorld);

            if (curWorld != null && !curWorld.equalsIgnoreCase(normTarget)) {
                MohistTeleport.teleportToWorld(player, normTarget, pos.x(), pos.y(), pos.z(), player.getYRot(), player.getXRot());
                return;
            }
        }
        ServerLevel level = pos.resolveLevel(player.getServer(), fallbackLevel != null ? fallbackLevel : player.serverLevel());
        player.teleportTo(level, pos.x(), pos.y(), pos.z(), player.getYRot(), player.getXRot());
    }

    private static void setRespawn(ServerPlayer player, SectorConfigLoader.Position pos, ServerLevel fallbackLevel) {
        if (pos == null) return;
        ServerLevel level = pos.resolveLevel(player.getServer(), fallbackLevel != null ? fallbackLevel : player.serverLevel());
        player.setRespawnPosition(level.dimension(), net.minecraft.core.BlockPos.containing(pos.toVec3()), 0, true, false);
    }

    private static void forEachParticipant(MatchContext ctx, java.util.function.Consumer<ServerPlayer> action) {
        if (ctx.battleLevel.getServer() == null) return;
        List<ServerPlayer> players = ctx.participants.stream()
                .map(id -> ctx.battleLevel.getServer().getPlayerList().getPlayer(id))
                .filter(Objects::nonNull)
                .toList();
        players.forEach(action);
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

        for (var sp : s.level.getServer().getPlayerList().getPlayers()) {
            if (sp.serverLevel() != s.level || sp.isCreative() || sp.isSpectator()) continue;
            if (!ctx.participants.contains(sp.getUUID())) continue;

            TeamId t = TeamManager.getTeam(sp);
            byte myTeam = toClientTeamCode(t);
            if (myTeam != 0 && myTeam != 1) {
                ctx.outsideAreaTicks.remove(sp.getUUID());
                continue;
            }

            List<Sector.AreaCircle> myAreas =
                    (myTeam == 0) ? sector.attackerAreas : sector.defenderAreas;

            boolean inside = BattlefieldAreaRules.isInsideAreas2D(sp.getX(), sp.getZ(), myAreas);

            if (inside) {
                ctx.outsideAreaTicks.remove(sp.getUUID());
                continue;
            }

            int ticks = ctx.outsideAreaTicks.getOrDefault(sp.getUUID(), 0) + 1;
            if (ticks >= BattlefieldAreaRules.OUTSIDE_AREA_KILL_TICKS) {
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
        ServerLevel level = resolveBattleLevel(defaultLevel, arena.world);
        if (level == null) return null;

        MatchContext created = new MatchContext(arena.areaName, arena, level);
        MATCHES.put(arena.areaName, created);
        return created;
    }

    public static int reloadSectors(ServerLevel level) {
        loadConfig(level);
        return config.arenas.values().stream().mapToInt(a -> a.sectors.size()).sum();
    }

    public static Set<String> arenaIds() {
        if (config == null) return Collections.emptySet();
        return Collections.unmodifiableSet(config.arenas.keySet());
    }

    public static String getPlayerAreaName(UUID playerId) {
        return PLAYER_MATCH.get(playerId);
    }
}
