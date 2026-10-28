package com.cheesecompat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Prevents the competitive IV-averaging logic in PotentialIV.fromParents().
 *
 * In competitive mode, fromParents() replaces the normal Power item logic with
 * an averaging system that makes all held items irrelevant. By redirecting
 * doCompetitiveBreeding() to return false within this method, the standard
 * IV inheritance (Power items force specific stats, WILDCARD for others) is preserved.
 */
@Mixin(targets = "com.provismet.cobblemon.daycareplus.breeding.PotentialPokemonProperties$PotentialIV", remap = false)
public class DaycareFromParentsMixin {

    @Redirect(
        method = "fromParents",
        at = @At(value = "INVOKE",
            target = "Lcom/provismet/cobblemon/daycareplus/config/DaycarePlusOptions;doCompetitiveBreeding()Z")
    )
    private static boolean cheesecompat$vanillaFromParents() {
        return false;
    }
}
