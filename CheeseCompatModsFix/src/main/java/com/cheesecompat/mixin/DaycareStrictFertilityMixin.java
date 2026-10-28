package com.cheesecompat.mixin;

import com.cobblemon.mod.common.pokemon.Pokemon;
import com.provismet.cobblemon.daycareplus.breeding.BreedingUtils;
import com.provismet.cobblemon.daycareplus.config.DaycarePlusOptions;
import com.provismet.cobblemon.daycareplus.feature.FertilityProperty;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hard guard: in competitive mode, a Pokémon with fertility &lt;= 0 can never breed,
 * regardless of the allow_breeding_without_fertility config flag. This protects
 * against misconfiguration and against the egg fertility ever going negative.
 */
@Mixin(value = BreedingUtils.class, remap = false)
public class DaycareStrictFertilityMixin {

    @Inject(method = "isAllowedToBreed", at = @At("HEAD"), cancellable = true)
    private static void cheesecompat$strictZero(Pokemon pokemon, CallbackInfoReturnable<Boolean> cir) {
        if (DaycarePlusOptions.doCompetitiveBreeding() && FertilityProperty.get(pokemon) <= 0) {
            cir.setReturnValue(false);
        }
    }
}
