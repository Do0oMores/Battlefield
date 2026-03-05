package top.mores.battlefield.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.server.BackpackBridge;

import java.util.function.Supplier;

public final class C2SDeployRequest {

    private final int spawnIndex; // 测试用：0=基地
    private final int bpSlot;     // 1..N

    public C2SDeployRequest(int spawnIndex, int bpSlot) {
        this.spawnIndex = spawnIndex;
        this.bpSlot = bpSlot;
    }

    public static void encode(C2SDeployRequest msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.spawnIndex);
        buf.writeVarInt(msg.bpSlot);
    }

    public static C2SDeployRequest decode(FriendlyByteBuf buf) {
        int spawnIndex = buf.readVarInt();
        int bpSlot = buf.readVarInt();
        return new C2SDeployRequest(spawnIndex, bpSlot);
    }

    public static void handle(C2SDeployRequest msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sp = ctx.getSender();
            if (sp == null) return;

            if (msg.bpSlot < 1 || msg.bpSlot > 9) return;

            var level = sp.serverLevel();
            var pos = level.getSharedSpawnPos(); // 世界出生点
            sp.teleportTo(level, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, sp.getYRot(), sp.getXRot());

            BackpackBridge.applyBackpackPreset(sp, msg.bpSlot);
        });
        ctx.setPacketHandled(true);
    }
}
