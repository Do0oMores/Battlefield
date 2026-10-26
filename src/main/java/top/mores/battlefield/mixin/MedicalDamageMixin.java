package top.mores.battlefield.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(net.minecraft.world.entity.player.Player.class)
public abstract class MedicalDamageMixin {
    @Unique private float battlefield$healthBefore;
    @Unique private float battlefield$absorptionBefore;
    @Inject(method = "actuallyHurt", at = @At("HEAD"))
    private void battlefield$before(net.minecraft.world.damagesource.DamageSource source, float amount, CallbackInfo ci) {
        var player = (net.minecraft.world.entity.player.Player)(Object)this;
        battlefield$healthBefore = player.getHealth(); battlefield$absorptionBefore = player.getAbsorptionAmount();
    }
    @Inject(method = "actuallyHurt", at = @At("RETURN"))
    private void battlefield$after(net.minecraft.world.damagesource.DamageSource source, float amount, CallbackInfo ci) {
        var player = (net.minecraft.world.entity.player.Player)(Object)this;
        if (player instanceof net.minecraft.server.level.ServerPlayer sp
                && (player.getHealth() < battlefield$healthBefore || player.getAbsorptionAmount() < battlefield$absorptionBefore))
            top.mores.battlefield.game.medical.MedicalPouchService.cancel(sp);
    }
}
