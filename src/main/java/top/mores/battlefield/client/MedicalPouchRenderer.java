package top.mores.battlefield.client;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import top.mores.battlefield.server.entity.MedicalPouchEntity;

public final class MedicalPouchRenderer extends EntityRenderer<MedicalPouchEntity> {
    public MedicalPouchRenderer(EntityRendererProvider.Context context) { super(context); shadowRadius=0.08F; }
    @Override public ResourceLocation getTextureLocation(MedicalPouchEntity e) { return MedicalPouchModel.TEXTURE; }
    @Override public void render(MedicalPouchEntity e, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        try {
            pose.translate(0,0.10,0);
            pose.mulPose(Axis.YP.rotationDegrees(180-yaw));
            pose.mulPose(Axis.XP.rotationDegrees(e.getXRot()));
            MedicalPouchModel.render(pose,buffers,light);
        } finally { pose.popPose(); }
        super.render(e,yaw,partialTick,pose,buffers,light);
    }
}
