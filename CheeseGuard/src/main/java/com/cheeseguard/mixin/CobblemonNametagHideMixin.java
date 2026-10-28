package com.cheeseguard.mixin;

import com.cheeseguard.client.CheeseGuardClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client mixin targeting Cobblemon's PokemonRenderer.renderNameTag().
 * Cobblemon overrides renderLabelIfPresent completely without calling super,
 * so the vanilla EntityRenderer mixin doesn't catch Pokemon labels.
 * This mixin intercepts Cobblemon's own label rendering.
 * @Pseudo makes the target class optional — no crash if Cobblemon is not installed.
 */
@Pseudo
@Mixin(targets = "com.cobblemon.mod.common.client.render.pokemon.PokemonRenderer", remap = false)
public abstract class CobblemonNametagHideMixin {

    /**
     * Intercept renderNameTag using explicit descriptor since Cobblemon is not on compile classpath.
     * Descriptor: (PokemonEntity, Text, MatrixStack, VertexConsumerProvider, int, float)V
     * Using intermediary class names since remap=false.
     */
    @Inject(
        method = "renderNameTag(Lcom/cobblemon/mod/common/entity/pokemon/PokemonEntity;Lnet/minecraft/class_2561;Lnet/minecraft/class_4587;Lnet/minecraft/class_4597;IF)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void cheeseguard$hideCobblemonNametag(CallbackInfo ci) {
        if (CheeseGuardClient.hideNametags) {
            ci.cancel();
        }
    }
}
