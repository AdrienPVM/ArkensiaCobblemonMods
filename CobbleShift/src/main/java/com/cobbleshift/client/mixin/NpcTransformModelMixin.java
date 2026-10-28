package com.cobbleshift.client.mixin;

import com.cobbleshift.client.TransformRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Mixin on EntityRenderDispatcher.render() to swap NPC models with Pokémon models.
 * Uses the same rendering approach as the player TransformModelMixin:
 * push matrices, translate to entity position, call dispatcher.render(ghost, 0,0,0), pop.
 *
 * The ghost entity's UUID is NOT in the transform map, so the mixin naturally
 * skips it on the recursive call — no guard flag needed.
 *
 * IMPORTANT: The NPC must NOT have the Invisibility status effect, because
 * Minecraft's WorldRenderer skips invisible entities entirely (never calls
 * EntityRenderDispatcher.render()), which would prevent this mixin from firing.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class NpcTransformModelMixin {

    @Inject(
        method = "render(Lnet/minecraft/entity/Entity;DDDFFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private <E extends Entity> void cobbleshift$renderNpcAsPokemon(
            E entity, double x, double y, double z, float yaw, float tickDelta,
            MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light,
            CallbackInfo ci) {
        try {
            if (entity instanceof PlayerEntity) return;

            UUID uuid = entity.getUuid();
            if (!TransformRenderer.isTransformed(uuid)) return;

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) return;

            Entity ghost = TransformRenderer.getGhostEntity(uuid, client.world);
            if (ghost == null) return;

            TransformRenderer.syncGhostToEntity(ghost, entity, tickDelta);

            // Render ghost using same pattern as player TransformModelMixin:
            // push, translate to entity position, render at 0,0,0, pop
            matrices.push();
            matrices.translate(x, y, z);
            // Apply custom scale multiplier if set
            float customScale = TransformRenderer.getScale(uuid);
            if (customScale != 1.0f) {
                matrices.scale(customScale, customScale, customScale);
            }
            try {
                ((EntityRenderDispatcher) (Object) this).render(
                    ghost, 0.0, 0.0, 0.0, yaw, tickDelta, matrices, vertexConsumers, light
                );
            } catch (Throwable ignored) {
                // Cobblemon's renderer may have pushed extra entries onto the MatrixStack
                // before throwing. Pop until root, then re-push one for the outer pop().
                while (!matrices.isEmpty()) {
                    try { matrices.pop(); } catch (Exception ex) { break; }
                }
                matrices.push();
            }
            matrices.pop();

            // Cancel the NPC's own render — replaced by the ghost Pokémon above
            ci.cancel();
        } catch (Exception e) {
            // Safety net — don't crash the game
        }
    }
}
