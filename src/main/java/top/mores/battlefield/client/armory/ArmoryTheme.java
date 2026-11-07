package top.mores.battlefield.client.armory;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public final class ArmoryTheme {
    static final int BACKGROUND = 0xFF171C20, PANEL = 0xFF242B30, SECONDARY = 0xFF30383E;
    static final int TEXT = 0xFFE5E6E2, MUTED = 0xFFA4ADB1, GOLD = 0xFFC6A45B;
    static final int GREEN = 0xFF77A88B, WARN = 0xFFD0A05B, RED = 0xFFB65A52;
    private ArmoryTheme() {}

    public static Component label(String key, Object... args) {
        return Component.translatable("battlefield.armory." + key, args);
    }

    public static Component status(String code) {
        return Component.translatableWithFallback("battlefield.armory.status." + code,
                label("status.UNKNOWN").getString());
    }

    public static void text(GuiGraphics g, Font font, Component label, int x, int y, int width, int color) {
        if (width > 0) g.drawString(font, net.minecraft.locale.Language.getInstance().getVisualOrder(font.substrByWidth(label, width)), x, y, color, false);
    }

    static int statusColor(String code) {
        return switch (code) {
            case "OK", "SAVED" -> GREEN;
            case "LOADING", "SAVING", "LOCKED", "COMMITTED_REFRESH" -> WARN;
            default -> RED;
        };
    }
}
