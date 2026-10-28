package com.cheesecompat.mixin;

import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.pokemon.IVs;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.provismet.cobblemon.daycareplus.breeding.PotentialPokemonProperties;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Two-part change to setIVs:
 *
 *  1) Drop the default forcedIVs from 3 to 0. Without this, the second loop
 *     always picks 3 random stats and copies one parent's exact value,
 *     producing the "min/max" effect the user complained about. The Destiny
 *     Knot branch still overrides forcedIVs to 5 (iconst_5), so wearing a
 *     Destiny Knot keeps inheriting 5 stats from parents.
 *
 *  2) Redirect the random fallback (third iv.set call, ordinal=2) to write
 *     the integer average of the two parents' IV for that stat.
 *
 * Net result:
 *   - No items   → 0 forced, all 6 IVs averaged.
 *   - Destiny Knot → 5 inherited from parents (random pick), 1 averaged.
 *   - Power item → that stat forced from holder, others averaged.
 *   - Power item + Destiny Knot → forced stat + 4 inherited + 1 averaged
 *     (Loop 1 decrements forcedIVs once per Power item, exactly like before).
 */
@Mixin(value = PotentialPokemonProperties.class, remap = false)
public class DaycareIVAverageMixin {

    @Shadow @Final private Pokemon primary;
    @Shadow @Final private Pokemon secondary;

    @ModifyConstant(
        method = "setIVs",
        constant = @Constant(intValue = 3)
    )
    private int cheesecompat$noBaseInherit(int original) {
        return 0;
    }

    @Redirect(
        method = "setIVs",
        at = @At(value = "INVOKE",
            target = "Lcom/cobblemon/mod/common/pokemon/IVs;set(Lcom/cobblemon/mod/common/api/pokemon/stats/Stat;I)V",
            ordinal = 2)
    )
    private void cheesecompat$averageIV(IVs iv, Stat stat, int randomValue) {
        int p1 = primary.getIvs().getOrDefault(stat);
        int p2 = secondary.getIvs().getOrDefault(stat);
        iv.set(stat, (p1 + p2) / 2);
    }
}
