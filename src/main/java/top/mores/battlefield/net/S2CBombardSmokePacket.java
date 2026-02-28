package top.mores.battlefield.net;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class S2CBombardSmokePacket {
    public double x, y, z;
    public float r, g, b;      // 0..1
    public int count;
    public float dx, dy, dz;   // 扩散
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
        ctx.get().enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;

            // 用彩色粉尘 + 少量烟
            var dust = new net.minecraft.core.particles.DustParticleOptions(
                    new org.joml.Vector3f(msg.r, msg.g, msg.b), 1.5f
            );

            for (int i = 0; i < msg.count; i++) {
                double px = msg.x + (mc.level.random.nextDouble() * 2 - 1) * msg.dx;
                double py = msg.y + (mc.level.random.nextDouble() * 2 - 1) * msg.dy;
                double pz = msg.z + (mc.level.random.nextDouble() * 2 - 1) * msg.dz;

                mc.level.addParticle(dust, px, py, pz, 0, msg.speed, 0);
                if ((i & 1) == 0) {
                    mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.SMOKE,
                            px, py, pz, 0, 0.01, 0);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
