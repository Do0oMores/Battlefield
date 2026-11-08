package top.mores.battlefield.bukkit;

import org.junit.jupiter.api.Test;
import top.mores.bFBackpack.api.*;
import top.mores.bFBackpack.backpack.BackpackType;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ArmoryCatalogTest {
    @Test void activeClassWinsAndOtherClassItemsRemainVisibleWithoutGrantingAccess() {
        var shared = entry("test:shared", ResultCode.LOCKED);
        var other = entry("test:other", ResultCode.OK);
        var api = backend(Map.of(BackpackType.ASSAULT, LoadoutResult.ok(List.of(shared)),
                BackpackType.RECON, LoadoutResult.ok(List.of(entry("test:shared", ResultCode.OK), other))));
        var result = ArmoryCatalog.read(api, UUID.randomUUID(), BackpackType.ASSAULT).join();
        assertEquals(List.of("test:shared", "test:other"), result.value().stream().map(e -> e.weapon().id()).toList());
        assertEquals(ResultCode.LOCKED, result.value().get(0).access());
        assertEquals(ResultCode.CLASS_DENIED, result.value().get(1).access());
    }

    @Test void backendReadFailureIsNotTreatedAsAnEmptyOrUnlockedCatalog() {
        var api = backend(Map.of(BackpackType.RECON, LoadoutResult.fail(ResultCode.NOT_READY, "session ended")));
        var result = ArmoryCatalog.read(api, UUID.randomUUID(), BackpackType.MEDIC).join();
        assertEquals(ResultCode.NOT_READY, result.code());
        assertNull(result.value());
    }

    private static WeaponCatalogEntry entry(String id, ResultCode status) {
        var weapon = new WeaponDefinition(id, "TACZ", "tacz:gun", id, "rifle", Set.of(),
                Set.of(LoadoutSlot.PRIMARY), false, "", true, true, Set.of(), Set.of(), "");
        return new WeaponCatalogEntry(weapon, null, status);
    }

    private static BFBackpackAPI backend(Map<BackpackType, LoadoutResult<List<WeaponCatalogEntry>>> catalogs) {
        return (BFBackpackAPI) Proxy.newProxyInstance(BFBackpackAPI.class.getClassLoader(), new Class<?>[]{BFBackpackAPI.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("getCatalog")) throw new AssertionError(method);
                    return CompletableFuture.completedFuture(catalogs.getOrDefault(args[1], LoadoutResult.ok(List.of())));
                });
    }
}
