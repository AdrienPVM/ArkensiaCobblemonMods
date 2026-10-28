package com.cheeseguard.mixin;

import com.cheeseguard.client.CheeseGuardClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client mixin: hides ALL nametags (players, mobs, Cobblemon) when the player
 * is inside a CheeseGuard zone with NAMETAG_HIDE flag set to DENY.
 */
@Mixin(EntityRenderer.class)
public abstract class NametagHideMixin {

    @Inject(
        method = "renderLabelIfPresent",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void cheeseguard$hideNametag(Entity entity, Text text, MatrixStack matrices,
                                         VertexConsumerProvider vertexConsumers, int light, float tickDelta,
                                         CallbackInfo ci) {
        if (CheeseGuardClient.hideNametags) {
            ci.cancel();
        }
    }
}
