package top.mores.battlefield;

import com.mojang.logging.LogUtils;
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
import org.slf4j.Logger;
import top.mores.battlefield.client.ModSounds;
import top.mores.battlefield.command.BtCommands;
import top.mores.battlefield.game.BattlefieldGameManager;
import top.mores.battlefield.net.BattlefieldNet;

@Mod(Battlefield.MODID)
public class Battlefield {

    public static final String MODID = "battlefield";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Battlefield() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        // 生命周期：通用初始化
        modEventBus.addListener(this::commonSetup);

        // 注册声音
        ModSounds.SOUND_EVENTS.register(modEventBus);

        // 注册配置
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Forge 事件
        MinecraftForge.EVENT_BUS.register(this);

        // 你的游戏管理器事件订阅
        MinecraftForge.EVENT_BUS.register(BattlefieldGameManager.class);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // 网络包注册建议 enqueueWork（你已经这么做了）
        event.enqueueWork(BattlefieldNet::init);

        LOGGER.info("[{}] Common setup complete.", MODID);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[{}] Server starting.", MODID);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        BtCommands.register(event.getDispatcher());
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            LOGGER.info("[{}] Client setup complete.", MODID);
        }
    }
}
