package top.mores.battlefield.server.entity;

import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.NotNull;
import top.mores.battlefield.team.TeamId;

import java.util.UUID;

public class RespawnBeaconEntity extends Entity {
    private static final float MAX_HEALTH = 40.0f;

    private UUID ownerUuid;
    private UUID squadUuid;
    private String matchId = "";
    private TeamId teamId = TeamId.SPECTATOR;
    private float health = MAX_HEALTH;

    public RespawnBeaconEntity(EntityType<? extends RespawnBeaconEntity> type, Level level) {
        super(type, level);
        this.noPhysics = false;
    }

    @Override
    protected void defineSynchedData() {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.hasUUID("Owner")) this.ownerUuid = tag.getUUID("Owner");
        if (tag.hasUUID("Squad")) this.squadUuid = tag.getUUID("Squad");
        this.matchId = tag.getString("MatchId");
        if (tag.contains("Team")) {
            this.teamId = TeamId.valueOf(tag.getString("Team"));
        }
        if (tag.contains("Health")) {
            this.health = tag.getFloat("Health");
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (ownerUuid != null) tag.putUUID("Owner", ownerUuid);
        if (squadUuid != null) tag.putUUID("Squad", squadUuid);
        tag.putString("MatchId", matchId == null ? "" : matchId);
        tag.putString("Team", teamId.name());
        tag.putFloat("Health", health);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    @Override
    public void tick() {
        super.tick();

        if (level().isClientSide) return;

        // 固定在原地
        setDeltaMovement(Vec3.ZERO);

        // 朝向锁定到 0~360
        setYRot(Mth.wrapDegrees(getYRot()));
        setYHeadRot(getYRot());

        // 脚下支撑没了就销毁
        if (!level().getBlockState(blockPosition().below()).isSolidRender(level(), blockPosition().below())) {
            destroyBeacon();
        }
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean canBeCollidedWith() {
        return true;
    }

    @Override
    public boolean canBeHitByProjectile() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean hurt(@NotNull DamageSource source, float amount) {
        if (level().isClientSide) return false;

        Entity direct = source.getDirectEntity();
        if (!(direct instanceof EntityKineticBullet)) {
            return false;
        }

        this.health -= Math.max(1.0f, amount);
        if (this.health <= 0.0f) {
            destroyBeacon();
        }
        return true;
    }

    public void destroyBeacon() {
        if (!(level() instanceof ServerLevel)) {
            discard();
            return;
        }
        RespawnBeaconManager.unregister(this);
        discard();
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public UUID getSquadUuid() {
        return squadUuid;
    }

    public void setSquadUuid(UUID squadUuid) {
        this.squadUuid = squadUuid;
    }

    public String getMatchId() {
        return matchId;
    }

    public void setMatchId(String matchId) {
        this.matchId = matchId;
    }

    public TeamId getTeamId() {
        return teamId;
    }

    public void setTeamId(TeamId teamId) {
        this.teamId = teamId;
    }

    public float getHealthValue() {
        return health;
    }

    public void setHealthValue(float health) {
        this.health = health;
    }
}