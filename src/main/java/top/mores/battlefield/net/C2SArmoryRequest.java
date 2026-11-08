package top.mores.battlefield.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.loadout.LoadoutCoordinator;
import top.mores.battlefield.protocol.BridgeProtocol;
import top.mores.battlefield.protocol.EquipmentSlot;
import top.mores.battlefield.protocol.SoldierClass;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public record C2SArmoryRequest(UUID requestId, boolean redeploy, SoldierClass soldierClass, Action action,
                               String weapon, String slot, String item, long revision) {
    public enum Action {
        LOADOUT, CUSTOMIZATION, SELECT_EQUIPMENT, SELECT_ATTACHMENT, REMOVE_ATTACHMENT;
        public boolean mutation() { return this != LOADOUT && this != CUSTOMIZATION; }
    }

    public C2SArmoryRequest {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(soldierClass);
        Objects.requireNonNull(action);
        if (revision < 0 || (!action.mutation() && revision != 0)) throw new IllegalArgumentException("Invalid revision");
        switch (action) {
            case LOADOUT -> { empty(weapon); empty(slot); empty(item); }
            case CUSTOMIZATION -> { BridgeProtocol.id(weapon); empty(slot); empty(item); }
            case SELECT_EQUIPMENT -> { empty(weapon); EquipmentSlot.valueOf(slot); BridgeProtocol.id(item); }
            case SELECT_ATTACHMENT -> { BridgeProtocol.id(weapon); attachmentType(slot); BridgeProtocol.id(item); }
            case REMOVE_ATTACHMENT -> { BridgeProtocol.id(weapon); attachmentType(slot); empty(item); }
        }
    }

    private static void empty(String value) {
        if (!value.isEmpty()) throw new IllegalArgumentException("Unexpected armory field");
    }

    private static void attachmentType(String type) {
        if (!type.matches("[A-Z][A-Z0-9_]{0,31}")) throw new IllegalArgumentException("Invalid attachment type");
    }

    public static void encode(C2SArmoryRequest msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.requestId);
        buf.writeBoolean(msg.redeploy);
        buf.writeUtf(msg.soldierClass.name(), 16);
        buf.writeUtf(msg.action.name(), 24);
        buf.writeUtf(msg.weapon, 128);
        buf.writeUtf(msg.slot, 32);
        buf.writeUtf(msg.item, 128);
        buf.writeVarLong(msg.revision);
    }

    public static C2SArmoryRequest decode(FriendlyByteBuf buf) {
        var request = new C2SArmoryRequest(buf.readUUID(), buf.readBoolean(), SoldierClass.valueOf(buf.readUtf(16)),
                Action.valueOf(buf.readUtf(24)), buf.readUtf(128), buf.readUtf(32), buf.readUtf(128), buf.readVarLong());
        if (buf.isReadable()) throw new IllegalArgumentException("Trailing armory request data");
        return request;
    }

    public static void handle(C2SArmoryRequest msg, Supplier<NetworkEvent.Context> supplier) {
        var ctx = supplier.get();
        ctx.enqueueWork(() -> {
            var player = ctx.getSender();
            if (player == null || !PacketRateLimiter.allow(player, "armory", 2)) return;
            var service = LoadoutCoordinator.get();
            if (service == null) {
                BattlefieldNet.sendToPlayer(player, S2CArmoryResult.failure(msg.requestId, "NOT_READY"));
            } else {
                service.armory().accept(player, msg);
            }
        });
        ctx.setPacketHandled(true);
    }
}
