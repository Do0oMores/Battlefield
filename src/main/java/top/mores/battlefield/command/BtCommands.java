package top.mores.battlefield.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import top.mores.battlefield.game.BattlefieldGameManager;
import top.mores.battlefield.team.TeamId;

public final class BtCommands {
    private BtCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("bt")
                .then(Commands.literal("join")
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            TeamId team = BattlefieldGameManager.joinBattle(p);
                            if (team == TeamId.SPECTATOR) {
                                return 0;
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal("已加入对局，队伍=" + team.name()), false);
                            return 1;
                        }))
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
                            ctx.getSource().sendSuccess(() -> Component.literal("[BT] sectors.json 热重载完成，战线数=" + n), true);
                            return 1;
                        }))
        );
    }
}
