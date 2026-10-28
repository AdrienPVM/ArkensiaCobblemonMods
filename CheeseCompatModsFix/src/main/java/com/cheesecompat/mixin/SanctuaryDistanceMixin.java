package com.cheesecompat.mixin;

import com.cheesecompat.CheeseCompatMod;
import github.jorgaomc.blocks.SanctuaryBlock;
import net.minecraft.block.Block;
import net.minecraft.block.AbstractBlock;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fixes integer overflow in SanctuaryBlock.isWithinRadius().
 *
 * The original code computes (dx * dx + dz * dz) using int arithmetic.
 * When the player is far from the sanctuary (e.g. in another dimension or
 * tens of thousands of blocks away), dx * dx overflows int range (>2^31),
 * producing a negative number that is always <= radiusSq, making the
 * sanctuary protection appear to cover the entire world.
 *
 * Fix: use long arithmetic to avoid overflow.
 */
@Mixin(value = SanctuaryBlock.class, remap = false)
public abstract class SanctuaryDistanceMixin extends Block {

    private SanctuaryDistanceMixin(Settings settings) {
        super(settings);
    }

    @Inject(method = "isWithinRadius", at = @At("HEAD"), cancellable = true, require = 0)
    private static void cheesecompat$fixOverflow(BlockPos pos, BlockPos sanctuaryPos,
                                                   int horizontalRadius, int verticalRadius,
                                                   CallbackInfoReturnable<Boolean> cir) {
        if (!CheeseCompatMod.getConfig().sanctuaryOverflowFix) return;

        // Use long arithmetic to prevent integer overflow
        long dx = (long) pos.getX() - (long) sanctuaryPos.getX();
        long dz = (long) pos.getZ() - (long) sanctuaryPos.getZ();
        int dy = Math.abs(pos.getY() - sanctuaryPos.getY());

        long horizontalDistSq = dx * dx + dz * dz;
        long radiusSq = (long) horizontalRadius * (long) horizontalRadius;

        cir.setReturnValue(horizontalDistSq <= radiusSq && dy <= verticalRadius);
    }
}
