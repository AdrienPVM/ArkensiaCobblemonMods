package com.aetherialbridge.mixin;

import net.minecraft.fluid.Fluids;
import net.minecraft.world.gen.feature.SpringFeature;
import net.minecraft.world.gen.feature.SpringFeatureConfig;
import net.minecraft.world.gen.feature.util.FeatureContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cancels {@code spring_lava_*} placement in dimensions whose chunk generator uses
 * sea_level &lt; -50 (Aetherial Islands floating dim sits at -55 with water column up
 * to that Y). Without this guard, vanilla still drops 20 lava springs per chunk
 * in the bottom-biased band, which produces visible lava tunnels through the water.
 *
 * Water springs are unaffected.
 */
@Mixin(SpringFeature.class)
public abstract class SpringFeatureMixin {

    @Inject(method = "generate(Lnet/minecraft/world/gen/feature/util/FeatureContext;)Z",
            at = @At("HEAD"), cancellable = true)
    private void aetherialbridge$skipLavaInLowSeaDims(FeatureContext<SpringFeatureConfig> ctx,
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (ctx.getConfig().state.getFluid() != Fluids.LAVA) return;
        if (ctx.getGenerator().getSeaLevel() >= -25) return;
        cir.setReturnValue(false);
    }
}
