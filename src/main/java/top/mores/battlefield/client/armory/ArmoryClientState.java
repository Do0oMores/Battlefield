package top.mores.battlefield.client.armory;

import top.mores.battlefield.armory.ArmoryView;
import top.mores.battlefield.net.C2SArmoryRequest;
import top.mores.battlefield.net.S2CArmoryResult;
import top.mores.battlefield.protocol.SoldierClass;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static top.mores.battlefield.net.C2SArmoryRequest.Action.*;

/** UI session only. Pending targets are bound to their request IDs and independent revision domains. */
public final class ArmoryClientState {
    private static final int TIMEOUT = 800;
    private static final int REFRESH = 300;

    public record Key(SoldierClass soldierClass, String weapon) {}
    private record Page(ArmoryView view, String status, int time, String intent) {}
    private record Pending(Key key, C2SArmoryRequest request, int deadline) {}

    private final boolean redeploy;
    private final Consumer<C2SArmoryRequest> send;
    private final Map<Key, Page> pages = new LinkedHashMap<>();
    private Key current;
    private Pending pending;
    private int tick;
    private int nextSend;
    private boolean refresh;

    public ArmoryClientState(boolean redeploy, Consumer<C2SArmoryRequest> send) {
        this.redeploy = redeploy;
        this.send = send;
    }

    public void focus(SoldierClass soldierClass, String weapon) {
        var key = new Key(soldierClass, weapon);
        if (key.equals(current)) return;
        current = key;
        refresh = pending == null || !pending.key.equals(key);
    }

    public void refresh() { refresh = true; }

    public void tick() {
        tick++;
        if (pending != null && tick >= pending.deadline) finish("VALIDATION_TIMEOUT", null);
        Page page = pages.get(current);
        if (pending == null && current != null && tick >= nextSend
                && (refresh || (page != null && page.view != null && tick - page.time >= REFRESH))) {
            refresh = false;
            begin(current.weapon.isEmpty() ? LOADOUT : CUSTOMIZATION, "", "", 0);
        }
    }

    public boolean busy() { return pending != null || refresh; }
    public boolean saving(SoldierClass type) {
        return pending != null && pending.request.action().mutation() && pending.key.soldierClass == type;
    }
    public ArmoryView view() { return view(current); }

    public ArmoryView view(Key key) {
        Page page = pages.get(key);
        return page == null ? null : page.view;
    }

    public String status() {
        if (pending != null && pending.key.equals(current)) return pending.request.action().mutation() ? "SAVING" : "LOADING";
        Page page = pages.get(current);
        return refresh || page == null ? "LOADING" : page.status;
    }

    public String intent() {
        if (pending != null && pending.key.equals(current) && pending.request.action().mutation()) return pending.request.item();
        Page page = pages.get(current);
        return page == null ? "" : page.intent;
    }

    public boolean readyToDeploy() {
        return !busy() && view() != null && "OK".equals(view().deployment());
    }

    public boolean select(C2SArmoryRequest.Action action, String slot, String id) {
        if (!action.mutation() || busy() || view() == null || tick < nextSend) return false;
        begin(action, slot, id, view().revision());
        return true;
    }

    private void begin(C2SArmoryRequest.Action action, String slot, String item, long revision) {
        var request = new C2SArmoryRequest(UUID.randomUUID(), redeploy, current.soldierClass, action,
                current.weapon, slot, item, revision);
        pending = new Pending(current, request, tick + TIMEOUT);
        nextSend = tick + 3;
        send.accept(request);
    }

    public void accept(S2CArmoryResult result) {
        if (pending == null || !pending.request.requestId().equals(result.requestId())) return;
        finish(result.code(), result.view());
    }

    private void finish(String code, ArmoryView view) {
        var completed = pending;
        pending = null;
        String intent = completed.request.action().mutation() && !code.equals("SAVED") && !code.equals("COMMITTED_REFRESH")
                ? completed.request.item() : "";
        pages.put(completed.key, new Page(view, code, tick, intent));
        if (pages.size() > 24) pages.remove(pages.keySet().iterator().next());
        if (!completed.key.weapon.isEmpty() && completed.request.action().mutation()) {
            pages.remove(new Key(completed.key.soldierClass, ""));
        }
    }
}
