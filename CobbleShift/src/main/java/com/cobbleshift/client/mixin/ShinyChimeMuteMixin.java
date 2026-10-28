package com.cobbleshift.client.mixin;

import com.cobbleshift.client.TransformRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Silences Cobblemon's shiny ambient chime — but only when the source is one of our morphed
 * ghost players. Real wild and captured shiny Pokémon keep their sound because they aren't
 * registered in TransformRenderer.
 *
 * <p>The mixin targets vanilla {@link SoundManager#play(SoundInstance)} (always present, no
 * Cobblemon dependency required at compile time) and matches the sound by string identifier,
 * so it works without referencing any Cobblemon class.
 *
 * <p>Match heuristic: the chime fires within ~1 block of the entity that triggered the
 * particle. We mute it iff a shiny morph player is within {@code MUTE_RADIUS} blocks of the
 * sound origin. If a real shiny happens to overlap a morphed player at that exact tick we'd
 * mute the real one too — acceptable trade-off and very rare in practice.
 */
@Mixin(SoundManager.class)
public class ShinyChimeMuteMixin {

    /** Cobblemon spawns the shiny particle slightly offset from the entity origin; 4 blocks
     *  is generous enough to cover typical entity sizes without bleeding to neighbours. */
    private static final double MUTE_RADIUS = 4.0;

    @Inject(method = "play(Lnet/minecraft/client/sound/SoundInstance;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void cobbleshift$muteShinyChime(SoundInstance sound, CallbackInfo ci) {
        try {
            Identifier id = sound.getId();
            if (id == null || !"cobblemon".equals(id.getNamespace())) return;
            String path = id.getPath();
            // Both shiny chimes share the "shiny_*_chime" suffix; matching once covers
            // shiny_ambient_chime and shiny_chime + the wild_ variants.
            if (!path.contains("shiny") || !path.contains("chime")) return;
            // Self-targeting fast path: if the local player is the shiny morph, sound origin
            // may be (0,0,0) until first tick — fall back to player position.
            double x = sound.getX(), y = sound.getY(), z = sound.getZ();
            if (x == 0 && y == 0 && z == 0) {
                MinecraftClient mc = MinecraftClient.getInstance();
                PlayerEntity self = mc != null ? mc.player : null;
                if (self != null) { x = self.getX(); y = self.getY(); z = self.getZ(); }
            }
            if (TransformRenderer.hasNearbyGhostShiny(x, y, z, MUTE_RADIUS)) {
                ci.cancel();
            }
        } catch (Exception ignored) {
            // Fail-open: never break sound playback because of our filter.
        }
    }
}
