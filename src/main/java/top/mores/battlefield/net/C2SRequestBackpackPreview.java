package top.mores.battlefield.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import top.mores.battlefield.game.BattlefieldGameManager;
import top.mores.battlefield.server.BackpackBridge;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.item.ItemStack;

public class C2SRequestBackpackPreview {
    private final int bpSlot;

    public C2SRequestBackpackPreview(int bpSlot) {
        this.bpSlot = bpSlot;
    }

    public static void encode(C2SRequestBackpackPreview msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.bpSlot);
    }

    public static C2SRequestBackpackPreview decode(FriendlyByteBuf buf) {
        return new C2SRequestBackpackPreview(buf.readInt());
    }

    public static void handle(C2SRequestBackpackPreview msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;
            // 槽位与部署一致只允许 1..9；每次请求都会在主线程反射调用背包插件，必须限频
            if (msg.bpSlot < 1 || msg.bpSlot > 9) return;
            if (BattlefieldGameManager.getPlayerMatchId(player.getUUID()) == null) return;
            if (!PacketRateLimiter.allow(player, "backpack_preview", 4)) return;

            List<ItemStack> preview = BackpackBridge.getBackpackPreview(player, msg.bpSlot);
            BattlefieldNet.CH.send(PacketDistributor.PLAYER.with(() -> player),
                    new S2CBackpackPreview(msg.bpSlot, preview));
        });
        ctx.setPacketHandled(true);
    }
}
