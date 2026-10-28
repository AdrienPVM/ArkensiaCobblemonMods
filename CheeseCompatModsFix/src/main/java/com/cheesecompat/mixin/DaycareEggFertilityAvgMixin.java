package com.cheesecompat.mixin;

import com.provismet.cobblemon.daycareplus.breeding.PastureExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Replaces Math.min(parent1Fertility, parent2Fertility) with the average of
 * the parents' PRE-decrement fertility, minus 1.
 *
 * Math.min is called AFTER FertilityProperty.decrement on both parents, so
 * (a, b) here are post-decrement values: a = pre1 - 1, b = pre2 - 1
 * (the clamp at 0 only triggers when the parent already had 0, which is
 * impossible because isAllowedToBreed blocks fertility &lt;= 0).
 *
 * The user wants egg = (pre1 + pre2) / 2 - 1.
 * Substituting: (a + 1 + b + 1) / 2 - 1 = (a + b) / 2.
 * So the formula simplifies to max(0, (a + b) / 2).
 *
 * Effect: the egg always inherits one less fertility than the parents'
 * average, so the breeding lineage decays by 1 per generation.
 */
@Mixin(value = PastureExtension.class, remap = false)
public class DaycareEggFertilityAvgMixin {

    @Redirect(
        method = "produceEgg",
        at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I")
    )
    private int cheesecompat$averageFertility(int a, int b) {
        return Math.max(0, (a + b) / 2);
    }
}
