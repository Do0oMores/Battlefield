package top.mores.battlefield.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.loading.FMLPaths;
import top.mores.battlefield.breakthrough.CapturePoint;
import top.mores.battlefield.breakthrough.Sector;
import top.mores.battlefield.config.SectorConfigLoader;
import top.mores.battlefield.game.BattlefieldGameManager;
import top.mores.battlefield.game.GameSession;
import top.mores.battlefield.team.SquadManager;
import top.mores.battlefield.team.TeamId;
import top.mores.battlefield.team.TeamManager;

import java.util.List;

public final class BtCommands {
    private BtCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("bt")
                .requires(s -> s.hasPermission(2))

                // /bt start  开始一个“就地生成A/B点”的测试局
                .then(Commands.literal("start")
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            ServerLevel level = p.serverLevel();

                            if (BattlefieldGameManager.SESSION != null && BattlefieldGameManager.SESSION.running) {
                                ctx.getSource().sendFailure(Component.literal("已有正在运行的测试局。先 /bt stop（暂未实现）或重启服务器。"));
                                return 0;
                            }

                            List<Sector> sectors = SectorConfigLoader.load(FMLPaths.CONFIGDIR.get().resolve("battlefield"));
                            if (sectors.isEmpty()) {
                                Vec3 base = p.position();
                                CapturePoint A = new CapturePoint("A", base.x + 8, base.y, base.z, 8);
                                CapturePoint B = new CapturePoint("B", base.x - 8, base.y, base.z, 8);
                                List<Sector.AreaCircle> attackerAreas = List.of(
                                        new Sector.AreaCircle(base.x + 24, base.z, 64)
                                );
                                List<Sector.AreaCircle> defenderAreas = List.of(
                                        new Sector.AreaCircle(base.x - 24, base.z, 64)
                                );
                                sectors = List.of(new Sector("S1", List.of(A, B), attackerAreas, defenderAreas));
                                ctx.getSource().sendFailure(Component.literal("配置文件无可用扇区，已回退到临时A/B测试点。"));
                            }

                            GameSession session = new GameSession(level, sectors);
                            BattlefieldGameManager.setSession(session);
                            SquadManager.resetForMatchStart(level);

                            Sector first = session.currentSector();
                            if (first != null) {
                                top.mores.battlefield.net.BattlefieldNet.sendSectorAreas(
                                        level,
                                        session.currentSectorIndex,
                                        first.attackerAreas,
                                        first.defenderAreas
                                );
                            }

                            level.getServer().getPlayerList().broadcastSystemMessage(
                                    Component.literal("[BT] 测试局已开始：已加载扇区=" + sectors.size() + "。"),
                                    false
                            );

                            return 1;
                        })
                )

                .then(Commands.literal("stop")
                        .executes(ctx -> {
                            boolean stopped = BattlefieldGameManager.stopSession();
                            if (!stopped) {
                                ctx.getSource().sendFailure(Component.literal("当前没有正在运行的对局。"));
                                return 0;
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal("已停止当前对局。"), true);
                            return 1;
                        })
                )

                // /bt debug  查看当前进度
                .then(Commands.literal("debug")
                        .executes(ctx -> {
                            if (BattlefieldGameManager.SESSION == null || !BattlefieldGameManager.SESSION.running) {
                                ctx.getSource().sendFailure(Component.literal("没有正在运行的测试局。先 /bt start"));
                                return 0;
                            }
                            GameSession s = BattlefieldGameManager.SESSION;
                            Sector cur = s.currentSector();
                            if (cur == null) {
                                ctx.getSource().sendSuccess(() -> Component.literal("当前扇区=null（可能已结束）"), false);
                                return 1;
                            }

                            StringBuilder sb = new StringBuilder();
                            sb.append("World=").append(s.level.dimension().location())
                                    .append(" Sector=").append(cur.id)
                                    .append(" Index=").append(s.currentSectorIndex)
                                    .append(" Tickets=").append(s.attackerTickets)
                                    .append("\n");

                            for (CapturePoint p : cur.points) {
                                sb.append("Point ").append(p.id)
                                        .append(" progress=").append(p.getProgress())
                                        .append(" in(A/D)=").append(p.lastAttackersIn).append("/").append(p.lastDefendersIn)
                                        .append(" pos=(").append((int) p.x).append(",").append((int) p.y).append(",").append((int) p.z).append(")")
                                        .append("\n");
                            }

                            ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
                            return 1;
                        })
                )

                // /bt join 自动均衡
                .then(Commands.literal("join")
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            TeamId team = TeamManager.autoAssign(p);
                            int squadId = SquadManager.getSquad(p);
                            ctx.getSource().sendSuccess(() -> Component.literal("已加入队伍: " + team.name() + "，小队=" + squadId), false);
                            return 1;
                        })
                )

                .then(Commands.literal("squad")
                        .then(Commands.literal("create")
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    TeamId t = TeamManager.getTeam(p);
                                    if (t == TeamId.SPECTATOR) {
                                        ctx.getSource().sendFailure(Component.literal("观战玩家不能创建小队。先加入队伍。"));
                                        return 0;
                                    }

                                    boolean ok = SquadManager.createSquad(p);
                                    if (!ok) {
                                        ctx.getSource().sendFailure(Component.literal("创建失败：已达到小队上限(16)。"));
                                        return 0;
                                    }

                                    int squadId = SquadManager.getSquad(p);
                                    ctx.getSource().sendSuccess(() -> Component.literal("已创建并加入小队: " + squadId), false);
                                    return 1;
                                })
                        )
                        .then(Commands.literal("join")
                                .then(Commands.argument("id", IntegerArgumentType.integer(1, SquadManager.TEAM_SQUAD_CAP))
                                        .executes(ctx -> {
                                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                                            int squadId = IntegerArgumentType.getInteger(ctx, "id");

                                            boolean ok = SquadManager.joinSquad(p, squadId);
                                            if (!ok) {
                                                ctx.getSource().sendFailure(Component.literal("加入失败：小队不存在、已满员(4)或你当前为观战。"));
                                                return 0;
                                            }

                                            ctx.getSource().sendSuccess(() -> Component.literal("已加入小队: " + squadId), false);
                                            return 1;
                                        })
                                )
                        )
                        .then(Commands.literal("leave")
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    int old = SquadManager.getSquad(p);
                                    SquadManager.leaveSquad(p);
                                    ctx.getSource().sendSuccess(() -> Component.literal("已退出小队: " + old), false);
                                    return 1;
                                })
                        )
                        .then(Commands.literal("info")
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    TeamId t = TeamManager.getTeam(p);
                                    int sid = SquadManager.getSquad(p);
                                    ctx.getSource().sendSuccess(() -> Component.literal("当前队伍=" + t.name() + "，小队=" + sid), false);
                                    return 1;
                                })
                        )
                )

                // /bt team attackers|defenders|spectator
                .then(Commands.literal("team")
                        .then(Commands.argument("team", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    TeamId t = TeamId.fromString(StringArgumentType.getString(ctx, "team"));

                                    if (t == TeamId.ATTACKERS && TeamManager.countTeam(p.serverLevel(), TeamId.ATTACKERS) >= TeamManager.TEAM_CAP) {
                                        ctx.getSource().sendFailure(Component.literal("攻击方已满员(16)。"));
                                        return 0;
                                    }
                                    if (t == TeamId.DEFENDERS && TeamManager.countTeam(p.serverLevel(), TeamId.DEFENDERS) >= TeamManager.TEAM_CAP) {
                                        ctx.getSource().sendFailure(Component.literal("防守方已满员(16)。"));
                                        return 0;
                                    }

                                    TeamManager.setTeam(p, t);
                                    ctx.getSource().sendSuccess(() -> Component.literal("已切换队伍: " + t.name()), false);
                                    return 1;
                                })
                        )
                )

                .then(Commands.literal("spectate")
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            TeamManager.setTeam(p, TeamId.SPECTATOR);
                            ctx.getSource().sendSuccess(() -> Component.literal("已进入观战(SPECTATOR)"), false);
                            return 1;
                        })
                )

                .then(Commands.literal("leave")
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            TeamManager.setTeam(p, TeamId.SPECTATOR);
                            ctx.getSource().sendSuccess(() -> Component.literal("已离开队伍，进入观战(SPECTATOR)"), false);
                            return 1;
                        })
                )
        );
    }
}
