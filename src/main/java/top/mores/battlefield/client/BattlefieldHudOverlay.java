package top.mores.battlefield.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import top.mores.battlefield.net.S2CGameStatePacket;

import java.util.List;

public final class BattlefieldHudOverlay {
    private BattlefieldHudOverlay(){}

    // 颜色：ARGB
    private static final int BLUE = 0xFF2E6BFF;
    private static final int RED  = 0xFFFF3B30;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int TEXT_SHADOW = 0xAA000000;

    public static void render(GuiGraphics g, float partialTick, int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        List<S2CGameStatePacket.PointInfo> pts = ClientGameState.points;
        if (pts == null || pts.isEmpty()) return;

        // 顶部位置
        int topY = 6;
        int centerX = screenWidth / 2;

        // 票数显示（仅数字也可以）
        String tickets = String.valueOf(ClientGameState.attackerTickets);
        int tw = mc.font.width(tickets);
        g.drawString(mc.font, tickets, centerX - tw / 2, topY, WHITE, true);

        // 菱形排布
        int diamondsY = topY + 14;
        int baseSize = 10;     // 菱形“半径”（越大越大）
        int gap = 10;          // 点位间距
        int count = pts.size();
        int totalW = count * (baseSize * 2) + (count - 1) * gap;
        int startX = centerX - totalW / 2;

        boolean blueIsAttackers = true;

        // 玩家正在占据/争夺的点：客户端用点坐标半径判断
        int activeIndex = findActivePointIndex(player.position(), pts);

        for (int i = 0; i < count; i++) {
            S2CGameStatePacket.PointInfo p = pts.get(i);

            int size = baseSize;
            if (i == activeIndex) size = (int)(baseSize * 1.25);

            int cx = startX + i * (baseSize * 2 + gap) + baseSize;
            int cy = diamondsY + baseSize;

            float attackersPct = (p.progress + 100) / 200f; // 0..1
            attackersPct = Mth.clamp(attackersPct, 0f, 1f);

            float bluePct = blueIsAttackers ? attackersPct : (1f - attackersPct);

            // 完全控制：纯色；争夺：红蓝占比
            boolean fullBlue = bluePct >= 0.999f;
            boolean fullRed  = bluePct <= 0.001f;

            if (fullBlue) {
                drawDiamondSolid(g, cx, cy, size, BLUE);
            } else if (fullRed) {
                drawDiamondSolid(g, cx, cy, size, RED);
            } else {
                drawDiamondSplit(g, cx, cy, size, BLUE, RED, bluePct);
            }

            // 点位字母
            int lw = mc.font.width(p.id);
            g.drawString(mc.font, p.id, cx - lw / 2, cy - 4, WHITE, true);
        }
    }

    private static int findActivePointIndex(Vec3 playerPos, List<S2CGameStatePacket.PointInfo> pts) {
        for (int i = 0; i < pts.size(); i++) {
            var p = pts.get(i);
            double dx = playerPos.x - p.x;
            double dy = playerPos.y - p.y;
            double dz = playerPos.z - p.z;
            if (dx*dx + dy*dy + dz*dz <= (double)p.radius * (double)p.radius) {
                return i;
            }
        }
        return -1;
    }

    // 用扫描线画实心菱形
    private static void drawDiamondSolid(GuiGraphics g, int cx, int cy, int size, int color) {
        for (int dy = -size; dy <= size; dy++) {
            int half = size - Math.abs(dy);
            int x1 = cx - half;
            int x2 = cx + half + 1;
            int y = cy + dy;
            g.fill(x1, y, x2, y + 1, color);
        }
    }

    // 扫描线菱形：按 bluePct 把每一行分成蓝/红两段
    private static void drawDiamondSplit(GuiGraphics g, int cx, int cy, int size, int blueColor, int redColor, float bluePct) {
        bluePct = Mth.clamp(bluePct, 0f, 1f);
        for (int dy = -size; dy <= size; dy++) {
            int half = size - Math.abs(dy);
            int width = half * 2 + 1;
            int blueW = Mth.floor(width * bluePct);

            int xLeft = cx - half;
            int y = cy + dy;

            // 蓝段
            if (blueW > 0) {
                g.fill(xLeft, y, xLeft + blueW, y + 1, blueColor);
            }
            // 红段
            if (blueW < width) {
                g.fill(xLeft + blueW, y, xLeft + width, y + 1, redColor);
            }
        }
    }
}
