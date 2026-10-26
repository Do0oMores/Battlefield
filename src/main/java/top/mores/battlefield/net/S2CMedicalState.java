package top.mores.battlefield.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import java.util.UUID;
import java.util.function.Supplier;

public record S2CMedicalState(UUID life, long revision, boolean deployed, boolean medic, int reserve,
                              long actionId, int elapsed, int duration) {
    public static void encode(S2CMedicalState p, FriendlyByteBuf b) {
        b.writeUUID(p.life); b.writeLong(p.revision); b.writeBoolean(p.deployed); b.writeBoolean(p.medic);
        b.writeByte(p.reserve); b.writeLong(p.actionId); b.writeVarInt(p.elapsed); b.writeVarInt(p.duration);
    }
    public static S2CMedicalState decode(FriendlyByteBuf b) {
        return new S2CMedicalState(b.readUUID(), b.readLong(), b.readBoolean(), b.readBoolean(), b.readUnsignedByte(),
                b.readLong(), b.readVarInt(), b.readVarInt());
    }
    public static void handle(S2CMedicalState p, Supplier<NetworkEvent.Context> supplier) {
        var ctx = supplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> top.mores.battlefield.client.MedicalClient.accept(p)));
        ctx.setPacketHandled(true);
    }
}
