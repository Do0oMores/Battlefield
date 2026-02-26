package top.mores.battlefield.game;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

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
    }
}
