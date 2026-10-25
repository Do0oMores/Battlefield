package top.mores.battlefield.client.deploy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import top.mores.battlefield.deploy.DeploymentData;
import top.mores.battlefield.deploy.DeploymentOption;
import top.mores.battlefield.net.S2CGameStatePacket;
import top.mores.battlefield.net.S2CSectorAreaPacket;

import java.util.List;

/**
 * 中央战区图。
 * <p>
 * 数据全部来自服务端：据点坐标与进度来自 {@code S2CGameStatePacket}，可活动区域来自
 * {@code S2CSectorAreaPacket}，部署点来自 {@code S2CDeployDataPacket}。工程里没有底图资源，
 * 因此这里画的是「按真实坐标投影的战术示意图」，不是伪造的卫星图——界面上也会明确标注这一点。
 * <p>
 * 换成真正的底图时只需要在这里加一次 blit：投影、命中判定、标记绘制都不用动。
 */
public final class DeployMapView {

    private int x;
    private int y;
    private int w;
    private int h;

    private DeploymentData data = DeploymentData.CLOSED;
    private List<S2CGameStatePacket.PointInfo> points = List.of();
    private List<S2CSectorAreaPacket.AreaRect> friendlyAreas = List.of();
    private List<S2CSectorAreaPacket.AreaRect> enemyAreas = List.of();
    private String selectedId = "";

    /** 服务端烘焙的底图；为 null 时只画坐标网格。 */
    @Nullable
    private DeployMapTexture.View background;

    private DeployMapProjection projection;

    public void layout(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        rebuild();
    }

    public void setBackground(@Nullable DeployMapTexture.View background) {
        this.background = background;
        rebuild();
    }

    public void update(DeploymentData data,
                       List<S2CGameStatePacket.PointInfo> points,
                       List<S2CSectorAreaPacket.AreaRect> friendlyAreas,
                       List<S2CSectorAreaPacket.AreaRect> enemyAreas) {
        this.data = data == null ? DeploymentData.CLOSED : data;
        this.points = points == null ? List.of() : points;
        this.friendlyAreas = friendlyAreas == null ? List.of() : friendlyAreas;
        this.enemyAreas = enemyAreas == null ? List.of() : enemyAreas;
        rebuild();
    }

    public void setSelectedId(String selectedId) {
        this.selectedId = selectedId == null ? "" : selectedId;
    }

    public DeploymentData data() {
        return data;
    }

    private void rebuild() {
        if (w <= 0 || h <= 0) {
            projection = null;
            return;
        }

        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        for (S2CSectorAreaPacket.AreaRect r : friendlyAreas) {
            minX = Math.min(minX, Math.min(r.x1, r.x2));
            maxX = Math.max(maxX, Math.max(r.x1, r.x2));
            minZ = Math.min(minZ, Math.min(r.z1, r.z2));
            maxZ = Math.max(maxZ, Math.max(r.z1, r.z2));
        }
        for (S2CSectorAreaPacket.AreaRect r : enemyAreas) {
            minX = Math.min(minX, Math.min(r.x1, r.x2));
            maxX = Math.max(maxX, Math.max(r.x1, r.x2));
            minZ = Math.min(minZ, Math.min(r.z1, r.z2));
            maxZ = Math.max(maxZ, Math.max(r.z1, r.z2));
        }
        for (S2CGameStatePacket.PointInfo p : points) {
            minX = Math.min(minX, p.x - p.radius);
            maxX = Math.max(maxX, p.x + p.radius);
            minZ = Math.min(minZ, p.z - p.radius);
            maxZ = Math.max(maxZ, p.z + p.radius);
        }
        for (DeploymentOption o : data.options()) {
            minX = Math.min(minX, o.x());
            maxX = Math.max(maxX, o.x());
            minZ = Math.min(minZ, o.z());
            maxZ = Math.max(maxZ, o.z());
        }
        if (background != null) {
            minX = Math.min(minX, background.minX());
            maxX = Math.max(maxX, background.maxX());
            minZ = Math.min(minZ, background.minZ());
            maxZ = Math.max(maxZ, background.maxZ());
        }

        if (minX > maxX) {
            // 没有任何战区数据：退化为以玩家为中心的 128 格视野，仍然画网格而不是编一张地图
            Vec3 pos = playerPos();
            double cx = pos == null ? 0 : pos.x;
            double cz = pos == null ? 0 : pos.z;
            minX = cx - 64;
            maxX = cx + 64;
            minZ = cz - 64;
            maxZ = cz + 64;
        } else {
            double padX = Math.max(8, (maxX - minX) * 0.08);
            double padZ = Math.max(8, (maxZ - minZ) * 0.08);
            minX -= padX;
            maxX += padX;
            minZ -= padZ;
            maxZ += padZ;
        }

        // 上方留给标题、下方留给图例
        int innerY = y + 16;
        int innerH = Math.max(24, h - 30);
        projection = DeployMapProjection.fit(x + 4, innerY, w - 8, innerH, minX, minZ, maxX, maxZ, 0.98);
    }

    @Nullable
    private static Vec3 playerPos() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : mc.player.position();
    }

    public boolean hasData() {
        return !(points.isEmpty() && friendlyAreas.isEmpty() && enemyAreas.isEmpty() && data.options().isEmpty());
    }

    @Nullable
    public String optionAt(int mouseX, int mouseY) {
        if (projection == null || !projection.contains(mouseX, mouseY)) return null;
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (DeploymentOption option : data.options()) {
            int sx = projection.screenX(option.x());
            int sy = projection.screenY(option.z());
            int dx = mouseX - sx;
            int dy = mouseY - sy;
            int dist = dx * dx + dy * dy;
            if (dist <= 10 * 10 && dist < bestDist) {
                best = option.id();
                bestDist = dist;
            }
        }
        return best;
    }

    public void render(GuiGraphics g, Font font, int mouseX, int mouseY, long millis) {
        g.fill(x, y, x + w, y + h, 0xE6070D0A);
        DeployTheme.frame(g, x, y, x + w, y + h, DeployTheme.BORDER);

        String title = "战区示意图";
        g.drawString(font, title, x + 8, y + 5, DeployTheme.TEXT_MUTED, false);
        String hint = background == null
                ? "坐标投影 · 无底图资源"
                : "服务端烘焙 · " + background.blocksPerPixel() + " 格/像素";
        int hintW = font.width(hint);
        if (x + 8 + font.width(title) + 10 + hintW < x + w - 8) {
            g.drawString(font, hint, x + w - hintW - 8, y + 5, DeployTheme.TEXT_DIM, false);
        }

        if (projection == null) return;

        int clipX1 = x + 1, clipY1 = y + 15, clipX2 = x + w - 1, clipY2 = y + h - 16;
        if (clipX2 <= clipX1 || clipY2 <= clipY1) return;
        g.enableScissor(clipX1, clipY1, clipX2, clipY2);

        drawBackground(g);
        // 网格画在底图之上：底图给地形，网格给坐标参照，两者都要能看见
        drawGrid(g, font);
        drawAreas(g);
        drawPoints(g, font);
        drawOptions(g, mouseX, mouseY, millis);
        drawPlayer(g);

        g.disableScissor();
        drawLegend(g, font);
    }

    private void drawGrid(GuiGraphics g, Font font) {
        int step = projection.gridStep();
        int x1 = x + 1, y1 = y + 15, x2 = x + w - 1, y2 = y + h - 16;

        // 用投影反解可见的世界坐标范围，避免遍历整个战区
        double minWorldX = Math.min(projection.worldX(x1), projection.worldX(x2));
        double maxWorldX = Math.max(projection.worldX(x1), projection.worldX(x2));
        double minWorldZ = Math.min(projection.worldZ(y1), projection.worldZ(y2));
        double maxWorldZ = Math.max(projection.worldZ(y1), projection.worldZ(y2));

        int color = DeployTheme.withAlpha(DeployTheme.ACCENT, 0x30);
        int startX = (int) (Math.floor(minWorldX / step) * step);
        for (double wx = startX; wx <= maxWorldX; wx += step) {
            int sx = projection.screenX(wx);
            if (sx < x1 || sx > x2) continue;
            g.fill(sx, y1, sx + 1, y2, color);
            g.drawString(font, String.valueOf((int) wx), sx + 2, y2 - 9, DeployTheme.withAlpha(DeployTheme.TEXT_DIM, 0xB0), false);
        }
        int startZ = (int) (Math.floor(minWorldZ / step) * step);
        for (double wz = startZ; wz <= maxWorldZ; wz += step) {
            int sy = projection.screenY(wz);
            if (sy < y1 || sy > y2) continue;
            g.fill(x1, sy, x2, sy + 1, color);
        }
    }

    /**
     * 贴底图。像素边界和投影用同一套换算，标记与地形不会错位；
     * 贴完再压一层暗色，让底图退到信息之后，标记和面板才是主角。
     */
    private void drawBackground(GuiGraphics g) {
        if (background == null) return;

        int x1 = projection.screenX(background.minX());
        int x2 = projection.screenX(background.maxX() + 1);
        int y1 = projection.screenY(background.minZ());
        int y2 = projection.screenY(background.maxZ() + 1);
        if (x2 <= x1 || y2 <= y1) return;

        g.blit(background.texture(), x1, y1, x2 - x1, y2 - y1,
                0, 0, background.textureWidth(), background.textureHeight(),
                background.textureWidth(), background.textureHeight());
        // 压暗并统一色调，让底图退到信息之后
        g.fill(x1, y1, x2, y2, 0x66000000);
    }

    private void drawAreas(GuiGraphics g) {
        for (S2CSectorAreaPacket.AreaRect r : friendlyAreas) {
            drawArea(g, r, DeployTheme.withAlpha(DeployTheme.ACCENT, 0x26), DeployTheme.withAlpha(DeployTheme.ACCENT, 0x99));
        }
        for (S2CSectorAreaPacket.AreaRect r : enemyAreas) {
            drawArea(g, r, DeployTheme.withAlpha(DeployTheme.DANGER, 0x24), DeployTheme.withAlpha(DeployTheme.DANGER, 0x99));
        }
    }

    private void drawArea(GuiGraphics g, S2CSectorAreaPacket.AreaRect r, int fill, int border) {
        int x1 = projection.screenX(Math.min(r.x1, r.x2));
        int x2 = projection.screenX(Math.max(r.x1, r.x2));
        int y1 = projection.screenY(Math.min(r.z1, r.z2));
        int y2 = projection.screenY(Math.max(r.z1, r.z2));
        if (x2 <= x1 || y2 <= y1) return;
        g.fill(x1, y1, x2, y2, fill);
        DeployTheme.frame(g, x1, y1, x2, y2, border);
    }

    private void drawPoints(GuiGraphics g, Font font) {
        for (S2CGameStatePacket.PointInfo p : points) {
            int sx = projection.screenX(p.x);
            int sy = projection.screenY(p.z);

            int owner = p.ownerTeam == 0 ? DeployTheme.ATTACKER : p.ownerTeam == 1 ? DeployTheme.DEFENDER : DeployTheme.TEXT_MUTED;
            DeployTheme.diamond(g, sx, sy, 5, DeployTheme.withAlpha(owner, 0x66));
            DeployTheme.diamondOutline(g, sx, sy, 6, owner);

            // 进度条：左半攻方、右半守方，填充比例 = progress 在 [-100,100] 的位置
            int barW = 22;
            int bx = sx - barW / 2;
            int by = sy + 8;
            g.fill(bx, by, bx + barW, by + 2, 0xFF101815);
            int split = bx + barW / 2;
            g.fill(split - 1, by - 1, split + 1, by + 3, DeployTheme.withAlpha(DeployTheme.TEXT_DIM, 0xAA));

            int prog = Math.max(-100, Math.min(100, p.progress));
            int atkPx = Math.round((prog + 100) / 200f * barW);
            if (atkPx > 0) g.fill(bx, by, bx + atkPx, by + 2, DeployTheme.ATTACKER);
            if (atkPx < barW) g.fill(bx + atkPx, by, bx + barW, by + 2, DeployTheme.DEFENDER);

            String label = p.id == null ? "?" : p.id;
            g.drawString(font, label, sx - font.width(label) / 2, sy - 17, DeployTheme.TEXT, true);
        }
    }

    private void drawOptions(GuiGraphics g, int mouseX, int mouseY, long millis) {
        String hoveredId = optionAt(mouseX, mouseY);
        for (DeploymentOption option : data.options()) {
            int sx = projection.screenX(option.x());
            int sy = projection.screenY(option.z());
            int color = DeployTheme.stateColor(option.state());
            boolean selected = option.id().equals(selectedId);
            boolean hovered = option.id().equals(hoveredId);

            if (option.kind() == DeploymentOption.Kind.BASE) {
                DeployTheme.squareOutline(g, sx, sy, 7, color);
                DeployTheme.diamond(g, sx, sy, 2, color);
            } else if (option.kind() == DeploymentOption.Kind.BEACON) {
                DeployTheme.frame(g, sx - 5, sy - 5, sx + 6, sy + 6, color);
                g.fill(sx - 1, sy - 1, sx + 2, sy + 2, color);
            } else {
                DeployTheme.diamondOutline(g, sx, sy, 8, color);
            }

            if (selected) {
                // 选中：外圈脉冲 + 四向准星，避免只靠颜色区分
                int pulse = 10 + (int) ((millis / 90) % 3);
                DeployTheme.diamondOutline(g, sx, sy, pulse, DeployTheme.withAlpha(DeployTheme.ACCENT, 0xEE));
                g.fill(sx - pulse - 4, sy, sx - pulse - 1, sy + 1, DeployTheme.ACCENT);
                g.fill(sx + pulse + 1, sy, sx + pulse + 4, sy + 1, DeployTheme.ACCENT);
                g.fill(sx, sy - pulse - 4, sx + 1, sy - pulse - 1, DeployTheme.ACCENT);
                g.fill(sx, sy + pulse + 1, sx + 1, sy + pulse + 4, DeployTheme.ACCENT);
            } else if (hovered) {
                DeployTheme.diamondOutline(g, sx, sy, 10, DeployTheme.withAlpha(color, 0xCC));
            }

            if (option.state() == DeploymentOption.State.UNAVAILABLE) {
                // 不可用：直接打叉，不依赖颜色分辨
                g.fill(sx - 4, sy - 4, sx - 2, sy - 2, DeployTheme.DANGER);
                g.fill(sx + 2, sy - 4, sx + 4, sy - 2, DeployTheme.DANGER);
            }
        }
    }

    private void drawPlayer(GuiGraphics g) {
        Vec3 pos = playerPos();
        if (pos == null) return;
        int sx = projection.screenX(pos.x);
        int sy = projection.screenY(pos.z);
        if (!projection.contains(sx, sy)) return;

        DeployTheme.diamond(g, sx, sy, 3, 0xFFFFFFFF);
        DeployTheme.diamondOutline(g, sx, sy, 4, 0xC0000000);
        g.drawString(Minecraft.getInstance().font, "你", sx + 5, sy - 4, 0xFFFFFFFF, true);
    }

    private void drawLegend(GuiGraphics g, Font font) {
        int legendY = y + h - 13;
        int cx = x + 8;
        cx = legend(g, font, cx, legendY, DeployTheme.ATTACKER, "攻方");
        cx = legend(g, font, cx, legendY, DeployTheme.DEFENDER, "守方");
        cx = legend(g, font, cx, legendY, DeployTheme.ACCENT, "可部署");
        legend(g, font, cx, legendY, DeployTheme.DANGER, "不可用");
    }

    private int legend(GuiGraphics g, Font font, int x0, int y0, int color, String text) {
        g.fill(x0, y0 + 2, x0 + 5, y0 + 7, color);
        g.drawString(font, text, x0 + 8, y0, DeployTheme.TEXT_MUTED, false);
        return x0 + 8 + font.width(text) + 8;
    }
}
