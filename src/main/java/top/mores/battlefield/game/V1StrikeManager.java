package top.mores.battlefield.game;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import top.mores.battlefield.server.entity.V1Entity;
import top.mores.battlefield.server.entity.V1MissileEntity;
import top.mores.battlefield.team.TeamId;

import javax.annotation.Nullable;

public final class V1StrikeManager {
    private V1StrikeManager() {
    }

    /**
     * @param level      当前对局世界
     * @param ownerTeam  发射方阵营
     * @param teamSpawn  发射方出生点中心
     * @param target     玩家选定的导弹落点（建议传地面中心点）
     */
    @Nullable
    public static V1MissileEntity launch(ServerLevel level, TeamId ownerTeam, Vec3 teamSpawn, Vec3 target) {
        Vec3 start = computeStart(level, teamSpawn, target);
        Vec3 control = computeControl(start, target);
        int flightTicks = computeFlightTicks(start, target);

        V1MissileEntity missile = V1Entity.V1_MISSILE.get().create(level);
        if (missile == null) return null;

        missile.setup(ownerTeam, start, control, target, flightTicks);
        level.addFreshEntity(missile);
        return missile;
    }

    private static Vec3 computeStart(ServerLevel level, Vec3 teamSpawn, Vec3 target) {
        Vec3 flat = new Vec3(target.x - teamSpawn.x, 0, target.z - teamSpawn.z);
        if (flat.lengthSqr() < 1.0e-6) {
            flat = new Vec3(1, 0, 0);
        }
        Vec3 dir = flat.normalize();

        // 从己方出生点“后方”拉开一段，再抬高
        Vec3 horizontalStart = teamSpawn.subtract(dir.scale(60.0));
        double startY = Math.max(teamSpawn.y + 120.0 + level.random.nextInt(81), target.y + 70.0);

        return new Vec3(horizontalStart.x, startY, horizontalStart.z);
    }

    private static Vec3 computeControl(Vec3 start, Vec3 target) {
        Vec3 mid = start.add(target).scale(0.5);
        double dx = target.x - start.x;
        double dz = target.z - start.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        double arcHeight = Math.max(45.0, horizontalDist * 0.18);
        double controlY = Math.max(start.y, target.y) + arcHeight;

        return new Vec3(mid.x, controlY, mid.z);
    }

    private static int computeFlightTicks(Vec3 start, Vec3 target) {
        double dx = target.x - start.x;
        double dz = target.z - start.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        return Mth.clamp((int) (horizontalDist / 2.2), 60, 130);
    }
}
