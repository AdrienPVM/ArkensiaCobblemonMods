package com.cheeseguard.mixin;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Flag;
import net.minecraft.block.*;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents all coral types from dying when the CORAL_DEATH flag is denied.
 * All four classes use scheduledTick to convert to their dead variant when not in water.
 */
@Mixin({CoralBlockBlock.class, CoralBlock.class, CoralFanBlock.class, CoralWallFanBlock.class})
public class CoralDeathMixin {
    @Inject(method = "scheduledTick", at = @At("HEAD"), cancellable = true)
    private void onCoralDeath(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        String worldId = world.getRegistryKey().getValue().toString();
        if (!CheeseGuardMod.getInstance().getRegionManager().isFlagAllowed(worldId, pos.getX(), pos.getY(), pos.getZ(), Flag.CORAL_DEATH))
            ci.cancel();
    }
}
