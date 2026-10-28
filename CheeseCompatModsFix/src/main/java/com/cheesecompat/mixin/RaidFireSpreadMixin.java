package com.cheesecompat.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.block.FireBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents fire from spreading/ticking in CobblemonRaidDens dimensions.
 * Raid dimensions use the "cobblemonraiddens" namespace for their registry keys.
 */
@Mixin(FireBlock.class)
public class RaidFireSpreadMixin {

    @Inject(method = "scheduledTick", at = @At("HEAD"), cancellable = true)
    private void cheesecompat$cancelFireInRaidDens(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        if (world.getRegistryKey().getValue().getNamespace().equals("cobblemonraiddens")) {
            ci.cancel();
        }
    }
}
