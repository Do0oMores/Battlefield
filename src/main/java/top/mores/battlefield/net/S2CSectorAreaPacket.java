package top.mores.battlefield.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import top.mores.battlefield.client.net.BattlefieldClientPackets;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class S2CSectorAreaPacket {

    public static class AreaRect {
        public double x1, z1;
        public double x2, z2;

        public AreaRect(double x1, double z1, double x2, double z2) {
            this.x1 = x1;
            this.z1 = z1;
            this.x2 = x2;
            this.z2 = z2;
        }
    }

    public int sectorIndex;
    public List<AreaRect> attackerAreas;
    public List<AreaRect> defenderAreas;

    public S2CSectorAreaPacket(int sectorIndex, List<AreaRect> attackerAreas, List<AreaRect> defenderAreas) {
        this.sectorIndex = sectorIndex;
        this.attackerAreas = attackerAreas;
        this.defenderAreas = defenderAreas;
    }

    public static void encode(S2CSectorAreaPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.sectorIndex);

        buf.writeVarInt(msg.attackerAreas.size());
        for (AreaRect c : msg.attackerAreas) {
            buf.writeDouble(c.x1);
            buf.writeDouble(c.z1);
            buf.writeDouble(c.x2);
            buf.writeDouble(c.z2);
        }

        buf.writeVarInt(msg.defenderAreas.size());
        for (AreaRect c : msg.defenderAreas) {
            buf.writeDouble(c.x1);
            buf.writeDouble(c.z1);
            buf.writeDouble(c.x2);
            buf.writeDouble(c.z2);
        }
    }

    public static S2CSectorAreaPacket decode(FriendlyByteBuf buf) {
        int idx = buf.readVarInt();

        int an = buf.readVarInt();
        List<AreaRect> atk = new ArrayList<>(an);
        for (int i = 0; i < an; i++) {
            atk.add(new AreaRect(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble()));
        }

        int dn = buf.readVarInt();
        List<AreaRect> def = new ArrayList<>(dn);
        for (int i = 0; i < dn; i++) {
            def.add(new AreaRect(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble()));
        }

        return new S2CSectorAreaPacket(idx, atk, def);
    }

    public static void handle(S2CSectorAreaPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> BattlefieldClientPackets.handleSectorAreas(msg)));
        ctx.get().setPacketHandled(true);
    }
}
