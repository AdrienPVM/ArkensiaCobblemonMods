package com.aetherialbridge.mixin;

import net.minecraft.world.gen.feature.LakeFeature;
import net.minecraft.world.gen.feature.util.FeatureContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cancels all lake placements in dimensions whose chunk generator uses sea_level
 * &lt; -50. Vanilla overworld biome gen only schedules {@code lake_lava_surface} /
 * {@code lake_lava_underground}; there are no water lakes to preserve.
 *
 * Pairs with {@link SpringFeatureMixin} to keep the floating-dim seafloor lava-free.
 */
@Mixin(LakeFeature.class)
public abstract class LakeFeatureMixin {

    @Inject(method = "generate(Lnet/minecraft/world/gen/feature/util/FeatureContext;)Z",
            at = @At("HEAD"), cancellable = true)
    private void aetherialbridge$skipLakeInLowSeaDims(FeatureContext<LakeFeature.Config> ctx,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (ctx.getGenerator().getSeaLevel() >= -25) return;
        cir.setReturnValue(false);
    }
}
