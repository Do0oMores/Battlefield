package top.mores.battlefield.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.tacz.guns.api.item.IGun;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.block.TaczAmmoStationBlock;
import top.mores.battlefield.net.BattlefieldNet;
import top.mores.battlefield.net.C2SAmmoStationInteractPacket;

@Mod.EventBusSubscriber(modid = Battlefield.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class AmmoStationKeybinds {
    public static final KeyMapping AMMO_STATION_INTERACT = new KeyMapping(
            "key.battlefield.ammo_station_interact",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F,
            "key.categories.battlefield"
    );

    private AmmoStationKeybinds() {}

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(AMMO_STATION_INTERACT);
    }

    @Mod.EventBusSubscriber(modid = Battlefield.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class ForgeHandler {
        private ForgeHandler() {}

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;

            while (AMMO_STATION_INTERACT.consumeClick()) {
                if (IGun.getIGunOrNull(mc.player.getMainHandItem()) == null) {
                    continue;
                }

                HitResult hit = mc.hitResult;
                if (!(hit instanceof BlockHitResult bhr)) {
                    continue;
                }

                BlockPos pos = bhr.getBlockPos();
                if (!(mc.level.getBlockState(pos).getBlock() instanceof TaczAmmoStationBlock)) {
                    continue;
                }

                BattlefieldNet.CH.sendToServer(new C2SAmmoStationInteractPacket(pos));
            }
        }
    }
}