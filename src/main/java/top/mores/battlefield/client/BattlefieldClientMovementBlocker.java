package top.mores.battlefield.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.mores.battlefield.Battlefield;

@Mod.EventBusSubscriber(modid = Battlefield.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BattlefieldClientMovementBlocker {
    private BattlefieldClientMovementBlocker() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (!ClientGameState.inBattle || ClientGameState.overlayTicks <= 0) return;
        if (ClientGameState.phase != 1 && ClientGameState.phase != 3) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        player.setSprinting(false);
        player.setDeltaMovement(0, 0, 0);

        if (player.input != null) {
            player.input.up = false;
            player.input.down = false;
            player.input.left = false;
            player.input.right = false;
            player.input.jumping = false;
            player.input.shiftKeyDown = false;
            player.input.forwardImpulse = 0;
            player.input.leftImpulse = 0;
        }

        if (mc.options != null) {
            mc.options.keyUp.setDown(false);
            mc.options.keyDown.setDown(false);
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keySprint.setDown(false);
            mc.options.keyShift.setDown(false);
        }
    }
}
