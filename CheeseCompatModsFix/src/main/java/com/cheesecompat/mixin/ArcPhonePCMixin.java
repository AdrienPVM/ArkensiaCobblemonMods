package com.cheesecompat.mixin;

import com.cheesecompat.CheeseCompatMod;
import github.jorgaomc.network.PCOpenPacket;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Allows disabling Arc Phone PC access via config.
 * Injects into the lambda that handles the PCOpenPacket on the server side.
 */
@Mixin(value = PCOpenPacket.class, remap = false)
public class ArcPhonePCMixin {

    @Inject(method = "lambda$registerServerReceiver$1", at = @At("HEAD"), cancellable = true, require = 0)
    private static void cheesecompat$blockArcPhonePC(PCOpenPacket packet, ServerPlayNetworking.Context context, CallbackInfo ci) {
        if (!CheeseCompatMod.getConfig().arcPhonePCEnabled) {
            ServerPlayerEntity player = context.player();
            player.sendMessage(
                Text.translatable("cheesecompat.arcphone.pc_disabled").formatted(Formatting.RED), false
            );
            ci.cancel();
        }
    }
}
