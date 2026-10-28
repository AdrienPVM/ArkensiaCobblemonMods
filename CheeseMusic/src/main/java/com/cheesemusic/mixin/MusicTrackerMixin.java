package com.cheesemusic.mixin;

import com.cheesemusic.client.ZoneMusicPlayer;
import net.minecraft.client.sound.MusicTracker;
import net.minecraft.client.sound.SoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppress vanilla MusicTracker while zone music is active.
 * Stops any currently tracked sound AND prevents new music from starting.
 */
@Mixin(MusicTracker.class)
public abstract class MusicTrackerMixin {

    @Shadow private SoundInstance current;
    @Shadow public abstract void stop();

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
    private void cheesemusic$suppressVanillaMusic(CallbackInfo ci) {
        if (ZoneMusicPlayer.getInstance().isActive()) {
            if (this.current != null) {
                this.stop();
            }
            ci.cancel();
        }
    }
}
