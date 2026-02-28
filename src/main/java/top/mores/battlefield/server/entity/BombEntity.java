package top.mores.battlefield.server.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import top.mores.battlefield.server.explosion.BombEffects;
import top.mores.battlefield.team.TeamId;

import java.util.UUID;

public class BombEntity extends Entity {
    private double targetX, targetY, targetZ;

    private float damageRadius = 3.0f;
    private float maxDamage = 18.0f;
    private int fuseTicks = 200;

    // 新增：呼叫者信息（用于队友免伤/敌方受伤）
    private UUID ownerId = new UUID(0L, 0L);
    private byte ownerTeam = 2; // 0 atk 1 def 2 spec

    public BombEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = false;
    }

    public BombEntity(EntityType<?> type, Level level,
                      Vec3 spawnPos, Vec3 targetPos,
                      float damageRadius, float maxDamage,
                      UUID ownerId, TeamId ownerTeam) {
        this(type, level);
        this.setPos(spawnPos.x, spawnPos.y, spawnPos.z);
        this.targetX = targetPos.x;
        this.targetY = targetPos.y;
        this.targetZ = targetPos.z;
        this.damageRadius = damageRadius;
        this.maxDamage = maxDamage;

        this.ownerId = ownerId;
        this.ownerTeam = (byte) (ownerTeam == TeamId.ATTACKERS ? 0 : (ownerTeam == TeamId.DEFENDERS ? 1 : 2));
    }

    @Override
    protected void defineSynchedData() {}

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        targetX = tag.getDouble("tx");
        targetY = tag.getDouble("ty");
        targetZ = tag.getDouble("tz");
        damageRadius = tag.getFloat("dr");
        maxDamage = tag.getFloat("md");
        fuseTicks = tag.getInt("fuse");

        if (tag.hasUUID("owner")) ownerId = tag.getUUID("owner");
        ownerTeam = tag.getByte("ot");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putDouble("tx", targetX);
        tag.putDouble("ty", targetY);
        tag.putDouble("tz", targetZ);
        tag.putFloat("dr", damageRadius);
        tag.putFloat("md", maxDamage);
        tag.putInt("fuse", fuseTicks);

        tag.putUUID("owner", ownerId);
        tag.putByte("ot", ownerTeam);
    }

    @Override
    public void tick() {
        super.tick();

        if (this.level().isClientSide) return;

        Vec3 v = this.getDeltaMovement();
        v = v.add(0, -0.08, 0);
        v = v.multiply(0.98, 0.98, 0.98);
        this.setDeltaMovement(v);

        this.move(MoverType.SELF, this.getDeltaMovement());

        if (this.horizontalCollision || this.verticalCollision) {
            detonate();
            return;
        }

        if (this.getY() <= targetY - 2) {
            detonate();
            return;
        }

        if (--fuseTicks <= 0) {
            detonate();
        }
    }

    private void detonate() {
        if (!(this.level() instanceof ServerLevel sl)) {
            this.discard();
            return;
        }

        Vec3 pos = this.position();
        BombEffects.detonateNoKnockback(
                sl,
                pos,
                damageRadius,
                maxDamage,
                ownerId,
                ownerTeam
        );

        this.discard();
    }

    @Override public boolean isPickable() { return false; }
    @Override public boolean hurt(DamageSource source, float amount) { return false; }
}
