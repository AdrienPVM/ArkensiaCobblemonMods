package com.cobblecap.mixin.client;

import com.cobblecap.client.PastureHideClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels rendering of Cobblemon pasture-tethered Pokémon when the local
 * player has enabled /pasturehide. Server-side behavior is untouched —
 * the entity still exists and ticks normally, it is simply not drawn.
 */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {

    // Debug telemetry — see PastureHideClient for the throttled log line
    private static int hitsThisSecond = 0;
    private static int cancelsThisSecond = 0;
    private static long lastWindow = 0L;

    @Inject(
        method = "render(Lnet/minecraft/entity/Entity;DDDFFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void cobblecap$skipPasturedPokemon(Entity entity, double x, double y, double z,
                                                 float yaw, float tickDelta,
                                                 MatrixStack matrices,
                                                 VertexConsumerProvider vertexConsumers, int light,
                                                 CallbackInfo ci) {
        if (!PastureHideClient.isHidden()) return;
        // Telemetry: report once per second how many Pokémon entities were
        // checked and how many we cancelled. Helps diagnose whether the mixin
        // is firing at all and whether the detection works.
        long now = System.currentTimeMillis();
        if (now - lastWindow >= 1000) {
            if (hitsThisSecond > 0) {
                org.slf4j.LoggerFactory.getLogger("cobblecap").info(
                        "[CobbleCap-Client] /pasturehide last 1s: checked {} Pokémon entities, hid {}",
                        hitsThisSecond, cancelsThisSecond);
            }
            hitsThisSecond = 0;
            cancelsThisSecond = 0;
            lastWindow = now;
        }
        if (entity != null && entity.getClass().getName().endsWith(".PokemonEntity")) {
            hitsThisSecond++;
            if (PastureHideClient.isPastured(entity)) {
                cancelsThisSecond++;
                ci.cancel();
            }
        }
    }
}
