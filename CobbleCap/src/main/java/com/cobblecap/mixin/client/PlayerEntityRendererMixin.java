package com.cobblecap.mixin.client;

import com.cobblecap.client.StatueStyleClient;
import com.cobblecap.logic.SyntheticUuidRegistry;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Swap the rendered skin texture for CobbleCap doppelganger statues.
 * For any entity carrying a synthetic UUID known to {@link SyntheticUuidRegistry}
 * AND with a StatueStyle other than NATURAL, we return our pre-baked PNG at
 * {@code assets/cobblecap/textures/statue/<style>.png} instead of the player's
 * normal skin. NATURAL doppels fall through to vanilla resolution (real skin).
 */
@Mixin(PlayerEntityRenderer.class)
public class PlayerEntityRendererMixin {

    @Inject(method = "getTexture(Lnet/minecraft/entity/Entity;)Lnet/minecraft/util/Identifier;",
            at = @At("HEAD"),
            cancellable = true)
    private void cobblecap$statueTexture(Entity entity, CallbackInfoReturnable<Identifier> cir) {
        if (!(entity instanceof PlayerEntity)) return;
        if (!SyntheticUuidRegistry.contains(entity.getUuid())) return;
        Identifier tex = StatueStyleClient.textureFor(entity.getUuid());
        if (tex != null) cir.setReturnValue(tex);
    }
}
