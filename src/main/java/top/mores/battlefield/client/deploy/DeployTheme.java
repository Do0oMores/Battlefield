package top.mores.battlefield.client.deploy;

import net.minecraft.client.gui.GuiGraphics;
import top.mores.battlefield.deploy.DeploymentOption;

/**
 * 部署界面的配色与小部件绘制。
 * <p>
 * 只放"怎么画"，不放"画什么"。界面上所有文案和状态都来自服务端同步的数据。
 */
public final class DeployTheme {

    private DeployTheme() {
    }

    public static final int SCRIM = 0xC8060A08;
    public static final int PANEL_BG = 0xE00B120E;
    public static final int PANEL_BG_SOFT = 0xB3121B15;
    public static final int PANEL_HEAD = 0xFF101A14;
    public static final int BORDER = 0xFF2A3A31;
    public static final int BORDER_BRIGHT = 0xFF4A6656;

    public static final int TEXT = 0xFFE6EFE9;
    public static final int TEXT_MUTED = 0xFF93A69A;
    public static final int TEXT_DIM = 0xFF5F7167;

    public static final int ACCENT = 0xFF74E3A2;
    public static final int WARN = 0xFFFFC24B;
    public static final int DANGER = 0xFFFF6B5E;
    public static final int ATTACKER = 0xFFE0603C;
    public static final int DEFENDER = 0xFF4A90D9;

    public static final int BTN_BG = 0xD0121B16;
    public static final int BTN_BG_HOVER = 0xE01B2A21;
    public static final int BTN_BG_DISABLED = 0x600E1410;

    /** 阵营色：0=攻方 1=守方 其他=灰。 */
    public static int teamColor(byte team) {
        if (team == 0) return ATTACKER;
        if (team == 1) return DEFENDER;
        return TEXT_MUTED;
    }

    public static int stateColor(DeploymentOption.State state) {
        return switch (state) {
            case AVAILABLE -> ACCENT;
            case CONTESTED -> WARN;
            case UNAVAILABLE -> DANGER;
        };
    }

    public static String stateLabel(DeploymentOption.State state) {
        return switch (state) {
            case AVAILABLE -> "可部署";
            case CONTESTED -> "交战中";
            case UNAVAILABLE -> "不可用";
        };
    }

    /** 半透明面板：底色 + 1px 描边 + 四角高亮。 */
    public static void panel(GuiGraphics g, int x1, int y1, int x2, int y2) {
        g.fill(x1, y1, x2, y2, PANEL_BG);
        frame(g, x1, y1, x2, y2, BORDER);
    }

    public static void frame(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        g.fill(x1, y1, x2, y1 + 1, color);
        g.fill(x1, y2 - 1, x2, y2, color);
        g.fill(x1, y1, x1 + 1, y2, color);
        g.fill(x2 - 1, y1, x2, y2, color);
    }

    /** 四角括号，战术终端的关键视觉符号。 */
    public static void corners(GuiGraphics g, int x1, int y1, int x2, int y2, int color, int len) {
        g.fill(x1, y1, x1 + len, y1 + 1, color);
        g.fill(x1, y1, x1 + 1, y1 + len, color);

        g.fill(x2 - len, y1, x2, y1 + 1, color);
        g.fill(x2 - 1, y1, x2, y1 + len, color);

        g.fill(x1, y2 - 1, x1 + len, y2, color);
        g.fill(x1, y2 - len, x1 + 1, y2, color);

        g.fill(x2 - len, y2 - 1, x2, y2, color);
        g.fill(x2 - 1, y2 - len, x2, y2, color);
    }

    /** 区块标题：左侧竖条 + 文本。 */
    public static void sectionTitle(GuiGraphics g, net.minecraft.client.gui.Font font, String text, int x, int y, int color) {
        g.fill(x, y, x + 2, y + 9, color);
        g.drawString(font, text, x + 6, y, color, false);
    }

    public static void diamond(GuiGraphics g, int cx, int cy, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int half = radius - Math.abs(dy);
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
    }

    public static void diamondOutline(GuiGraphics g, int cx, int cy, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int half = radius - Math.abs(dy);
            g.fill(cx - half, cy + dy, cx - half + 1, cy + dy + 1, color);
            g.fill(cx + half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
    }

    public static void squareOutline(GuiGraphics g, int cx, int cy, int radius, int color) {
        int x1 = cx - radius, y1 = cy - radius, x2 = cx + radius + 1, y2 = cy + radius + 1;
        frame(g, x1, y1, x2, y2, color);
    }

    /** 实心按钮的四种状态。 */
    public static void button(GuiGraphics g, net.minecraft.client.gui.Font font, int x1, int y1, int x2, int y2,
                              String label, int accent, boolean enabled, boolean hovered, boolean pressed) {
        int bg;
        if (!enabled) {
            bg = BTN_BG_DISABLED;
        } else if (pressed) {
            bg = blend(accent, 0xFF000000, 0.55f);
        } else if (hovered) {
            bg = BTN_BG_HOVER;
        } else {
            bg = BTN_BG;
        }
        g.fill(x1, y1, x2, y2, bg);
        frame(g, x1, y1, x2, y2, enabled ? (hovered || pressed ? accent : BORDER_BRIGHT) : BORDER);
        if (enabled && (hovered || pressed)) {
            corners(g, x1, y1, x2, y2, accent, 4);
        }

        int color = enabled ? (pressed ? 0xFF0B120E : accent) : TEXT_DIM;
        int tx = x1 + (x2 - x1 - font.width(label)) / 2;
        int ty = y1 + (y2 - y1 - 8) / 2;
        g.drawString(font, label, tx, ty, color, false);
    }

    /** 小标签（状态胶囊）。 */
    public static void chip(GuiGraphics g, net.minecraft.client.gui.Font font, String text, int x, int y, int color) {
        int w = font.width(text) + 8;
        g.fill(x, y, x + w, y + 11, blend(color, 0xFF000000, 0.72f));
        frame(g, x, y, x + w, y + 11, blend(color, 0xFF000000, 0.35f));
        g.drawString(font, text, x + 4, y + 2, color, false);
    }

    public static int blend(int color, int other, float ratio) {
        float r = 1.0f - ratio;
        int a = (int) (((color >> 24) & 0xFF) * r + ((other >> 24) & 0xFF) * ratio);
        int cr = (int) (((color >> 16) & 0xFF) * r + ((other >> 16) & 0xFF) * ratio);
        int cg = (int) (((color >> 8) & 0xFF) * r + ((other >> 8) & 0xFF) * ratio);
        int cb = (int) ((color & 0xFF) * r + (other & 0xFF) * ratio);
        return (a << 24) | (cr << 16) | (cg << 8) | cb;
    }

    public static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    /** 按像素宽度截断文本，超长补省略号。窄屏/长中文名不会再画出面板外。 */
    public static String trim(net.minecraft.client.gui.Font font, String text, int maxWidth) {
        if (text == null) return "";
        if (font.width(text) <= maxWidth) return text;
        String ellipsis = "…";
        int limit = maxWidth - font.width(ellipsis);
        if (limit <= 0) return ellipsis;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (font.width(sb.toString() + text.charAt(i)) > limit) break;
            sb.append(text.charAt(i));
        }
        return sb + ellipsis;
    }
}
