package top.mores.battlefield.net;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.client.ClientGameState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class S2CGameStatePacket {
    public static class PointInfo {
        public String id;
        public double x, y, z;
        public float radius;
        public int progress; // [-100..100]
        public PointInfo(String id, double x, double y, double z, float radius, int progress){
            this.id=id; this.x=x; this.y=y; this.z=z; this.radius=radius; this.progress=progress;
        }
    }

    public int attackerTickets;
    public int defenderTickets;
    public List<PointInfo> points;

    public S2CGameStatePacket(int attackerTickets, int defenderTickets, List<PointInfo> points) {
        this.attackerTickets = attackerTickets;
        this.defenderTickets = defenderTickets;
        this.points = points;
    }

    public static void encode(S2CGameStatePacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.attackerTickets);
        buf.writeInt(msg.defenderTickets);
        buf.writeVarInt(msg.points.size());
        for (PointInfo p : msg.points) {
            buf.writeUtf(p.id);
            buf.writeDouble(p.x);
            buf.writeDouble(p.y);
            buf.writeDouble(p.z);
            buf.writeFloat(p.radius);
            buf.writeInt(p.progress);
        }
    }

    public static S2CGameStatePacket decode(FriendlyByteBuf buf) {
        int atk = buf.readInt();
        int def = buf.readInt();
        int n = buf.readVarInt();
        List<PointInfo> pts = new ArrayList<>(n);
        for (int i=0;i<n;i++){
            String id = buf.readUtf(8);
            double x=buf.readDouble(), y=buf.readDouble(), z=buf.readDouble();
            float r=buf.readFloat();
            int prog=buf.readInt();
            pts.add(new PointInfo(id,x,y,z,r,prog));
        }
        return new S2CGameStatePacket(atk, def, pts);
    }

    public static void handle(S2CGameStatePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 仅客户端：更新 HUD 数据
            if (Minecraft.getInstance().player != null) {
                ClientGameState.update(msg.attackerTickets, msg.defenderTickets, msg.points);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
