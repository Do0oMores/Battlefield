package top.mores.battlefield.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import top.mores.battlefield.game.BattlefieldAreaRules;
import top.mores.battlefield.net.S2CGameStatePacket;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BattlefieldHudOverlay {
    private BattlefieldHudOverlay() {
    }

    // 颜色：ARGB
    private static final int BLUE = 0xFF2E6BFF;
    private static final int RED = 0xFFFF3B30;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int WARNING_TEXT = 0xFFFF5555;
    private static final int HUD_SCORE_HIDE_TICKS = 15 * 20;

    // 方向判定的“死区”，避免抖动导致方向频繁翻转
    private static final float DIR_DEADZONE = 0.0035f;

    // 点位id -> 上一帧蓝色占比
    private static final Map<String, Float> LAST_BLUE_FRAC = new HashMap<>();
    // 点位id -> 上一次使用的方向（true=顺时针）
    private static final Map<String, Boolean> LAST_DIR_CLOCKWISE = new HashMap<>();

    public static void render(ForgeGui gui,
                              GuiGraphics g,
                              float partialTick,
                              int screenWidth,
                              int screenHeight) {

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (!ClientGameState.inBattle) {
            LAST_BLUE_FRAC.clear();
            LAST_DIR_CLOCKWISE.clear();
            return;
        }

        List<S2CGameStatePacket.PointInfo> pts = ClientGameState.points;
        if (pts == null || pts.isEmpty()) return;

        // 顶部位置
        int topY = 6;
        int centerX = screenWidth / 2;

        // 票数显示：永远显示进攻方兵力（防守方无限兵力不显示）
        String timer = formatRemainingTime(ClientGameState.remainingTimeTicks);
        String tickets = String.valueOf(ClientGameState.attackerTickets);
        String topLine = timer + "   " + tickets;
        int tw = mc.font.width(topLine);
        g.drawString(mc.font, topLine, centerX - tw / 2, topY, WHITE, true);

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
            if (p == null) continue;

            // key 保护（可选）
            String key = (p.id == null) ? ("#" + i) : p.id;

            int size = (i == activeIndex) ? (int) (baseSize * 2f) : baseSize;

            int cx = startX + i * (baseSize * 2 + gap) + baseSize;
            int cy = diamondsY + baseSize;

            // progress -> 攻方占比 0..1
            float attackersPct = (p.progress + 100) / 200f;
            attackersPct = Mth.clamp(attackersPct, 0f, 1f);

            // 蓝永远代表我方：我是攻方 -> 蓝=攻方占比；我是守方 -> 蓝=守方占比
            float blueFrac = (ClientGameState.myTeam == 0) ? attackersPct : (1f - attackersPct);
            blueFrac = Mth.clamp(blueFrac, 0f, 1f);

            // 用 blueFrac 的变化决定顺/逆时针
            float last = LAST_BLUE_FRAC.getOrDefault(key, blueFrac);
            float dBlue = blueFrac - last;
            LAST_BLUE_FRAC.put(key, blueFrac);

            boolean clockwise = LAST_DIR_CLOCKWISE.getOrDefault(key, true);
            if (dBlue > DIR_DEADZONE) {
                clockwise = true;
            } else if (dBlue < -DIR_DEADZONE) {
                clockwise = false;
            }
            LAST_DIR_CLOCKWISE.put(key, clockwise);

            // 画菱形（满蓝/满红/争夺扇形）
            if (blueFrac >= 0.999f) {
                LAST_BLUE_FRAC.remove(key);
                LAST_DIR_CLOCKWISE.remove(key);
                drawDiamondSolid(g, cx, cy, size, BLUE);
            } else if (blueFrac <= 0.001f) {
                LAST_BLUE_FRAC.remove(key);
                LAST_DIR_CLOCKWISE.remove(key);
                drawDiamondSolid(g, cx, cy, size, RED);
            } else {
                drawDiamondRadialFill(g, cx, cy, size, BLUE, RED, blueFrac, clockwise);
            }

            // 点位字母（居中）
            String label = (p.id == null) ? "?" : p.id;
            int lw = mc.font.width(label);
            g.drawString(mc.font, label, cx - lw / 2, cy - 4, WHITE, true);

            if (i == activeIndex) {
                int blueCount = (ClientGameState.myTeam == 0) ? p.attackersIn : p.defendersIn;
                int redCount = (ClientGameState.myTeam == 0) ? p.defendersIn : p.attackersIn;
                drawUnderBarOnly(g, cx, cy, size, blueCount, redCount);
            }
        }

        renderCenterScore(g, mc, screenWidth, screenHeight);
        renderSquadScore(g, mc, screenWidth);
        renderOutsideAreaWarning(g, mc, screenWidth, screenHeight);
        renderPhaseOverlay(g, mc, screenWidth, screenHeight);
    }

    private static void renderCenterScore(GuiGraphics g, Minecraft mc, int screenWidth, int screenHeight) {
        if (mc.player == null) return;

        int nowTick = mc.player.tickCount;

        // 中心HUD超时隐藏（你原逻辑）
        if (ClientGameState.hudLastScoreClientTick >= 0
                && nowTick - ClientGameState.hudLastScoreClientTick >= HUD_SCORE_HIDE_TICKS) {
            ClientGameState.hudScore = 0;
            ClientGameState.hudLastScoreClientTick = -1;
        }
        if (ClientGameState.hudLastScoreClientTick < 0) return;

        // 过期 toast 清理
        ClientGameState.pruneScoreToasts(nowTick);

        int baseY = screenHeight / 2 + 36;

        // ===== 总分：更大 =====
        String total = String.valueOf(ClientGameState.hudScore);
        float totalScale = 1.6f;
        int totalW = mc.font.width(total);
        float scaledTotalW = totalW * totalScale;

        g.pose().pushPose();
        g.pose().scale(totalScale, totalScale, 1f);
        float totalX = (screenWidth / 2f - scaledTotalW / 2f) / totalScale;
        float totalY = baseY / totalScale;
        g.drawString(mc.font, total, (int) totalX, (int) totalY, WHITE, true);
        g.pose().popPose();

        // ===== 多行 toast：从下往上或从上往下随你 =====
        float toastScale = 1.0f;
        int lineGap = 11;      // 行间距（未缩放前）
        int startY = baseY + 16;

        int idx = 0;
        for (ClientGameState.ScoreToast t : ClientGameState.SCORE_TOASTS) {
            // 计算淡出（最后 15 tick 线性淡出）
            int age = nowTick - t.startTick;
            int remain = ClientGameState.HUD_TOAST_HIDE_TICKS - age;
            float alpha = 1.0f;
            int fadeTicks = 15;
            if (remain < fadeTicks) alpha = Math.max(0f, remain / (float) fadeTicks);

            // ARGB 叠 alpha
            int a = (int) (((t.color >>> 24) & 0xFF) * alpha);
            int color = (a << 24) | (t.color & 0x00FFFFFF);

            String line = "+" + t.amount + " " + t.text;
            if (t.count > 1) line += " x" + t.count;

            int y = startY + idx * lineGap;

            int w = mc.font.width(line);
            float scaledW = w * toastScale;

            g.pose().pushPose();
            g.pose().scale(toastScale, toastScale, 1f);
            float x = (screenWidth / 2f - scaledW / 2f) / toastScale;
            float yf = y / toastScale;

            g.drawString(mc.font, line, (int) x, (int) yf, color, true);
            g.pose().popPose();

            idx++;
            if (idx >= ClientGameState.HUD_TOAST_MAX_LINES) break;
        }
    }

    private static void renderSquadScore(GuiGraphics g, Minecraft mc, int screenWidth) {
        boolean pDown = GLFW.glfwGetKey(mc.getWindow().getWindow(), GLFW.GLFW_KEY_P) == GLFW.GLFW_PRESS;
        int baseX = pDown ? 22 : 8;
        int y = 8;

        var ids = ClientGameState.squadPlayerIds;
        var scores = ClientGameState.squadPlayerScores;
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            g.drawString(mc.font, id, baseX, y, WHITE, true);

            if (i == 0) {
                g.drawString(mc.font, "总分:" + ClientGameState.squadTotalScore, baseX + mc.font.width(id) + 6, y, 0xFF66CCFF, true);
            }

            if (pDown && i < scores.size()) {
                g.drawString(mc.font, String.valueOf(scores.get(i)), baseX + 90, y, 0xFFFFFF66, true);
            }
            y += 11;
        }
    }

    private static String formatRemainingTime(int ticks) {
        int totalSec = Math.max(0, ticks / 20);
        int min = totalSec / 60;
        int sec = totalSec % 60;
        return String.format("%02d:%02d", min, sec);
    }

    private static void renderOutsideAreaWarning(GuiGraphics g, Minecraft mc, int screenWidth, int screenHeight) {
        if (!BattlefieldAreaRenderer.isOutsideMovableArea()) return;

        if (BattlefieldAreaRenderer.consumeOutsideAreaVoicePending()) {
            VoiceManager.play(ModSounds.VOICE_RETURN_AREA.get());
        }

        g.fill(0, 0, screenWidth, screenHeight, 0x88000000);

        String title = "您正在离开战斗区域";
        int remainingTicks = Math.max(0, BattlefieldAreaRules.OUTSIDE_AREA_KILL_TICKS
                - BattlefieldAreaRenderer.getOutsideAreaTicks());
        int seconds = Mth.ceil(remainingTicks / 20.0f);
        String countdown = seconds + " 秒";

        int titleW = mc.font.width(title);
        int cdW = mc.font.width(countdown);
        int cx = screenWidth / 2;
        int y = screenHeight / 2 - 12;

        g.drawString(mc.font, title, cx - titleW / 2, y, WARNING_TEXT, true);
        g.drawString(mc.font, countdown, cx - cdW / 2, y + 14, WHITE, true);
    }


    private static void renderPhaseOverlay(GuiGraphics g, Minecraft mc, int screenWidth, int screenHeight) {
        if (ClientGameState.phase != 1 && ClientGameState.phase != 3) return;

        g.fill(0, 0, screenWidth, screenHeight, 0x99000000);
        String title = ClientGameState.overlayTitle == null ? "" : ClientGameState.overlayTitle;
        String sub = ClientGameState.overlaySub == null ? "" : ClientGameState.overlaySub;
        int sec = Mth.ceil(Math.max(0, ClientGameState.overlayTicks) / 20.0f);

        int cx = screenWidth / 2;
        int y = screenHeight / 2 - 20;

        int tw = mc.font.width(title);
        g.drawString(mc.font, title, cx - tw / 2, y, WHITE, true);

        if (!sub.isBlank()) {
            int sw = mc.font.width(sub);
            g.drawString(mc.font, sub, cx - sw / 2, y + 12, 0xFFCCCCCC, true);
        }

        String cd = sec + " 秒";
        int cw = mc.font.width(cd);
        g.drawString(mc.font, cd, cx - cw / 2, y + 24, WHITE, true);
    }

    /**
     * 返回玩家当前所在点（如多个点重叠，取最近的）
     * ✅ 改为水平距离判定（dx/dz），避免 y 高度导致误判
     */
    private static int findActivePointIndex(Vec3 playerPos, List<S2CGameStatePacket.PointInfo> pts) {
        int best = -1;
        double bestDist2 = Double.MAX_VALUE;

        for (int i = 0; i < pts.size(); i++) {
            var p = pts.get(i);
            if (p == null) continue;

            double dx = playerPos.x - p.x;
            double dz = playerPos.z - p.z;
            double dist2 = dx * dx + dz * dz;

            double r2 = (double) p.radius * (double) p.radius;
            if (dist2 <= r2 && dist2 < bestDist2) {
                bestDist2 = dist2;
                best = i;
            }
        }
        return best;
    }

    /**
     * 实心菱形
     */
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

                int color = (frac <= blueFrac) ? blueColor : redColor;
                g.fill(x, y, x + 1, y + 1, color);
            }
        }
    }

    private static void drawUnderBarOnly(GuiGraphics g, int cx, int cy, int size, int blueCount, int redCount) {
        int barW = size * 2;
        int barH = 2;
        int barX = cx - barW / 2;
        int barY = cy + size + 3;

        int total = blueCount + redCount;
        int blueW = (total <= 0) ? 0 : Mth.floor((blueCount / (float) total) * barW);

        // 背景
        g.fill(barX, barY, barX + barW, barY + barH, 0xAA000000);

        // 蓝段 / 红段
        if (blueW > 0) g.fill(barX, barY, barX + blueW, barY + barH, BLUE);
        if (blueW < barW) g.fill(barX + blueW, barY, barX + barW, barY + barH, RED);
    }
}
