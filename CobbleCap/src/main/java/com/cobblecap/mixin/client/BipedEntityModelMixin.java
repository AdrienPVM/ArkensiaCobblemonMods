package com.cobblecap.mixin.client;

import com.cobblecap.client.StatueFreezeGuard;
import com.cobblecap.logic.SyntheticUuidRegistry;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freezes the biped animation for CobbleCap player-skin statues.
 *
 * The doppelganger entity is a regular client-side player model — vanilla
 * applies idle breathing / limb sway from {@code BipedEntityModel.setAngles}
 * using {@code animationProgress} (driven by client-local entity.age) and
 * {@code limbAngle / limbDistance}. For our synthetic-UUID statues we re-run
 * the call with all motion arguments zeroed so the model stays perfectly
 * still (a true "statue" pose).
 *
 * The shared {@link StatueFreezeGuard} prevents infinite recursion between
 * this mixin and {@link PlayerEntityModelMixin} (PlayerEntityModel extends
 * BipedEntityModel, so when our re-entrant call hits the player override
 * its mixin re-invokes again — guard breaks the cycle).
 */
@Mixin(BipedEntityModel.class)
public class BipedEntityModelMixin {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(method = "setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V",
            at = @At("HEAD"),
            cancellable = true)
    private void cobblecap$freezeStatuePose(LivingEntity entity, float limbAngle, float limbDistance,
                                              float animationProgress, float headYaw, float headPitch,
                                              CallbackInfo ci) {
        if (StatueFreezeGuard.active()) return;
        if (!(entity instanceof PlayerEntity)) return;
        if (!SyntheticUuidRegistry.contains(entity.getUuid())) return;

        StatueFreezeGuard.set(true);
        try {
            ((BipedEntityModel) (Object) this).setAngles(entity, 0f, 0f, 0f, 0f, 0f);
        } finally {
            StatueFreezeGuard.set(false);
        }
        ci.cancel();
    }
}
