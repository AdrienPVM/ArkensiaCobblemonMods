package com.cheesemusic.client;

import net.minecraft.client.sound.AbstractSoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;

/**
 * A sound instance with mutable volume for smooth fading.
 * Uses SoundCategory.MUSIC so it respects the Music volume slider.
 */
public class ZoneMusicSoundInstance extends AbstractSoundInstance {

    private float musicVolume;
    private boolean done;

    public ZoneMusicSoundInstance(Identifier id, SoundCategory category, float initialVolume) {
        super(id, category, Random.create());
        this.musicVolume = initialVolume;
        this.volume = initialVolume;
        this.repeat = false;
        this.x = 0;
        this.y = 0;
        this.z = 0;
        this.relative = true; // relative to listener (no positional audio)
    }

    public void setMusicVolume(float volume) {
        this.musicVolume = volume;
        this.volume = volume;
    }

    @Override
    public float getVolume() {
        return musicVolume;
    }

    public boolean isDone() {
        return done;
    }

    public void markDone() {
        this.done = true;
    }
}
