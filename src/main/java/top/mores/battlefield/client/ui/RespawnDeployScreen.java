package top.mores.battlefield.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import top.mores.battlefield.net.BattlefieldNet;
import top.mores.battlefield.net.C2SDeployRequest;

public class RespawnDeployScreen extends Screen {

    private int bpSlot = 1;      // 默认 Backpack1
    private int spawnIndex = 0;  // 测试：0=基地

    private Button deployBtn;
    private Button bpMinus;
    private Button bpPlus;
    public RespawnDeployScreen() {
        super(Component.literal("重新部署"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height - 60;

        bpMinus = Button.builder(Component.literal("<"), b -> {
            bpSlot = Math.max(1, bpSlot - 1);
        }).bounds(cx - 90, y, 20, 20).build();

        bpPlus = Button.builder(Component.literal(">"), b -> {
            bpSlot = Math.min(9, bpSlot + 1); // 上限按你想要的背包数
        }).bounds(cx + 70, y, 20, 20).build();

        deployBtn = Button.builder(Component.literal("DEPLOY"), b -> {
            BattlefieldNet.CH.sendToServer(new C2SDeployRequest(spawnIndex, bpSlot));
            Minecraft.getInstance().setScreen(null);
        }).bounds(cx - 50, y + 26, 100, 20).build();

        this.addRenderableWidget(bpMinus);
        this.addRenderableWidget(bpPlus);
        this.addRenderableWidget(deployBtn);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xAA000000);

        int cx = this.width / 2;

        g.drawString(this.font, "Respawn Deploy UI (TEST)", cx - 80, 20, 0xFFFFFFFF, true);

        // 背包槽位显示
        g.drawString(this.font, "Loadout: Backpack" + bpSlot, cx - 60, this.height - 88, 0xFFFFFFFF, false);

        // 出生点显示（测试）
        g.drawString(this.font, "Spawn: BASE (test)", cx - 60, this.height - 105, 0xFFCCCCCC, false);

        g.drawString(this.font, "U to open, ESC to close", cx - 70, 40, 0xFFAAAAAA, false);

        super.render(g, mouseX, mouseY, partialTick);

    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            Minecraft.getInstance().setScreen(null);
            return true;
        }
        // 快捷键：A/D 或 ←/→ 切换背包
        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_A) {
            bpSlot = Math.max(1, bpSlot - 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_D) {
            bpSlot = Math.min(9, bpSlot + 1);
            return true;
        }
        // Enter 直接部署
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
