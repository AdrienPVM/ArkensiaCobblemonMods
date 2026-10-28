package com.cobbleshift.client.mixin;

import com.cobbleshift.client.TransformRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts mouse scroll when the local player is transformed.
 * Scroll up = zoom in (closer), scroll down = zoom out (further).
 * Prevents the scroll from changing the hotbar slot.
 */
@Mixin(Mouse.class)
public abstract class TransformScrollMixin {

    @Shadow @Final private MinecraftClient client;

    @Inject(
        method = "onMouseScroll",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void cobbleshift$handleZoomScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        try {
            if (client.player == null || client.currentScreen != null) return;
            if (!TransformRenderer.isTransformed(client.player.getUuid())) return;

            if (vertical != 0) {
                // Scroll up (vertical > 0) = zoom in (decrease zoom = closer camera)
                // Scroll down (vertical < 0) = zoom out (increase zoom = further camera)
                float delta = (float) -vertical * 0.15f;
                TransformRenderer.adjustZoom(delta);
                ci.cancel();
            }
        } catch (Exception ignored) {}
    }
}
