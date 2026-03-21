package top.mores.battlefield.client.key;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.net.BattlefieldNet;
import top.mores.battlefield.net.team.C2SOpenSquadPanelPacket;

public final class SquadKeyMappings {

    private static long lastOpenMs = 0L;

    private SquadKeyMappings() {
    }

    public static final String CATEGORY = "key.categories." + Battlefield.MODID;

    /** 默认按键：P，可改成 O / K / M 等 */
    public static final KeyMapping OPEN_SQUAD_PANEL = new KeyMapping(
            "key." + Battlefield.MODID + ".open_squad_panel",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_P,
            CATEGORY
    );

    @Mod.EventBusSubscriber(modid = Battlefield.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        private ModEvents() {
        }

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(OPEN_SQUAD_PANEL);
        }
    }

    @Mod.EventBusSubscriber(modid = Battlefield.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class ForgeEvents {
        private ForgeEvents() {
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            if (mc.screen != null) return;

            while (OPEN_SQUAD_PANEL.consumeClick()) {
                long now = System.currentTimeMillis();
                if (now - lastOpenMs < 250L) return;
                lastOpenMs = now;
                BattlefieldNet.sendToServer(new C2SOpenSquadPanelPacket());
            }
        }
    }
}