package top.mores.battlefield.team.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import top.mores.battlefield.net.BattlefieldNet;
import top.mores.battlefield.net.team.C2SSquadActionPacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static top.mores.battlefield.team.ui.SquadViewModel.*;

public class TeamHud extends Screen {
    private static final int PANEL_W = 340;
    private static final int PANEL_H = 240;

    private final Map<Integer, Boolean> expanded = new HashMap<>();
    private final List<MemberLine> memberLines = new ArrayList<>();

    private List<SquadEntryView> squads;
    private int selfSquadId;
    private int squadCap;

    private int left;
    private int top;

    public TeamHud(SquadPanelView view) {
        super(Component.literal("小队管理"));
        this.squads = new ArrayList<>(view.squads());
        this.selfSquadId = view.selfSquadId();
        this.squadCap = view.squadCap();
    }

    public void applySnapshot(SquadPanelView view) {
        this.squads = new ArrayList<>(view.squads());
        this.selfSquadId = view.selfSquadId();
        this.squadCap = view.squadCap();

        if (this.minecraft != null && this.minecraft.screen == this) {
            rebuildWidgets();
        }
    }

    @Override
    protected void init() {
        this.left = (this.width - PANEL_W) / 2;
        this.top = (this.height - PANEL_H) / 2;
        rebuildWidgets();
    }

    protected void rebuildWidgets() {
        clearWidgets();
        memberLines.clear();

        int y = top + 24;

        addRenderableWidget(Button.builder(Component.literal("创建小队"),
                        b -> BattlefieldNet.sendToServer(new C2SSquadActionPacket(C2SSquadActionPacket.Action.CREATE, 0)))
                .bounds(left + 10, y, 110, 20)
                .build());

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(left + PANEL_W - 70, y, 60, 20)
                .build());

        y += 30;

        if (squads.isEmpty()) {
            memberLines.add(new MemberLine(left + 12, y + 4, "当前暂无已创建的小队"));
            return;
        }

        for (SquadEntryView squad : squads) {
            final int squadId = squad.squadId();
            final boolean nowExpanded = expanded.getOrDefault(squadId, false);

            String title = squad.displayName() + "  [" + squad.members().size() + "/" + squadCap + "]";
            if (squad.isMine()) {
                title += "  (当前)";
            }

            addRenderableWidget(Button.builder(Component.literal(title),
                            b -> {
                                expanded.put(squadId, !expanded.getOrDefault(squadId, false));
                                rebuildWidgets();
                            })
                    .bounds(left + 10, y, 240, 20)
                    .build());

            Button joinBtn = Button.builder(
                    Component.literal(squad.isMine() ? "当前" : "加入"),
                    b -> BattlefieldNet.sendToServer(new C2SSquadActionPacket(C2SSquadActionPacket.Action.JOIN, squadId))
            ).bounds(left + 258, y, 72, 20).build();

            joinBtn.active = !squad.isMine() && squad.canJoin();
            addRenderableWidget(joinBtn);

            y += 24;

            if (nowExpanded) {
                for (SquadMemberView member : squad.members()) {
                    memberLines.add(new MemberLine(left + 24, y + 2, "• " + member.name()));
                    y += 14;
                }
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);

        g.fill(left, top, left + PANEL_W, top + PANEL_H, 0xD0101010);
        g.drawCenteredString(this.font, this.title, left + PANEL_W / 2, top + 8, 0xFFFFFF);

        super.render(g, mouseX, mouseY, partialTick);

        for (MemberLine line : memberLines) {
            g.drawString(this.font, line.text, line.x, line.y, 0xE0E0E0, false);
        }

        g.drawString(this.font,
                "点击小队名展开成员，右侧按钮加入该小队",
                left + 10,
                top + PANEL_H - 12,
                0xA0A0A0,
                false);
    }

    private record MemberLine(int x, int y, String text) {
    }
}