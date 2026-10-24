package top.mores.battlefield.game;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.block.TaczAmmoStationBlock;
import top.mores.battlefield.config.TaczAmmoCapConfig;

@Mod.EventBusSubscriber(modid = Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AmmoStationCooldownCleaner {
    private AmmoStationCooldownCleaner() {
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel serverLevel)) return;
        if (serverLevel.getGameTime() % 100L != 0L) return;

        TaczAmmoStationBlock.cleanupExpiredCooldowns(serverLevel);

        // 弹药上限配置改为"标记脏 + 定期合并落盘"后的统一出口。
        // 只在主世界触发一次，避免多维度重复写盘。
        if (serverLevel.dimension() == Level.OVERWORLD) {
            TaczAmmoCapConfig.flushIfDirty();
        }
    }
}
