package com.cobblecap.data;

import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;

import java.util.Locale;

/**
 * Visual style options for diorama statues placed via /cobblecap statue.
 * NATURAL = no overlay (Pokémon and player keep their normal textures).
 * Others apply a glowing tint + a periodic particle aura that suggests the material.
 */
public enum StatueStyle {
    NATURAL   ("natural",   0xFFFFFF, null,                                0f),
    STONE     ("stone",     0xAAAAAA, ParticleTypes.WHITE_ASH,             0.06f),
    GOLD      ("gold",      0xFFD700, ParticleTypes.END_ROD,               0.04f),
    EMERALD   ("emerald",   0x50C878, ParticleTypes.HAPPY_VILLAGER,        0.05f),
    DIAMOND   ("diamond",   0x5DECF5, ParticleTypes.ENCHANTED_HIT,         0.05f),
    AMETHYST  ("amethyst",  0xB180EB, ParticleTypes.DRAGON_BREATH,         0.03f),
    COPPER    ("copper",    0xC87533, ParticleTypes.WAX_ON,                0.04f),
    NETHERITE ("netherite", 0x4D4544, ParticleTypes.SCULK_SOUL,            0.02f),
    OBSIDIAN  ("obsidian",  0x42325B, ParticleTypes.FALLING_OBSIDIAN_TEAR, 0.03f),
    CRYSTAL   ("crystal",   0xF8E7FF, ParticleTypes.GLOW,                  0.05f),
    GLASS     ("glass",     0xCCEEFF, ParticleTypes.BUBBLE_POP,            0.04f),
    FROST     ("frost",     0x88CCFF, ParticleTypes.SNOWFLAKE,             0.04f),
    SOUL      ("soul",      0x29D3DB, ParticleTypes.SOUL_FIRE_FLAME,       0.03f);

    private final String key;
    private final int glowColor;       // RGB used for the scoreboard team color
    private final ParticleEffect particle; // null = no aura
    private final float particleSpeed;

    StatueStyle(String key, int glowColor, ParticleEffect particle, float particleSpeed) {
        this.key = key; this.glowColor = glowColor; this.particle = particle; this.particleSpeed = particleSpeed;
    }

    public String key() { return key; }
    public int glowColor() { return glowColor; }
    public ParticleEffect particle() { return particle; }
    public float particleSpeed() { return particleSpeed; }
    public boolean glow() { return this != NATURAL; }

    public static StatueStyle fromKey(String key) {
        if (key == null) return NATURAL;
        for (StatueStyle s : values()) {
            if (s.key.equalsIgnoreCase(key)) return s;
        }
        try { return StatueStyle.valueOf(key.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { return NATURAL; }
    }
}
