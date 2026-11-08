package top.mores.battlefield.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.server.BackpackBridge;
import top.mores.battlefield.protocol.SoldierClass;
import java.util.*;
import java.util.function.Supplier;

/** Client may select a class and server-computed option, never a UUID, resource ID or quantity. */
public record C2SDeployRequest(String optionId, SoldierClass soldierClass, UUID requestId) {
    public static final int MAX_OPTION_ID = 48;
    public static void encode(C2SDeployRequest msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.optionId, MAX_OPTION_ID); buf.writeUtf(msg.soldierClass.name(), 16); buf.writeUUID(msg.requestId);
    }
    public static C2SDeployRequest decode(FriendlyByteBuf buf) {
        return new C2SDeployRequest(buf.readUtf(MAX_OPTION_ID), SoldierClass.valueOf(buf.readUtf(16)), buf.readUUID());
    }
    public static void handle(C2SDeployRequest msg, Supplier<NetworkEvent.Context> supplier) {
        var ctx = supplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender(); if (sender == null) return;
            var server = sender.server; UUID player = sender.getUUID();
            BackpackBridge.deploy(sender, msg.optionId, msg.soldierClass, msg.requestId).whenComplete((result, error) -> server.execute(() -> {
                ServerPlayer current = server.getPlayerList().getPlayer(player); if (current == null) return;
                boolean ok = error == null && result.ok();
                String reason = error == null ? result.code() + ": " + result.message() : "DEPLOYMENT_FAILED";
                BattlefieldNet.sendToPlayer(current, new S2CDeployResultPacket(msg.requestId, ok, reason));
            }));
        }); ctx.setPacketHandled(true);
    }
}