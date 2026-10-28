package com.aetherialbridge.mixin;

import com.cheeseguard.api.CheeseGuardWorldgenApi;
import net.minecraft.world.gen.feature.util.FeatureContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cancels Cobblemon raid den placement in CheeseGuard custom dimensions whose
 * config has {@code raidDensEnabled = false}. CobblemonRaidDens has no built-in
 * per-dimension blacklist; its placed feature is added to every biome tagged
 * {@code #minecraft:is_overworld}, so a mixin is the only granular hook.
 *
 * Targets the cobblemonraiddens RaidDenFeature class via {@link Pseudo} since
 * the mod is a soft dependency. Both yarn and intermediary method names are
 * listed so the injector binds without needing a refmap on the third-party class.
 */
@Pseudo
@Mixin(targets = "com.necro.raid.dens.common.worldgen.RaidDenFeature", remap = false)
public abstract class RaidDenFeatureMixin {

    @Inject(method = {
        "generate(Lnet/minecraft/world/gen/feature/util/FeatureContext;)Z",
        "method_13151(Lnet/minecraft/class_5821;)Z"
    }, at = @At("HEAD"), cancellable = true, require = 0)
    private void aetherialbridge$skipDisabledDims(FeatureContext<?> ctx,
                                                  CallbackInfoReturnable<Boolean> cir) {
        try {
            var world = ctx.getWorld().toServerWorld();
            String worldId = world.getRegistryKey().getValue().toString();
            if (!CheeseGuardWorldgenApi.isRaidDensEnabled(worldId)) {
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) {
            // never block worldgen on a lookup failure
        }
    }
}
