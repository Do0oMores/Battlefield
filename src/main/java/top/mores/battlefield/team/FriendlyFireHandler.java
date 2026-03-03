package top.mores.battlefield.team;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.game.CombatRules;

@Mod.EventBusSubscriber(modid = Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FriendlyFireHandler {
    private FriendlyFireHandler() {
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!Battlefield.isEnabled()) return;
        if (!(event.getEntity() instanceof ServerPlayer victim)) return;
        ServerPlayer attacker = CombatRules.resolveAttacker(event.getSource());
        if (attacker == null) return;

        if (CombatRules.isFriendlyFire(attacker, victim)) {
            event.setCanceled(true);
        }
    }
}
