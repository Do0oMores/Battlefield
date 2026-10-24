package top.mores.battlefield.game;

import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * 一个可用的信标复活点。
 *
 * @param level 信标所在维度（服务端已确认是 ServerLevel），传送时直接用它
 */
public record BeaconSpawnCandidate(
        UUID beaconUuid,
        UUID ownerUuid,
        Vec3 pos,
        float yaw,
        String label,
        net.minecraft.server.level.ServerLevel level
) {
}
