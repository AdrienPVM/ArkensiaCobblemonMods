package com.cheesekart.client.mixin;

import com.cheesekart.client.CheeseKartClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.BoatEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.vehicle.BoatEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides boat entities used as kart vehicles.
 * Primary mechanism is setInvisible(true) on the server — this mixin is a safety net.
 */
@Mixin(BoatEntityRenderer.class)
public class BoatRenderMixin {

    @Inject(method = "render(Lnet/minecraft/entity/vehicle/BoatEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void cheesekart$hideKartBoat(BoatEntity entity, float yaw, float tickDelta,
                                          MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                          int light, CallbackInfo ci) {
        if (CheeseKartClient.isKartBoat(entity.getId())) {
            ci.cancel();
        }
    }
}
