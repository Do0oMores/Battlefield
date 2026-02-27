package top.mores.battlefield.net;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.breakthrough.Sector;

import java.util.List;

public final class BattlefieldNet {
    private BattlefieldNet() {}

    private static final String PROTOCOL = "1";
    public static final SimpleChannel CH = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Battlefield.MODID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals
    );

    private static int id = 0;

    public static void init() {
        id = 0;

        CH.registerMessage(
                id++,
                S2CGameStatePacket.class,
                S2CGameStatePacket::encode,
                S2CGameStatePacket::decode,
                S2CGameStatePacket::handle
        );

        CH.registerMessage(
                id++,
                S2CSectorAreaPacket.class,
                S2CSectorAreaPacket::encode,
                S2CSectorAreaPacket::decode,
                S2CSectorAreaPacket::handle
        );
    }

    // ===== 发送工具 =====
    public static void sendToPlayer(ServerPlayer sp, Object pkt) {
        CH.send(PacketDistributor.PLAYER.with(() -> sp), pkt);
    }

    public static void sendToAll(ServerLevel level, Object pkt) {
        CH.send(PacketDistributor.DIMENSION.with(level::dimension), pkt);
    }

    /**
     * 推进/开局时调用：同步当前战线的固定可活动区域
     */
    public static void sendSectorAreas(ServerLevel level, int sectorIndex,
                                       List<Sector.AreaCircle> atk,
                                       List<Sector.AreaCircle> def) {
        List<S2CSectorAreaPacket.AreaCircle> atk2 = atk.stream()
                .map(c -> new S2CSectorAreaPacket.AreaCircle(c.x(), c.z(), c.r()))
                .toList();
        List<S2CSectorAreaPacket.AreaCircle> def2 = def.stream()
                .map(c -> new S2CSectorAreaPacket.AreaCircle(c.x(), c.z(), c.r()))
                .toList();

        sendToAll(level, new S2CSectorAreaPacket(sectorIndex, atk2, def2));
    }
}
