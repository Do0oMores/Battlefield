package top.mores.battlefield.bukkit;

import top.mores.bFBackpack.api.BFBackpackAPI;
import top.mores.bFBackpack.api.LoadoutResult;
import top.mores.bFBackpack.api.ResultCode;
import top.mores.bFBackpack.api.WeaponCatalogEntry;
import top.mores.bFBackpack.backpack.BackpackType;
import top.mores.battlefield.protocol.SoldierClass;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** API v3 filters each catalog by class. Merge the four views to explain class restrictions. */
final class ArmoryCatalog {
    private ArmoryCatalog() {}

    static CompletableFuture<LoadoutResult<List<WeaponCatalogEntry>>> read(BFBackpackAPI api, UUID player, BackpackType selected) {
        var catalogs = new LinkedHashMap<BackpackType, CompletableFuture<LoadoutResult<List<WeaponCatalogEntry>>>>();
        catalogs.put(selected, api.getCatalog(player, selected));
        for (var soldierClass : SoldierClass.values()) {
            var type = BackpackType.valueOf(soldierClass.name());
            if (type != selected) catalogs.put(type, api.getCatalog(player, type));
        }
        return CompletableFuture.allOf(catalogs.values().toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            Map<String, WeaponCatalogEntry> entries = new LinkedHashMap<>();
            for (var catalog : catalogs.entrySet()) {
                var result = catalog.getValue().join(); // All catalog futures have completed.
                if (!result.successful()) return LoadoutResult.fail(result.code(), result.message());
                for (var entry : result.value()) {
                    entries.putIfAbsent(entry.weapon().id(), catalog.getKey() == selected ? entry
                            : new WeaponCatalogEntry(entry.weapon(), entry.unlock(), ResultCode.CLASS_DENIED));
                }
            }
            return LoadoutResult.ok(List.copyOf(entries.values()));
        });
    }
}
