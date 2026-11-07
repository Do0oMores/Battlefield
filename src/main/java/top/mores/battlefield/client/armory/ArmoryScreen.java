package top.mores.battlefield.client.armory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import top.mores.battlefield.armory.ArmoryView;
import top.mores.battlefield.armory.ArmoryView.Entry;
import top.mores.battlefield.client.ClientGameState;
import top.mores.battlefield.client.ui.RedeployScreen;
import top.mores.battlefield.net.BattlefieldNet;
import top.mores.battlefield.net.C2SArmoryRequest.Action;
import top.mores.battlefield.net.S2CArmoryResult;
import top.mores.battlefield.protocol.EquipmentSlot;
import top.mores.battlefield.protocol.SoldierClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static top.mores.battlefield.client.armory.ArmoryTheme.*;

/** One editor for both lobby and deployment, with a separate weapon detail route. */
public final class ArmoryScreen extends Screen {
    private static final int MARGIN = 8, ROW = 32;
    private static final String[] CLASS_MARKS = {"▲", "+", "■", "◆"};

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private final ArmoryClientState state;
    private final RedeployScreen parent;
    private final ClientPacketListener connection;
    private final List<Hit> hits = new ArrayList<>();
    private SoldierClass soldierClass;
    private EquipmentSlot slot = EquipmentSlot.PRIMARY;
    private Entry weapon;
    private String attachmentType = "", category = "", tag = "", search = "";
    private int filter, cursor, scroll;
    private boolean reverse;
    private EditBox searchBox;
    private int left, top, middleWidth, right, rightWidth, listTop, listBottom, visibleRows;
    private long transition = System.nanoTime();

    public ArmoryScreen() {
        this(null, new ArmoryClientState(false, BattlefieldNet::sendToServer), SoldierClass.ASSAULT, null);
    }

    public ArmoryScreen(RedeployScreen parent, ArmoryClientState state, SoldierClass soldierClass, Entry weapon) {
        super(label("title"));
        this.parent = parent;
        this.state = state;
        this.soldierClass = soldierClass;
        this.weapon = weapon;
        connection = Minecraft.getInstance().getConnection();
    }

    public static void accept(S2CArmoryResult result) {
        Screen current = Minecraft.getInstance().screen;
        if (current instanceof ArmoryScreen screen && screen.connected()) screen.state.accept(result);
        if (current instanceof RedeployScreen screen) screen.armory().accept(result);
    }

    public RedeployScreen deploymentScreen() { return parent; }

    @Override protected void init() {
        boolean compact = width < 420;
        left = compact ? MARGIN : 103;
        top = compact ? 52 : 33;
        rightWidth = Math.max(72, Math.min(155, width / 4));
        right = width - rightWidth - MARGIN;
        middleWidth = Math.max(1, right - left - MARGIN);
        listTop = top + 44;
        listBottom = Math.max(listTop, height - 32);
        visibleRows = Math.max(1, (listBottom - listTop) / ROW);
        searchBox = new EditBox(font, left + 6, top + 4, Math.max(1, middleWidth - 12), 16, label("search"));
        searchBox.setMaxLength(80);
        searchBox.setValue(search);
        searchBox.setHint(label("search"));
        searchBox.setResponder(value -> { search = value; resetList(); });
        addRenderableWidget(searchBox);
        state.focus(soldierClass, weaponId());
    }

    @Override public void tick() {
        if (connection != minecraft.getConnection() || (parent == null && ClientGameState.inBattle)) {
            minecraft.setScreen(null);
            return;
        }
        if (parent != null && (!ClientGameState.inBattle || ClientGameState.phase == 3)) {
            minecraft.setScreen(null);
            return;
        }
        if (connected()) state.tick();
    }

    private boolean connected() {
        return connection != null && connection == minecraft.getConnection() && minecraft.player != null
                && !minecraft.hasSingleplayerServer();
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void onClose() {
        if (weapon != null) {
            openWeapon(null);
        } else if (parent != null) {
            parent.armoryClosed(soldierClass);
            minecraft.setScreen(parent);
        } else {
            minecraft.setScreen(null);
        }
    }

    private String weaponId() { return weapon == null ? "" : weapon.id(); }

    private void openWeapon(Entry entry) {
        weapon = entry;
        attachmentType = "";
        category = "";
        tag = "";
        searchBox.setValue("");
        resetList();
        state.focus(soldierClass, weaponId());
    }

    private void chooseClass(SoldierClass type) {
        if (soldierClass == type) return;
        soldierClass = type;
        openWeapon(null);
    }

    private void resetList() {
        cursor = 0;
        scroll = 0;
        transition = System.nanoTime();
    }

    @Override public void render(GuiGraphics g, int mx, int my, float partialTick) {
        hits.clear();
        g.fill(0, 0, width, height, BACKGROUND);
        g.fill(MARGIN, 27, width - MARGIN, 28, GOLD);
        text(g, font, weapon == null ? getTitle() : label("customize_title", weapon.name()), MARGIN, 13, width - (weapon == null ? 116 : 16), GOLD);
        g.fill(left, top, right - MARGIN, listBottom, PANEL);
        g.fill(right, top, width - MARGIN, listBottom, PANEL);
        drawClasses(g, mx, my);

        ArmoryView view = state.view();
        var entries = filtered(view);
        Entry focus = entries.isEmpty() ? null : entries.get(Math.min(cursor, entries.size() - 1));
        drawFilters(g, view, mx, my);
        drawEntries(g, view, entries, mx, my);
        drawDetail(g, view, focus, mx, my);
        if (weapon == null && focus != null && focus.customizable()) {
            button(g, width - 106, 7, 98, 16, label("customize"), true,
                    focus.selectable() && !state.busy(), mx, my, () -> openWeapon(focus));
        }
        drawFooter(g, view, mx, my);
        searchBox.setVisible(connected());
        super.render(g, mx, my, partialTick);
        for (int i = scroll; i < Math.min(entries.size(), scroll + visibleRows); i++) {
            int y = listTop + (i - scroll) * ROW;
            if (mx >= left && mx < right - MARGIN && my >= y && my < Math.min(y + ROW, listBottom)) {
                var entry = entries.get(i);
                if (my >= listTop) g.renderTooltip(font, label("tooltip", entry.name(), status(entry.status())), mx, my);
                break;
            }
        }
        int fade = (int) Math.max(0, 86 - (System.nanoTime() - transition) / 2_000_000L);
        if (fade > 0) g.fill(left, listTop, right - MARGIN, listBottom, fade << 24 | (BACKGROUND & 0xFFFFFF));
    }

    private void drawClasses(GuiGraphics g, int mx, int my) {
        SoldierClass[] classes = SoldierClass.values();
        for (int i = 0; i < classes.length; i++) {
            SoldierClass type = classes[i];
            boolean compact = left == MARGIN;
            int w = compact ? (width - MARGIN * 2) / classes.length : left - MARGIN * 2;
            int x = compact ? MARGIN + i * w : MARGIN;
            int y = compact ? 32 : top + i * 27;
            Component name = Component.translatable("role.battlefield." + type.name().toLowerCase(Locale.ROOT));
            button(g, x, y, w - 2, 17, label("class_tab", CLASS_MARKS[i], name,
                    state.saving(type) ? " •" : ""), soldierClass == type, true, mx, my, () -> chooseClass(type));
        }
    }

    private void drawFilters(GuiGraphics g, ArmoryView view, int mx, int my) {
        int quarter = Math.max(1, (middleWidth - 8) / 4);
        int y = top + 23;
        var available = view == null ? List.<Entry>of() : view.entries().stream()
                .filter(e -> weapon != null || e.slots().contains(slot)).toList();
        var categories = new java.util.TreeSet<String>();
        available.forEach(entry -> categories.add(entry.category()));
        if (weapon != null && view != null) categories.addAll(view.installed().keySet());
        var groups = List.copyOf(categories);
        var tags = available.stream().flatMap(e -> e.tags().stream()).distinct().sorted().toList();
        String group = weapon == null ? category : attachmentType;
        button(g, left + 4, y, quarter - 2, 16, group.isEmpty() ? label(weapon == null ? "category" : "type") : categoryLabel(group),
                !group.isEmpty(), view != null, mx, my, () -> {
                    if (weapon == null) category = next(groups, category);
                    else attachmentType = next(groups, attachmentType);
                    resetList();
                });
        button(g, left + 4 + quarter, y, quarter - 2, 16, tag.isEmpty() ? label("tag") : Component.literal(tag),
                !tag.isEmpty(), !tags.isEmpty(), mx, my, () -> { tag = next(tags, tag); resetList(); });
        button(g, left + 4 + quarter * 2, y, quarter - 2, 16, label("filter." + filter),
                filter != 0, view != null, mx, my, () -> { filter = (filter + 1) % 3; resetList(); });
        button(g, left + 4 + quarter * 3, y, quarter - 2, 16, label(reverse ? "sort.desc" : "sort.asc"),
                false, view != null, mx, my, () -> { reverse = !reverse; resetList(); });
    }

    private static String next(List<String> values, String current) {
        int index = values.indexOf(current) + 1;
        return index < values.size() ? values.get(index) : "";
    }

    private Component categoryLabel(String name) {
        return Component.translatableWithFallback("battlefield.armory.category." + name.toLowerCase(Locale.ROOT), name);
    }

    private List<Entry> filtered(ArmoryView view) {
        if (view == null) return List.of();
        String term = search.trim().toLowerCase(Locale.ROOT);
        String group = weapon == null ? category : attachmentType;
        Comparator<Entry> order = Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::id);
        return view.entries().stream()
                .filter(e -> weapon != null || e.slots().contains(slot))
                .filter(e -> group.isEmpty() || group.equals(e.category()))
                .filter(e -> tag.isEmpty() || e.tags().contains(tag))
                .filter(e -> filter == 0 || (filter == 1 ? e.unlocked() : equipped(view, e)))
                .filter(e -> e.name().toLowerCase(Locale.ROOT).contains(term) || e.id().contains(term))
                .sorted(reverse ? order.reversed() : order).toList();
    }

    private boolean equipped(ArmoryView view, Entry entry) {
        return entry.id().equals(weapon == null ? view.equipped().get(slot) : view.installed().get(entry.category()));
    }

    private void drawEntries(GuiGraphics g, ArmoryView view, List<Entry> entries, int mx, int my) {
        if (entries.isEmpty()) {
            var message = connected() ? label(view == null && state.busy() ? "loading" : "empty") : status("OFFLINE");
            text(g, font, message, left + 6, listTop + 8, middleWidth - 12, MUTED);
            return;
        }
        cursor = Math.min(cursor, entries.size() - 1);
        scroll = Math.max(0, Math.min(scroll, entries.size() - visibleRows));
        g.enableScissor(left, listTop, right - MARGIN, listBottom);
        for (int i = scroll; i < Math.min(entries.size(), scroll + visibleRows); i++) {
            Entry entry = entries.get(i);
            int index = i;
            int y = listTop + (i - scroll) * ROW;
            int x = left + 4, w = middleWidth - 8;
            boolean active = equipped(view, entry);
            boolean pending = entry.id().equals(state.intent());
            g.fill(x, y, x + w, y + ROW - 2, SECONDARY);
            if (active || cursor == i || pending) g.fill(x, y, x + 2, y + ROW - 2, pending ? WARN : GOLD);
            ArmoryIcons.draw(g, entry, x + 4, y + 3, 43, 23);
            int tx = x + 51;
            text(g, font, Component.literal(entry.name()), tx, y + 5, w - 54, TEXT);
            Component stateText = active ? label("equipped_state", status(entry.status()))
                    : label("card_state", categoryLabel(entry.category()), status(entry.status()));
            text(g, font, stateText, tx, y + 17, w - 54, statusColor(entry.status()));
            hits.add(new Hit(x, y, w, Math.max(0, Math.min(ROW - 2, listBottom - y)), () -> {
                cursor = index;
                choose(entry);
            }));
        }
        g.disableScissor();
    }

    private void drawDetail(GuiGraphics g, ArmoryView view, Entry focus, int mx, int my) {
        int x = right + 5, w = rightWidth - 10;
        if (weapon == null) {
            text(g, font, label("equipment"), x, top + 6, w, GOLD);
            int stride = Math.max(11, Math.min(24, (listBottom - top - 47) / 6));
            for (var target : EquipmentSlot.values()) {
                int y = top + 18 + target.ordinal() * stride;
                String id = view == null ? "" : view.equipped().getOrDefault(target, "");
                Entry equipment = view == null ? null : view.entries().stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
                var slotName = label("slot." + target.name());
                var name = equipment != null ? Component.literal(equipment.name())
                        : id.isEmpty() ? label("unconfigured") : Component.literal(id);
                boolean issue = view != null && (view.issues().containsKey(target) || !id.isEmpty() && equipment == null);
                Component title = issue ? label("slot_issue", slotName) : slotName;
                Runnable selectSlot = () -> { slot = target; category = ""; tag = ""; resetList(); };
                if (stride >= 21) {
                    var hit = new Hit(x, y, w, stride - 1, selectSlot);
                    g.fill(x, y, x + w, y + stride - 1, hit.contains(mx, my) ? PANEL : SECONDARY);
                    if (target == slot) g.fill(x, y, x + 2, y + stride - 1, GOLD);
                    text(g, font, title, x + 4, y + 2, w - 7, TEXT);
                    text(g, font, name, x + 4, y + 12, w - 7, issue ? RED : MUTED);
                    hits.add(hit);
                } else {
                    button(g, x, y, w, stride - 1, label("slot_item", title, name),
                            target == slot, true, mx, my, selectSlot);
                }
            }
            int y = top + 18 + 6 * stride;
            if (focus != null && listBottom - y >= 34) {
                text(g, font, Component.literal(focus.name()), x, y, w, TEXT);
                ArmoryIcons.draw(g, focus, x, y + 10, w, listBottom - y - 24);
                text(g, font, ArmoryIcons.stats(focus), x, listBottom - 12, w, MUTED);
            }
        } else {
            text(g, font, Component.literal(weapon.name()), x, top + 6, w, GOLD);
            ArmoryIcons.draw(g, weapon, x, top + 18, w, Math.min(50, w / 2));
            int y = top + Math.min(50, w / 2) + 21;
            String type = !attachmentType.isEmpty() ? attachmentType : focus != null ? focus.category()
                    : view == null ? "" : view.installed().keySet().stream().sorted().findFirst().orElse("");
            if (view != null && !type.isEmpty()) {
                text(g, font, categoryLabel(type), x, y, w, GOLD);
                String selected = view.installed().get(type);
                Entry attachment = view.entries().stream().filter(e -> e.id().equals(selected)).findFirst().orElse(null);
                var name = attachment != null ? Component.literal(attachment.name())
                        : selected == null ? label("default") : Component.literal(selected);
                text(g, font, name, x, y + 13, w, MUTED);
                if (attachment != null && y + 62 < listBottom) ArmoryIcons.draw(g, attachment, x, y + 23, w, 32);
                button(g, x, listBottom - 20, w, 16, label("restore_default"), false,
                        selected != null && !state.busy(), mx, my, () -> state.select(Action.REMOVE_ATTACHMENT, type, ""));
            }
        }
    }

    private void drawFooter(GuiGraphics g, ArmoryView view, int mx, int my) {
        int y = height - 24;
        button(g, width - 68, y, 60, 16, label(parent == null && weapon == null ? "close" : "back"), false, true, mx, my, this::onClose);
        button(g, width - 132, y, 60, 16, label("refresh"), false, connected() && !state.busy(), mx, my, state::refresh);
        String status = connected() ? state.status() : "OFFLINE";
        String spinner = state.busy() && connected() ? " " + "·◦•◦".charAt((int) ((System.nanoTime() / 150_000_000L) % 4)) : "";
        var message = status(status).copy().append(spinner);
        text(g, font, view == null ? message : label("revision", message, view.revision()), MARGIN, y + 4,
                width - 145, statusColor(status));
    }

    private void button(GuiGraphics g, int x, int y, int w, int h, Component name, boolean active, boolean enabled,
                        int mx, int my, Runnable action) {
        var hit = new Hit(x, y, w, h, action);
        g.fill(x, y, x + w, y + h, hit.contains(mx, my) && enabled ? PANEL : SECONDARY);
        if (active) g.fill(x, y, x + 2, y + h, GOLD);
        text(g, font, name, x + 4, y + Math.max(1, (h - 8) / 2), w - 7, enabled ? TEXT : MUTED);
        if (enabled) hits.add(hit);
    }

    private void choose(Entry entry) {
        var view = state.view();
        if (!connected() || view == null || !entry.selectable() || equipped(view, entry)) return;
        state.select(weapon == null ? Action.SELECT_EQUIPMENT : Action.SELECT_ATTACHMENT,
                weapon == null ? slot.name() : entry.category(), entry.id());
    }

    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (button == 1) { onClose(); return true; }
        if (super.mouseClicked(mx, my, button)) return true;
        if (button == 0) {
            for (var hit : hits) if (hit.contains(mx, my)) {
                setFocused(null);
                hit.action.run();
                return true;
            }
        }
        return false;
    }

    @Override public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx < left || mx >= right || my < listTop || my >= listBottom) return false;
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(delta), filtered(state.view()).size() - visibleRows));
        return true;
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            var entries = filtered(state.view());
            if (entries.isEmpty()) return false;
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) choose(entries.get(Math.min(cursor, entries.size() - 1)));
            else {
                cursor = Math.max(0, Math.min(entries.size() - 1, cursor + (key == GLFW.GLFW_KEY_UP ? -1 : 1)));
                scroll = Math.min(cursor, Math.max(scroll, cursor - visibleRows + 1));
            }
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }
}
