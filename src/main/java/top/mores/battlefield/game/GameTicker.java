package top.mores.battlefield.game;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.mores.battlefield.Battlefield;

@Mod.EventBusSubscriber(modid = Battlefield.MODID)
public class GameTicker {
    private static int tickCounter = 0;
    private static final SectorManager sectorManager = new SectorManager();

    public static GameSession SESSION;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (SESSION == null) return;

        tickCounter++;
        if (tickCounter % SectorManager.CAPTURE_INTERVAL_TICKS == 0) {
            sectorManager.tick(SESSION);
        }
    }
}
