package top.mores.battlefield.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.net.BattlefieldNet;
import top.mores.battlefield.net.C2SRequestDeployData;

@Mod.EventBusSubscriber(modid = Battlefield.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class BFClientKeyHandler {
    private BFClientKeyHandler() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.ClientTickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (BFKeyBinds.OPEN_RESPAWN_UI.consumeClick()) {
            if (mc.screen != null) return;
            // 不直接开界面：先问服务端能不能部署。不允许时服务端只回一条原因，不会弹空界面。
            BattlefieldNet.sendToServer(new C2SRequestDeployData());
        }
    }
}
