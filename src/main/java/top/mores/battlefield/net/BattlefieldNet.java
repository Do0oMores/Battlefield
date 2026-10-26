package top.mores.battlefield.net;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.breakthrough.Sector;
import top.mores.battlefield.net.ammo.C2SAmmoStationInteractPacket;
import top.mores.battlefield.net.ammo.S2CAmmoStationCooldownPacket;
import top.mores.battlefield.net.team.C2SOpenSquadPanelPacket;
import top.mores.battlefield.net.team.C2SSquadActionPacket;
import top.mores.battlefield.net.team.S2CSquadPanelPacket;

import java.util.List;

public final class BattlefieldNet {
    private BattlefieldNet() {
    }

    // 2：S2CBombardSmokePacket 由「逐玩家下发 r/g/b」改为「广播 ownerTeam」，字节布局已变
    // 3：与 PR #43-#46 合并后消息表变了（新增部署界面、小队面板 3 个包，消息 id 整体后移），
    //    而那批提交没有升协议号；旧客户端（无论来自哪条分支）一律在握手阶段拒绝，不再静默错位解包
    // 4：部署界面重做——S2COpenDeployScreenPacket 换成带数据的 S2CDeployDataPacket，
    //    C2SDeployRequest 的 spawnIndex(int) 换成 optionId(utf)，并新增请求/结果两个包，消息表整体变化
    // 5：部署界面加战区底图（S2CDeployMapPacket / C2SRequestDeployMap），S2CDeployDataPacket 多一个 mapId 字段
    // 6: 部署请求增加生效兵种；医疗意图与状态包仅追加到旧消息表末尾。
    private static final String PROTOCOL = "6"; // medical state + effective deployment role
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
                S2CDeployDataPacket.class,
                S2CDeployDataPacket::encode,
                S2CDeployDataPacket::decode,
                S2CDeployDataPacket::handle
        );

        CH.registerMessage(
                id++,
                C2SRequestDeployData.class,
                C2SRequestDeployData::encode,
                C2SRequestDeployData::decode,
                C2SRequestDeployData::handle
        );

        CH.registerMessage(
                id++,
                S2CDeployResultPacket.class,
                S2CDeployResultPacket::encode,
                S2CDeployResultPacket::decode,
                S2CDeployResultPacket::handle
        );

        // ===== 部署界面底图 =====
        CH.registerMessage(
                id++,
                S2CDeployMapPacket.class,
                S2CDeployMapPacket::encode,
                S2CDeployMapPacket::decode,
                S2CDeployMapPacket::handle
        );

        CH.registerMessage(
                id++,
                C2SRequestDeployMap.class,
                C2SRequestDeployMap::encode,
                C2SRequestDeployMap::decode,
                C2SRequestDeployMap::handle
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
        CH.registerMessage(id++, C2SMedicalRequest.class, C2SMedicalRequest::encode, C2SMedicalRequest::decode,
                C2SMedicalRequest::handle, java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        CH.registerMessage(id++, S2CMedicalState.class, S2CMedicalState::encode, S2CMedicalState::decode,
                S2CMedicalState::handle, java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
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

    /**
     * 发给本局参与者。原先按维度广播：多个对局共用同一个战场维度（槽位）时，
     * 一局推进战线会覆盖另一局所有人的区域框。
     */
    public static void sendSectorAreas(List<ServerPlayer> players, int sectorIndex,
                                       List<Sector.AreaRect> atk, List<Sector.AreaRect> def) {
        if (players.isEmpty()) return;
        S2CSectorAreaPacket pkt = buildSectorAreaPacket(sectorIndex, atk, def);
        for (ServerPlayer sp : players) {
            sendToPlayer(sp, pkt);
        }
    }

    /**
     * 只发给单个玩家。用于对局中途加入：区域包原本只在开局与战线推进时整维度广播，
     * 中途加入者永远收不到，客户端会既没有区域框也没有出界警告，但服务端照样计时击杀。
     */
    public static void sendSectorAreasTo(ServerPlayer sp, int sectorIndex,
                                         List<Sector.AreaRect> atk, List<Sector.AreaRect> def) {
        sendToPlayer(sp, buildSectorAreaPacket(sectorIndex, atk, def));
    }

    private static S2CSectorAreaPacket buildSectorAreaPacket(int sectorIndex,
                                                             List<Sector.AreaRect> atk, List<Sector.AreaRect> def) {
        var atk2 = atk.stream().map(c -> new S2CSectorAreaPacket.AreaRect(c.x1(), c.z1(), c.x2(), c.z2())).toList();
        var def2 = def.stream().map(c -> new S2CSectorAreaPacket.AreaRect(c.x1(), c.z1(), c.x2(), c.z2())).toList();
        return new S2CSectorAreaPacket(sectorIndex, atk2, def2);
    }
}
