package top.mores.battlefield.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.game.BattlefieldGameManager;
import top.mores.battlefield.loadout.LoadoutCoordinator;
import top.mores.battlefield.protocol.SoldierClass;
import java.util.*;
import java.util.function.Supplier;

public record C2SRequestBackpackPreview(SoldierClass soldierClass) {
    public static void encode(C2SRequestBackpackPreview msg, FriendlyByteBuf buf) { buf.writeUtf(msg.soldierClass.name(), 16); }
    public static C2SRequestBackpackPreview decode(FriendlyByteBuf buf) { return new C2SRequestBackpackPreview(SoldierClass.valueOf(buf.readUtf(16))); }
    public static void handle(C2SRequestBackpackPreview msg, Supplier<NetworkEvent.Context> supplier) {
        var ctx = supplier.get(); ctx.enqueueWork(() -> {
            var player = ctx.getSender(); if (player == null || BattlefieldGameManager.getPlayerMatchId(player.getUUID()) == null) return;
            if (!PacketRateLimiter.allow(player, "loadout_preview", 4)) return;
            var service = LoadoutCoordinator.get(); if (service == null) return;
            var server = player.server;
            service.preview(player, msg.soldierClass).whenComplete((items, error) -> server.execute(() -> {
                if (server.getPlayerList().getPlayer(player.getUUID()) != player) return;
                BattlefieldNet.sendToPlayer(player, new S2CBackpackPreview(msg.soldierClass, error == null ? items : List.of(), error == null ? "OK" : "配装未验证或桥接不可用"));
            }));
        }); ctx.setPacketHandled(true);
    }
}