package top.mores.battlefield.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = com.tacz.guns.client.gameplay.LocalPlayerShoot.class, remap = false)
public abstract class TaczMedicalClientShootMixin {
    @Inject(method="shoot", at=@At("HEAD"), cancellable=true)
    private void battlefield$shoot(CallbackInfoReturnable<com.tacz.guns.api.entity.ShootResult> ci) {
        if (top.mores.battlefield.client.MedicalClient.blockShoot())
            ci.setReturnValue(com.tacz.guns.api.entity.ShootResult.FORGE_EVENT_CANCEL);
    }
    @Inject(method="lambda$doShoot$2", at=@At("HEAD"), cancellable=true)
    private void battlefield$burst(CallbackInfo ci) {
        if (!top.mores.battlefield.client.MedicalClient.blockShoot()) return;
        // Both actual TACZ versions cancel this same ScheduledFuture thread when a burst ends.
        // Terminate it; a paused burst counter could otherwise resume after medical completion.
        if (Thread.currentThread() instanceof java.util.concurrent.Future<?> future) {
            future.cancel(false); ci.cancel();
        } else throw new java.util.concurrent.CancellationException("Medical interrupted scheduled TACZ burst");
    }
    @Inject(method="lambda$doShoot$1", at=@At("HEAD"), cancellable=true)
    private void battlefield$queued(CallbackInfo ci) {
        if (top.mores.battlefield.client.MedicalClient.blockShoot()) ci.cancel();
    }
    // Charging exists in 1.1.8-hotfix; the inspected 1.1.7 version has no charging API/state.
    @Inject(method="chargeShoot(Z)Z", at=@At("HEAD"), cancellable=true, require=0)
    private void battlefield$charge(CallbackInfoReturnable<Boolean> ci) {
        if (top.mores.battlefield.client.MedicalClient.blockShoot()) {
            top.mores.battlefield.client.MedicalClient.clearCharge(); ci.setReturnValue(false);
        }
    }
}
