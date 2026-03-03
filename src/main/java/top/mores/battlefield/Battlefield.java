package top.mores.battlefield;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import top.mores.battlefield.client.ModSounds;
import top.mores.battlefield.command.BtCommands;
import top.mores.battlefield.game.BattlefieldGameManager;
import top.mores.battlefield.net.BattlefieldNet;
import top.mores.battlefield.server.LicenseVerifier;

@Mod(Battlefield.MODID)
public class Battlefield {

    public static final String MODID = "battlefield";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile RuntimeState runtimeState = RuntimeState.ENABLED;
    private static volatile String disableReason = "";

    public Battlefield() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        // 生命周期：通用初始化
        modEventBus.addListener(this::commonSetup);

        // 注册声音
        ModSounds.SOUND_EVENTS.register(modEventBus);

        // 注册配置
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, Config.SPEC);

        // Forge 事件
        MinecraftForge.EVENT_BUS.register(this);
        ModEntities.register(modEventBus);
        // 你的游戏管理器事件订阅
        MinecraftForge.EVENT_BUS.register(BattlefieldGameManager.class);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(BattlefieldNet::init);

        LOGGER.info("[{}] Common setup complete.", MODID);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        if (!event.getServer().isDedicatedServer()) {
            LOGGER.info("[{}] Non-dedicated server detected, skipping license verification.", MODID);
            return;
        }

        LicenseVerifier.VerificationResult result = LicenseVerifier.verify(Config.licenseFile);
        if (!result.valid()) {
            disable(result.message());
            LOGGER.error("[{}] Battlefield disabled due to invalid license: {}", MODID, result.message());
            return;
        }

        runtimeState = RuntimeState.ENABLED;
        disableReason = "";
        LOGGER.info("[{}] License verified successfully: {}", MODID, result.message());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        BtCommands.register(event.getDispatcher());
    }

    public static boolean isEnabled() {
        return runtimeState == RuntimeState.ENABLED;
    }

    public static String disableReason() {
        return disableReason;
    }

    public static void disable(String reason) {
        runtimeState = RuntimeState.DISABLED;
        disableReason = reason == null ? "unknown" : reason;
    }

    private enum RuntimeState {
        ENABLED,
        DISABLED
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            LOGGER.info("[{}] Client setup complete.", MODID);
            event.enqueueWork(() -> {
                var rl = new ResourceLocation("battlefield", "sounds.json");
                boolean ok;
                try {
                    ok = Minecraft.getInstance()
                            .getResourceManager()
                            .getResource(rl)
                            .isPresent();
                } catch (Exception e) {
                    ok = false;
                }

                System.out.println("[BT] has sounds.json = " + ok);
            });
        }
    }

    @Mod.EventBusSubscriber(modid = Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public class ModBusDebug {
        @SubscribeEvent
        public static void onRegisterSounds(net.minecraftforge.registries.RegisterEvent e) {
            if (e.getRegistryKey().equals(ForgeRegistries.Keys.SOUND_EVENTS)) {
                Battlefield.LOGGER.info("[BT] SoundEvent registry fired.");
            }
        }
    }
}
