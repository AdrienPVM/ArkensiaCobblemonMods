package com.cheesecompat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Clamps FertilityProperty.decrement() at 0.
 *
 * Without this, a Pokémon's fertility drifts negative if the server is
 * misconfigured (allow_breeding_without_fertility = true) or if a race
 * lets a zero-fertility parent produce another egg.
 *
 * Modifies the second arg of the new IntSpeciesFeature(KEY, value) call
 * inside decrement(), replacing "get - 1" with "max(0, get - 1)".
 */
@Mixin(targets = "com.provismet.cobblemon.daycareplus.feature.FertilityProperty", remap = false)
public class FertilityClampMixin {

    @ModifyArg(
        method = "decrement",
        at = @At(value = "INVOKE",
            target = "Lcom/cobblemon/mod/common/api/pokemon/feature/IntSpeciesFeature;<init>(Ljava/lang/String;I)V"),
        index = 1
    )
    private static int cheesecompat$clampFertility(int value) {
        return Math.max(0, value);
    }
}
