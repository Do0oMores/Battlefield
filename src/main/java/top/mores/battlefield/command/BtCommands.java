package top.mores.battlefield.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import top.mores.battlefield.config.TaczAmmoCapConfig;
import top.mores.battlefield.game.BattlefieldGameManager;
import top.mores.battlefield.game.V1StrikeManager;
import top.mores.battlefield.server.BombardmentManager;
import top.mores.battlefield.team.TeamId;
import top.mores.battlefield.team.TeamManager;

public final class BtCommands {
    private BtCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("bt")
                .then(Commands.literal("join")
                        .executes(ctx -> join(ctx.getSource().getPlayerOrException(), null, ctx.getSource()))
                        .then(Commands.argument("arena", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    BattlefieldGameManager.arenaIds().forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> join(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "arena"), ctx.getSource()))))
                .then(Commands.literal("leave")
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            BattlefieldGameManager.leaveBattle(p);
                            ctx.getSource().sendSuccess(() -> Component.literal("已离开对局并返回主城。"), false);
                            return 1;
                        }))
                .then(Commands.literal("reload")
                        .requires(s -> s.hasPermission(2))
                        .executes(ctx -> {
                            ServerLevel level = ctx.getSource().getLevel();
                            int n = BattlefieldGameManager.reloadSectors(level);
                            int arenas = BattlefieldGameManager.arenaCount();
                            TaczAmmoCapConfig.reload();
                            ctx.getSource().sendSuccess(() -> Component.literal("[Battlefield] 配置热重载完成，对局数=" + arenas + "，总战线数=" + n), true);
                            return 1;
                        }))
                .then(Commands.literal("testBombard")
                        .requires(s -> s.hasPermission(2))
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            BombardmentManager.start(player, player.position());
                            ctx.getSource().sendSuccess(() -> Component.literal("已在当前位置调用区域轰炸。"), true);
                            return 1;
                        }))
                .then(Commands.literal("testV1")
                        .requires(s -> s.hasPermission(2))
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            TeamId ownerTeam = TeamManager.getTeam(player);
                            if (ownerTeam == TeamId.SPECTATOR) {
                                ctx.getSource().sendFailure(Component.literal("你当前不在进攻/防守阵营，无法测试 V1 导弹。"));
                                return 0;
                            }

                            if (V1StrikeManager.launch(player.serverLevel(), ownerTeam, player.position(), player.position()) == null) {
                                ctx.getSource().sendFailure(Component.literal("V1 导弹实体创建失败。"));
                                return 0;
                            }

                            ctx.getSource().sendSuccess(() -> Component.literal("已在当前位置调用 V1 导弹。"), true);
                            return 1;
                        }))
        );
    }

    private static int join(ServerPlayer player, String arenaId, CommandSourceStack source) {
        TeamId team = arenaId == null ? BattlefieldGameManager.joinBattle(player) : BattlefieldGameManager.joinBattle(player, arenaId);
        if (team == TeamId.SPECTATOR) {
            return 0;
        }
        String arenaText = arenaId == null ? "默认" : arenaId;
        source.sendSuccess(() -> Component.literal("已加入对局 " + arenaText + "，队伍=" + team.name()), false);
        return 1;
    }
}
