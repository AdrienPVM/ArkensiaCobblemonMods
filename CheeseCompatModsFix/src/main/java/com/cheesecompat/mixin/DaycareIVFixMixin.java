package com.cheesecompat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Prevents DaycarePlus competitive mode from overriding the vanilla IV/Nature
 * inheritance algorithm. With this mixin, competitive mode still tracks fertility
 * and consumes held items, but Destiny Knot, Power items, and Everstone work
 * exactly like in standard (non-competitive) breeding.
 *
 * Targets: setIVs() and setNature() in PotentialPokemonProperties.
 * Each method checks doCompetitiveBreeding() — we redirect those calls to return false,
 * so the competitive IV-averaging and nature-blocking logic is skipped.
 */
@Mixin(targets = "com.provismet.cobblemon.daycareplus.breeding.PotentialPokemonProperties", remap = false)
public class DaycareIVFixMixin {

    @Redirect(
        method = "setIVs",
        at = @At(value = "INVOKE",
            target = "Lcom/provismet/cobblemon/daycareplus/config/DaycarePlusOptions;doCompetitiveBreeding()Z")
    )
    private boolean cheesecompat$vanillaIVs() {
        return false;
    }

    @Redirect(
        method = "setNature",
        at = @At(value = "INVOKE",
            target = "Lcom/provismet/cobblemon/daycareplus/config/DaycarePlusOptions;doCompetitiveBreeding()Z")
    )
    private boolean cheesecompat$vanillaNature() {
        return false;
    }
}
