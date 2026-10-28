package com.cobbleshift.client.mixin;

import com.cobbleshift.client.TransformRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin on PlayerEntityRenderer.render() to swap the player model with a Pokemon model.
 * When a player is transformed, instead of rendering the player model:
 *   1. Get the ghost PokemonEntity from TransformRenderer
 *   2. Sync its position/rotation to the player
 *   3. Use EntityRenderDispatcher to render the ghost (Cobblemon's PokemonRenderer handles it)
 *   4. Cancel the normal player render
 *
 * Targets the bridge method render(Entity, float, float, MatrixStack, VertexConsumerProvider, int)
 * which is present on PlayerEntityRenderer in MC 1.21.1.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class TransformModelMixin {

    @Inject(
        method = "render(Lnet/minecraft/entity/Entity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void cobbleshift$renderPokemonModel(Entity entity, float yaw, float tickDelta,
                                                  MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                                  int light, CallbackInfo ci) {
        try {
            if (!(entity instanceof PlayerEntity player)) return;

            java.util.UUID playerUuid = player.getUuid();
            if (!TransformRenderer.isTransformed(playerUuid)) return;

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) return;

            Entity ghost = TransformRenderer.getGhostEntity(playerUuid, client.world);
            if (ghost == null) return;

            TransformRenderer.syncGhostToPlayer(ghost, player, tickDelta);

            // First-person mode: if this is the local player and zoom is 0, hide the model
            if (player == client.player && TransformRenderer.getZoomFactor() <= 0.01f) {
                ci.cancel();
                return;
            }

            // Use EntityRenderDispatcher to render the ghost entity
            // This automatically finds Cobblemon's PokemonRenderer
            EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();

            matrices.push();
            try {
                // Apply custom scale multiplier if set
                float customScale = TransformRenderer.getScale(playerUuid);
                if (customScale != 1.0f) {
                    matrices.scale(customScale, customScale, customScale);
                }
                // dispatcher.render expects: (entity, x, y, z, yaw, tickDelta, matrices, vc, light)
                // Since the matrix stack is already translated to the player position, we render at 0,0,0
                dispatcher.render(ghost, 0.0, 0.0, 0.0, yaw, tickDelta, matrices, vertexConsumers, light);
            } catch (Exception e) {
                // Cobblemon's renderer may have pushed extra entries onto the MatrixStack
                // before throwing (e.g., bone transforms, model parts). If we don't clean
                // them up, MC's LevelRenderer crashes with "Pose stack not empty".
                // Pop until we're back to the root state, then re-push one entry so the
                // outer pop() below has something to pop.
                while (!matrices.isEmpty()) {
                    try { matrices.pop(); } catch (Exception ex) { break; }
                }
                matrices.push();
            }
            matrices.pop();

            ci.cancel();
        } catch (Exception e) {
            // Don't crash — fall through to normal player render
        }
    }
}
