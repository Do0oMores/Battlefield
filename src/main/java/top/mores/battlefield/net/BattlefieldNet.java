package top.mores.battlefield.net;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.breakthrough.Sector;
import top.mores.battlefield.net.team.C2SOpenSquadPanelPacket;
import top.mores.battlefield.net.team.C2SSquadActionPacket;
import top.mores.battlefield.net.team.S2CSquadPanelPacket;

import java.util.List;

public final class BattlefieldNet {
    private BattlefieldNet() {
    }

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

        CH.registerMessage(
                id++,
                S2CBombardSmokePacket.class,
                S2CBombardSmokePacket::encode,
                S2CBombardSmokePacket::decode,
                S2CBombardSmokePacket::handle
        );

        CH.registerMessage(
                id++,
                S2CScoreToastPacket.class,
                S2CScoreToastPacket::encode,
                S2CScoreToastPacket::decode,
                S2CScoreToastPacket::handle
        );

        CH.registerMessage(
                id++,
                C2SDeployRequest.class,
                C2SDeployRequest::encode,
                C2SDeployRequest::decode,
                C2SDeployRequest::handle
        );

        CH.registerMessage(
                id++,
                C2SAmmoStationInteractPacket.class,
                C2SAmmoStationInteractPacket::encode,
                C2SAmmoStationInteractPacket::decode,
                C2SAmmoStationInteractPacket::handle
        );

        CH.registerMessage(
                id++,
                S2CAmmoStationCooldownPacket.class,
                S2CAmmoStationCooldownPacket::encode,
                S2CAmmoStationCooldownPacket::decode,
                S2CAmmoStationCooldownPacket::handle
        );

        CH.registerMessage(
                id++,
                C2SRequestBackpackPreview.class,
                C2SRequestBackpackPreview::encode,
                C2SRequestBackpackPreview::decode,
                C2SRequestBackpackPreview::handle
        );

        CH.registerMessage(
                id++,
                S2CBackpackPreview.class,
                S2CBackpackPreview::encode,
                S2CBackpackPreview::decode,
                S2CBackpackPreview::handle
        );

        // ===== 小队面板 =====
        CH.registerMessage(
                id++,
                C2SOpenSquadPanelPacket.class,
                C2SOpenSquadPanelPacket::encode,
                C2SOpenSquadPanelPacket::decode,
                C2SOpenSquadPanelPacket::handle
        );

        CH.registerMessage(
                id++,
                C2SSquadActionPacket.class,
                C2SSquadActionPacket::encode,
                C2SSquadActionPacket::decode,
                C2SSquadActionPacket::handle
        );

        CH.registerMessage(
                id++,
                S2CSquadPanelPacket.class,
                S2CSquadPanelPacket::encode,
                S2CSquadPanelPacket::decode,
                S2CSquadPanelPacket::handle
        );
    }

    public static void sendToPlayer(ServerPlayer sp, Object pkt) {
        CH.send(PacketDistributor.PLAYER.with(() -> sp), pkt);
    }

    public static void sendToAllInLevel(ServerLevel level, Object pkt) {
        CH.send(PacketDistributor.DIMENSION.with(level::dimension), pkt);
    }

    public static void sendToServer(Object pkt) {
        CH.sendToServer(pkt);
    }

    public static void sendSectorAreas(ServerLevel level, int sectorIndex,
                                       List<Sector.AreaRect> atk, List<Sector.AreaRect> def) {
        var atk2 = atk.stream().map(c -> new S2CSectorAreaPacket.AreaRect(c.x1(), c.z1(), c.x2(), c.z2())).toList();
        var def2 = def.stream().map(c -> new S2CSectorAreaPacket.AreaRect(c.x1(), c.z1(), c.x2(), c.z2())).toList();
        sendToAllInLevel(level, new S2CSectorAreaPacket(sectorIndex, atk2, def2));
    }
}