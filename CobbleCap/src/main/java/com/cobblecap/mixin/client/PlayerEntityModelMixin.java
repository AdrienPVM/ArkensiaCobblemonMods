package com.cobblecap.mixin.client;

import com.cobblecap.client.StatueFreezeGuard;
import com.cobblecap.logic.SyntheticUuidRegistry;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Player-model freeze for CobbleCap statues. PlayerEntityModel.setAngles
 * is an override of BipedEntityModel.setAngles and contains its own
 * additional logic (sleeve sway, ear tracking). A mixin on the parent
 * BipedEntityModel only catches the super.setAngles(...) call inside this
 * override — by then sleeves/ears were already animated. We also intercept
 * here at HEAD to fully short-circuit the player path.
 */
@Mixin(PlayerEntityModel.class)
public class PlayerEntityModelMixin {

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(method = "setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V",
            at = @At("HEAD"),
            cancellable = true)
    private void cobblecap$freezePlayerPose(LivingEntity entity, float limbAngle, float limbDistance,
                                              float animationProgress, float headYaw, float headPitch,
                                              CallbackInfo ci) {
        if (StatueFreezeGuard.active()) return;
        if (!SyntheticUuidRegistry.contains(entity.getUuid())) return;

        StatueFreezeGuard.set(true);
        try {
            ((PlayerEntityModel) (Object) this).setAngles(entity, 0f, 0f, 0f, 0f, 0f);
        } finally {
            StatueFreezeGuard.set(false);
        }
        ci.cancel();
    }
}
