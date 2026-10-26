package top.mores.battlefield.loadout.tacz;

import com.google.gson.*;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.util.AllowAttachmentTagMatcher;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.registries.ForgeRegistries;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.loadout.EquipmentFactory;
import top.mores.battlefield.protocol.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static top.mores.battlefield.protocol.BridgeProtocol.*;

/** Main-thread captured immutable resource index; no validation thread reads live TaCZ state. */
public final class TaczResourceValidator implements AutoCloseable {
    public record Gun(Set<String> types, Set<String> allowed, boolean ammoValid, boolean builtinsValid) {
        public Gun { types = Set.copyOf(types); allowed = Set.copyOf(allowed); }
    }
    public record Index(long generation, boolean ready, Map<String, Gun> guns, Map<String, String> attachments, Set<String> ammo, Set<String> items) {
        public Index { guns = Map.copyOf(guns); attachments = Map.copyOf(attachments); ammo = Set.copyOf(ammo); items = Set.copyOf(items); }
    }
    private final MinecraftServer server;
    private final EquipmentFactory factory;
    private final AtomicLong epochs = new AtomicLong();
    private volatile Index index = new Index(0, false, Map.of(), Map.of(), Set.of(), Set.of());
    private final Map<LoadoutDto.Equipment, String> combinations = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<String>> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    public TaczResourceValidator(MinecraftServer server, EquipmentFactory factory) { this.server = server; this.factory = factory; }
    public long generation() { return index.generation(); }
    public boolean ready() { return index.ready() && !closed; }
    public void invalidate() { index = new Index(epochs.incrementAndGet(), false, Map.of(), Map.of(), Set.of(), Set.of()); combinations.clear(); }
    public void refresh() {
        if (!server.isSameThread()) throw new IllegalStateException("Forge main thread required");
        if (closed) return;
        invalidate(); long epoch = index.generation();
        try {
            Map<String, String> attachments = new HashMap<>(); Set<String> ammo = new HashSet<>(), items = new HashSet<>();
            TimelessAPI.getAllCommonAttachmentIndex().forEach(e -> attachments.put(e.getKey().toString(), e.getValue().getType().name()));
            TimelessAPI.getAllCommonAmmoIndex().forEach(e -> ammo.add(e.getKey().toString()));
            ForgeRegistries.ITEMS.getKeys().forEach(id -> { if (!id.toString().equals("minecraft:air")) items.add(id.toString()); });
            Map<String, Gun> guns = new HashMap<>();
            TimelessAPI.getAllCommonGunIndex().forEach(entry -> {
                var data = entry.getValue().getGunData(); Set<String> types = new HashSet<>(), allowed = new HashSet<>();
                if (data.getAllowAttachments() != null) data.getAllowAttachments().forEach(t -> types.add(t.name()));
                TimelessAPI.getAllCommonAttachmentIndex().forEach(a -> {
                    if (types.contains(a.getValue().getType().name()) && AllowAttachmentTagMatcher.match(entry.getKey(), a.getKey())) allowed.add(a.getKey().toString());
                });
                boolean builtin = data.getBuiltInAttachments().values().stream().allMatch(id -> attachments.containsKey(id.toString()));
                boolean buildable;
                try { factory.createEquipment(new LoadoutDto.Equipment(new LoadoutDto.Weapon("battlefield:resource_check", "TACZ_GUN", entry.getKey().toString(), true), 0, Map.of())); buildable = true; }
                catch (RuntimeException e) { buildable = false; }
                guns.put(entry.getKey().toString(), new Gun(types, allowed, buildable && data.getAmmoId() != null && ammo.contains(data.getAmmoId().toString()), builtin));
            });
            index = new Index(epoch, !guns.isEmpty(), guns, attachments, ammo, items);
            Battlefield.LOGGER.info("TaCZ resource snapshot generation={} guns={} attachments={} ready={}", epoch, guns.size(), attachments.size(), index.ready());
        } catch (RuntimeException e) { Battlefield.LOGGER.error("TaCZ snapshot unavailable; deployment disabled", e); }
    }
    public CompletableFuture<String> validate(String json) {
        try {
            JsonObject request = parse(json);
            if (number(request, "bridgeProtocol") != VERSION) return result("UNVERIFIED", "API_INCOMPATIBLE", "Bridge protocol mismatch");
            Index captured = index;
            if (closed || !captured.ready()) return result("UNVERIFIED", "PROVIDER_UNAVAILABLE", "Resource index loading/unavailable");
            var weapon = LoadoutDtoAdapter.weapon(object(request, "weapon"));
            String check = checkWeapon(captured, weapon);
            if (check != null) return result("REJECTED", check, "Provider resource unavailable");
            String operation = text(request, "operation");
            if (operation.equals("weapon")) return result("VERIFIED", "OK", "Resource indexed");
            if (!operation.equals("attachments")) return result("REJECTED", "INVALID_INPUT", "Unknown operation");
            JsonObject plan = object(request, "customization");
            if (!text(plan, "weaponId").equals(weapon.id()) || !text(plan, "skinId").isEmpty()) return result("REJECTED", "ATTACHMENT_INVALID", "Weapon/skin mismatch");
            var selected = LoadoutDtoAdapter.attachments(object(plan, "attachments"), object(request, "attachmentDefinitions"));
            var equipment = new LoadoutDto.Equipment(weapon, 0, selected);
            if (!selected.isEmpty() && !weapon.attachmentsEnabled()) return result("REJECTED", "ATTACHMENT_INVALID", "Attachments disabled");
            if (!selected.isEmpty() && !Set.of("TACZ", "TACZ_GUN").contains(weapon.provider())) return result("REJECTED", "ATTACHMENT_INVALID", "Provider has no attachments");
            Gun gun = captured.guns().get(weapon.resource());
            for (var entry : selected.entrySet()) {
                var attachment = entry.getValue(); String type = captured.attachments().get(attachment.resource());
                if (type == null) return result("REJECTED", "RESOURCE_NOT_FOUND", "Attachment not indexed");
                if (!type.equals(entry.getKey()) || gun == null || !gun.types().contains(type) || !gun.allowed().contains(attachment.resource())) return result("REJECTED", "ATTACHMENT_INVALID", "Type/compatibility rejected");
            }
            String cached = combinations.get(equipment); if (cached != null) return CompletableFuture.completedFuture(cached);
            if (pending.size() >= 128) return result("UNVERIFIED", "VALIDATION_BUSY", "Combination queue full");
            CompletableFuture<String> future = new CompletableFuture<>(); pending.add(future);
            future.orTimeout(2500, TimeUnit.MILLISECONDS).whenComplete((v, e) -> pending.remove(future));
            server.execute(() -> {
                if (future.isDone()) return;
                if (closed || index != captured) { future.complete(compatibility("UNVERIFIED", "PROVIDER_UNAVAILABLE", "Resources changed")); return; }
                String answer;
                try { factory.createEquipment(equipment); answer = compatibility("VERIFIED", "OK", "Exact combination constructed and inspected"); }
                catch (RuntimeException e) { answer = compatibility("REJECTED", "ATTACHMENT_INVALID", "Exact construction/combination rejected"); }
                if (combinations.size() < 1024) combinations.put(equipment, answer);
                future.complete(answer);
            });
            return future.exceptionally(e -> compatibility("UNVERIFIED", "VALIDATION_TIMEOUT", "Forge validation timed out"));
        } catch (RuntimeException e) { return result("REJECTED", "INVALID_INPUT", "Malformed validation request"); }
    }
    private String checkWeapon(Index i, LoadoutDto.Weapon w) {
        return switch (w.provider()) {
            case "TACZ", "TACZ_GUN" -> {
                Gun g = i.guns().get(w.resource()); yield g == null || !g.ammoValid() || !g.builtinsValid() ? "RESOURCE_NOT_FOUND" : null;
            }
            case "TACZ_AMMO" -> i.ammo().contains(w.resource()) ? null : "RESOURCE_NOT_FOUND";
            case "MINECRAFT_ITEM" -> w.resource().startsWith("minecraft:") && i.items().contains(w.resource()) ? null : "RESOURCE_NOT_FOUND";
            case "BATTLEFIELD_ITEM" -> w.resource().startsWith("battlefield:") && i.items().contains(w.resource()) ? null : "RESOURCE_NOT_FOUND";
            default -> "PROVIDER_UNAVAILABLE";
        };
    }
    private static CompletableFuture<String> result(String state, String code, String message) { return CompletableFuture.completedFuture(compatibility(state, code, message)); }
    @Override public void close() { closed = true; invalidate(); pending.forEach(f -> f.complete(compatibility("UNVERIFIED", "NOT_READY", "Server stopping"))); pending.clear(); }
}
