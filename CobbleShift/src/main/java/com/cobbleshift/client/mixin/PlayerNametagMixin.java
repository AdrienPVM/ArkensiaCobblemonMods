package com.cobbleshift.client.mixin;

import com.cobbleshift.client.TransformRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to hide nametag rendering for transformed players.
 * Targets EntityRenderer (parent class) where renderLabelIfPresent is defined.
 */
@Mixin(EntityRenderer.class)
public abstract class PlayerNametagMixin {

    @Inject(
        method = "renderLabelIfPresent",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void cobbleshift$hideNametag(Entity entity, Text text, MatrixStack matrices,
                                          VertexConsumerProvider vertexConsumers, int light, float tickDelta,
                                          CallbackInfo ci) {
        // Hide nametag for both transformed players and transformed NPCs
        if (TransformRenderer.isTransformed(entity.getUuid())) {
            ci.cancel();
        }
    }
}
