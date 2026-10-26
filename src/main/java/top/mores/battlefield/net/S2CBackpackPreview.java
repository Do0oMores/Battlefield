package top.mores.battlefield.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.client.net.BattlefieldClientPackets;
import top.mores.battlefield.protocol.SoldierClass;
import java.util.*;
import java.util.function.Supplier;

public record S2CBackpackPreview(SoldierClass soldierClass, List<ItemStack> items, String status) {
    public S2CBackpackPreview { if (items.size() > 6) throw new IllegalArgumentException("Preview limit"); items = items.stream().map(ItemStack::copy).toList(); }
    public static void encode(S2CBackpackPreview msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.soldierClass.name(), 16); buf.writeVarInt(msg.items.size());
        msg.items.forEach(buf::writeItem); buf.writeUtf(msg.status, 96);
    }
    public static S2CBackpackPreview decode(FriendlyByteBuf buf) {
        SoldierClass type = SoldierClass.valueOf(buf.readUtf(16)); int count = buf.readVarInt();
        if (count < 0 || count > 6) throw new IllegalArgumentException("Preview count");
        List<ItemStack> items = new ArrayList<>(); for (int i = 0; i < count; i++) items.add(buf.readItem());
        return new S2CBackpackPreview(type, items, buf.readUtf(96));
    }
    public static void handle(S2CBackpackPreview msg, Supplier<NetworkEvent.Context> supplier) {
        var ctx = supplier.get(); ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> BattlefieldClientPackets.handleBackpackPreview(msg))); ctx.setPacketHandled(true);
    }
}