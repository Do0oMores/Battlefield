package top.mores.battlefield.game;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.net.BattlefieldNet;
import top.mores.battlefield.net.S2CGameStatePacket;
import top.mores.battlefield.team.TeamId;
import top.mores.battlefield.team.TeamManager;

@Mod.EventBusSubscriber(modid = Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BattlefieldGameManager {
    private BattlefieldGameManager() {}

    public static GameSession SESSION;

    private static int tickCounter = 0;
    private static final SectorManager sectorManager = new SectorManager();

    public static boolean startDemo(ServerLevel level) {
        if (SESSION != null && SESSION.running) return false;

        return true;
    }

    public static void setSession(GameSession session) {
        SESSION = session;
        tickCounter = 0;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (SESSION == null || !SESSION.running) return;

        tickCounter++;

        // 每 10 tick（0.5s）更新占点 + 同步 HUD
        if (tickCounter % SectorManager.CAPTURE_INTERVAL_TICKS != 0) return;

        // 1) 占点逻辑
        sectorManager.tick(SESSION);

        // 2) 发包同步（只发当前扇区点位）
        var s = SESSION;
        var sector = s.currentSector();
        if (sector == null) return;

        var list = new java.util.ArrayList<S2CGameStatePacket.PointInfo>(sector.points.size());
        for (var p : sector.points) {
            list.add(new S2CGameStatePacket.PointInfo(
                    p.id, p.x, p.y, p.z, (float) p.radius,
                    p.getProgress(),
                    p.lastAttackersIn,
                    p.lastDefendersIn
            ));
        }

        for (var sp : s.level.getServer().getPlayerList().getPlayers()) {
            if (sp.serverLevel() != s.level) continue;

            TeamId t = TeamManager.getTeam(sp);
            byte myTeam = (byte) (t == TeamId.ATTACKERS ? 0 : (t == TeamId.DEFENDERS ? 1 : 2));

            var pkt = new S2CGameStatePacket(myTeam, s.attackerTickets, -1, list);
            BattlefieldNet.CH.send(
                    net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> sp),
                    pkt
            );
        }
    }
}
