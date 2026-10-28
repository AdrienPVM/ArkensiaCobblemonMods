package com.cheeseguard.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Cross-mod fix for {@code cobblemon_spawn_alerts} (Cobblemon Spawn Alerts by
 * "Stainless Stasis", v1.9.2 inspected).
 *
 * <p>That mod's {@code AlertHandler.getConfigForPokemon(String)} resolves a
 * spawned Pokemon to its per-species config by iterating the JSON keys and
 * doing a substring check via {@code String.contains}, after normalising both
 * sides (lowercase, strip {@code [ _-]}).</p>
 *
 * <p>That substring check produces false positives for any short translated
 * name. Concrete cacademy reports:</p>
 * <ul>
 *   <li>Klink (FR "Tic") matches "Articoo" / "Articuno" / "Iron Justice" — the
 *       three letters {@code tic} appear in the middle of those names — and
 *       fires their alerts (Articoo is configured as a baby legendary).</li>
 *   <li>Gloom (FR "Ortide"): expected to fire similarly via "Gloomshade".</li>
 *   <li>Natu and other 3-4 letter translated names: same pattern.</li>
 * </ul>
 *
 * <p>This mixin redirects the {@code String.contains} call to a strict
 * {@code equalsIgnoreCase}: the lookup now requires an exact normalised match,
 * eliminating the false positives without touching any other behaviour.</p>
 *
 * <p>{@code @Pseudo} + {@code remap = false} let the mixin target a class
 * outside the Yarn-mapped namespace.</p>
 */
@Pseudo
@Mixin(targets = "io.github.stainlessstasis.alert.AlertHandler", remap = false)
public class CSAFuzzyMatchFixMixin {

    @Redirect(
        method = "getConfigForPokemon",
        at = @At(value = "INVOKE",
                 target = "Ljava/lang/String;contains(Ljava/lang/CharSequence;)Z"),
        require = 0
    )
    private static boolean cheeseguard$exactMatchOnly(String normalizedKey, CharSequence fixedInputName) {
        return normalizedKey.equalsIgnoreCase(fixedInputName.toString());
    }
}
