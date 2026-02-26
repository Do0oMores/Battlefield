package top.mores.battlefield.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import top.mores.battlefield.team.TeamId;
import top.mores.battlefield.team.TeamManager;

public final class BtCommands {
    private BtCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("bt")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("join")
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            TeamId team = TeamManager.autoAssign(p);
                            ctx.getSource().sendSuccess(() -> Component.literal("已加入队伍: " + team.name()), false);
                            return 1;
                        })
                )
                .then(Commands.literal("team")
                        .then(Commands.argument("team", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    String s = StringArgumentType.getString(ctx, "team");
                                    TeamId t = TeamId.fromString(s);

                                    // 限制手动加入攻击/防守的人数上限
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
