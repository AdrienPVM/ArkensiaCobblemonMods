package com.cobbleshift.client.mixin;

import com.cobbleshift.client.TransformRenderer;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Silences human footstep sounds when a player is transformed into a Pokemon.
 * The player is invisible + rendered as a Pokemon model, so human step sounds
 * would break immersion.
 */
@Mixin(Entity.class)
public abstract class TransformStepSoundMixin {

    @Inject(
        method = "playStepSound",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void cobbleshift$silenceFootsteps(BlockPos pos, BlockState state, CallbackInfo ci) {
        try {
            Entity self = (Entity) (Object) this;
            if (self instanceof PlayerEntity) {
                if (TransformRenderer.isTransformed(self.getUuid())) {
                    ci.cancel();
                }
            }
        } catch (Exception ignored) {}
    }
}
