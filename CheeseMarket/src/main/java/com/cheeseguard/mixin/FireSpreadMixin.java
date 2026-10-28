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
 * Prevent fire from spreading in protected regions.
 * Uses require=0 to avoid crash if method signature changes between MC versions.
 */
@Mixin(FireBlock.class)
public class FireSpreadMixin {

    @Inject(method = "scheduledTick", at = @At("HEAD"), cancellable = true, require = 0)
    private void onScheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        checkAndCancel(world, pos, ci);
    }

    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true, require = 0)
    private void onRandomTick(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        checkAndCancel(world, pos, ci);
    }

    private void checkAndCancel(ServerWorld world, BlockPos pos, CallbackInfo ci) {
        String worldId = world.getRegistryKey().getValue().toString();
        if (!CheeseGuardMod.getInstance().getRegionManager()
                .isFlagAllowed(worldId, pos.getX(), pos.getY(), pos.getZ(), Flag.FIRE_SPREAD)) {
            world.removeBlock(pos, false);
            ci.cancel();
        }
    }
}
