package top.mores.battlefield.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import top.mores.battlefield.net.S2CGameStatePacket;

import java.util.List;

public final class BattlefieldHudOverlay {
    private BattlefieldHudOverlay(){}

    // 颜色：ARGB
    private static final int BLUE  = 0xFF2E6BFF;
    private static final int RED   = 0xFFFF3B30;
    private static final int WHITE = 0xFFFFFFFF;

    public static void render(ForgeGui gui,
                              GuiGraphics g,
                              float partialTick,
                              int screenWidth,
                              int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        List<S2CGameStatePacket.PointInfo> pts = ClientGameState.points;
        if (pts == null || pts.isEmpty()) return;

        // 顶部位置
        int topY = 6;
        int centerX = screenWidth / 2;

        // 票数显示（仅数字）
        String tickets = String.valueOf(ClientGameState.attackerTickets);
        int tw = mc.font.width(tickets);
        g.drawString(mc.font, tickets, centerX - tw / 2, topY, WHITE, true);

        // 菱形排布
        int diamondsY = topY + 14;
        int baseSize = 10;     // 菱形“半径”
        int gap = 10;          // 点位间距
        int count = pts.size();
        int totalW = count * (baseSize * 2) + (count - 1) * gap;
        int startX = centerX - totalW / 2;

        // 玩家正在占据/争夺的点：站在点内就放大（选最近的）
        int activeIndex = findActivePointIndex(player.position(), pts);

        for (int i = 0; i < count; i++) {
            S2CGameStatePacket.PointInfo p = pts.get(i);

            int size = baseSize;
            if (i == activeIndex) size = (int)(baseSize * 1.25);

            int cx = startX + i * (baseSize * 2 + gap) + baseSize;
            int cy = diamondsY + baseSize;

            // 进度 -> 攻方占比 0..1
            float attackersPct = (p.progress + 100) / 200f;
            attackersPct = Mth.clamp(attackersPct, 0f, 1f);

            // 我方蓝：攻方(0)则蓝=攻方；守方(1)则蓝=守方
            float blueFrac = (ClientGameState.myTeam == 0) ? attackersPct : (1f - attackersPct);
            blueFrac = Mth.clamp(blueFrac, 0f, 1f);

            // 本次进度变化量（用于判断顺/逆时针）
            int dp = ClientGameState.deltaProgressById.getOrDefault(p.id, 0);

            // 本次是否“朝蓝方方向变化”
            boolean towardBlue;
            if (ClientGameState.myTeam == 0) {          // 我是攻方：progress 增大 => 更蓝
                towardBlue = dp > 0;
            } else if (ClientGameState.myTeam == 1) {   // 我是守方：progress 减小 => 更蓝
                towardBlue = dp < 0;
            } else {                                     // 观战：不重要
                towardBlue = dp != 0;
            }

            // 反推恢复：蓝方正在变强，但目前蓝方仍落后(<50%) => 逆时针恢复
            boolean counterClockwiseRecover = towardBlue && (blueFrac < 0.5f);
            boolean clockwise = !counterClockwiseRecover;

            // 画菱形（满蓝/满红/争夺扇形）
            if (blueFrac >= 0.999f) {
                drawDiamondSolid(g, cx, cy, size, BLUE);
            } else if (blueFrac <= 0.001f) {
                drawDiamondSolid(g, cx, cy, size, RED);
            } else {
                drawDiamondRadialFill(g, cx, cy, size, BLUE, RED, blueFrac, clockwise);
            }

            // 点位字母（居中）
            int lw = mc.font.width(p.id);
            g.drawString(mc.font, p.id, cx - lw / 2, cy - 4, WHITE, true);
            g.drawString(
                    mc.font,
                    String.format("%d", p.progress),
                    cx - 6, cy + 6,
                    0xFFFFFFAA,
                    true
            );

            // 点内人数条 + 彩色 “4:1”（我方=蓝、敌方=红）
            int blueCount = (ClientGameState.myTeam == 0) ? p.attackersIn : p.defendersIn;
            int redCount  = (ClientGameState.myTeam == 0) ? p.defendersIn : p.attackersIn;
            drawUnderBarAndCount(g, mc, cx, cy, size, blueCount, redCount);
        }
    }

    /** 返回玩家当前所在点（如多个点重叠，取最近的） */
    private static int findActivePointIndex(Vec3 playerPos, List<S2CGameStatePacket.PointInfo> pts) {
        int best = -1;
        double bestDist2 = Double.MAX_VALUE;
        for (int i = 0; i < pts.size(); i++) {
            var p = pts.get(i);
            double dx = playerPos.x - p.x;
            double dy = playerPos.y - p.y;
            double dz = playerPos.z - p.z;
            double dist2 = dx*dx + dy*dy + dz*dz;
            double r2 = (double)p.radius * (double)p.radius;
            if (dist2 <= r2 && dist2 < bestDist2) {
                bestDist2 = dist2;
                best = i;
            }
        }
        return best;
    }

    /** 实心菱形 */
    private static void drawDiamondSolid(GuiGraphics g, int cx, int cy, int size, int color) {
        for (int dy = -size; dy <= size; dy++) {
            int half = size - Math.abs(dy);
            int x1 = cx - half;
            int x2 = cx + half + 1;
            int y = cy + dy;
            g.fill(x1, y, x2, y + 1, color);
        }
    }

    /**
     * 扇形填充菱形：
     * - blueFrac: 0..1 (蓝色扇形占比)
     * - clockwise=true：蓝色顺时针扩张
     * - clockwise=false：蓝色逆时针扩张（反推恢复）
     */
    private static void drawDiamondRadialFill(GuiGraphics g, int cx, int cy, int size,
                                              int blueColor, int redColor, float blueFrac,
                                              boolean clockwise) {
        blueFrac = Mth.clamp(blueFrac, 0f, 1f);

        for (int dy = -size; dy <= size; dy++) {
            int half = size - Math.abs(dy);
            int y = cy + dy;

            for (int dx = -half; dx <= half; dx++) {
                int x = cx + dx;

                // 以 12 点方向为起点，顺时针得到 [0,1)
                double ang = Math.atan2(dx, -dy); // [-pi, pi]
                double frac = ang / (Math.PI * 2.0);
                if (frac < 0) frac += 1.0;

                // 逆时针：镜像翻转
                if (!clockwise) {
                    frac = 1.0 - frac;
                    if (frac >= 1.0) frac -= 1.0;
                }

                int color = (frac <= blueFrac) ? blueColor : redColor;
                g.fill(x, y, x + 1, y + 1, color);
            }
        }
    }

    /** 菱形下方的小进度条 + “蓝:红” 彩色数字 */
    private static void drawUnderBarAndCount(GuiGraphics g, Minecraft mc, int cx, int cy, int size, int blueCount, int redCount) {
        int barW = size * 2;
        int barH = 2;
        int barX = cx - barW / 2;
        int barY = cy + size + 3;

        int total = blueCount + redCount;
        int blueW = (total <= 0) ? 0 : Mth.floor((blueCount / (float)total) * barW);

        // 背景
        g.fill(barX, barY, barX + barW, barY + barH, 0xAA000000);

        // 蓝段 / 红段
        if (blueW > 0) g.fill(barX, barY, barX + blueW, barY + barH, BLUE);
        if (blueW < barW) g.fill(barX + blueW, barY, barX + barW, barY + barH, RED);

        // 彩色 “4:1”
        String left = String.valueOf(blueCount);
        String mid = ":";
        String right = String.valueOf(redCount);

        int yText = barY + 3;
        int wLeft = mc.font.width(left);
        int wMid  = mc.font.width(mid);
        int wRight= mc.font.width(right);
        int totalW = wLeft + wMid + wRight;

        int x = cx - totalW / 2;
        g.drawString(mc.font, left,  x,yText, BLUE, true);
        g.drawString(mc.font, mid,   x + wLeft,        yText, WHITE, true);
        g.drawString(mc.font, right, x + wLeft + wMid, yText, RED, true);
    }
}