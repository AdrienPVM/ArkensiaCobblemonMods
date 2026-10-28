package com.cheeseguard.mixin;

import com.cheeseguard.client.CheeseGuardClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client mixin: hides JourneyMap minimap when the player is inside a
 * CheeseGuard zone with MINIMAP_HIDE flag set to DENY.
 * Uses @Pseudo so the game doesn't crash if JourneyMap is not installed.
 */
@Pseudo
@Mixin(targets = "journeymap.client.ui.minimap.MiniMap")
public class JourneyMapMinimapHideMixin {

    @Inject(
        method = "drawMap",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void cheeseguard$hideMinimap(CallbackInfo ci) {
        if (CheeseGuardClient.hideMinimap) {
            ci.cancel();
        }
    }
}
