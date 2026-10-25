package top.mores.battlefield.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import top.mores.battlefield.config.BattlefieldServerConfig;
import top.mores.battlefield.config.TaczAmmoCapConfig;
import top.mores.battlefield.game.BattlefieldGameManager;
import top.mores.battlefield.game.V1StrikeManager;
import top.mores.battlefield.game.combat.CombatRegenService;
import top.mores.battlefield.game.combat.PlayerHealthService;
import top.mores.battlefield.server.BombardmentManager;
import top.mores.battlefield.team.TeamId;
import top.mores.battlefield.team.TeamManager;

import javax.annotation.Nullable;
import java.util.Locale;

public final class BtCommands {
    private BtCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        LiteralCommandNode<CommandSourceStack> root = d.register(Commands.literal("bt")
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
                            TaczAmmoCapConfig.reload();
                            BattlefieldServerConfig.reload();
                            int n = BattlefieldGameManager.reloadSectors(ctx.getSource().getServer());
                            if (n < 0) {
                                ctx.getSource().sendFailure(Component.literal("[Battlefield] sectors.json 解析失败，已保留旧配置（详见服务端日志）"));
                                return 0;
                            }
                            int arenas = BattlefieldGameManager.arenaCount();
                            ctx.getSource().sendSuccess(() -> Component.literal("[Battlefield] 配置热重载完成，对局数=" + arenas + "，总战线数=" + n
                                    + "（进行中的对局沿用开局时的配置）"), true);
                            return 1;
                        }))
                .then(Commands.literal("status")
                        .requires(s -> s.hasPermission(2))
                        .executes(ctx -> {
                            for (String line : BattlefieldGameManager.statusLines(ctx.getSource().getServer())) {
                                ctx.getSource().sendSuccess(() -> Component.literal(line), false);
                                top.mores.battlefield.Battlefield.LOGGER.info("[Battlefield][status] {}", line);
                            }
                            return 1;
                        }))
                .then(Commands.literal("admin")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("start")
                                .then(Commands.argument("match", StringArgumentType.string())
                                        .suggests((c, b) -> {
                                            BattlefieldGameManager.matchIds().forEach(id -> b.suggest(StringArgumentType.escapeIfRequired(id)));
                                            return b.buildFuture();
                                        })
                                        .executes(ctx -> reply(ctx.getSource(),
                                                BattlefieldGameManager.forceStart(StringArgumentType.getString(ctx, "match"))))))
                        .then(Commands.literal("end")
                                .then(Commands.argument("match", StringArgumentType.string())
                                        .suggests((c, b) -> {
                                            BattlefieldGameManager.matchIds().forEach(id -> b.suggest(StringArgumentType.escapeIfRequired(id)));
                                            return b.buildFuture();
                                        })
                                        .executes(ctx -> reply(ctx.getSource(),
                                                BattlefieldGameManager.forceEnd(StringArgumentType.getString(ctx, "match"), TeamId.DEFENDERS)))
                                        .then(Commands.argument("winner", StringArgumentType.word())
                                                .suggests((c, b) -> b.suggest("attackers").suggest("defenders").buildFuture())
                                                .executes(ctx -> reply(ctx.getSource(),
                                                        BattlefieldGameManager.forceEnd(StringArgumentType.getString(ctx, "match"),
                                                                TeamId.fromString(StringArgumentType.getString(ctx, "winner"))))))))
                        .then(Commands.literal("restore")
                                .then(Commands.argument("slot", IntegerArgumentType.integer(0))
                                        .executes(ctx -> reply(ctx.getSource(),
                                                BattlefieldGameManager.restoreSlot(ctx.getSource().getServer(),
                                                        IntegerArgumentType.getInteger(ctx, "slot")))))))
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
                .then(Commands.literal("heal")
                        .requires(s -> s.hasPermission(2))
                        .executes(ctx -> heal(ctx.getSource(), null))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> heal(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("regeninfo")
                        .requires(s -> s.hasPermission(2))
                        .executes(ctx -> regenInfo(ctx.getSource(), null))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> regenInfo(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
        );

        // /battlefield 是 /bt 的别名：redirect 到同一棵树，不复制任何子命令。
        // 两边都写一遍 register 会变成两套互不相干的节点，以后加子命令必然漏一边。
        d.register(Commands.literal("battlefield").redirect(root));
    }

    /**
     * {@code /bt heal [player]}：治疗到满血并重置自然回血上限。
     * <p>
     * 走的是正式入口 {@link PlayerHealthService#healPlayer}，不是直接 {@code setHealth} ——
     * 以后医疗包接入时也调同一个方法，指令和道具的行为不会各走各的。
     */
    private static int heal(CommandSourceStack source, @Nullable ServerPlayer target) throws CommandSyntaxException {
        ServerPlayer player = target != null ? target : source.getPlayerOrException();
        float before = player.getHealth();
        float maxHealth = player.getMaxHealth();
        PlayerHealthService.healPlayer(player, maxHealth, true);

        String name = player.getGameProfile().getName();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "[Battlefield] 已治疗 %s：%.1f → %.1f / %.1f，自然回血上限重置为 %.1f",
                        name, before, player.getHealth(), maxHealth, CombatRegenService.regenCeiling(player))),
                false);
        return 1;
    }

    /** {@code /bt regeninfo [player]}：显示生命值、最大生命值与当前的动态回血上限。 */
    private static int regenInfo(CommandSourceStack source, @Nullable ServerPlayer target) throws CommandSyntaxException {
        ServerPlayer player = target != null ? target : source.getPlayerOrException();
        boolean enabled = BattlefieldServerConfig.get().combat.enableRegenCeiling;
        // 开关关掉时上限已经不参与计算了，再报一个旧数字只会让人以为它还生效
        double ceiling = enabled ? CombatRegenService.regenCeiling(player) : player.getMaxHealth();
        String line = String.format(Locale.ROOT, "[Battlefield] %s 生命 %.1f / %.1f，自然回血上限 %.1f%s",
                player.getGameProfile().getName(), player.getHealth(), player.getMaxHealth(), ceiling,
                enabled ? "" : "（配置里已关闭动态上限，实际可回满）");
        source.sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int reply(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal("[Battlefield] " + message), true);
        return 1;
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
