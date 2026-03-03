package top.mores.battlefield;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(modid = Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.ConfigValue<String> LICENSE_FILE = BUILDER
            .comment("Battlefield 离线授权文件路径（相对服务端根目录或绝对路径）。Dedicated Server 启动时会进行 Ed25519 验签。")
            .define("licenseFile", "config/battlefield-license.json");

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static String licenseFile;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        licenseFile = LICENSE_FILE.get();
    }
}
