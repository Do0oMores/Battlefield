package top.mores.battlefield.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import top.mores.battlefield.net.BattlefieldNet;
import top.mores.battlefield.net.C2SDeployRequest;

import net.minecraft.world.item.ItemStack;
import top.mores.battlefield.client.DeployPreviewClientState;
import top.mores.battlefield.net.C2SRequestBackpackPreview;

import java.util.List;

public class RespawnDeployScreen extends Screen {

    private int bpSlot = 1;
    private final int spawnIndex = 0;

    private Button deployBtn;
    private Button bpMinus;
    private Button bpPlus;

    private static final int MIN_BP = 1;
    private static final int MAX_BP = 9;

    private static final int PREVIEW_COUNT = 7;   // 中间最多显示 7 个物品
    private static final int SLOT_SIZE = 18;

    public RespawnDeployScreen() {
        super(Component.literal("重新部署"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height - 60;

        bpMinus = Button.builder(Component.literal("<"), b -> changeBpSlot(-1))
                .bounds(cx - 90, y, 20, 20).build();

        bpPlus = Button.builder(Component.literal(">"), b -> changeBpSlot(1))
                .bounds(cx + 70, y, 20, 20).build();

        deployBtn = Button.builder(Component.literal("DEPLOY"), b -> {
            BattlefieldNet.CH.sendToServer(new C2SDeployRequest(spawnIndex, bpSlot));
            Minecraft.getInstance().setScreen(null);
        }).bounds(cx - 50, y + 26, 100, 20).build();

        this.addRenderableWidget(bpMinus);
        this.addRenderableWidget(bpPlus);
        this.addRenderableWidget(deployBtn);

        requestPreview();
    }

    private void changeBpSlot(int delta) {
        int old = bpSlot;
        bpSlot += delta;
        if (bpSlot < MIN_BP) bpSlot = MIN_BP;
        if (bpSlot > MAX_BP) bpSlot = MAX_BP;

        if (old != bpSlot) {
            requestPreview();
        }
    }

    private void requestPreview() {
        BattlefieldNet.CH.sendToServer(new C2SRequestBackpackPreview(bpSlot));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xAA000000);

        int cx = this.width / 2;
        int y = this.height - 60;

        g.drawString(this.font, "Respawn Deploy UI (TEST)", cx - 80, 20, 0xFFFFFFFF, true);
        g.drawString(this.font, "Loadout: Backpack" + bpSlot, cx - 60, this.height - 88, 0xFFFFFFFF, false);
        g.drawString(this.font, "Spawn: BASE (test)", cx - 60, this.height - 105, 0xFFCCCCCC, false);
        g.drawString(this.font, "U to open, ESC to close", cx - 70, 40, 0xFFAAAAAA, false);

        super.render(g, mouseX, mouseY, partialTick);

        renderPreview(g, mouseX, mouseY, cx, y + 1);
    }

    private void renderPreview(GuiGraphics g, int mouseX, int mouseY, int centerX, int y) {
        List<ItemStack> preview = DeployPreviewClientState.getPreview(bpSlot);

        int totalWidth = PREVIEW_COUNT * SLOT_SIZE;
        int startX = centerX - totalWidth / 2;

        ItemStack hovered = ItemStack.EMPTY;

        for (int i = 0; i < PREVIEW_COUNT; i++) {
            int x = startX + i * SLOT_SIZE;

            // 槽位背景
            g.fill(x, y, x + 18, y + 18, 0x88444444);
            g.fill(x, y, x + 18, y + 1, 0x99AAAAAA);
            g.fill(x, y + 17, x + 18, y + 18, 0x99222222);
            g.fill(x, y, x + 1, y + 18, 0x99AAAAAA);
            g.fill(x + 17, y, x + 18, y + 18, 0x99222222);

            ItemStack stack = i < preview.size() ? preview.get(i) : ItemStack.EMPTY;

            if (!stack.isEmpty()) {
                g.renderItem(stack, x + 1, y + 1);
                g.renderItemDecorations(this.font, stack, x + 1, y + 1);
            }

            if (mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18 && !stack.isEmpty()) {
                hovered = stack;
            }
        }

        if (!hovered.isEmpty()) {
            g.renderTooltip(this.font, hovered, mouseX, mouseY);
        }

        if (preview.isEmpty()) {
            g.drawString(this.font, "No Preview", centerX - 22, y + 5, 0xFF888888, false);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            Minecraft.getInstance().setScreen(null);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_A) {
            changeBpSlot(-1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_D) {
            changeBpSlot(1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            BattlefieldNet.CH.sendToServer(new C2SDeployRequest(spawnIndex, bpSlot));
            Minecraft.getInstance().setScreen(null);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
