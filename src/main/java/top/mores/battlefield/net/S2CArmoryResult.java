package top.mores.battlefield.net;

import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.armory.ArmoryView;
import top.mores.battlefield.client.armory.ArmoryScreen;
import top.mores.battlefield.protocol.BridgeProtocol;

import java.util.UUID;
import java.util.function.Supplier;

public record S2CArmoryResult(UUID requestId, String code, ArmoryView view) {
    public static S2CArmoryResult failure(UUID requestId, String code) {
        return new S2CArmoryResult(requestId, code, null);
    }

    public static void encode(S2CArmoryResult msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.requestId);
        buf.writeUtf(msg.code, 40);
        buf.writeBoolean(msg.view != null);
        if (msg.view != null) buf.writeUtf(BridgeProtocol.JSON.toJson(msg.view), BridgeProtocol.MAX_JSON);
    }

    public static S2CArmoryResult decode(FriendlyByteBuf buf) {
        UUID request = buf.readUUID();
        String code = buf.readUtf(40);
        JsonObject view = buf.readBoolean() ? BridgeProtocol.parse(buf.readUtf(BridgeProtocol.MAX_JSON)) : null;
        return new S2CArmoryResult(request, code, BridgeProtocol.JSON.fromJson(view, ArmoryView.class));
    }

    public static void handle(S2CArmoryResult msg, Supplier<NetworkEvent.Context> supplier) {
        var ctx = supplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ArmoryScreen.accept(msg)));
        ctx.setPacketHandled(true);
    }
}
