package com.aetherialbridge.mixin;

import net.minecraft.world.gen.feature.util.FeatureContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cancels Minerally feature placement in dimensions whose chunk generator uses
 * sea_level &lt; -25 (Aetherial Islands floating dim sits at -30).
 *
 * Minerally registers via {@code BiomeModifications.addFeatureInOverWorldBiome},
 * so its features get attached to every biome tagged {@code #minecraft:is_overworld}.
 * Since CheeseGuard's floating-islands dim reuses the vanilla overworld biome
 * source, Minerally features inherit into that dim and choke {@code PositionChecker
 * .isValidPositionDown} on the unique floating geometry — produces "Empty height
 * range: [-55 absolute--60 absolute]" warnings and watchdog timeouts as the
 * placement scan never converges.
 *
 * Pseudo-mixin because Minerally is a soft runtime dependency. Both yarn and
 * intermediary method names are listed so the injector binds without a refmap.
 */
@Pseudo
@Mixin(targets = {
    "theone_ss.minerally.generation.feature.OverWorldMineralFeature",
    "theone_ss.minerally.generation.feature.DeepslateMineralFeature",
    "theone_ss.minerally.generation.feature.MineralRockFeature",
    "theone_ss.minerally.generation.feature.CrystalFeature"
}, remap = false)
public abstract class MinerallyFeatureMixin {

    @Inject(method = {
        "generate(Lnet/minecraft/world/gen/feature/util/FeatureContext;)Z",
        "method_13151(Lnet/minecraft/class_5821;)Z"
    }, at = @At("HEAD"), cancellable = true, require = 0)
    private void aetherialbridge$skipMinerallyInLowSeaDims(FeatureContext<?> ctx,
                                                           CallbackInfoReturnable<Boolean> cir) {
        try {
            if (ctx.getGenerator().getSeaLevel() < -25) {
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) {
            // never block worldgen on a lookup failure
        }
    }
}
