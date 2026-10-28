package com.cobbleshift.client.mixin;

import com.cobbleshift.client.AbilityHudRenderer;
import com.cobbleshift.client.TransformRenderer;
import com.cobbleshift.network.AttackAbilityPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts left-click (doAttack) when the local player is transformed.
 * Instead of the normal attack/mine action, sends a C2S packet to fire a type projectile.
 * Includes whether an attack animation is available — this controls projectile delay on server.
 */
@Mixin(MinecraftClient.class)
public abstract class AttackInputMixin {

    @Inject(
        method = "doAttack",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void cobbleshift$interceptAttack(CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient client = (MinecraftClient) (Object) this;
        if (client.player != null && TransformRenderer.isTransformed(client.player.getUuid())) {
            // Block attack if cooldown bar isn't fully filled yet
            if (AbilityHudRenderer.isOnCooldown()) {
                cir.setReturnValue(false);
                return;
            }

            // Check if the ghost can play an attack animation (determines projectile delay on server)
            boolean hasAnim = TransformRenderer.canPlayAttackAnimation(client.player.getUuid());

            // Send attack packet to server + trigger visual attack animation on ghost
            try {
                ClientPlayNetworking.send(new AttackAbilityPayload(hasAnim));
                AbilityHudRenderer.triggerAttackCooldown();
                TransformRenderer.triggerAttackAnimation(client.player.getUuid());
            } catch (Exception ignored) {}
            cir.setReturnValue(false);
        }
    }
}
