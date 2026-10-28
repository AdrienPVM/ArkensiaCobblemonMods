package com.cobbleshift.client.mixin;

import com.cobbleshift.client.TransformRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adjusts the camera when the local player is transformed:
 * - Orbit center shifted to the Pokémon model center (not player eye height)
 * - All orbit axes (X, Y, Z) scaled by scroll zoom factor
 * - Preserves MC's natural pitch-based vertical orbit (mouse up → camera goes down, etc.)
 */
@Mixin(Camera.class)
public abstract class TransformCameraMixin {

    @Shadow private Vec3d pos;
    @Shadow private boolean thirdPerson;
    @Shadow private Entity focusedEntity;

    @Shadow protected abstract void setPos(Vec3d pos);

    @Inject(
        method = "update",
        at = @At("TAIL"),
        require = 0
    )
    private void cobbleshift$adjustCameraForTransform(BlockView area, Entity entity,
                                                       boolean thirdPerson, boolean inverseView,
                                                       float tickDelta, CallbackInfo ci) {
        try {
            if (!this.thirdPerson || this.focusedEntity == null) return;
            if (!(this.focusedEntity instanceof PlayerEntity player)) return;
            if (!TransformRenderer.isTransformed(player.getUuid())) return;

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) return;

            Entity ghost = TransformRenderer.getGhostEntity(player.getUuid(), client.world);
            if (ghost == null) return;

            float ghostHeight = ghost.getHeight();

            // Player interpolated position
            double playerX = player.prevX + (player.getX() - player.prevX) * tickDelta;
            double playerY = player.prevY + (player.getY() - player.prevY) * tickDelta;
            double playerZ = player.prevZ + (player.getZ() - player.prevZ) * tickDelta;

            // Player eye height — what MC's camera naturally orbits around
            double eyeHeight = player.getStandingEyeHeight();

            // Pokémon orbit center: 55% of Pokemon height + 0.55 base offset
            // This is where we want the camera to orbit around instead of the player's eye
            double pokemonCenter = ghostHeight * 0.55 + 0.55;

            // MC already computed the camera position with pitch-based orbit.
            // Extract the orbit vector (offset from the player's eye position):
            //   pos = playerInterp + (0, eyeHeight, 0) + orbitVector
            //   orbitVector = pos - playerInterp - (0, eyeHeight, 0)
            double orbitX = this.pos.x - playerX;
            double orbitY = this.pos.y - (playerY + eyeHeight);
            double orbitZ = this.pos.z - playerZ;

            // Apply zoom to ALL orbit axes — this preserves MC's natural
            // pitch-based vertical movement (mouse up = camera down, mouse down = camera up)
            float zoom = TransformRenderer.getZoomFactor();

            // Re-center the orbit around the Pokémon center instead of the player's eye
            this.setPos(new Vec3d(
                playerX + orbitX * zoom,
                playerY + pokemonCenter + orbitY * zoom,
                playerZ + orbitZ * zoom
            ));
        } catch (Exception e) {
            // Don't crash — fall through to normal camera
        }
    }
}
