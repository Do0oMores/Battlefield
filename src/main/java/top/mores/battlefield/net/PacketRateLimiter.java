package top.mores.battlefield.net;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.mores.battlefield.Battlefield;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * C2S 包的简单限频：同一玩家、同一类包，两次之间至少间隔 N tick，超频的直接丢弃。
 * 只在主线程（{@code enqueueWork} 内）调用。
 */
@Mod.EventBusSubscriber(modid = Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PacketRateLimiter {
    private PacketRateLimiter() {
    }

    private static final Map<UUID, Map<String, Integer>> LAST = new HashMap<>();

    public static boolean allow(ServerPlayer player, String kind, int minIntervalTicks) {
        int now = player.server.getTickCount();
        Map<String, Integer> perPlayer = LAST.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
        Integer last = perPlayer.get(kind);
        if (last != null && now - last < minIntervalTicks) return false;
        perPlayer.put(kind, now);
        return true;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST.remove(event.getEntity().getUUID());
    }
}
