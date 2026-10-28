package com.cobbleshift.client.mixin;

import com.cobbleshift.client.TransformRenderer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides first-person arm rendering when the local player is transformed.
 * Targets PlayerEntityRenderer's arm rendering methods.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class TransformArmMixin {

    @Inject(
        method = "renderRightArm",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void cobbleshift$hideRightArm(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                           int light, AbstractClientPlayerEntity player,
                                           CallbackInfo ci) {
        if (player != null && TransformRenderer.isTransformed(player.getUuid())) {
            ci.cancel();
        }
    }

    @Inject(
        method = "renderLeftArm",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void cobbleshift$hideLeftArm(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                          int light, AbstractClientPlayerEntity player,
                                          CallbackInfo ci) {
        if (player != null && TransformRenderer.isTransformed(player.getUuid())) {
            ci.cancel();
        }
    }
}
