package com.roicheese.eggandcheese.renderer;

import com.roicheese.eggandcheese.blockentity.AdminCollectibleBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;

public class AdminCollectibleBESR implements BlockEntityRenderer<AdminCollectibleBlockEntity> {
    public AdminCollectibleBESR(BlockEntityRendererFactory.Context ctx) {}

    @Override
    public void render(AdminCollectibleBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vcp, int light, int overlay) {
        if (entity.getDna() == null) return;

        int maxDist = entity.getRenderDistanceOverride() >= 0
                ? entity.getRenderDistanceOverride()
                : EggRenderConfig.textureRenderDistance;
        if (maxDist > 0) {
            double distSq = MinecraftClient.getInstance().gameRenderer.getCamera().getPos()
                .squaredDistanceTo(
                    entity.getPos().getX() + 0.5,
                    entity.getPos().getY() + 0.5,
                    entity.getPos().getZ() + 0.5);
            if (distSq > (double) maxDist * maxDist) {
                CollectibleModelRenderer.renderSimple(entity.getCollectibleType(), entity.getDna(),
                    entity.getScale(), matrices, vcp, light, overlay, entity.yRotation);
                return;
            }
        }

        CollectibleModelRenderer.render(entity.getCollectibleType(), entity.getDna(),
            entity.getScale(), matrices, vcp, light, overlay, entity.yRotation);
    }

    @Override
    public boolean rendersOutsideBoundingBox(AdminCollectibleBlockEntity entity) { return entity.getScale() > 1.0f; }
}
