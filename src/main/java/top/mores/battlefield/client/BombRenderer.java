package top.mores.battlefield.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import org.jetbrains.annotations.NotNull;
import top.mores.battlefield.block.ModBlocks;
import top.mores.battlefield.server.entity.BombEntity;

public class BombRenderer extends EntityRenderer<BombEntity> {
    private final BlockRenderDispatcher blockRenderer;

    public BombRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.45F;
        this.blockRenderer = Minecraft.getInstance().getBlockRenderer();
    }

    @Override
    public void render(BombEntity entity,
                       float entityYaw,
                       float partialTick,
                       PoseStack poseStack,
                       @NotNull MultiBufferSource buffer,
                       int packedLight) {
        poseStack.pushPose();

        poseStack.translate(0.0D, 0.12D, 0.0D);
        poseStack.mulPose(Axis.YP.rotationDegrees(-entityYaw));

        // 与 V1 导弹一致：复用方块模型
        poseStack.scale(0.6F, 0.6F, 0.6F);
        poseStack.translate(-0.5D, -0.5D, -0.5D);

        blockRenderer.renderSingleBlock(
                ModBlocks.V1_MISSILE.get().defaultBlockState(),
                poseStack,
                buffer,
                packedLight,
                OverlayTexture.NO_OVERLAY
        );

        poseStack.popPose();
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public @NotNull ResourceLocation getTextureLocation(@NotNull BombEntity entity) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
