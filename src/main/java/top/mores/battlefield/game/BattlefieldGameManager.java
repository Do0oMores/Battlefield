package top.mores.battlefield.game;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.breakthrough.CapturePoint;
import top.mores.battlefield.breakthrough.Sector;
import top.mores.battlefield.config.SectorConfigLoader;
import top.mores.battlefield.net.BattlefieldNet;
import top.mores.battlefield.net.S2CGameStatePacket;
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

    public static GameSession SESSION;
    public static Phase PHASE = Phase.WAITING;
    public static TeamId WINNER = TeamId.SPECTATOR;

    private static int tickCounter = 0;
    private static final SectorManager sectorManager = new SectorManager();
    private static final Map<UUID, Integer> OUTSIDE_AREA_TICKS = new HashMap<>();
    private static final Set<UUID> PARTICIPANTS = new HashSet<>();

    private static int countdownTicks = 0;
    private static int endingTicks = 0;
    private static SectorConfigLoader.SectorConfig config;
    private static ServerLevel battleLevel;
    private static TeamId pendingEndWinner = null;

    public static void loadConfig(ServerLevel defaultLevel) {
        Path cfgDir = FMLPaths.CONFIGDIR.get().resolve("battlefield");
        config = SectorConfigLoader.loadConfig(cfgDir);
        battleLevel = resolveBattleLevel(defaultLevel, config.world);
    }

    public static TeamId joinBattle(ServerPlayer player) {
        ensureConfig(player.serverLevel());
        if (PARTICIPANTS.contains(player.getUUID())) {
            return TeamManager.getTeam(player);
        }
        if (PARTICIPANTS.size() >= config.maxPlayerNumber) {
            player.sendSystemMessage(Component.literal("当前对局已满"));
            return TeamId.SPECTATOR;
        }

        TeamId team = autoAssignWithLimit(player);
        if (team == TeamId.SPECTATOR) {
            player.sendSystemMessage(Component.literal("当前对局已满"));
            return team;
        }

        PARTICIPANTS.add(player.getUUID());
        SquadManager.autoAssignSquad(player);
        teleportTo(player, config.wait);
        setRespawn(player, config.lobby);

        if (PHASE == Phase.WAITING && PARTICIPANTS.size() >= config.minPlayerNumber) {
            beginCountdown();
        }
        return team;
    }

    public static void leaveBattle(ServerPlayer player) {
        PARTICIPANTS.remove(player.getUUID());
        TeamManager.clearTeam(player);
        ScoreManager.clearPlayer(player.getUUID());
        teleportTo(player, config != null ? config.lobby : null);
        setRespawn(player, config != null ? config.lobby : null);

        if (PARTICIPANTS.isEmpty()) {
            resetMatch();
            return;
        }

        if ((PHASE == Phase.COUNTDOWN || PHASE == Phase.WAITING) && PARTICIPANTS.size() < config.minPlayerNumber) {
            PHASE = Phase.WAITING;
            countdownTicks = 0;
        }
    }

    @SubscribeEvent
    public static void onLogout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp && PARTICIPANTS.contains(sp.getUUID())) {
            leaveBattle(sp);
        }
    }

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer dead)) return;
        if (PHASE != Phase.RUNNING || SESSION == null) return;
        if (!PARTICIPANTS.contains(dead.getUUID())) return;

        TeamId team = TeamManager.getTeam(dead);
        if (team == TeamId.ATTACKERS) {
            SESSION.attackerTickets = Math.max(0, SESSION.attackerTickets - 1);
            if (SESSION.attackerTickets == 0) {
                if (!isSpecialLastPointCapturing()) {
                    pendingEndWinner = TeamId.DEFENDERS;
                }
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!PARTICIPANTS.contains(sp.getUUID()) || config == null) return;

        TeamId team = TeamManager.getTeam(sp);
        if (team == TeamId.ATTACKERS) {
            teleportTo(sp, config.firstAttackSpawnPoint);
        } else if (team == TeamId.DEFENDERS) {
            teleportTo(sp, config.firstDefendSpawnPoint);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (config == null || battleLevel == null) return;

        tickCounter++;

        if (PHASE == Phase.COUNTDOWN) {
            countdownTicks--;
            if (countdownTicks <= 0) {
                startRunningMatch();
            }
        } else if (PHASE == Phase.ENDING) {
            endingTicks--;
            if (endingTicks <= 0) {
                finishAndReset();
                return;
            }
        }

        if (PHASE == Phase.RUNNING && SESSION != null && SESSION.running) {
            if (SESSION.getRemainingTicks() <= 0) {
                startEnding(TeamId.DEFENDERS);
                return;
            }
            enforceMovableArea();

            if (tickCounter % SectorManager.CAPTURE_INTERVAL_TICKS == 0) {
                sectorManager.tick(SESSION);
                checkWinConditionBySector();
            }

            if (pendingEndWinner != null) {
                startEnding(pendingEndWinner);
                pendingEndWinner = null;
            }
        }

        sendState();
    }

    private static void beginCountdown() {
        PHASE = Phase.COUNTDOWN;
        countdownTicks = 10 * 20;
        ensureSession();
        forEachParticipant(sp -> {
            if (TeamManager.getTeam(sp) == TeamId.ATTACKERS) teleportTo(sp, config.firstAttackSpawnPoint);
            if (TeamManager.getTeam(sp) == TeamId.DEFENDERS) teleportTo(sp, config.firstDefendSpawnPoint);
        });
    }

    private static void startRunningMatch() {
        PHASE = Phase.RUNNING;
        pendingEndWinner = null;
    }

    private static void startEnding(TeamId winner) {
        PHASE = Phase.ENDING;
        WINNER = winner;
        endingTicks = 10 * 20;

        forEachParticipant(sp -> {
            sp.getInventory().clearContent();
            sp.setGameMode(GameType.ADVENTURE);
            sp.setDeltaMovement(0, 0, 0);
        });
    }

    private static void finishAndReset() {
        executeResultCommands();
        forEachParticipant(sp -> {
            teleportTo(sp, config.lobby);
            setRespawn(sp, config.lobby);
            TeamManager.clearTeam(sp);
        });
        resetMatch();
    }

    private static void resetMatch() {
        SESSION = null;
        PHASE = Phase.WAITING;
        WINNER = TeamId.SPECTATOR;
        countdownTicks = 0;
        endingTicks = 0;
        OUTSIDE_AREA_TICKS.clear();
        PARTICIPANTS.clear();
        pendingEndWinner = null;
        ScoreManager.reset();
    }

    private static void ensureSession() {
        if (SESSION != null) return;
        GameSession s = new GameSession(battleLevel, config.sectors, config.military, config.timeMinutes);
        SESSION = s;
        Sector cur = s.currentSector();
        if (cur != null) {
            BattlefieldNet.sendSectorAreas(SESSION.level, SESSION.currentSectorIndex, cur.attackerAreas, cur.defenderAreas);
        }
    }

    private static void checkWinConditionBySector() {
        if (SESSION == null) return;

        if (SESSION.currentSector() == null) {
            pendingEndWinner = TeamId.ATTACKERS;
            return;
        }

        if (SESSION.attackerTickets <= 0 && !isSpecialLastPointCapturing()) {
            pendingEndWinner = TeamId.DEFENDERS;
        }
    }

    private static boolean isSpecialLastPointCapturing() {
        if (SESSION == null || SESSION.sectors.isEmpty()) return false;
        if (SESSION.currentSectorIndex != SESSION.sectors.size() - 1) return false;

        Sector lastSector = SESSION.currentSector();
        if (lastSector == null || lastSector.points.isEmpty()) return false;
        CapturePoint lastPoint = lastSector.points.get(lastSector.points.size() - 1);

        if (lastPoint.getProgress() >= 100) return true;
        if (lastPoint.lastAttackersIn > 0 && lastPoint.lastDefendersIn == 0) return true;

        for (CapturePoint p : lastSector.points) {
            if (p.owner == TeamId.DEFENDERS) return false;
        }
        return false;
    }

    private static void sendState() {
        if (battleLevel == null || battleLevel.getServer() == null) return;

        forEachParticipant(sp -> {
            TeamId t = TeamManager.getTeam(sp);
            byte myTeam = (byte) (t == TeamId.ATTACKERS ? 0 : (t == TeamId.DEFENDERS ? 1 : 2));

            int remain = SESSION != null ? SESSION.getRemainingTicks() : 0;
            int atk = SESSION != null ? SESSION.attackerTickets : 0;
            int def = SESSION != null ? SESSION.defenderTickets : 0;
            List<S2CGameStatePacket.PointInfo> list = buildPoints();

            List<String> squadIds = new ArrayList<>();
            List<Integer> squadScores = new ArrayList<>();
            int squadTotal = 0;
            if (t != TeamId.SPECTATOR) {
                int squadId = SquadManager.getSquad(sp);
                var members = SquadManager.getSquadMembers(sp.serverLevel(), t, squadId);
                members.sort(Comparator.comparing(UUID::toString));
                for (UUID id : members) {
                    var member = sp.serverLevel().getPlayerByUUID(id);
                    String display = member != null ? member.getGameProfile().getName() : id.toString().substring(0, 8);
                    int sc = ScoreManager.getScore(id);
                    squadIds.add(display);
                    squadScores.add(sc);
                    squadTotal += sc;
                }
            }

            String overlayTitle = "";
            String overlaySub = "";
            int overlayTicks = 0;
            if (PHASE == Phase.COUNTDOWN) {
                overlayTitle = "即将开始对局";
                overlaySub = "倒计时";
                overlayTicks = countdownTicks;
            } else if (PHASE == Phase.ENDING) {
                overlayTitle = (WINNER == TeamId.ATTACKERS ? "进攻方" : "防守方") + "赢得了这场战斗的胜利";
                overlaySub = participantNames(WINNER);
                overlayTicks = endingTicks;
            }

            var pkt = new S2CGameStatePacket(PHASE != Phase.WAITING, myTeam,
                    atk, def,
                    remain, ScoreManager.getScore(sp.getUUID()), ScoreManager.getLastBonus(sp.getUUID(), sp.serverLevel().getGameTime()),
                    squadIds, squadScores, squadTotal, list,
                    PHASE.ordinal(), overlayTitle, overlaySub, overlayTicks);
            BattlefieldNet.sendToPlayer(sp, pkt);
        });
    }

    private static List<S2CGameStatePacket.PointInfo> buildPoints() {
        if (SESSION == null || SESSION.currentSector() == null) return Collections.emptyList();
        List<S2CGameStatePacket.PointInfo> list = new ArrayList<>();
        for (var p : SESSION.currentSector().points) {
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

    private static void executeResultCommands() {
        if (config == null || battleLevel == null) return;
        List<String> winnerCommands = config.winCommand;
        List<String> loserCommands = config.loseCommand;
        forEachParticipant(sp -> {
            TeamId t = TeamManager.getTeam(sp);
            boolean isWinner = t == WINNER;
            List<String> list = isWinner ? winnerCommands : loserCommands;
            for (String raw : list) {
                String cmd = raw.replace("{player}", sp.getGameProfile().getName());
                battleLevel.getServer().getCommands().performPrefixedCommand(
                        battleLevel.getServer().createCommandSourceStack().withSuppressedOutput(), cmd);
            }
        });
    }

    private static TeamId autoAssignWithLimit(ServerPlayer player) {
        long atk = PARTICIPANTS.stream().map(player.serverLevel()::getPlayerByUUID).filter(Objects::nonNull).filter(sp -> TeamManager.getTeam((ServerPlayer) sp) == TeamId.ATTACKERS).count();
        long def = PARTICIPANTS.stream().map(player.serverLevel()::getPlayerByUUID).filter(Objects::nonNull).filter(sp -> TeamManager.getTeam((ServerPlayer) sp) == TeamId.DEFENDERS).count();

        TeamId pick;
        if (atk >= config.attackNumber && def >= config.defendNumber) {
            pick = TeamId.SPECTATOR;
        } else if (atk >= config.attackNumber) {
            pick = TeamId.DEFENDERS;
        } else if (def >= config.defendNumber) {
            pick = TeamId.ATTACKERS;
        } else {
            pick = atk <= def ? TeamId.ATTACKERS : TeamId.DEFENDERS;
        }
        TeamManager.setTeam(player, pick);
        return pick;
    }

    private static void ensureConfig(ServerLevel defaultLevel) {
        if (config == null || battleLevel == null) {
            loadConfig(defaultLevel);
        }
    }

    private static ServerLevel resolveBattleLevel(ServerLevel defaultLevel, String world) {
        var id = net.minecraft.resources.ResourceLocation.tryParse(world);
        if (id == null) return defaultLevel;
        ServerLevel level = defaultLevel.getServer().getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id));
        return level != null ? level : defaultLevel;
    }

    private static void teleportTo(ServerPlayer player, SectorConfigLoader.Position pos) {
        if (pos == null) return;
        ServerLevel level = pos.resolveLevel(player.getServer(), player.serverLevel());
        player.teleportTo(level, pos.x(), pos.y(), pos.z(), player.getYRot(), player.getXRot());
    }

    private static void setRespawn(ServerPlayer player, SectorConfigLoader.Position pos) {
        if (pos == null) return;
        ServerLevel level = pos.resolveLevel(player.getServer(), player.serverLevel());
        player.setRespawnPosition(level.dimension(), net.minecraft.core.BlockPos.containing(pos.toVec3()), 0, true, false);
    }

    private static void forEachParticipant(java.util.function.Consumer<ServerPlayer> action) {
        if (battleLevel == null || battleLevel.getServer() == null) return;
        List<ServerPlayer> players = PARTICIPANTS.stream()
                .map(id -> battleLevel.getServer().getPlayerList().getPlayer(id))
                .filter(Objects::nonNull)
                .toList();
        players.forEach(action);
    }

    private static String participantNames(TeamId teamId) {
        if (battleLevel == null || battleLevel.getServer() == null) return "";
        return PARTICIPANTS.stream()
                .map(id -> battleLevel.getServer().getPlayerList().getPlayer(id))
                .filter(Objects::nonNull)
                .filter(sp -> TeamManager.getTeam(sp) == teamId)
                .map(Player::getScoreboardName)
                .collect(Collectors.joining(", "));
    }

    private static void enforceMovableArea() {
        var s = SESSION;
        var sector = s.currentSector();
        if (sector == null) {
            OUTSIDE_AREA_TICKS.clear();
            return;
        }

        for (var sp : s.level.getServer().getPlayerList().getPlayers()) {
            if (sp.serverLevel() != s.level || sp.isCreative() || sp.isSpectator()) continue;
            if (!PARTICIPANTS.contains(sp.getUUID())) continue;

            TeamId t = TeamManager.getTeam(sp);
            byte myTeam = (byte) (t == TeamId.ATTACKERS ? 0 : (t == TeamId.DEFENDERS ? 1 : 2));
            if (myTeam != 0 && myTeam != 1) {
                OUTSIDE_AREA_TICKS.remove(sp.getUUID());
                continue;
            }

            List<Sector.AreaCircle> myAreas =
                    (myTeam == 0) ? sector.attackerAreas : sector.defenderAreas;

            boolean inside = BattlefieldAreaRules.isInsideAreas2D(sp.getX(), sp.getZ(), myAreas);

            if (inside) {
                OUTSIDE_AREA_TICKS.remove(sp.getUUID());
                continue;
            }

            int ticks = OUTSIDE_AREA_TICKS.getOrDefault(sp.getUUID(), 0) + 1;
            if (ticks >= BattlefieldAreaRules.OUTSIDE_AREA_KILL_TICKS) {
                OUTSIDE_AREA_TICKS.remove(sp.getUUID());
                sp.kill();
                continue;
            }
            OUTSIDE_AREA_TICKS.put(sp.getUUID(), ticks);
        }

        OUTSIDE_AREA_TICKS.keySet().removeIf(id -> s.level.getPlayerByUUID(id) == null);
    }

    public static int reloadSectors(ServerLevel level) {
        loadConfig(level);
        return config.sectors.size();
    }
}
