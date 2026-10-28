package com.cobbleshift;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps Pokémon types to passive Minecraft status effects.
 * Each type gives a themed buff when the matching Pokémon is in the player's lead slot.
 */
public enum AbilityType {

    FIRE("fire", "Flamme Intérieure"),
    WATER("water", "Nage Rapide"),
    GRASS("grass", "Photosynthèse"),
    ELECTRIC("electric", "Charge Statique"),
    ICE("ice", "Coeur de Glace"),
    FLYING("flying", "Plume Légère"),
    GROUND("ground", "Ancrage"),
    ROCK("rock", "Peau de Pierre"),
    STEEL("steel", "Blindage"),
    POISON("poison", "Immunité Toxique"),
    PSYCHIC("psychic", "Perception Extra"),
    GHOST("ghost", "Ombre Furtive"),
    DARK("dark", "Vision Nocturne"),
    DRAGON("dragon", "Fureur Draconique"),
    FAIRY("fairy", "Grâce Féerique"),
    FIGHTING("fighting", "Esprit Combatif"),
    BUG("bug", "Agilité Insecte"),
    NORMAL("normal", "Endurance");

    public final String typeId;
    public final String displayName;

    AbilityType(String typeId, String displayName) {
        this.typeId = typeId;
        this.displayName = displayName;
    }

    /**
     * Get the status effects for this type.
     * @param durationTicks how long effects last (should overlap with refresh interval)
     */
    public List<StatusEffectInstance> getEffects(int durationTicks) {
        List<StatusEffectInstance> effects = new ArrayList<>();
        boolean ambient = true;
        boolean showParticles = false;
        boolean showIcon = true;

        switch (this) {
            case FIRE -> effects.add(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, durationTicks, 0, ambient, showParticles, showIcon));
            case WATER -> {
                effects.add(new StatusEffectInstance(StatusEffects.WATER_BREATHING, durationTicks, 0, ambient, showParticles, showIcon));
                effects.add(new StatusEffectInstance(StatusEffects.DOLPHINS_GRACE, durationTicks, 0, ambient, showParticles, showIcon));
            }
            case GRASS -> effects.add(new StatusEffectInstance(StatusEffects.REGENERATION, durationTicks, 0, ambient, showParticles, showIcon));
            case ELECTRIC -> effects.add(new StatusEffectInstance(StatusEffects.SPEED, durationTicks, 0, ambient, showParticles, showIcon));
            case ICE -> effects.add(new StatusEffectInstance(StatusEffects.RESISTANCE, durationTicks, 0, ambient, showParticles, showIcon));
            case FLYING -> effects.add(new StatusEffectInstance(StatusEffects.SLOW_FALLING, durationTicks, 0, ambient, showParticles, showIcon));
            case GROUND -> effects.add(new StatusEffectInstance(StatusEffects.RESISTANCE, durationTicks, 0, ambient, showParticles, showIcon));
            case ROCK -> effects.add(new StatusEffectInstance(StatusEffects.RESISTANCE, durationTicks, 0, ambient, showParticles, showIcon));
            case STEEL -> {
                effects.add(new StatusEffectInstance(StatusEffects.RESISTANCE, durationTicks, 0, ambient, showParticles, showIcon));
                effects.add(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, durationTicks, 0, ambient, showParticles, showIcon));
            }
            case POISON -> // Immunity to poison — we give the same effect to "cure" it on tick
                effects.add(new StatusEffectInstance(StatusEffects.POISON, 1, 0, true, false, false)); // instant clear
            case PSYCHIC -> effects.add(new StatusEffectInstance(StatusEffects.GLOWING, durationTicks, 0, ambient, showParticles, showIcon));
            case GHOST -> effects.add(new StatusEffectInstance(StatusEffects.INVISIBILITY, durationTicks, 0, ambient, showParticles, showIcon));
            case DARK -> effects.add(new StatusEffectInstance(StatusEffects.NIGHT_VISION, durationTicks, 0, ambient, showParticles, showIcon));
            case DRAGON -> effects.add(new StatusEffectInstance(StatusEffects.STRENGTH, durationTicks, 0, ambient, showParticles, showIcon));
            case FAIRY -> {
                effects.add(new StatusEffectInstance(StatusEffects.REGENERATION, durationTicks, 0, ambient, showParticles, showIcon));
                effects.add(new StatusEffectInstance(StatusEffects.LUCK, durationTicks, 0, ambient, showParticles, showIcon));
            }
            case FIGHTING -> {
                effects.add(new StatusEffectInstance(StatusEffects.STRENGTH, durationTicks, 0, ambient, showParticles, showIcon));
                effects.add(new StatusEffectInstance(StatusEffects.SPEED, durationTicks, 0, ambient, showParticles, showIcon));
            }
            case BUG -> effects.add(new StatusEffectInstance(StatusEffects.JUMP_BOOST, durationTicks, 0, ambient, showParticles, showIcon));
            case NORMAL -> {} // no special effect
        }
        return effects;
    }

    /**
     * Find AbilityType by Cobblemon type name (case-insensitive).
     */
    public static AbilityType fromTypeName(String typeName) {
        if (typeName == null) return NORMAL;
        String lower = typeName.toLowerCase().trim();
        for (AbilityType t : values()) {
            if (t.typeId.equals(lower)) return t;
        }
        return NORMAL;
    }
}
