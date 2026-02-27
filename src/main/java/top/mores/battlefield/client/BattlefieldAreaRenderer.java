package top.mores.battlefield.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import top.mores.battlefield.Battlefield;
import top.mores.battlefield.game.BattlefieldAreaRules;
import top.mores.battlefield.net.S2CGameStatePacket;

import java.util.List;

@Mod.EventBusSubscriber(modid = Battlefield.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BattlefieldAreaRenderer {
    private BattlefieldAreaRenderer() {
    }

    private static final int SEGMENTS = 48;
    private static int outsideAreaTicks = 0;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        List<S2CGameStatePacket.PointInfo> points = ClientGameState.points;
        if (points == null || points.isEmpty()) return;

        byte myTeam = ClientGameState.myTeam;
        if (myTeam != 0 && myTeam != 1) return;

        Vec3 cam = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffer.getBuffer(RenderType.lines());

        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);

        for (S2CGameStatePacket.PointInfo point : points) {
            boolean attackersOwned = point.progress >= 100;
            boolean defendersOwned = point.progress <= -100;
            boolean friendlyOwned = (myTeam == 0 && attackersOwned) || (myTeam == 1 && defendersOwned);

            // 点位轮廓：贴地圆线。己方蓝色，敌方红色。
            if (friendlyOwned) {
                drawGroundCircle(poseStack, lines, point.x, point.y + 0.05, point.z,
                        point.radius,
                        0.2f, 0.5f, 1.0f, 1.0f);
            } else {
                drawGroundCircle(poseStack, lines, point.x, point.y + 0.05, point.z,
                        point.radius,
                        1.0f, 0.2f, 0.2f, 1.0f);
            }

            var myAreas = (myTeam == 0) ? ClientGameState.attackerAreas : ClientGameState.defenderAreas;
            for (var c : myAreas) {
                drawGroundCircle(poseStack, lines, c.x, mc.player.getY() + 0.05, c.z,
                        c.r,
                        1.0f, 1.0f, 1.0f, 0.9f);
            }
        }

        poseStack.popPose();
        buffer.endBatch(RenderType.lines());
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            outsideAreaTicks = 0;
            return;
        }

        byte myTeam = ClientGameState.myTeam;
        if (myTeam != 0 && myTeam != 1) {
            outsideAreaTicks = 0;
            return;
        }

        List<S2CGameStatePacket.PointInfo> points = ClientGameState.points;
        if (points == null || points.isEmpty()) {
            outsideAreaTicks = 0;
            return;
        }

        if (BattlefieldAreaRules.isInsideMovableArea(myTeam, mc.player.getX(), mc.player.getZ())) {
            outsideAreaTicks = 0;
        } else {
            outsideAreaTicks++;
        }
    }

    public static boolean isOutsideMovableArea() {
        return outsideAreaTicks > 0;
    }

    public static int getOutsideAreaTicks() {
        return outsideAreaTicks;
    }

    private static void drawGroundCircle(PoseStack poseStack, VertexConsumer vc,
                                         double cx, double y, double cz,
                                         double radius,
                                         float r, float g, float b, float a) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f m4 = pose.pose();
        Matrix3f m3 = pose.normal();

        for (int i = 0; i < SEGMENTS; i++) {
            double t0 = (Math.PI * 2.0 * i) / SEGMENTS;
            double t1 = (Math.PI * 2.0 * (i + 1)) / SEGMENTS;

            float x0 = (float) (cx + Math.cos(t0) * radius);
            float z0 = (float) (cz + Math.sin(t0) * radius);
            float x1 = (float) (cx + Math.cos(t1) * radius);
            float z1 = (float) (cz + Math.sin(t1) * radius);

            addLine(vc, m4, m3, x0, (float) y, z0, x1, (float) y, z1, r, g, b, a);
        }
    }

    private static void addLine(VertexConsumer vc, Matrix4f m4, Matrix3f m3,
                                float x0, float y0, float z0,
                                float x1, float y1, float z1,
                                float r, float g, float b, float a) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float dz = z1 - z0;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len == 0) len = 1;

        float nx = dx / len;
        float ny = dy / len;
        float nz = dz / len;

        vc.vertex(m4, x0, y0, z0).color(r, g, b, a).normal(m3, nx, ny, nz).endVertex();
        vc.vertex(m4, x1, y1, z1).color(r, g, b, a).normal(m3, nx, ny, nz).endVertex();
    }
}
