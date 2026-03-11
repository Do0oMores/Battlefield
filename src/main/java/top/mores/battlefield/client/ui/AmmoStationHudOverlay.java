package top.mores.battlefield.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.block.TaczAmmoStationBlock;
import top.mores.battlefield.client.AmmoStationClientState;

@Mod.EventBusSubscriber(modid = Battlefield.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AmmoStationHudOverlay {
    private AmmoStationHudOverlay() {
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        if (!event.getOverlay().id().equals(VanillaGuiOverlay.HOTBAR.id())) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) {
            return;
        }

        // 有界面打开时不显示
        if (mc.screen != null) {
            return;
        }

        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult blockHitResult)) {
            return;
        }

        if (!(mc.level.getBlockState(blockHitResult.getBlockPos()).getBlock() instanceof TaczAmmoStationBlock)) {
            return;
        }

        AmmoStationClientState.cleanupExpired();

        int remainSeconds = AmmoStationClientState.getRemainingSeconds(blockHitResult.getBlockPos());

        String text;
        int color;
        if (remainSeconds > 0) {
            text = "弹药补给箱 - 冷却中 " + remainSeconds + "秒";
            color = 0xFFFF5555;
        } else {
            text = "弹药补给箱 - 按F键补给";
            color = 0xFF55FF55;
        }

        GuiGraphics g = event.getGuiGraphics();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        int x = (screenWidth - mc.font.width(text)) / 2;
        int y = screenHeight / 2 + 18;

        g.drawString(mc.font, text, x, y, color, true);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        AmmoStationClientState.clear();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            AmmoStationClientState.clear();
        }
    }
}