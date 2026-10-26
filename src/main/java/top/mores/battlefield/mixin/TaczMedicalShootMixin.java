package top.mores.battlefield.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = com.tacz.guns.entity.shooter.LivingEntityShoot.class, remap = false)
public abstract class TaczMedicalShootMixin {
    @Shadow @Final private net.minecraft.world.entity.LivingEntity shooter;
    // Every shoot overload in both inspected versions returns ShootResult. Callback-only injection
    // covers the normal, charged and shared entries without relying on their parameter layouts.
    @Inject(method="shoot*", at=@At("HEAD"), cancellable=true)
    private void battlefield$shoot(CallbackInfoReturnable<com.tacz.guns.api.entity.ShootResult> ci) {
        if (top.mores.battlefield.game.medical.MedicalPouchService.locked(shooter))
            ci.setReturnValue(com.tacz.guns.api.entity.ShootResult.FORGE_EVENT_CANCEL);
    }
}
