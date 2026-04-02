package top.mores.battlefield.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.client.net.BattlefieldClientPackets;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class S2CBackpackPreview {
    private final int bpSlot;
    private final List<ItemStack> items;

    public S2CBackpackPreview(int bpSlot, List<ItemStack> items) {
        this.bpSlot = bpSlot;
        this.items = items;
    }

    public static void encode(S2CBackpackPreview msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.bpSlot);
        buf.writeInt(msg.items.size());
        for (ItemStack stack : msg.items) {
            buf.writeItem(stack);
        }
    }

    public static S2CBackpackPreview decode(FriendlyByteBuf buf) {
        int bpSlot = buf.readInt();
        int size = buf.readInt();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            items.add(buf.readItem());
        }
        return new S2CBackpackPreview(bpSlot, items);
    }

    public static void handle(S2CBackpackPreview msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> BattlefieldClientPackets.handleBackpackPreview(msg)));
        ctx.setPacketHandled(true);
    }

    public int bpSlot() {
        return bpSlot;
    }

    public List<ItemStack> items() {
        return items;
    }
}
