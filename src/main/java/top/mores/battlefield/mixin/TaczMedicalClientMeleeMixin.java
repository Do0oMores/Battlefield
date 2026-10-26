package top.mores.battlefield.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value = com.tacz.guns.client.gameplay.LocalPlayerMelee.class, remap = false)
public abstract class TaczMedicalClientMeleeMixin {
    @Inject(method = "melee", at = @At("HEAD"), cancellable = true)
    private void battlefield$block(CallbackInfo ci) {
        if (top.mores.battlefield.client.MedicalClient.active()) ci.cancel();
    }
}
