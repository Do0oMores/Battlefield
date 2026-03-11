package top.mores.battlefield.server.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import top.mores.battlefield.team.TeamId;
import top.mores.battlefield.team.TeamManager;

public class V1MissileEntity extends Entity {
    private Vec3 start = Vec3.ZERO;
    private Vec3 control = Vec3.ZERO;
    private Vec3 target = Vec3.ZERO;

    private int flightTicks = 80;
    private int elapsedTicks = 0;
    private boolean exploded = false;
    private String ownerTeamName = TeamId.SPECTATOR.name();

    public V1MissileEntity(EntityType<? extends V1MissileEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    public void setup(TeamId ownerTeam, Vec3 start, Vec3 control, Vec3 target, int flightTicks) {
        this.ownerTeamName = ownerTeam.name();
        this.start = start;
        this.control = control;
        this.target = target;
        this.flightTicks = Math.max(1, flightTicks);
        this.elapsedTicks = 0;
        this.exploded = false;

        this.setPos(start.x, start.y, start.z);
        this.refreshRot();
    }

    public TeamId getOwnerTeam() {
        try {
            return TeamId.valueOf(ownerTeamName);
        } catch (Exception e) {
            return TeamId.SPECTATOR;
        }
    }

    @Override
    protected void defineSynchedData() {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.start = readVec(tag, "Start");
        this.control = readVec(tag, "Control");
        this.target = readVec(tag, "Target");
        this.flightTicks = Math.max(1, tag.getInt("FlightTicks"));
        this.elapsedTicks = Math.max(0, tag.getInt("ElapsedTicks"));
        this.exploded = tag.getBoolean("Exploded");
        this.ownerTeamName = tag.getString("OwnerTeam");

        if (tag.contains("PosX")) {
            this.setPos(tag.getDouble("PosX"), tag.getDouble("PosY"), tag.getDouble("PosZ"));
        }
        this.setYRot(tag.getFloat("YRot"));
        this.setXRot(tag.getFloat("XRot"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        writeVec(tag, "Start", start);
        writeVec(tag, "Control", control);
        writeVec(tag, "Target", target);
        tag.putInt("FlightTicks", flightTicks);
        tag.putInt("ElapsedTicks", elapsedTicks);
        tag.putBoolean("Exploded", exploded);
        tag.putString("OwnerTeam", ownerTeamName);

        tag.putDouble("PosX", getX());
        tag.putDouble("PosY", getY());
        tag.putDouble("PosZ", getZ());
        tag.putFloat("YRot", getYRot());
        tag.putFloat("XRot", getXRot());
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // 不能被攻击
        return false;
    }

    @Override
    public boolean isPickable() {
        // 不允许被命中判定当作可交互/可攻击实体
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        // 高空也能较远看到
        return distance < 512.0 * 512.0;
    }

    @Override
    public void tick() {
        super.tick();

        if (level().isClientSide) {
            return;
        }
        if (exploded) {
            discard();
            return;
        }

        Vec3 oldPos = position();

        elapsedTicks++;
        double t = Mth.clamp(elapsedTicks / (double) flightTicks, 0.0, 1.0);
        Vec3 newPos = bezier(start, control, target, t);

        setPos(newPos.x, newPos.y, newPos.z);
        updateRotation(oldPos, newPos);

        spawnTrail((ServerLevel) level(), oldPos, newPos);
        playFlightSound();

        if (checkBlockCollision(oldPos, newPos)) {
            return;
        }

        if (t >= 1.0) {
            explode(target);
        }
    }

    private boolean checkBlockCollision(Vec3 oldPos, Vec3 newPos) {
        HitResult hit = level().clip(new ClipContext(
                oldPos,
                newPos,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                this
        ));

        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockHitResult bhr = (BlockHitResult) hit;
            explode(bhr.getLocation());
            return true;
        }
        return false;
    }

    private void spawnTrail(ServerLevel level, Vec3 oldPos, Vec3 newPos) {
        for (int i = 0; i < 3; i++) {
            double a = i / 2.0;
            Vec3 p = oldPos.lerp(newPos, a);

            level.sendParticles(ParticleTypes.SMOKE,
                    p.x, p.y, p.z,
                    2, 0.03, 0.03, 0.03, 0.0);

            level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                    p.x, p.y, p.z,
                    1, 0.01, 0.01, 0.01, 0.002);
        }
    }

    private void playFlightSound() {
        // 先用占位音效，后面你可以换成自己的 V1 飞行声
        if (elapsedTicks % 8 == 0) {
            level().playSound(null, getX(), getY(), getZ(),
                    SoundEvents.ELYTRA_FLYING,
                    SoundSource.HOSTILE,
                    2.5F,
                    0.6F);
        }
    }

    private void explode(Vec3 pos) {
        if (exploded) return;
        exploded = true;

        ServerLevel level = (ServerLevel) level();

        // 视觉与声音
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER,
                pos.x, pos.y, pos.z,
                1, 0, 0, 0, 0);

        level.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE,
                pos.x, pos.y + 0.2, pos.z,
                25, 1.6, 0.8, 1.6, 0.02);

        level.sendParticles(ParticleTypes.FLAME,
                pos.x, pos.y + 0.2, pos.z,
                20, 1.0, 0.5, 1.0, 0.02);

        level.playSound(null, pos.x, pos.y, pos.z,
                SoundEvents.GENERIC_EXPLODE,
                SoundSource.HOSTILE,
                4.0F,
                0.8F);

        damageEnemies(level, pos);

        discard();
    }

    private void damageEnemies(ServerLevel level, Vec3 pos) {
        final double killRadius = 20.0;
        final double maxRadius = 30.0;
        final double maxRadiusSqr = maxRadius * maxRadius;

        for (ServerPlayer sp : level.getPlayers(player ->
                player.isAlive() &&
                        !player.isSpectator() &&
                        player.position().distanceToSqr(pos) <= maxRadiusSqr)) {

            if (!isEnemy(sp)) continue;

            double dist = sp.position().distanceTo(pos);
            if (dist > maxRadius) continue;

            if (dist <= killRadius) {
                // 20 格内直接死亡
                sp.invulnerableTime = 0;
                sp.hurt(level.damageSources().genericKill(), Float.MAX_VALUE);
                continue;
            }

            // 20~30 格二次衰减
            double x = (dist - killRadius) / (maxRadius - killRadius); // 0..1
            float damage = (float) (40.0 * (1.0 - x) * (1.0 - x));
            if (damage > 0.5F) {
                sp.invulnerableTime = 0;
                sp.hurt(level.damageSources().explosion(this, null), damage);
            }
        }
    }

    private boolean isEnemy(ServerPlayer player) {
        TeamId team = TeamManager.getTeam(player);
        return team != null && team != TeamId.SPECTATOR && team != getOwnerTeam();
    }

    private void updateRotation(Vec3 oldPos, Vec3 newPos) {
        Vec3 motion = newPos.subtract(oldPos);
        if (motion.lengthSqr() < 1.0e-8) return;

        double dx = motion.x;
        double dy = motion.y;
        double dz = motion.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Mth.atan2(dx, dz) * (180.0F / Math.PI));
        float pitch = (float) (-(Mth.atan2(dy, horizontal) * (180.0F / Math.PI)));

        setYRot(yaw);
        setXRot(pitch);
        yRotO = yaw;
        xRotO = pitch;
    }

    private void refreshRot() {
        Vec3 p0 = bezier(start, control, target, 0.0);
        Vec3 p1 = bezier(start, control, target, Math.min(0.02, 1.0 / Math.max(1, flightTicks)));
        updateRotation(p0, p1);
    }

    private static Vec3 bezier(Vec3 p0, Vec3 p1, Vec3 p2, double t) {
        double u = 1.0 - t;
        return p0.scale(u * u)
                .add(p1.scale(2.0 * u * t))
                .add(p2.scale(t * t));
    }

    private static void writeVec(CompoundTag root, String key, Vec3 vec) {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("x", vec.x);
        tag.putDouble("y", vec.y);
        tag.putDouble("z", vec.z);
        root.put(key, tag);
    }

    private static Vec3 readVec(CompoundTag root, String key) {
        CompoundTag tag = root.getCompound(key);
        return new Vec3(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"));
    }
}
