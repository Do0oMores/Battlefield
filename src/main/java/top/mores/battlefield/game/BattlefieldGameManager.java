package top.mores.battlefield.game;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;
import top.mores.battlefield.net.BattlefieldNet;
import top.mores.battlefield.net.S2CGameStatePacket;
import top.mores.battlefield.team.TeamId;
import top.mores.battlefield.team.TeamManager;

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
        if (tickCounter % SectorManager.CAPTURE_INTERVAL_TICKS == 0) {
            sectorManager.tick(SESSION);
        }
        var s = SESSION;
        var sector = s.currentSector();
        if (sector != null) {
            var list = new java.util.ArrayList<S2CGameStatePacket.PointInfo>();
            for (var p : sector.points) {
                list.add(new S2CGameStatePacket.PointInfo(
                        p.id, p.x, p.y, p.z, (float)p.radius,
                        p.getProgress(),
                        p.lastAttackersIn,
                        p.lastDefendersIn
                ));
            }

            for (var sp : s.level.getServer().getPlayerList().getPlayers()) {
                if (sp.serverLevel() != s.level) continue;

                TeamId t = TeamManager.getTeam(sp);
                byte myTeam =
                        (byte) (t == TeamId.ATTACKERS ? 0 : (t == TeamId.DEFENDERS ? 1 : 2));

                var pkt = new S2CGameStatePacket(myTeam, s.attackerTickets, -1, list);
                BattlefieldNet.CH.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> sp), pkt);
            }
        }
    }
}
