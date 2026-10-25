package top.mores.battlefield.net;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.client.ui.BattlefieldHudOverlay;
import top.mores.battlefield.client.ui.HealthHudOverlay;

@Mod.EventBusSubscriber(modid = Battlefield.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class BattlefieldClient {

    @SubscribeEvent
    public static void onRegisterOverlays(RegisterGuiOverlaysEvent e) {
        // 血条单独注册：它在底部左侧，与顶部战区 HUD 没有重叠，
        // 但绘制顺序仍要放在战区 HUD 之后，避免被阶段遮罩（开局倒计时那层半透明黑）盖住
        e.registerAboveAll("battlefield_health_hud", HealthHudOverlay::render);
        e.registerAboveAll("battlefield_hud", BattlefieldHudOverlay::render);
    }
}
