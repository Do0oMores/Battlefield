package top.mores.battlefield.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import top.mores.battlefield.block.ModBlocks;
import top.mores.battlefield.server.entity.RespawnBeaconEntity;

public class RespawnBeaconRenderer extends EntityRenderer<RespawnBeaconEntity> {
    private final BlockRenderDispatcher blockRenderer;

    public RespawnBeaconRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.blockRenderer = Minecraft.getInstance().getBlockRenderer();
        this.shadowRadius = 0.35f;
    }

    @Override
    public void render(RespawnBeaconEntity entity,
                       float entityYaw,
                       float partialTick,
                       PoseStack poseStack,
                       MultiBufferSource buffer,
                       int packedLight) {

        poseStack.pushPose();

        poseStack.translate(-0.5, 0.0, -0.5);

        blockRenderer.renderSingleBlock(
                ModBlocks.RESPAWN_BEACON.get().defaultBlockState(),
                poseStack,
                buffer,
                packedLight,
                OverlayTexture.NO_OVERLAY
        );

        poseStack.popPose();
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(RespawnBeaconEntity entity) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
