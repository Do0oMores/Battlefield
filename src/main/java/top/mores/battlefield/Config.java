package top.mores.battlefield;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(modid = Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.ConfigValue<String> SERVER_KEY = BUILDER
            .comment("Battlefield 服务端密钥。未配置正确密钥时，服务端将拒绝启动。")
            .define("serverKey", "PLEASE_SET_SERVER_KEY");

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static String serverKey;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        serverKey = SERVER_KEY.get();
    }
}
