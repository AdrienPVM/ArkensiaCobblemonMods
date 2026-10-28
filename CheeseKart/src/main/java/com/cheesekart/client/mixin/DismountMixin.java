package com.cheesekart.client.mixin;

import com.cheesekart.client.CheeseKartClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Block client-side dismount (Shift key) when player is in a locked kart.
 * Server-side protection is in KartManager.tick() (force remount).
 */
@Mixin(ClientPlayerEntity.class)
public class DismountMixin {

    @Inject(method = "dismountVehicle", at = @At("HEAD"), cancellable = true, require = 0)
    private void cheesekart$blockDismount(CallbackInfo ci) {
        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        if (CheeseKartClient.isInKart(self.getUuid())) {
            ci.cancel();
        }
    }
}
