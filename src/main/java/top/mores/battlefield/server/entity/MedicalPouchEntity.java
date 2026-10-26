package top.mores.battlefield.server.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import top.mores.battlefield.ModEntities;
import top.mores.battlefield.config.BattlefieldServerConfig;
import top.mores.battlefield.game.medical.MedicalPouchService;
import java.util.UUID;

/** Temporary combat effect. No item, gravity, damage, pickup, tickets, or disk persistence. */
public final class MedicalPouchEntity extends Entity {
    private UUID sender, target, senderLife, targetLife;
    private Vec3 clientTarget;
    private int lerpSteps;
    public MedicalPouchEntity(EntityType<? extends MedicalPouchEntity> type, Level level) { super(type, level); }
    public MedicalPouchEntity(ServerLevel level, ServerPlayer from, ServerPlayer to, Vec3 start) {
        this(ModEntities.MEDICAL_POUCH.get(), level);
        sender = from.getUUID(); target = to.getUUID();
        senderLife = MedicalPouchService.state(from).life; targetLife = MedicalPouchService.state(to).life;
        setPos(start);
        setDeltaMovement(to.getBoundingBox().getCenter().subtract(start).normalize().scale(BattlefieldServerConfig.get().medical.speed()));
    }
    public boolean involves(UUID playerId) { return playerId.equals(sender) || playerId.equals(target); }
    @Override protected void defineSynchedData() {}
    @Override protected void readAdditionalSaveData(CompoundTag tag) { discard(); }
    @Override protected void addAdditionalSaveData(CompoundTag tag) {}
    @Override public boolean shouldBeSaved() { return false; }
    @Override public boolean isPickable() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public Packet<ClientGamePacketListener> getAddEntityPacket() { return NetworkHooks.getEntitySpawningPacket(this); }
    @Override public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps, boolean teleport) {
        clientTarget = new Vec3(x,y,z); lerpSteps = Math.max(1, steps);
        setYRot(yaw); setXRot(pitch);
    }
    @Override public void tick() {
        super.tick();
        if (level().isClientSide) {
            if (lerpSteps > 0 && clientTarget != null) {
                setPos(position().lerp(clientTarget, 1.0/lerpSteps)); lerpSteps--;
            }
            return;
        }
        if (!(level() instanceof ServerLevel level) || sender == null || target == null) { discard(); return; }
        var from = level.getServer().getPlayerList().getPlayer(sender);
        var to = level.getServer().getPlayerList().getPlayer(target);
        var cfg = BattlefieldServerConfig.get().medical;
        if (tickCount > cfg.lifetimeTicks() || from == null || to == null || from.level() != level || to.level() != level
                || !MedicalPouchService.receiver(from,to) || !MedicalPouchService.state(from).life.equals(senderLife)
                || !MedicalPouchService.state(to).life.equals(targetLife)) { discard(); return; }
        Vec3 desired = to.getBoundingBox().getCenter().subtract(position()).normalize();
        Vec3 direction = getDeltaMovement().normalize();
        double angle = Math.acos(Math.max(-1, Math.min(1, direction.dot(desired))));
        double maxTurn = Math.toRadians(cfg.turnDegrees());
        // Spherical interpolation bounds angular turn; opposite directions retain forward travel.
        if (angle > 1.0E-5 && angle < Math.PI - 1.0E-5) {
            double t = Math.min(1, maxTurn/angle);
            direction = direction.scale(Math.sin((1-t)*angle)/Math.sin(angle)).add(desired.scale(Math.sin(t*angle)/Math.sin(angle)));
        }
        Vec3 velocity = direction.scale(cfg.speed());
        Vec3 start = position(), end = start.add(velocity);
        // Do not ask clip() to synchronously load an unavailable chunk.
        if (!MedicalPouchService.loadedSegment(level,start,end)) { discard(); return; }
        var wall = level.clip(new ClipContext(start,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,this));
        var receivingBox = to.getBoundingBox().inflate(0.10);
        var hit = receivingBox.contains(start) ? java.util.Optional.of(start) : receivingBox.clip(start,end);
        double wallDistance = wall.getType() == HitResult.Type.MISS ? Double.POSITIVE_INFINITY : start.distanceToSqr(wall.getLocation());
        if (hit.isPresent() && start.distanceToSqr(hit.get()) < wallDistance) {
            MedicalPouchService.supply(from,to,senderLife,targetLife); discard(); return;
        }
        if (wall.getType() != HitResult.Type.MISS) { discard(); return; }
        setDeltaMovement(velocity); setPos(end);
        setYRot((float)Math.toDegrees(Math.atan2(velocity.x,velocity.z)));
        setXRot((float)-Math.toDegrees(Math.atan2(velocity.y,velocity.horizontalDistance())));
    }
}
