package com.cobblecap.mixin.client;

import com.cobblecap.logic.SyntheticUuidRegistry;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels BipedEntityModel.animateModel for our synthetic-UUID statues.
 * animateModel runs BEFORE setAngles and applies pre-pose offsets:
 * leaning lean, swim/crawl angles, rider/sneak crouch. Without this mixin,
 * a statue placed near a swimmer-pose source would mirror that pose.
 *
 * Method signature in Yarn 1.21.1: animateModel(LivingEntity, F, F, F).
 */
@Mixin(BipedEntityModel.class)
public class BipedAnimateModelMixin {

    @Inject(method = "animateModel(Lnet/minecraft/entity/LivingEntity;FFF)V",
            at = @At("HEAD"),
            cancellable = true)
    private void cobblecap$freezeAnimateModel(LivingEntity entity, float limbAngle, float limbDistance,
                                                float tickDelta, CallbackInfo ci) {
        if (!(entity instanceof PlayerEntity)) return;
        if (!SyntheticUuidRegistry.contains(entity.getUuid())) return;
        ci.cancel();
    }
}
