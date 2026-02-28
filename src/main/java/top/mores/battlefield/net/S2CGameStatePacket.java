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
        public int progress;
        public byte ownerTeam;
        public int attackersIn;
        public int defendersIn;

        public PointInfo(String id, double x, double y, double z, float radius, int progress, byte ownerTeam, int attackersIn, int defendersIn) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = radius;
            this.progress = progress;
            this.ownerTeam = ownerTeam;
            this.attackersIn = attackersIn;
            this.defendersIn = defendersIn;
        }
    }

    public boolean inBattle;
    public byte myTeam;

    public int attackerTickets;
    public int defenderTickets;
    public int remainingTimeTicks;
    public int myScore;
    public int myLastBonus;
    public List<String> squadPlayerIds;
    public List<Integer> squadPlayerScores;
    public int squadTotalScore;
    public List<PointInfo> points;
    public int phase;
    public String overlayTitle;
    public String overlaySub;
    public int overlayTicks;

    public S2CGameStatePacket(boolean inBattle, byte myTeam, int attackerTickets, int defenderTickets,
                              int remainingTimeTicks, int myScore, int myLastBonus,
                              List<String> squadPlayerIds, List<Integer> squadPlayerScores, int squadTotalScore,
                              List<PointInfo> points, int phase, String overlayTitle, String overlaySub, int overlayTicks) {
        this.inBattle = inBattle;
        this.myTeam = myTeam;
        this.attackerTickets = attackerTickets;
        this.defenderTickets = defenderTickets;
        this.remainingTimeTicks = remainingTimeTicks;
        this.myScore = myScore;
        this.myLastBonus = myLastBonus;
        this.squadPlayerIds = squadPlayerIds;
        this.squadPlayerScores = squadPlayerScores;
        this.squadTotalScore = squadTotalScore;
        this.points = points;
        this.phase = phase;
        this.overlayTitle = overlayTitle;
        this.overlaySub = overlaySub;
        this.overlayTicks = overlayTicks;
    }

    public static void encode(S2CGameStatePacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.inBattle);
        buf.writeByte(msg.myTeam);
        buf.writeInt(msg.attackerTickets);
        buf.writeInt(msg.defenderTickets);
        buf.writeInt(msg.remainingTimeTicks);
        buf.writeInt(msg.myScore);
        buf.writeInt(msg.myLastBonus);

        buf.writeVarInt(msg.squadPlayerIds.size());
        for (int i = 0; i < msg.squadPlayerIds.size(); i++) {
            buf.writeUtf(msg.squadPlayerIds.get(i));
            buf.writeInt(msg.squadPlayerScores.get(i));
        }
        buf.writeInt(msg.squadTotalScore);

        buf.writeVarInt(msg.points.size());
        for (PointInfo p : msg.points) {
            buf.writeUtf(p.id);
            buf.writeDouble(p.x);
            buf.writeDouble(p.y);
            buf.writeDouble(p.z);
            buf.writeFloat(p.radius);
            buf.writeInt(p.progress);
            buf.writeByte(p.ownerTeam);
            buf.writeVarInt(p.attackersIn);
            buf.writeVarInt(p.defendersIn);
        }

        buf.writeVarInt(msg.phase);
        buf.writeUtf(msg.overlayTitle == null ? "" : msg.overlayTitle);
        buf.writeUtf(msg.overlaySub == null ? "" : msg.overlaySub, 512);
        buf.writeVarInt(msg.overlayTicks);
    }

    public static S2CGameStatePacket decode(FriendlyByteBuf buf) {
        boolean inBattle = buf.readBoolean();
        byte myTeam = buf.readByte();
        int atk = buf.readInt();
        int def = buf.readInt();
        int remainingTimeTicks = buf.readInt();
        int myScore = buf.readInt();
        int myLastBonus = buf.readInt();

        int squadN = buf.readVarInt();
        List<String> squadIds = new ArrayList<>(squadN);
        List<Integer> squadScores = new ArrayList<>(squadN);
        for (int i = 0; i < squadN; i++) {
            squadIds.add(buf.readUtf(32));
            squadScores.add(buf.readInt());
        }
        int squadTotal = buf.readInt();

        int n = buf.readVarInt();
        List<PointInfo> pts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String id = buf.readUtf(8);
            double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
            float r = buf.readFloat();
            int prog = buf.readInt();
            byte ownerTeam = buf.readByte();
            int aIn = buf.readVarInt();
            int dIn = buf.readVarInt();
            pts.add(new PointInfo(id, x, y, z, r, prog, ownerTeam, aIn, dIn));
        }
        int phase = buf.readVarInt();
        String overlayTitle = buf.readUtf();
        String overlaySub = buf.readUtf(512);
        int overlayTicks = buf.readVarInt();

        return new S2CGameStatePacket(inBattle, myTeam, atk, def,
                remainingTimeTicks, myScore, myLastBonus,
                squadIds, squadScores, squadTotal, pts,
                phase, overlayTitle, overlaySub, overlayTicks);
    }

    public static void handle(S2CGameStatePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (Minecraft.getInstance().player != null) {
                ClientGameState.update(msg.inBattle, msg.myTeam, msg.attackerTickets, msg.defenderTickets,
                        msg.remainingTimeTicks, msg.myScore, msg.myLastBonus,
                        msg.squadPlayerIds, msg.squadPlayerScores, msg.squadTotalScore,
                        msg.points, msg.phase, msg.overlayTitle, msg.overlaySub, msg.overlayTicks);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
