package top.mores.battlefield.loadout;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import top.mores.battlefield.armory.ArmoryView;
import top.mores.battlefield.bridge.BFBackpackServiceBridge;
import top.mores.battlefield.game.BattlefieldGameManager;
import top.mores.battlefield.loadout.tacz.TaczResourceValidator;
import top.mores.battlefield.net.BattlefieldNet;
import top.mores.battlefield.net.C2SArmoryRequest;
import top.mores.battlefield.net.S2CArmoryResult;
import top.mores.battlefield.protocol.BridgeFailure;
import top.mores.battlefield.protocol.EquipmentSlot;
import top.mores.battlefield.protocol.LoadoutDto;
import top.mores.battlefield.protocol.LoadoutDtoAdapter;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static top.mores.battlefield.protocol.BridgeProtocol.*;

/** Forge main owns connections, request admission and resource checks. BFBackpack owns the CAS. */
public final class ArmoryService {
    private static final int RECENT_REQUESTS = 64;
    private record DeploymentCheck(String status, long revision, long catalogRevision) {}
    private record CheckedLoadout(ArmoryView view, long catalogRevision) {}

    private static final class Session {
        final LinkedHashSet<UUID> recent = new LinkedHashSet<>();
        C2SArmoryRequest active;
    }

    private final BFBackpackServiceBridge bridge;
    private final TaczResourceValidator resources;
    private final Predicate<UUID> deploying;
    private final Map<ServerPlayer, Session> sessions = new WeakHashMap<>();

    ArmoryService(BFBackpackServiceBridge bridge, TaczResourceValidator resources, Predicate<UUID> deploying) {
        this.bridge = bridge;
        this.resources = resources;
        this.deploying = deploying;
    }

    boolean saving(UUID player) {
        return sessions.entrySet().stream().anyMatch(entry -> entry.getKey().getUUID().equals(player)
                && entry.getValue().active != null && entry.getValue().active.action().mutation());
    }

    public void accept(ServerPlayer player, C2SArmoryRequest request) {
        var match = BattlefieldGameManager.getPlayerMatchId(player.getUUID());
        if (!permitted(player, request, match)) {
            reply(player, request, "SESSION_DENIED");
            return;
        }
        Session session = sessions.computeIfAbsent(player, ignored -> new Session());
        if (session.recent.contains(request.requestId())) {
            // A replay is never executed again. Its old snapshot may no longer describe this session.
            reply(player, request, "DUPLICATE_REQUEST");
            return;
        }
        if (session.active != null) {
            if (!session.active.requestId().equals(request.requestId())) reply(player, request, "VALIDATION_BUSY");
            return;
        }
        if (request.action().mutation() && deploying.test(player.getUUID())) {
            reply(player, request, "VALIDATION_BUSY");
            return;
        }
        session.active = request;
        UUID id = player.getUUID();
        var task = request.action().mutation()
                ? mutate(player, request, match).handle((result, error) -> error == null ? result : code(error))
                : CompletableFuture.completedFuture("OK");
        task.thenComposeAsync(status -> {
            if (!permitted(player, request, match)) return CompletableFuture.completedFuture(S2CArmoryResult.failure(request.requestId(), "SESSION_DENIED"));
            return snapshot(player, request).handle((view, error) -> {
                if (error != null) {
                    String failure = request.action().mutation() && status.equals("OK") ? "COMMITTED_REFRESH"
                            : status.equals("OK") ? code(error) : status;
                    return S2CArmoryResult.failure(request.requestId(), failure);
                }
                String response = request.action().mutation() && status.equals("OK") ? "SAVED" : status;
                try {
                    parse(JSON.toJson(view));
                    return new S2CArmoryResult(request.requestId(), response, view);
                } catch (IllegalArgumentException tooLarge) {
                    return S2CArmoryResult.failure(request.requestId(), request.action().mutation() && status.equals("OK")
                            ? "COMMITTED_REFRESH" : "CATALOG_LIMIT");
                }
            });
        }, player.server).whenCompleteAsync((result, error) -> {
            session.active = null;
            S2CArmoryResult answer = error == null ? result : S2CArmoryResult.failure(request.requestId(), code(error));
            session.recent.add(request.requestId());
            if (session.recent.size() > RECENT_REQUESTS) session.recent.remove(session.recent.iterator().next());
            if (player.server.getPlayerList().getPlayer(id) == player) {
                if (permitted(player, request, match)) BattlefieldNet.sendToPlayer(player, answer);
                else reply(player, request, "SESSION_DENIED");
            }
        }, player.server);
    }

    private static boolean permitted(ServerPlayer player, C2SArmoryRequest request, String acceptedMatch) {
        String currentMatch = BattlefieldGameManager.getPlayerMatchId(player.getUUID());
        return player.server.getPlayerList().getPlayer(player.getUUID()) == player
                && !player.server.isSingleplayer()
                && Objects.equals(acceptedMatch, currentMatch)
                && request.redeploy() == (currentMatch != null)
                && (!request.redeploy() || BattlefieldGameManager.canOpenDeployScreen(player));
    }

    private CompletableFuture<ArmoryView> snapshot(ServerPlayer player, C2SArmoryRequest request) {
        UUID id = player.getUUID();
        if (request.weapon().isEmpty()) {
            var catalog = query(id, request, "armoryCatalog");
            var loadout = query(id, request, "validateLoadout");
            var deployment = request.redeploy()
                    ? query(id, request, "deployment").thenApply(envelope -> {
                        String status = text(envelope, "code");
                        if (!status.equals("OK")) return new DeploymentCheck(status, -1, -1);
                        var verified = LoadoutDtoAdapter.deployment(JSON.toJson(envelope), id, request.soldierClass());
                        return new DeploymentCheck("OK", verified.revision(), verified.catalogRevision());
                    }).exceptionally(error -> new DeploymentCheck(code(error), -1, -1))
                    : CompletableFuture.completedFuture(new DeploymentCheck("", -1, -1));
            return catalog.thenCombineAsync(loadout, (items, loadoutResult) -> {
                var data = value(loadoutResult).getAsJsonObject();
                var saved = object(data, "saved");
                var equipment = new EnumMap<EquipmentSlot, String>(EquipmentSlot.class);
                object(saved, "equipment").entrySet().forEach(e -> equipment.put(EquipmentSlot.valueOf(e.getKey()), id(e.getValue().getAsString())));
                var issues = new EnumMap<EquipmentSlot, String>(EquipmentSlot.class);
                for (var issue : data.getAsJsonArray("issues")) {
                    var detail = issue.getAsJsonObject();
                    issues.put(EquipmentSlot.valueOf(text(detail, "slot")), text(detail, "code"));
                }
                var view = new ArmoryView(number(saved, "revision"), equipment, issues, Map.of(),
                        elements(items).stream().map(this::equipmentEntry).toList(), "");
                return new CheckedLoadout(view, number(data, "catalogRevision"));
            }, player.server).thenCombine(deployment, (checked, check) -> {
                var view = checked.view();
                String status = check.status();
                if (status.equals("OK") && (view.revision() != check.revision()
                        || checked.catalogRevision() != check.catalogRevision())) status = "REVISION_CONFLICT";
                return new ArmoryView(view.revision(), view.equipped(), view.issues(), view.installed(), view.entries(), status);
            });
        }
        return query(id, request, "catalog").thenComposeAsync(items -> {
            var weapon = authorizedWeapon(items, request.weapon());
            var attachments = query(id, request, "attachments");
            var customization = query(id, request, "customization");
            return attachments.thenCombineAsync(customization, (options, planResult) -> {
                var plan = value(planResult).getAsJsonObject();
                Map<String, String> installed = new HashMap<>();
                object(plan, "attachments").entrySet().forEach(e -> installed.put(e.getKey(), id(e.getValue().getAsString())));
                var entries = elements(options).stream().map(option -> attachmentEntry(weapon, option)).toList();
                return new ArmoryView(number(plan, "revision"), Map.of(), Map.of(), installed, entries, "");
            }, player.server);
        }, player.server);
    }

    private CompletableFuture<String> mutate(ServerPlayer player, C2SArmoryRequest request, String match) {
        UUID id = player.getUUID();
        return query(id, request, "catalog").thenComposeAsync(items -> {
            if (!permitted(player, request, match)) throw denied("SESSION_DENIED");
            if (request.action() == C2SArmoryRequest.Action.SELECT_EQUIPMENT) {
                JsonObject entry = find(items, "weapon", request.item(), "CLASS_DENIED");
                var weapon = object(entry, "weapon");
                require(text(entry, "access"));
                if (!slots(weapon).contains(EquipmentSlot.valueOf(request.slot()))) throw denied("CLASS_DENIED");
                require(resources.armoryWeaponStatus(LoadoutDtoAdapter.weapon(weapon)));
                return query(id, request, "selectEquipment").thenApply(result -> text(result, "code"));
            }
            var weapon = authorizedWeapon(items, request.weapon());
            if (request.action() == C2SArmoryRequest.Action.REMOVE_ATTACHMENT) {
                return query(id, request, "removeAttachment").thenApply(result -> text(result, "code"));
            }
            return query(id, request, "attachments").thenComposeAsync(options -> {
                if (!permitted(player, request, match)) throw denied("SESSION_DENIED");
                var entry = attachmentEntry(weapon, find(options, "attachment", request.item(), "ATTACHMENT_INVALID"));
                if (!entry.category().equals(request.slot())) throw denied("ATTACHMENT_INVALID");
                require(entry.status());
                return query(id, request, "selectAttachment").thenApply(result -> text(result, "code"));
            }, player.server);
        }, player.server);
    }

    private CompletableFuture<JsonObject> query(UUID player, C2SArmoryRequest request, String operation) {
        var input = new JsonObject();
        input.addProperty("bridgeProtocol", VERSION);
        input.addProperty("player", player.toString());
        input.addProperty("soldierClass", request.soldierClass().name());
        input.addProperty("operation", operation);
        input.addProperty("weaponId", request.weapon());
        input.addProperty("slot", request.slot());
        input.addProperty("itemId", request.item());
        input.addProperty("revision", request.revision());
        return bridge.request(input).thenApply(top.mores.battlefield.protocol.BridgeProtocol::parse);
    }

    private ArmoryView.Entry equipmentEntry(JsonObject entry) {
        var definition = object(entry, "weapon");
        var weapon = LoadoutDtoAdapter.weapon(definition);
        String status = text(entry, "access");
        if (status.equals("OK")) status = resources.armoryWeaponStatus(weapon);
        Set<String> tags = StreamSupport.stream(definition.getAsJsonArray("tags").spliterator(), false)
                .map(e -> e.getAsString()).collect(Collectors.toUnmodifiableSet());
        return new ArmoryView.Entry(weapon.id(), text(definition, "displayName"), text(definition, "category"),
                weapon.provider(), weapon.resource(), slots(definition), tags, weapon.attachmentsEnabled(), flag(object(entry, "unlock"), "unlocked"), status);
    }

    private ArmoryView.Entry attachmentEntry(LoadoutDto.Weapon weapon, JsonObject entry) {
        var attachment = object(entry, "attachment");
        String resource = id(text(attachment, "providerId"));
        String type = text(attachment, "type");
        boolean unlocked = flag(object(entry, "unlock"), "unlocked");
        String status = unlocked ? resources.armoryAttachmentStatus(weapon, resource, type) : "LOCKED";
        return new ArmoryView.Entry(id(text(attachment, "id")), text(attachment, "displayName"), type,
                "ATTACHMENT", resource, Set.of(), Set.of(), false, unlocked, status);
    }

    private static Set<EquipmentSlot> slots(JsonObject definition) {
        return StreamSupport.stream(definition.getAsJsonArray("slots").spliterator(), false)
                .map(e -> EquipmentSlot.valueOf(e.getAsString())).collect(Collectors.toUnmodifiableSet());
    }

    private static LoadoutDto.Weapon authorizedWeapon(JsonObject items, String weapon) {
        var entry = find(items, "weapon", weapon, "CLASS_DENIED");
        require(text(entry, "access"));
        var definition = LoadoutDtoAdapter.weapon(object(entry, "weapon"));
        if (!definition.attachmentsEnabled()) throw denied("ATTACHMENT_INVALID");
        return definition;
    }

    private static JsonObject find(JsonObject envelope, String key, String id, String missing) {
        return elements(envelope).stream().filter(entry -> text(object(entry, key), "id").equals(id)).findFirst()
                .orElseThrow(() -> denied(missing));
    }

    private static List<JsonObject> elements(JsonObject envelope) {
        return StreamSupport.stream(value(envelope).getAsJsonArray().spliterator(), false).map(e -> e.getAsJsonObject()).toList();
    }

    private static com.google.gson.JsonElement value(JsonObject envelope) {
        require(text(envelope, "code"));
        return envelope.get("value");
    }

    private static void require(String status) {
        if (!status.equals("OK")) throw denied(status);
    }

    private static BridgeFailure denied(String code) {
        return new BridgeFailure(code, "Armory operation rejected");
    }

    private static String code(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        return error instanceof BridgeFailure failure ? failure.code() : "PROVIDER_UNAVAILABLE";
    }

    private static void reply(ServerPlayer player, C2SArmoryRequest request, String status) {
        BattlefieldNet.sendToPlayer(player, S2CArmoryResult.failure(request.requestId(), status));
    }
}
