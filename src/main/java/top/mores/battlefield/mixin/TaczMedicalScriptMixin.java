package top.mores.battlefield.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = com.tacz.guns.item.ModernKineticGunScriptAPI.class, remap = false)
public abstract class TaczMedicalScriptMixin {
    @Shadow private net.minecraft.world.entity.LivingEntity shooter;
    @Inject(method = "shootOnce", at = @At("HEAD"), cancellable = true)
    private void battlefield$scriptShot(boolean consume, CallbackInfo ci) {
        if (top.mores.battlefield.game.medical.MedicalPouchService.locked(shooter)) ci.cancel();
    }
}
