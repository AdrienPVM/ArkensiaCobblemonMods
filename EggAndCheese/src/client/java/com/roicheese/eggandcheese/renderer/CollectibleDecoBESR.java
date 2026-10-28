package com.roicheese.eggandcheese.renderer;

import com.roicheese.eggandcheese.blockentity.CollectibleDecoBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;

public class CollectibleDecoBESR implements BlockEntityRenderer<CollectibleDecoBlockEntity> {
    public CollectibleDecoBESR(BlockEntityRendererFactory.Context ctx) {}

    @Override
    public void render(CollectibleDecoBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vcp, int light, int overlay) {
        if (entity.getDna() == null) return;

        int maxDist = EggRenderConfig.textureRenderDistance;
        if (maxDist > 0) {
            double distSq = MinecraftClient.getInstance().gameRenderer.getCamera().getPos()
                .squaredDistanceTo(
                    entity.getPos().getX() + 0.5,
                    entity.getPos().getY() + 0.5,
                    entity.getPos().getZ() + 0.5);
            if (distSq > (double) maxDist * maxDist) {
                CollectibleModelRenderer.renderSimple(entity.getCollectibleType(), entity.getDna(),
                    1.0f, matrices, vcp, light, overlay, entity.yRotation);
                return;
            }
        }

        CollectibleModelRenderer.render(entity.getCollectibleType(), entity.getDna(),
            1.0f, matrices, vcp, light, overlay, entity.yRotation);
    }
}
