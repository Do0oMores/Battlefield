package top.mores.battlefield.game;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.projectile.Projectile;
import top.mores.battlefield.team.TeamId;
import top.mores.battlefield.team.TeamManager;

import java.util.UUID;

/**
 * 统一处理战斗规则：
 * - 伤害归因（直接攻击/弹射物/可拥有实体）
 * - 友伤判定
 */
public final class CombatRules {
    private CombatRules() {
    }

    public static ServerPlayer resolveAttacker(DamageSource source) {
        if (source == null) return null;

        Entity attacker = source.getEntity();
        ServerPlayer byEntity = toServerPlayer(attacker);
        if (byEntity != null) return byEntity;

        Entity direct = source.getDirectEntity();
        ServerPlayer byDirect = toOwnerPlayer(direct);
        if (byDirect != null) return byDirect;

        return null;
    }

    public static boolean isFriendlyFire(ServerPlayer attacker, ServerPlayer victim) {
        if (attacker == null || victim == null) return false;
        return TeamManager.isSameTeam(attacker, victim);
    }

    public static boolean isFriendlyFire(TeamId attackerTeam, ServerPlayer victim) {
        if (attackerTeam == null || victim == null || attackerTeam == TeamId.SPECTATOR) return false;
        return TeamManager.getTeam(victim) == attackerTeam;
    }

    public static boolean isSelf(UUID attackerId, ServerPlayer victim) {
        return attackerId != null && victim != null && attackerId.equals(victim.getUUID());
    }

    private static ServerPlayer toOwnerPlayer(Entity entity) {
        if (entity == null) return null;

        ServerPlayer bySelf = toServerPlayer(entity);
        if (bySelf != null) return bySelf;

        if (entity instanceof Projectile projectile) {
            ServerPlayer byProjectileOwner = toServerPlayer(projectile.getOwner());
            if (byProjectileOwner != null) return byProjectileOwner;
        }

        if (entity instanceof OwnableEntity ownable) {
            ServerPlayer byOwnableOwner = toServerPlayer(ownable.getOwner());
            if (byOwnableOwner != null) return byOwnableOwner;
        }

        return null;
    }

    private static ServerPlayer toServerPlayer(Entity entity) {
        return entity instanceof ServerPlayer sp ? sp : null;
    }
}

