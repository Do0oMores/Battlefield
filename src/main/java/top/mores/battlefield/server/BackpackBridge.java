package top.mores.battlefield.server;

import net.minecraft.server.level.ServerPlayer;
import top.mores.battlefield.loadout.*;
import top.mores.battlefield.protocol.SoldierClass;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** BFBackpack v3 entry point; legacy numbered Backpack/ItemStack conversion is removed. */
public final class BackpackBridge {
    private BackpackBridge() {}
    public static CompletableFuture<DeploymentLedger.Outcome> deploy(ServerPlayer player, String option, SoldierClass type, UUID request) {
        LoadoutCoordinator service = LoadoutCoordinator.get();
        return service == null ? CompletableFuture.completedFuture(DeploymentLedger.Outcome.fail("NOT_READY", "配装服务未就绪")) : service.deploy(player, option, type, request);
    }
}