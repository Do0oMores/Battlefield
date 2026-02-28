package top.mores.battlefield.server.explosion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GlassBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import top.mores.battlefield.game.ScoreManager;
import top.mores.battlefield.team.TeamId;
import top.mores.battlefield.team.TeamManager;

import java.util.List;
import java.util.UUID;

public final class BombEffects {

    private BombEffects() {}

    /**
     * 引爆：不使用 level.explode（避免击退/方块破坏）
     * 只做：粒子 + 声音 + 自定义伤害 + 仅破坏树叶/玻璃
     */
    public static void detonateNoKnockback(ServerLevel level, Vec3 center,
                                           float damageRadius, float maxDamage,
                                           UUID ownerId, byte ownerTeam) {
        // 视觉/声音（这部分保持，你想的话也能改为“逐人”）
        level.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y, center.z, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.SMOKE, center.x, center.y, center.z, 60, 1.2, 0.4, 1.2, 0.02);
        level.playSound(null, center.x, center.y, center.z,
                SoundEvents.GENERIC_EXPLODE,
                SoundSource.HOSTILE,
                2.2f, 0.9f + level.random.nextFloat() * 0.2f
        );

        breakOnlyLeavesAndGlass(level, BlockPos.containing(center), 2);

        // ✅ 伤害过滤队友
        applyDistanceDamage(level, center, damageRadius, maxDamage, ownerId, ownerTeam);
    }

    private static void applyDistanceDamage(ServerLevel level, Vec3 center,
                                            float radius, float maxDamage,
                                            UUID ownerId, byte ownerTeam) {

        double r = radius;
        AABB aabb = new AABB(
                center.x - r, center.y - r, center.z - r,
                center.x + r, center.y + r, center.z + r
        );

        // ✅ 只对玩家做（不然会炸到怪/队友动物等）
        List<ServerPlayer> targets = level.getEntitiesOfClass(ServerPlayer.class, aabb, e -> e.isAlive());

        DamageSource src = level.damageSources().explosion(null);

        for (ServerPlayer p : targets) {
            // ✅ 队友免伤（但呼叫者本人照样受伤）
            byte team = (byte) (TeamManager.getTeam(p) == TeamId.ATTACKERS ? 0 :
                    TeamManager.getTeam(p) == TeamId.DEFENDERS ? 1 : 2);

            boolean isOwner = p.getUUID().equals(ownerId);
            boolean isTeammate = (team == ownerTeam);

            if (!isOwner && isTeammate) continue;

            Vec3 eye = p.getEyePosition();
            double dist = eye.distanceTo(center);
            if (dist > radius) continue;

            if (isFullyBlocked(level, center, eye)) continue;

            float t = (float) (1.0 - (dist / radius));
            t = Mth.clamp(t, 0f, 1f);

            float dmg = maxDamage * t;
            if (dmg <= 0.1f) continue;

            p.hurt(src, dmg);
            ScoreManager.onBombDamage(level, ownerId, p.getUUID(), dmg);
            if (!p.isAlive()) {
                ScoreManager.onBombKill(level, ownerId, p.getUUID());
            }

            Vec3 v = p.getDeltaMovement();
            p.setDeltaMovement(0.0, v.y, 0.0);
            p.hurtMarked = true;
        }
    }
    /**
     * 线段采样遮挡：
     * - 从爆点 -> 玩家眼睛
     * - 采样遇到非空气且“可阻挡”的方块就视为完全阻挡
     * - 树叶/玻璃不阻挡
     */
    private static boolean isFullyBlocked(ServerLevel level, Vec3 from, Vec3 to) {
        Vec3 dir = to.subtract(from);
        double len = dir.length();
        if (len <= 0.001) return false;

        Vec3 step = dir.scale(1.0 / len);
        double t = 0.0;

        // 步长 0.25 格，性能足够（30发*少量实体）
        double stepLen = 0.25;
        Vec3 p = from;

        for (int i = 0; t <= len; i++) {
            BlockPos bp = BlockPos.containing(p.x, p.y, p.z);
            BlockState bs = level.getBlockState(bp);

            if (!bs.isAir() && blocksDamageLine(bs)) {
                return true;
            }

            t += stepLen;
            p = from.add(step.scale(t));
        }
        return false;
    }

    /** 是否阻挡伤害线：树叶/玻璃不算，其它“有碰撞”的方块算 */
    private static boolean blocksDamageLine(BlockState bs) {
        if (isLeaves(bs) || isGlass(bs)) return false;
        // 有碰撞形状才算“能挡住”
        return !bs.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
    }

    private static boolean isLeaves(BlockState bs) {
        return bs.is(BlockTags.LEAVES);
    }

    private static boolean isGlass(BlockState bs) {
        Block b = bs.getBlock();
        return (b instanceof GlassBlock) || (b instanceof StainedGlassBlock)
                || bs.is(net.minecraft.tags.BlockTags.IMPERMEABLE); // 可按需删掉这行
    }

    /** 只破坏树叶/玻璃 */
    private static void breakOnlyLeavesAndGlass(ServerLevel level, BlockPos center, int radius) {
        int r = radius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    BlockState bs = level.getBlockState(p);
                    if (bs.isAir()) continue;
                    if (isLeaves(bs) || isGlass(bs)) {
                        // false：不掉落（更像战地）；想掉落改 true
                        level.destroyBlock(p, false);
                    }
                }
            }
        }
    }
}
