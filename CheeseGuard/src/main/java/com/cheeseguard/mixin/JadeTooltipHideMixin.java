package com.cheeseguard.mixin;

import com.cheeseguard.client.CheeseGuardClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client mixin targeting Jade's OverlayRenderer.renderOverlay478757().
 * When the NAMETAG_HIDE flag is active in a CheeseGuard zone, this suppresses
 * the Jade entity tooltip overlay so transformed players are indistinguishable.
 * @Pseudo makes the target class optional — no crash if Jade is not installed.
 */
@Pseudo
@Mixin(targets = "snownee.jade.overlay.OverlayRenderer", remap = false)
public abstract class JadeTooltipHideMixin {

    /**
     * Intercept Jade's main overlay render method.
     * The method is static: renderOverlay478757(MinecraftClient, float)V
     * Using intermediary class name for MinecraftClient since remap=false.
     */
    @Inject(
        method = "renderOverlay478757(Lnet/minecraft/class_310;F)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private static void cheeseguard$hideJadeTooltip(CallbackInfo ci) {
        if (CheeseGuardClient.hideNametags) {
            ci.cancel();
        }
    }
}
