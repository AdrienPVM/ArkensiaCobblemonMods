package com.cobbleshift.client.mixin;

import com.cobbleshift.client.TransformRenderer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents FOV distortion caused by speed attribute modifiers on transformed players.
 * Returns 1.0f (no FOV change) when the local player is transformed.
 */
@Mixin(AbstractClientPlayerEntity.class)
public class TransformFovMixin {

    @Inject(method = "getFovMultiplier", at = @At("HEAD"), cancellable = true, require = 0)
    private void cobbleshift$neutralizeFov(CallbackInfoReturnable<Float> cir) {
        try {
            AbstractClientPlayerEntity self = (AbstractClientPlayerEntity) (Object) this;
            if (TransformRenderer.isTransformed(self.getUuid())) {
                cir.setReturnValue(1.0f);
            }
        } catch (Exception ignored) {}
    }
}
