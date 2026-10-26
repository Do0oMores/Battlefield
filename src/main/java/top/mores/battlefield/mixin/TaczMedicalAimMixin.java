package top.mores.battlefield.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = com.tacz.guns.entity.shooter.LivingEntityAim.class, remap = false)
public abstract class TaczMedicalAimMixin {
    @Shadow @Final private net.minecraft.world.entity.LivingEntity shooter;
    @Inject(method = "aim", at = @At("HEAD"), cancellable = true)
    private void battlefield$aim(boolean aim, CallbackInfo ci) {
        if (aim && top.mores.battlefield.game.medical.MedicalPouchService.locked(shooter)) ci.cancel();
    }
}
