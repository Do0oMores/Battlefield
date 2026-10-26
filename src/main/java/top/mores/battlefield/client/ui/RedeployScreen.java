package top.mores.battlefield.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import top.mores.battlefield.client.ClientGameState;
import top.mores.battlefield.client.DeployPreviewClientState;
import top.mores.battlefield.client.deploy.DeployClientState;
import top.mores.battlefield.client.deploy.DeployHeaderModel;
import top.mores.battlefield.client.deploy.DeployMapTexture;
import top.mores.battlefield.client.deploy.DeployMapView;
import top.mores.battlefield.client.deploy.DeployTheme;
import top.mores.battlefield.deploy.DeploymentData;
import top.mores.battlefield.deploy.DeploymentOption;
import top.mores.battlefield.net.BattlefieldNet;
import top.mores.battlefield.net.C2SDeployRequest;
import top.mores.battlefield.net.C2SRequestBackpackPreview;
import top.mores.battlefield.net.C2SRequestDeployData;
import top.mores.battlefield.net.C2SRequestDeployMap;
import top.mores.battlefield.net.S2CSectorAreaPacket;

import java.util.List;

/**
 * 重新部署界面（战地风格战术终端）。
 * <p>
 * 职责边界：
 * <ul>
 *   <li>只画界面、处理点击与当前选中项；</li>
 *   <li>不判断某个出生点是否合法——那是服务端 {@code DeploymentRules} 的事，这里只展示服务端给的
 *       {@link DeploymentOption.State}；</li>
 *   <li>不保存任何权威状态：落点、等待时间、能否部署全部来自 {@code S2CDeployDataPacket}；
 *       本地倒计时只用于显示，按钮是否可用看 {@link DeploymentData#canDeploy()}。</li>
 * </ul>
 * 布局：顶部战局栏 / 左侧部署点列表 / 中央战区图 / 右侧士兵配置 / 底部操作栏。
 */
public class RedeployScreen extends Screen {

    private static final int ROW_H = 30;
    private static final int ROW_GAP = 2;
    private static final int GAP = 6;
    private static final int MARGIN = 8;
    private static final int SLOT = 18;
    private static final int SLOT_GAP = 2;
    private static final int REFRESH_INTERVAL_TICKS = 40;
    private static final int SEND_TIMEOUT_TICKS = 120;

    private DeploymentData data = DeploymentData.CLOSED;
    private DeployHeaderModel header = DeployHeaderModel.build(DeploymentData.CLOSED);
    private final DeployMapView map = new DeployMapView();

    private String selectedId = "";
    private top.mores.battlefield.protocol.SoldierClass selectedRole =
            top.mores.battlefield.protocol.SoldierClass.ASSAULT;
    private int listScroll;
    private String requestedMapId = "";

    private String notice = "";
    private int noticeColor = DeployTheme.WARN;
    private int noticeTicks;

    private java.util.UUID deploymentRequestId = java.util.UUID.randomUUID();
    private String requestChoice = "";
    private boolean sending;
    private int sendingTicks;
    private int refreshTicks;
    private int localWaitTicks;

    private int lastPhase = -1;
    private int lastSector = -1;

    private String pressedButton = "";

    private Rect topBar = Rect.EMPTY;
    private Rect listPanel = Rect.EMPTY;
    private Rect mapPanel = Rect.EMPTY;
    private Rect rightPanel = Rect.EMPTY;
    private Rect bottomBar = Rect.EMPTY;
    private Rect deployBtn = Rect.EMPTY;
    private Rect closeBtn = Rect.EMPTY;
    private Rect bpPrev = Rect.EMPTY;
    private Rect bpNext = Rect.EMPTY;

    public RedeployScreen(DeploymentData initial) {
        super(Component.literal("重新部署"));
        this.data = initial == null ? DeploymentData.CLOSED : initial;
        this.header = DeployHeaderModel.build(this.data);
        this.selectedRole = DeployClientState.soldierClass();
        this.selectedId = DeployClientState.optionId();
    }

    // ===================================================================================
    // 生命周期
    // ===================================================================================

    @Override
    protected void init() {
        computeLayout();
        map.setBackground(DeployMapTexture.view());
        map.setSelectedId(selectedId);
        map.update(data, ClientGameState.points, friendlyAreas(), enemyAreas());
        keepSelectionValid(false);
        requestData();
        requestMapIfNeeded();
        requestPreview();
        lastPhase = ClientGameState.phase;
        lastSector = ClientGameState.sectorIndex;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            onClose();
            return;
        }
        if (!ClientGameState.inBattle) {
            // 离局 / 对局释放：旧界面不能再用来发包
            onClose();
            return;
        }
        if (ClientGameState.phase == 3) {
            notice("对局已结束，无法部署", DeployTheme.WARN);
            onClose();
            return;
        }
        if (localWaitTicks > 0) localWaitTicks--;
        if (noticeTicks > 0) noticeTicks--;

        if (sending && ++sendingTicks > SEND_TIMEOUT_TICKS) {
            sending = false;
            notice("服务器未响应，请重试", DeployTheme.WARN);
        }
        if (sending) return;

        // 已经复活（部署成功或自己点了原版复活）：界面自动退出
        if (mc.player.isAlive() && ClientGameState.phase != 1) {
            onClose();
            return;
        }

        if (refreshTicks > 0) {
            refreshTicks--;
        } else {
            refreshTicks = REFRESH_INTERVAL_TICKS;
            requestData();
        }

        if (ClientGameState.phase != lastPhase || ClientGameState.sectorIndex != lastSector) {
            lastPhase = ClientGameState.phase;
            lastSector = ClientGameState.sectorIndex;
            requestData();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g) {
        // 自己画遮罩，避免原版背景把战场盖掉
    }

    // ===================================================================================
    // 数据
    // ===================================================================================

    public void applyData(DeploymentData next) {
        this.data = next == null ? DeploymentData.CLOSED : next;
        this.header = DeployHeaderModel.build(this.data);
        this.localWaitTicks = this.data.waitTicks();
        map.update(this.data, ClientGameState.points, friendlyAreas(), enemyAreas());
        keepSelectionValid(true);
        requestMapIfNeeded();
    }

    /** 底图到达：换纹理并重算投影（底图边界参与了包围盒计算）。 */
    public void onMapArrived() {
        map.setBackground(DeployMapTexture.view());
        map.setSelectedId(selectedId);
        map.update(data, ClientGameState.points, friendlyAreas(), enemyAreas());
        requestedMapId = "";
    }

    public void onDeployResult(java.util.UUID requestId, boolean ok, String message) {
        if (!deploymentRequestId.equals(requestId)) return;
        requestChoice = "";
        sending = false;
        if (ok) {
            onClose();
            return;
        }
        notice(message == null || message.isBlank() ? "部署失败" : message, DeployTheme.DANGER);
        refreshTicks = 0;
        requestData();
    }

    /** 界面被服务端要求关闭（离局、结算、数据失效）。 */
    public void closeFromServer() {
        onClose();
    }

    private void requestData() {
        BattlefieldNet.sendToServer(new C2SRequestDeployData());
    }

    private void requestPreview() {
        BattlefieldNet.sendToServer(new C2SRequestBackpackPreview(selectedRole));
    }

    /**
     * 手里没有这一局的底图才请求；同一 mapId 只问一次，避免每次刷新都发。
     * 底图还没烘好时服务端不会回包，下一次数据刷新（2 秒后带上新 mapId）会再触发。
     */
    private void requestMapIfNeeded() {
        String id = data.mapId();
        if (id == null || id.isBlank()) return;
        if (DeployMapTexture.has(id)) return;
        if (id.equals(requestedMapId)) return;
        requestedMapId = id;
        BattlefieldNet.sendToServer(new C2SRequestDeployMap());
    }

    private void keepSelectionValid(boolean notify) {
        DeploymentOption previous = data.find(selectedId);
        if (selectedId != null && !selectedId.isBlank()) {
            if (previous == null) {
                if (notify) notice("所选部署点已失效", DeployTheme.WARN);
                selectedId = "";
            } else if (!previous.selectable()) {
                if (notify) notice("所选部署点已不可用：" + previous.detail(), DeployTheme.WARN);
                selectedId = "";
            }
        }
        if (selectedId == null || selectedId.isBlank()) {
            DeploymentOption first = data.firstSelectable();
            selectedId = first == null ? "" : first.id();
        }
        DeployClientState.optionId(selectedId);
    }

    private void select(String optionId) {
        if (optionId == null || optionId.isBlank()) return;
        selectedId = optionId;
        DeployClientState.optionId(selectedId);
        map.setSelectedId(selectedId);
    }

    private void moveSelection(int delta) {
        List<DeploymentOption> options = data.options();
        if (options.isEmpty()) return;
        int current = -1;
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).id().equals(selectedId)) {
                current = i;
                break;
            }
        }
        int next = Math.floorMod(current + delta, options.size());
        select(options.get(next).id());
        ensureRowVisible(next);
    }

    private void ensureRowVisible(int index) {
        int visible = visibleRows();
        if (index < listScroll) listScroll = index;
        if (index >= listScroll + visible) listScroll = index - visible + 1;
        clampScroll();
    }

    private void clampScroll() {
        int max = Math.max(0, data.options().size() - visibleRows());
        listScroll = Math.max(0, Math.min(listScroll, max));
    }

    private int visibleRows() {
        int body = listPanel.h() - 18;
        return Math.max(1, body / (ROW_H + ROW_GAP));
    }

    // ===================================================================================
    // 布局
    // ===================================================================================

    private void computeLayout() {
        topBar = new Rect(MARGIN, MARGIN, width - MARGIN, MARGIN + 40);
        bottomBar = new Rect(MARGIN, height - MARGIN - 40, width - MARGIN, height - MARGIN);

        int contentY1 = topBar.y2() + GAP;
        int contentY2 = bottomBar.y1() - GAP;
        int availW = width - MARGIN * 2;

        int leftW = clamp((int) (availW * 0.27), 118, 210);
        int rightW = clamp((int) (availW * 0.23), 108, 190);
        int centerW = availW - leftW - rightW - GAP * 2;
        if (centerW < 110) {
            int need = 110 - centerW;
            int cutLeft = Math.min(need / 2, Math.max(0, leftW - 96));
            int cutRight = Math.min(need - cutLeft, Math.max(0, rightW - 96));
            leftW -= cutLeft;
            rightW -= cutRight;
            centerW = availW - leftW - rightW - GAP * 2;
        }

        listPanel = new Rect(MARGIN, contentY1, MARGIN + leftW, contentY2);
        mapPanel = new Rect(listPanel.x2() + GAP, contentY1, listPanel.x2() + GAP + centerW, contentY2);
        rightPanel = new Rect(mapPanel.x2() + GAP, contentY1, width - MARGIN, contentY2);
        map.layout(mapPanel.x1(), mapPanel.y1(), mapPanel.w(), mapPanel.h());

        int btnW = clamp(rightPanel.w() - 8, 74, 132);
        int btnH = 20;
        deployBtn = new Rect(bottomBar.x2() - btnW - 6, bottomBar.y1() + 10, bottomBar.x2() - 6, bottomBar.y1() + 10 + btnH);
        closeBtn = new Rect(deployBtn.x1() - 58, deployBtn.y1(), deployBtn.x1() - 6, deployBtn.y2());

        int bpY = rightPanel.y1() + 62;
        bpPrev = new Rect(rightPanel.x1() + 8, bpY, rightPanel.x1() + 24, bpY + 12);
        bpNext = new Rect(rightPanel.x2() - 24, bpY, rightPanel.x2() - 8, bpY + 12);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ===================================================================================
    // 渲染
    // ===================================================================================

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, DeployTheme.SCRIM);

        renderTopBar(g);
        renderOptionList(g, mouseX, mouseY);
        map.setSelectedId(selectedId);
        map.render(g, font, mouseX, mouseY, System.currentTimeMillis());
        renderSoldierPanel(g, mouseX, mouseY);
        renderBottomBar(g, mouseX, mouseY);
        renderNotice(g);
        renderMapTooltip(g, mouseX, mouseY);
    }

    private void renderTopBar(GuiGraphics g) {
        DeployTheme.panel(g, topBar.x1(), topBar.y1(), topBar.x2(), topBar.y2());
        DeployTheme.corners(g, topBar.x1(), topBar.y1(), topBar.x2(), topBar.y2(), DeployTheme.BORDER_BRIGHT, 10);

        int x = topBar.x1() + 10;
        g.drawString(font, header.mode(), x, topBar.y1() + 7, DeployTheme.ACCENT, true);
        int modeW = font.width(header.mode());
        g.drawString(font, "/ " + header.map(), x + modeW + 6, topBar.y1() + 7, DeployTheme.TEXT, false);

        String teamText = data.team() == 0 ? "攻方" : data.team() == 1 ? "守方" : "观战";
        int teamColor = DeployTheme.teamColor(data.team());
        g.drawString(font, "你的阵营", x, topBar.y1() + 22, DeployTheme.TEXT_DIM, false);
        g.drawString(font, teamText, x + font.width("你的阵营") + 6, topBar.y1() + 22, teamColor, true);

        String sector = header.sector();
        if (!sector.isBlank()) {
            int sx = x + font.width("你的阵营") + 6 + font.width(teamText) + 12;
            int maxW = Math.max(0, topBar.x1() + 200 - sx);
            if (maxW > 30) {
                g.drawString(font, DeployTheme.trim(font, sector, maxW),
                        sx, topBar.y1() + 22, DeployTheme.TEXT_MUTED, false);
            }
        }

        int rx = topBar.x2() - 10;
        List<DeployHeaderModel.Stat> stats = header.stats();
        for (int i = stats.size() - 1; i >= 0; i--) {
            DeployHeaderModel.Stat stat = stats.get(i);
            int w = Math.max(font.width(stat.label()), font.width(stat.value()));
            rx -= w;
            if (rx < topBar.x1() + 120) break;
            g.drawString(font, stat.label(), rx, topBar.y1() + 7, DeployTheme.TEXT_DIM, false);
            g.drawString(font, stat.value(), rx, topBar.y1() + 22, stat.color(), true);
            rx -= 16;
        }
    }

    private void renderOptionList(GuiGraphics g, int mouseX, int mouseY) {
        DeployTheme.panel(g, listPanel.x1(), listPanel.y1(), listPanel.x2(), listPanel.y2());

        DeployTheme.sectionTitle(g, font, "部署位置", listPanel.x1() + 8, listPanel.y1() + 6, DeployTheme.ACCENT);
        String count = data.options().size() + " 处";
        g.drawString(font, count, listPanel.x2() - 8 - font.width(count), listPanel.y1() + 6, DeployTheme.TEXT_DIM, false);

        List<DeploymentOption> options = data.options();
        if (options.isEmpty()) {
            g.drawString(font, "当前没有可用部署点", listPanel.x1() + 8, listPanel.y1() + 26, DeployTheme.WARN, false);
            if (!data.denyReason().isBlank()) {
                g.drawString(font, data.denyReason(), listPanel.x1() + 8, listPanel.y1() + 40, DeployTheme.TEXT_DIM, false);
            }
            return;
        }

        int rowY = listPanel.y1() + 18;
        int visible = visibleRows();
        clampScroll();
        for (int i = 0; i < visible; i++) {
            int index = listScroll + i;
            if (index >= options.size()) break;
            DeploymentOption option = options.get(index);
            Rect row = new Rect(listPanel.x1() + 4, rowY + i * (ROW_H + ROW_GAP),
                    listPanel.x2() - 4, rowY + i * (ROW_H + ROW_GAP) + ROW_H);
            renderOptionRow(g, row, option, mouseX, mouseY);
        }

        if (options.size() > visible) {
            int trackX = listPanel.x2() - 3;
            int trackY1 = listPanel.y1() + 18;
            int trackY2 = listPanel.y2() - 4;
            g.fill(trackX, trackY1, trackX + 2, trackY2, 0x40000000);
            float ratio = visible / (float) options.size();
            int barH = Math.max(10, (int) ((trackY2 - trackY1) * ratio));
            int maxScroll = Math.max(1, options.size() - visible);
            int offset = (int) ((trackY2 - trackY1 - barH) * (listScroll / (float) maxScroll));
            g.fill(trackX, trackY1 + offset, trackX + 2, trackY1 + offset + barH, DeployTheme.BORDER_BRIGHT);
        }
    }

    private void renderOptionRow(GuiGraphics g, Rect row, DeploymentOption option, int mouseX, int mouseY) {
        boolean selected = option.id().equals(selectedId);
        boolean hovered = row.contains(mouseX, mouseY);
        int stateColor = DeployTheme.stateColor(option.state());

        int bg = selected ? DeployTheme.withAlpha(DeployTheme.ACCENT, 0x2A)
                : hovered ? 0x30FFFFFF : 0x16000000;
        g.fill(row.x1(), row.y1(), row.x2(), row.y2(), bg);
        if (selected) {
            g.fill(row.x1(), row.y1(), row.x1() + 2, row.y2(), DeployTheme.ACCENT);
            DeployTheme.frame(g, row.x1(), row.y1(), row.x2(), row.y2(), DeployTheme.withAlpha(DeployTheme.ACCENT, 0x88));
        }

        int iconCx = row.x1() + 11;
        int iconCy = row.y1() + 10;
        drawKindIcon(g, option.kind(), iconCx, iconCy, stateColor);

        int textX = row.x1() + 20;
        String state = DeployTheme.stateLabel(option.state());
        int chipW = font.width(state) + 6;
        int textW = Math.max(20, row.x2() - textX - chipW - 6);
        g.drawString(font, DeployTheme.trim(font, option.name(), textW),
                textX, row.y1() + 4, option.selectable() ? DeployTheme.TEXT : DeployTheme.TEXT_MUTED, false);
        g.drawString(font, DeployTheme.trim(font, option.detail(), Math.max(20, row.x2() - textX - 6)),
                textX, row.y1() + 16, stateColor, false);

        int chipX = row.x2() - font.width(state) - 10;
        if (chipX > textX + 10) {
            g.drawString(font, state, chipX, row.y1() + 4, stateColor, false);
        }
    }

    private void drawKindIcon(GuiGraphics g, DeploymentOption.Kind kind, int cx, int cy, int color) {
        switch (kind) {
            case BASE -> {
                g.fill(cx - 4, cy, cx + 5, cy + 1, color);
                g.fill(cx - 3, cy + 1, cx + 4, cy + 2, color);
                g.fill(cx - 2, cy + 2, cx + 3, cy + 3, color);
                g.fill(cx - 1, cy + 3, cx + 2, cy + 4, color);
            }
            case CAPTURE_POINT -> DeployTheme.diamond(g, cx, cy + 1, 4, color);
            case BEACON -> {
                DeployTheme.frame(g, cx - 3, cy - 2, cx + 4, cy + 5, color);
                g.fill(cx, cy, cx + 1, cy + 2, color);
            }
        }
    }

    private void renderSoldierPanel(GuiGraphics g, int mouseX, int mouseY) {
        DeployTheme.panel(g, rightPanel.x1(), rightPanel.y1(), rightPanel.x2(), rightPanel.y2());
        DeployTheme.sectionTitle(g, font, "士兵配置", rightPanel.x1() + 8, rightPanel.y1() + 6, DeployTheme.ACCENT);

        int x = rightPanel.x1() + 8;
        int y = rightPanel.y1() + 22;
        g.drawString(font, "兵种", x, y, DeployTheme.TEXT_DIM, false);
        String roleName = net.minecraft.client.resources.language.I18n.get("role.battlefield." + selectedRole.name().toLowerCase(java.util.Locale.ROOT));
        g.drawString(font, "< " + roleName + " >", x + 34, y, DeployTheme.ACCENT, false);

        int lineY = rightPanel.y1() + 38;
        g.fill(rightPanel.x1() + 8, lineY, rightPanel.x2() - 8, lineY + 1, DeployTheme.BORDER);

        g.drawString(font, "配装", x, rightPanel.y1() + 48, DeployTheme.TEXT_DIM, false);
        DeployTheme.button(g, font, bpPrev.x1(), bpPrev.y1(), bpPrev.x2(), bpPrev.y2(), "<",
                DeployTheme.ACCENT, true, bpPrev.contains(mouseX, mouseY), pressedButton.equals("bpPrev"));
        DeployTheme.button(g, font, bpNext.x1(), bpNext.y1(), bpNext.x2(), bpNext.y2(), ">",
                DeployTheme.ACCENT, true, bpNext.contains(mouseX, mouseY), pressedButton.equals("bpNext"));
        String slotLabel = "当前兵种配装";
        g.drawString(font, slotLabel,
                (bpPrev.x2() + bpNext.x1()) / 2 - font.width(slotLabel) / 2,
                bpPrev.y1() + 2, DeployTheme.TEXT, false);

        renderItemGrid(g, mouseX, mouseY);

        boolean hasPreview = !DeployPreviewClientState.getPreview(selectedRole).isEmpty();
        int noteW = rightPanel.w() - 16;
        if (hasPreview) {
            g.drawString(font, DeployTheme.trim(font, "装备来自 BFBackpack", noteW),
                    x, rightPanel.y2() - 14, DeployTheme.TEXT_DIM, false);
        } else {
            // 两行，避免窄面板把「背包插件未响应」截掉一半
            g.drawString(font, DeployTheme.trim(font, "无配装数据", noteW),
                    x, rightPanel.y2() - 24, DeployTheme.TEXT_DIM, false);
            g.drawString(font, DeployTheme.trim(font, DeployPreviewClientState.status(selectedRole), noteW),
                    x, rightPanel.y2() - 14, DeployTheme.TEXT_DIM, false);
        }
    }

    private void renderItemGrid(GuiGraphics g, int mouseX, int mouseY) {
        List<ItemStack> preview = DeployPreviewClientState.getPreview(selectedRole);
        int gridX = rightPanel.x1() + 8;
        int gridY = rightPanel.y1() + 80;
        int cell = SLOT + SLOT_GAP;

        int cols = 3;
        int maxRows = Math.max(1, (rightPanel.y2() - 20 - gridY) / cell);
        if (maxRows < 3) cols = 3;

        for (int i = 0; i < 9; i++) {
            int col = i % cols;
            int row = i / cols;
            if (row >= maxRows) break;
            int sx = gridX + col * cell;
            int sy = gridY + row * cell;

            g.fill(sx, sy, sx + SLOT, sy + SLOT, 0x70101814);
            DeployTheme.frame(g, sx, sy, sx + SLOT, sy + SLOT, DeployTheme.BORDER);

            ItemStack stack = i < preview.size() ? preview.get(i) : ItemStack.EMPTY;
            if (!stack.isEmpty()) {
                g.renderItem(stack, sx + 1, sy + 1);
                g.renderItemDecorations(font, stack, sx + 1, sy + 1);
            }
            if (mouseX >= sx && mouseX < sx + SLOT && mouseY >= sy && mouseY < sy + SLOT && !stack.isEmpty()) {
                DeployTheme.frame(g, sx, sy, sx + SLOT, sy + SLOT, DeployTheme.ACCENT);
                g.renderTooltip(font, stack, mouseX, mouseY);
            }
        }
    }

    private void renderBottomBar(GuiGraphics g, int mouseX, int mouseY) {
        DeployTheme.panel(g, bottomBar.x1(), bottomBar.y1(), bottomBar.x2(), bottomBar.y2());

        DeploymentOption selected = data.find(selectedId);
        int x = bottomBar.x1() + 10;
        int y = bottomBar.y1() + 8;
        int textLimit = Math.max(40, closeBtn.x1() - x - 10);

        g.drawString(font, "已选", x, y, DeployTheme.TEXT_DIM, false);
        String name = selected == null ? "尚未选择" : selected.name();
        String trimmedName = DeployTheme.trim(font, name, textLimit - 26);
        g.drawString(font, trimmedName, x + 22, y, selected == null ? DeployTheme.TEXT_MUTED : DeployTheme.TEXT, true);
        if (selected != null) {
            DeployTheme.chip(g, font, DeployTheme.stateLabel(selected.state()),
                    x + 22 + font.width(trimmedName) + 8, y - 2, DeployTheme.stateColor(selected.state()));
        }

        String status = statusLine(selected);
        int statusColor = statusColor(selected);
        g.drawString(font, DeployTheme.trim(font, status, textLimit), x, y + 14, statusColor, false);

        boolean canSend = !sending && data.canDeploy() && selected != null && selected.selectable();
        String label = sending ? "部署中…"
                : (!data.canDeploy() && localWaitTicks > 0) ? "等待 " + ((localWaitTicks + 19) / 20) + "s"
                : "DEPLOY 部署";
        DeployTheme.button(g, font, deployBtn.x1(), deployBtn.y1(), deployBtn.x2(), deployBtn.y2(),
                label, DeployTheme.ACCENT, canSend, deployBtn.contains(mouseX, mouseY), pressedButton.equals("deploy"));
        DeployTheme.button(g, font, closeBtn.x1(), closeBtn.y1(), closeBtn.x2(), closeBtn.y2(),
                "返回", DeployTheme.TEXT_MUTED, true, closeBtn.contains(mouseX, mouseY), pressedButton.equals("close"));
    }

    private String statusLine(@Nullable DeploymentOption selected) {
        if (!data.canDeploy() && !data.denyReason().isBlank()) return data.denyReason();
        if (selected == null) return "从左侧列表或地图上选择一个部署点";
        if (!selected.selectable()) return "该位置当前不可部署：" + selected.detail();
        return "服务端将重新校验后执行部署";
    }

    private int statusColor(@Nullable DeploymentOption selected) {
        if (!data.canDeploy() && !data.denyReason().isBlank()) return DeployTheme.WARN;
        if (selected == null || !selected.selectable()) return DeployTheme.DANGER;
        return DeployTheme.TEXT_MUTED;
    }

    private void renderNotice(GuiGraphics g) {
        if (noticeTicks <= 0 || notice.isBlank()) return;
        String text = DeployTheme.trim(font, notice, Math.max(60, width - 40));
        int w = font.width(text) + 16;
        int x = width / 2 - w / 2;
        int y = bottomBar.y1() - 16;
        g.fill(x, y, x + w, y + 13, 0xE0100808);
        DeployTheme.frame(g, x, y, x + w, y + 13, DeployTheme.withAlpha(noticeColor, 0xCC));
        g.drawString(font, text, x + 8, y + 3, noticeColor, false);
    }

    private void renderMapTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (!mapPanel.contains(mouseX, mouseY)) return;
        String id = map.optionAt(mouseX, mouseY);
        if (id == null || id.isBlank()) return;
        DeploymentOption option = data.find(id);
        if (option == null) return;

        String line1 = option.name() + " · " + DeployTheme.stateLabel(option.state());
        String line2 = option.detail();
        String line3 = distanceLabel(option);
        int maxLineW = Math.min(220, mapPanel.w() - 12);
        line1 = DeployTheme.trim(font, line1, maxLineW);
        line2 = DeployTheme.trim(font, line2, maxLineW);
        line3 = DeployTheme.trim(font, line3, maxLineW);
        int w = Math.max(font.width(line1), Math.max(font.width(line2), font.width(line3))) + 14;
        int h = line3.isBlank() ? 26 : 38;
        int x = Math.min(mouseX + 10, mapPanel.x2() - w - 2);
        x = Math.max(mapPanel.x1() + 2, x);
        int y = Math.max(mapPanel.y1() + 2, mouseY - h - 6);

        g.fill(x, y, x + w, y + h, 0xF00A0F0C);
        DeployTheme.frame(g, x, y, x + w, y + h, DeployTheme.BORDER_BRIGHT);
        g.drawString(font, line1, x + 7, y + 5, DeployTheme.stateColor(option.state()), false);
        g.drawString(font, line2, x + 7, y + 15, DeployTheme.TEXT_MUTED, false);
        if (!line3.isBlank()) {
            g.drawString(font, line3, x + 7, y + 25, DeployTheme.TEXT_DIM, false);
        }
    }

    /** 距离只在拿到了可靠的玩家位置时显示。 */
    private String distanceLabel(DeploymentOption option) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 pos = mc.player == null ? null : mc.player.position();
        if (pos == null) return "";
        double dist = Math.sqrt(Math.pow(pos.x - option.x(), 2) + Math.pow(pos.z - option.z(), 2));
        if (!Double.isFinite(dist)) return "";
        return "距离 " + Math.round(dist) + " 格";
    }

    private void notice(String text, int color) {
        this.notice = text == null ? "" : text;
        this.noticeColor = color;
        this.noticeTicks = 100;
    }

    // ===================================================================================
    // 交互
    // ===================================================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        int mx = (int) mouseX;
        int my = (int) mouseY;

        if (mx >= rightPanel.x1() + 34 && mx < rightPanel.x2() - 8
                && my >= rightPanel.y1() + 19 && my <= rightPanel.y1() + 34 && !sending) {
            changeBackpack(1);
            return true;
        }
        if (deployBtn.contains(mx, my)) {
            pressedButton = "deploy";
            tryDeploy();
            return true;
        }
        if (closeBtn.contains(mx, my)) {
            pressedButton = "close";
            attemptClose();
            return true;
        }
        if (bpPrev.contains(mx, my)) {
            pressedButton = "bpPrev";
            changeBackpack(-1);
            return true;
        }
        if (bpNext.contains(mx, my)) {
            pressedButton = "bpNext";
            changeBackpack(1);
            return true;
        }

        int rowIndex = rowAt(mx, my);
        if (rowIndex >= 0 && rowIndex < data.options().size()) {
            select(data.options().get(rowIndex).id());
            return true;
        }

        String markerId = map.optionAt(mx, my);
        if (markerId != null && !markerId.isBlank()) {
            select(markerId);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        pressedButton = "";
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (listPanel.contains((int) mouseX, (int) mouseY)) {
            listScroll -= (int) Math.signum(delta);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                attemptClose();
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                tryDeploy();
                return true;
            }
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> {
                moveSelection(-1);
                return true;
            }
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> {
                moveSelection(1);
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                changeBackpack(-1);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                changeBackpack(1);
                return true;
            }
            case GLFW.GLFW_KEY_R -> {
                refreshTicks = 0;
                requestData();
                notice("已请求刷新", DeployTheme.TEXT_MUTED);
                return true;
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
    }

    private void tryDeploy() {
        if (sending) return;

        DeploymentOption selected = data.find(selectedId);
        if (selected == null) {
            notice("请先选择部署点", DeployTheme.WARN);
            return;
        }
        if (!selected.selectable()) {
            notice("该位置不可部署：" + selected.detail(), DeployTheme.DANGER);
            return;
        }
        if (!data.canDeploy()) {
            notice(data.denyReason().isBlank() ? "当前无法部署" : data.denyReason(), DeployTheme.DANGER);
            return;
        }

        String choice = selectedId + "/" + selectedRole.name();
        if (!choice.equals(requestChoice)) { deploymentRequestId = java.util.UUID.randomUUID(); requestChoice = choice; }
        sending = true;
        sendingTicks = 0;
        DeployClientState.optionId(selectedId);
        BattlefieldNet.sendToServer(new C2SDeployRequest(selectedId, selectedRole, deploymentRequestId));
    }

    private void attemptClose() {
        Minecraft mc = Minecraft.getInstance();
        // 阵亡状态下关闭界面会让玩家没有任何入口可以复活，所以这里只提示不关闭
        if (mc.player != null && !mc.player.isAlive() && ClientGameState.phase != 1) {
            notice("阵亡后需要选择部署点才能重返战场", DeployTheme.WARN);
            return;
        }
        onClose();
    }

    private void changeBackpack(int delta) {
        if (sending) return;
        var classes = top.mores.battlefield.protocol.SoldierClass.values();
        selectedRole = classes[Math.floorMod(selectedRole.ordinal() + delta, classes.length)];
        DeployClientState.soldierClass(selectedRole);
        requestPreview();
    }
    private int rowAt(int mouseX, int mouseY) {
        if (!listPanel.contains(mouseX, mouseY)) return -1;
        int rowY = listPanel.y1() + 18;
        if (mouseY < rowY) return -1;
        int rel = mouseY - rowY;
        int index = rel / (ROW_H + ROW_GAP);
        if (index < 0 || index >= visibleRows()) return -1;
        int inRow = rel % (ROW_H + ROW_GAP);
        if (inRow >= ROW_H) return -1;
        return listScroll + index;
    }

    private List<S2CSectorAreaPacket.AreaRect> friendlyAreas() {
        return ClientGameState.myTeam == 0 ? ClientGameState.attackerAreas : ClientGameState.defenderAreas;
    }

    private List<S2CSectorAreaPacket.AreaRect> enemyAreas() {
        return ClientGameState.myTeam == 0 ? ClientGameState.defenderAreas : ClientGameState.attackerAreas;
    }

    private record Rect(int x1, int y1, int x2, int y2) {
        static final Rect EMPTY = new Rect(0, 0, 0, 0);

        int w() {
            return x2 - x1;
        }

        int h() {
            return y2 - y1;
        }

        boolean contains(int x, int y) {
            return x >= x1 && x < x2 && y >= y1 && y < y2;
        }
    }
}
