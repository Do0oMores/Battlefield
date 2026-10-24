package top.mores.battlefield.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.client.net.BattlefieldClientPackets;

import java.util.function.Supplier;

/**
 * 区域轰炸的烟幕标记。
 * <p>
 * 颜色不再由服务端逐玩家计算后各发一份，而是随包只带一个"发射方阵营"，
 * 由客户端拿自己的阵营比对得出红/蓝。这样整片烟幕可以一次广播给范围内所有人。
 */
public class S2CBombardSmokePacket {

    /** 与 ClientGameState.myTeam 对齐：0=攻方 1=守方 2=观战 */
    public static final byte TEAM_ATTACKERS = 0;
    public static final byte TEAM_DEFENDERS = 1;
    public static final byte TEAM_SPECTATOR = 2;

    // 友方：蓝；敌方：红（保持原有配色）
    public static final float[] FRIENDLY_COLOR = {0.18f, 0.45f, 1.00f};
    public static final float[] ENEMY_COLOR = {1.00f, 0.10f, 0.10f};

    public double x, y, z;
    public byte ownerTeam;
    public int count;
    public float dx, dy, dz;
    public float speed;

    public S2CBombardSmokePacket(double x, double y, double z,
                                 byte ownerTeam,
                                 int count, float dx, float dy, float dz, float speed) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.ownerTeam = ownerTeam;
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
        buf.writeByte(msg.ownerTeam);
        buf.writeVarInt(msg.count);
        buf.writeFloat(msg.dx);
        buf.writeFloat(msg.dy);
        buf.writeFloat(msg.dz);
        buf.writeFloat(msg.speed);
    }

    public static S2CBombardSmokePacket decode(FriendlyByteBuf buf) {
        return new S2CBombardSmokePacket(
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readByte(),
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
