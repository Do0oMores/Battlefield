package top.mores.battlefield.game;

import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public record BeaconSpawnCandidate(
        UUID beaconUuid,
        UUID ownerUuid,
        Vec3 pos,
        float yaw,
        String label
) {
}