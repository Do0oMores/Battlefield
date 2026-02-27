package top.mores.battlefield.net;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.client.ClientGameState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class S2CSectorAreaPacket {

    public static class AreaCircle {
        public double x, z;
        public double r;

        public AreaCircle(double x, double z, double r) {
            this.x = x;
            this.z = z;
            this.r = r;
        }
    }

    public int sectorIndex;
    public List<AreaCircle> attackerAreas;
    public List<AreaCircle> defenderAreas;

    public S2CSectorAreaPacket(int sectorIndex, List<AreaCircle> attackerAreas, List<AreaCircle> defenderAreas) {
        this.sectorIndex = sectorIndex;
        this.attackerAreas = attackerAreas;
        this.defenderAreas = defenderAreas;
    }

    public static void encode(S2CSectorAreaPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.sectorIndex);

        buf.writeVarInt(msg.attackerAreas.size());
        for (AreaCircle c : msg.attackerAreas) {
            buf.writeDouble(c.x);
            buf.writeDouble(c.z);
            buf.writeDouble(c.r);
        }

        buf.writeVarInt(msg.defenderAreas.size());
        for (AreaCircle c : msg.defenderAreas) {
            buf.writeDouble(c.x);
            buf.writeDouble(c.z);
            buf.writeDouble(c.r);
        }
    }

    public static S2CSectorAreaPacket decode(FriendlyByteBuf buf) {
        int idx = buf.readVarInt();

        int an = buf.readVarInt();
        List<AreaCircle> atk = new ArrayList<>(an);
        for (int i = 0; i < an; i++) {
            atk.add(new AreaCircle(buf.readDouble(), buf.readDouble(), buf.readDouble()));
        }

        int dn = buf.readVarInt();
        List<AreaCircle> def = new ArrayList<>(dn);
        for (int i = 0; i < dn; i++) {
            def.add(new AreaCircle(buf.readDouble(), buf.readDouble(), buf.readDouble()));
        }

        return new S2CSectorAreaPacket(idx, atk, def);
    }

    public static void handle(S2CSectorAreaPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (Minecraft.getInstance().player != null) {
                ClientGameState.updateAreas(msg.sectorIndex, msg.attackerAreas, msg.defenderAreas);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}