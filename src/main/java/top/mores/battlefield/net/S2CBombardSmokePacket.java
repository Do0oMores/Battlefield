package top.mores.battlefield.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.client.net.BattlefieldClientPackets;

import java.util.function.Supplier;

public class S2CBombardSmokePacket {
    public double x, y, z;
    public float r, g, b;
    public int count;
    public float dx, dy, dz;
    public float speed;

    public S2CBombardSmokePacket(double x, double y, double z,
                                 float r, float g, float b,
                                 int count, float dx, float dy, float dz, float speed) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.r = r;
        this.g = g;
        this.b = b;
        this.count = count;
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.speed = speed;
    }

    public static void encode(S2CBombardSmokePacket msg, FriendlyByteBuf buf) {
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.y);
        buf.writeDouble(msg.z);
        buf.writeFloat(msg.r);
        buf.writeFloat(msg.g);
        buf.writeFloat(msg.b);
        buf.writeVarInt(msg.count);
        buf.writeFloat(msg.dx);
        buf.writeFloat(msg.dy);
        buf.writeFloat(msg.dz);
        buf.writeFloat(msg.speed);
    }

    public static S2CBombardSmokePacket decode(FriendlyByteBuf buf) {
        return new S2CBombardSmokePacket(
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readVarInt(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat()
        );
    }

    public static void handle(S2CBombardSmokePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> BattlefieldClientPackets.handleBombardSmoke(msg)));
        ctx.get().setPacketHandled(true);
    }
}
