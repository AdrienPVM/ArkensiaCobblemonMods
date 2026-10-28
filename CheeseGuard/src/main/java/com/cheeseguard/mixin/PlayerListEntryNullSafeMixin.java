package com.cheeseguard.mixin;

import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes {@link PlayerListS2CPacket.Entry}'s ServerPlayerEntity-based constructor
 * tolerate a null {@code networkHandler}. This is needed by CheeseGuard's prison
 * doppelgänger feature, which builds raw add-player packets from a fake
 * {@code ServerPlayerEntity} that was never registered with PlayerManager and so
 * has no network handler attached.
 *
 * <p>Without this redirect, the vanilla constructor NPEs on
 * {@code networkHandler.getLatency()} during packet construction.</p>
 */
@Mixin(PlayerListS2CPacket.Entry.class)
public class PlayerListEntryNullSafeMixin {

    @Redirect(
        method = "<init>(Lnet/minecraft/server/network/ServerPlayerEntity;)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/server/network/ServerPlayNetworkHandler;getLatency()I"))
    private static int cheeseguard$safeLatency(ServerPlayNetworkHandler handler) {
        return handler != null ? handler.getLatency() : 0;
    }
}
