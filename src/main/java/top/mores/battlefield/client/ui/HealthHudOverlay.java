package top.mores.battlefield.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.client.ClientGameState;
import top.mores.battlefield.config.BattlefieldServerConfig;

/**
 * 对局内的血条。
 * <p>
 * 100 血不能继续用原版心形：20 血 = 10 颗心，100 血就是 50 颗心，铺满两行屏宽还读不出精确值。
 * 所以这里直接画一根横条 + 一个当前血量数字（如 {@code 87}），并且把原版的 {@code PLAYER_HEALTH} 与
 * {@code FOOD_LEVEL} 覆盖层取消掉 —— 饥饿值在对局里被钉死在 17（见 {@code keepPlayerFed}），
 * 画出来只会让人以为要吃食物。
 * <p>
 * 位置在<b>经验条正上方</b>（原版心形那一行，{@code screenHeight - 39}），与快捷栏、经验条一起居中。
 * 放最左下角会被聊天栏从头到脚盖住，而战场里没人有空去翻聊天记录。
 * <p>
 * 血条分两段：白色是实血，淡红是「本次脱战后能回来的部分」（到 {@code regenMaxHealthFraction} 为止），
 * 深色底是既没有血、也回不来的部分。玩家因此能一眼看出「现在是该躲，还是该打」。
 * <p>
 * <b>显示值不是直接读血量</b>：服务端把回血拆成 0.1 秒 1 点，客户端再按帧向真实值收敛，
 * 数字才会一位一位往上跳、血条连续爬起来。两条规则不对称 ——
 * <b>涨的时候慢慢追，掉的时候立刻对齐</b>：受伤必须第一帧就见底，不然"被打掉 20 血却像在缓慢流失"。
 */
@Mod.EventBusSubscriber(modid = Battlefield.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HealthHudOverlay {
    private static final int BAR_WIDTH = 120;
    private static final int BAR_HEIGHT = 5;
    /** 原版心形所在行：{@code screenHeight - 39}，正好压在经验条（{@code screenHeight - 29}）上方。 */
    private static final int BAR_ROW_OFFSET = 39;
    private static final int LABEL_GAP = 6;

    private static final int COLOR_BACKDROP = 0xB4000000;
    private static final int COLOR_OUTLINE = 0x66FFFFFF;
    private static final int COLOR_HEALTH = 0xFFF2F2F2;
    /** 淡红：脱战后可以补回来的那一段。 */
    private static final int COLOR_RECOVERABLE = 0xFFFF9E9E;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_HEALTHY = 0xFFBFE6FF;

    /** 追赶速度：每秒收敛约 90%。再快就回到"整段跳"，再慢数字会明显落后于真实血量。 */
    private static final float CATCH_UP_PER_SECOND = 14.0F;
    /** 与真实值相差不到这么多就直接对齐，避免血条永远差零点几像素。 */
    private static final float SNAP_EPSILON = 0.02F;

    /** 当前屏幕上画的血量。-1 表示还没初始化（进对局 / 换人 / 复活后重新对齐）。 */
    private static float displayedHealth = -1.0F;
    private static long lastFrameNanos;

    private HealthHudOverlay() {
    }

    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.options.hideGui) return;
        if (!ClientGameState.inBattle) {
            resetDisplay();
            return;
        }
        if (player.isCreative() || player.isSpectator() || !player.isAlive()) return;

        float maxHealth = player.getMaxHealth();
        if (maxHealth <= 0.0F) return;

        float health = Mth.clamp(player.getHealth(), 0.0F, maxHealth);
        float shown = smoothHealth(health, maxHealth);
        float recoverable = Mth.clamp(maxHealth * regenCapFraction(), shown, maxHealth);

        // 宽度仍按「满血时的数字」预留：数字只显示当前血量后，9→10→100 位数会变，
        // 按实时宽度排版会让整组（血条 + 数字 + 医疗十字 + 储备）左右抖动
        int labelWidth = mc.font.width(Integer.toString(Math.round(maxHealth)));
        int groupWidth = BAR_WIDTH + LABEL_GAP + labelWidth + 29;
        int barX = Math.max(2, (screenWidth - groupWidth) / 2);
        int barY = screenHeight - BAR_ROW_OFFSET;
        int barRight = barX + BAR_WIDTH;

        g.fill(barX - 1, barY - 1, barRight + 1, barY + BAR_HEIGHT + 1, COLOR_OUTLINE);
        g.fill(barX, barY, barRight, barY + BAR_HEIGHT, COLOR_BACKDROP);

        int healthPixels = Mth.ceil(shown / maxHealth * BAR_WIDTH);
        int recoverablePixels = Mth.ceil(recoverable / maxHealth * BAR_WIDTH);
        if (recoverablePixels > healthPixels) {
            g.fill(barX + healthPixels, barY, barX + recoverablePixels, barY + BAR_HEIGHT, COLOR_RECOVERABLE);
        }
        if (healthPixels > 0) {
            g.fill(barX, barY, barX + healthPixels, barY + BAR_HEIGHT, COLOR_HEALTH);
        }

        // 数字跟着显示值走，而不是真实值：否则会先看到 "45" 再看到血条爬过去
        String label = healthLabel(shown);
        int textColor = health >= maxHealth - 0.5F ? COLOR_TEXT_HEALTHY : COLOR_TEXT;
        g.drawString(mc.font, label, barRight + LABEL_GAP, barY - 2, textColor, true);
        if (!top.mores.battlefield.client.MedicalClient.deployed()) return;
        int iconX = barRight + LABEL_GAP + labelWidth + 6;
        int iconY = barY - 1;
        // Transparent, equal-arm 7x7 medical cross, not a font '+' glyph.
        g.fill(iconX+2,iconY,iconX+5,iconY+7,0xFFF2F2F2);
        g.fill(iconX,iconY+2,iconX+7,iconY+5,0xFFF2F2F2);
        if (top.mores.battlefield.client.MedicalClient.medic()) {
            // Pixel infinity fallback, independent of the active resource-pack font.
            String[] pixels = {"01100110","10011001","10011001","01100110"};
            for (int row=0; row<pixels.length; row++) for (int col=0; col<8; col++)
                if (pixels[row].charAt(col)=='1') g.fill(iconX+11+col,iconY+1+row,iconX+12+col,iconY+2+row,COLOR_TEXT);
        } else g.drawString(mc.font,Integer.toString(top.mores.battlefield.client.MedicalClient.reserve()),iconX+11,barY-2,COLOR_TEXT,true);
    }

    /**
     * 把「服务端下发的一次 1 点」抹成连续上涨。
     * <p>
     * 三条规则：
     * <ul>
     *   <li>掉血 <b>立刻</b>对齐（受伤、出界、被炸都算），血条不许慢慢往下淌；</li>
     *   <li>涨血按帧收敛：服务端 0.1 秒发 1 点，客户端每帧补一点，看起来就是数字逐位跳动；</li>
     *   <li>差值过大（复活满血、更换实体）直接跳过去，不做动画。</li>
     * </ul>
     */
    private static float smoothHealth(float actual, float maxHealth) {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 0.016F : (now - lastFrameNanos) / 1.0E9F;
        lastFrameNanos = now;
        dt = Mth.clamp(dt, 0.001F, 0.1F);

        boolean reset = displayedHealth < 0.0F
                || actual < displayedHealth                       // 掉血：立刻对齐
                || actual - displayedHealth > maxHealth * 0.25F;  // 复活/换人：直接跳
        if (reset) {
            displayedHealth = actual;
            return displayedHealth;
        }

        displayedHealth += (actual - displayedHealth) * Mth.clamp(dt * CATCH_UP_PER_SECOND, 0.0F, 1.0F);
        if (actual - displayedHealth < SNAP_EPSILON) {
            displayedHealth = actual;
        }
        return displayedHealth;
    }

    /**
     * 血条右侧的数字：<b>只显示当前血量</b>，不带 {@code /100} 后缀。
     * <p>
     * 满值在对局里固定是 100，写出来只是噪音；比例已经由血条长度表达，数字只负责给精确值。
     * 抽成独立方法是为了让取整规则能被单元测试钉住（39.6 → "40"，不是 "39.6" 也不是 "39"）——
     * 整个 HUD 只有这一处产生文字。
     */
    static String healthLabel(float shown) {
        return Integer.toString(Math.round(shown));
    }

    private static void resetDisplay() {
        displayedHealth = -1.0F;
        lastFrameNanos = 0L;
    }

    /**
     * 取消原版心形与饥饿条。
     * <p>
     * 只在「已进入对局」时取消：大厅里还是原版 20 血，玩家需要那 10 颗心。
     * 创造/旁观本来就不画这两条，交回原版也不会出错。
     */
    @SubscribeEvent
    public static void onRenderGuiOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (!ClientGameState.inBattle) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        ResourceLocation id = event.getOverlay().id();
        if (VanillaGuiOverlay.PLAYER_HEALTH.id().equals(id) || VanillaGuiOverlay.FOOD_LEVEL.id().equals(id)) {
            event.setCanceled(true);
        }
    }

    /** 本档位允许回复到的百分比。配置被改坏时退回 1.0（回满）。 */
    private static float regenCapFraction() {
        double fraction = BattlefieldServerConfig.get().combat.maxHealthFraction;
        if (Double.isNaN(fraction)) return 1.0F;
        return (float) Mth.clamp(fraction, 0.0, 1.0);
    }
}
