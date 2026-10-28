package com.cheesestore.cosmetic;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Stable, reflection-friendly entry point called by FactionWar (the {@code factionwar}
 * mod looks this class up by name and invokes {@link #onKill}/{@link #onDeath} reflectively,
 * so the fully-qualified class name and method signatures below MUST NOT change).
 *
 * <p>Reflection contract (do not rename):
 * <pre>
 *   com.cheesestore.cosmetic.CosmeticCombatBridge
 *     public static void onKill(ServerPlayerEntity killer, ServerPlayerEntity victim)
 *     public static void onDeath(ServerPlayerEntity victim)
 * </pre>
 *
 * <p>It simply delegates to {@link CombatEffectsManager} (implemented by another agent),
 * keeping all the actual particle/sound work behind a single stable façade.
 */
public final class CosmeticCombatBridge {

    private CosmeticCombatBridge() {}

    /**
     * Invoked when {@code killer} kills {@code victim} in PvP. Plays the killer's equipped
     * KILL_EFFECT (and any victim DEATH_EFFECT is handled separately via {@link #onDeath}).
     */
    public static void onKill(ServerPlayerEntity killer, ServerPlayerEntity victim) {
        CombatEffectsManager.handleKill(killer, victim);
    }

    /**
     * Invoked when {@code victim} dies. Plays the victim's equipped DEATH_EFFECT.
     */
    public static void onDeath(ServerPlayerEntity victim) {
        CombatEffectsManager.handleDeath(victim);
    }
}
