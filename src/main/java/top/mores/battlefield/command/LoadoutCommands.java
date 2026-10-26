package top.mores.battlefield.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import top.mores.battlefield.game.BattlefieldGameManager;
import top.mores.battlefield.game.medical.MedicalPouchService;
import top.mores.battlefield.loadout.LoadoutCoordinator;
import top.mores.battlefield.protocol.*;
import java.util.*;

/** Administrative diagnostics; class changes use the same authorized deployment path as players. */
public final class LoadoutCommands {
    private LoadoutCommands() {}
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        var root = Commands.literal("loadout").requires(s -> s.hasPermission(2));
        root.then(Commands.literal("bridge").then(Commands.literal("status").executes(c -> {
            var service = LoadoutCoordinator.get(); c.getSource().sendSuccess(() -> Component.literal(service == null ? "NOT_READY" : service.health()), false); return 1;
        })));
        for (String action : List.of("status", "preview", "validate", "class")) {
            var player = Commands.argument("player", EntityArgument.player());
            if (action.equals("status")) player.executes(c -> inspect(c.getSource(), EntityArgument.getPlayer(c, "player"),
                    SoldierClass.valueOf(MedicalPouchService.state(EntityArgument.getPlayer(c, "player")).role.name()), "loadout"));
            else player.then(Commands.argument("class", StringArgumentType.word()).suggests((c, b) -> {
                for (var type : SoldierClass.values()) b.suggest(type.name()); return b.buildFuture();
            }).executes(c -> {
                try { return inspect(c.getSource(), EntityArgument.getPlayer(c, "player"), SoldierClass.valueOf(StringArgumentType.getString(c, "class").toUpperCase(Locale.ROOT)), action); }
                catch (IllegalArgumentException invalid) { c.getSource().sendFailure(Component.literal("INVALID_INPUT: 兵种必须为 ASSAULT/MEDIC/SUPPORT/RECON")); return 0; }
            }));
            root.then(Commands.literal(action).then(player));
        }
        return root;
    }
    private static int inspect(CommandSourceStack source, ServerPlayer player, SoldierClass type, String action) {
        var service = LoadoutCoordinator.get();
        if (service == null || !BattlefieldGameManager.isEnteredParticipant(player)) { source.sendFailure(Component.literal("NOT_READY: 玩家不在有效对局中")); return 0; }
        if (action.equals("class")) {
            // No arbitrary give operation: alive combatants and invalid spawn points are rejected.
            service.deploy(player, "base", type, UUID.randomUUID()).thenAccept(result -> source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(result.code() + ": " + result.message()), false)));
        } else service.bridge().request(player.getUUID(), type, action.equals("loadout") ? "loadout" : "deployment").thenAccept(json -> source.getServer().execute(() -> {
            String summary = json.length() > 2000 ? json.substring(0, 2000) + "…" : json;
            source.sendSuccess(() -> Component.literal(summary), false);
        }));
        return 1;
    }
}
