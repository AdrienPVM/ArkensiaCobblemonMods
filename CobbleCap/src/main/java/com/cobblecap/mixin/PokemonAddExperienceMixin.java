package com.cobblecap.mixin;

import com.cobblemon.mod.common.api.pokemon.experience.ExperienceSource;
import com.cobblemon.mod.common.pokemon.AddExperienceResult;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblecap.CobbleCapMod;
import com.cobblecap.data.PlayerCapData;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

/**
 * Intercepts Pokemon.addExperience() and addExperienceWithPlayer() BEFORE any XP is added.
 * This prevents level-up messages from appearing when XP would push past the cap.
 *
 * Two behaviors:
 * - At or above cap: block ALL XP (amount → 0)
 * - Below cap but XP would overshoot: reduce XP to exactly reach cap level threshold
 */
@Mixin(value = Pokemon.class, remap = false)
public abstract class PokemonAddExperienceMixin {

    /** Re-entry guard: prevents recursive mixin trigger when we re-call with capped amount. */
    @Unique
    private static final ThreadLocal<Boolean> cobblecap$capping = ThreadLocal.withInitial(() -> false);

    /**
     * Block or cap addExperienceWithPlayer — main entry point for
     * exp candies and battle XP that includes the player reference.
     */
    @Inject(method = "addExperienceWithPlayer", at = @At("HEAD"), cancellable = true)
    private void onAddExperienceWithPlayer(ServerPlayerEntity player, ExperienceSource source, int amount,
                                            CallbackInfoReturnable<AddExperienceResult> cir) {
        if (cobblecap$capping.get()) return; // Re-entry from capped call — let through
        Pokemon self = (Pokemon) (Object) this;
        int cap = cobblecap$getEffectiveCap(self, player);
        if (cap <= 0) return; // No cap or not enabled

        int capped = cobblecap$capXpAmount(self, cap, amount);
        if (capped == 0) {
            int lvl = self.getLevel();
            cir.setReturnValue(new AddExperienceResult(lvl, lvl, java.util.Set.of(), 0));
        } else if (capped < amount) {
            // Re-call with reduced amount via reflection (avoids obfuscated class resolution)
            cobblecap$capping.set(true);
            try {
                AddExperienceResult res = cobblecap$invokeAddExpWithPlayer(self, player, source, capped);
                if (res != null) cir.setReturnValue(res);
            } finally {
                cobblecap$capping.set(false);
            }
        }
    }

    /**
     * Block or cap addExperience (without player) — fallback for other XP sources.
     */
    @Inject(method = "addExperience", at = @At("HEAD"), cancellable = true)
    private void onAddExperience(ExperienceSource source, int amount,
                                  CallbackInfoReturnable<AddExperienceResult> cir) {
        if (cobblecap$capping.get()) return;
        Pokemon self = (Pokemon) (Object) this;
        ServerPlayerEntity player = cobblecap$resolvePlayer(self);
        if (player == null) return;

        int cap = cobblecap$getEffectiveCap(self, player);
        if (cap <= 0) return;

        int capped = cobblecap$capXpAmount(self, cap, amount);
        if (capped == 0) {
            int lvl = self.getLevel();
            cir.setReturnValue(new AddExperienceResult(lvl, lvl, java.util.Set.of(), 0));
        } else if (capped < amount) {
            cobblecap$capping.set(true);
            try {
                // Use the 2-arg addExperience (no player) to avoid obfuscation issues
                AddExperienceResult res = self.addExperience(source, capped);
                cir.setReturnValue(res);
            } finally {
                cobblecap$capping.set(false);
            }
        }
    }

    /**
     * Returns the effective cap for this player, or -1 if XP should not be restricted.
     * Returns Integer.MAX_VALUE if expLocked (block all).
     */
    @Unique
    private static int cobblecap$getEffectiveCap(Pokemon pokemon, ServerPlayerEntity player) {
        try {
            if (CobbleCapMod.INSTANCE == null || CobbleCapMod.INSTANCE.getCapStorage() == null) return -1;

            UUID playerUuid = player.getUuid();
            Optional<PlayerCapData> dataOpt = CobbleCapMod.INSTANCE.getCapStorage().getCapData(playerUuid);
            if (dataOpt.isEmpty()) return -1;

            PlayerCapData data = dataOpt.get();

            // ExpLock: block ALL XP — use cap=0 to force full block
            if (data.isExpLocked()) return 0;

            if (!CobbleCapMod.INSTANCE.getCapConfig().isEnabled()) return -1;
            return data.getCapLevel();
        } catch (Exception e) {
            return -1; // Fail open
        }
    }

    /**
     * Calculate the max XP that can be added without exceeding the cap level.
     * Returns 0 if no XP should be added, or the capped amount.
     * Returns the original amount if it's safe.
     */
    @Unique
    private static int cobblecap$capXpAmount(Pokemon pokemon, int cap, int requestedAmount) {
        try {
            int currentLevel = pokemon.getLevel();

            // Already at or above cap — block all
            if (currentLevel >= cap) return 0;

            // ExpLock (cap == 0) — block all
            if (cap == 0) return 0;

            // Calculate XP headroom before reaching cap level
            int currentXp = pokemon.getExperience();
            // getExperienceGroup().getExperience(level) returns total XP needed to REACH that level
            int xpForCap = pokemon.getExperienceGroup().getExperience(cap);
            int headroom = xpForCap - currentXp - 1; // -1 so pokemon reaches cap-1 XP (stays below cap level)

            if (headroom <= 0) {
                // Already at the XP boundary — block
                return 0;
            }

            if (requestedAmount <= headroom) {
                // Safe — won't exceed cap
                return requestedAmount;
            }

            // Cap the amount
            return headroom;
        } catch (Exception e) {
            // Fail open — allow original amount
            return requestedAmount;
        }
    }

    /**
     * Invoke addExperienceWithPlayer via reflection to avoid compile-time resolution
     * of obfuscated ServerPlayerEntity class name.
     */
    @Unique
    private static AddExperienceResult cobblecap$invokeAddExpWithPlayer(
            Pokemon pokemon, ServerPlayerEntity player, ExperienceSource source, int amount) {
        try {
            for (Method m : Pokemon.class.getMethods()) {
                if (m.getParameterCount() == 3
                        && m.getReturnType() == AddExperienceResult.class
                        && m.getParameterTypes()[2] == int.class) {
                    Object result = m.invoke(pokemon, player, source, amount);
                    if (result instanceof AddExperienceResult aer) return aer;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Unique
    private static ServerPlayerEntity cobblecap$resolvePlayer(Pokemon pokemon) {
        try {
            for (Method m : pokemon.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && ServerPlayerEntity.class.isAssignableFrom(m.getReturnType())) {
                    Object result = m.invoke(pokemon);
                    if (result instanceof ServerPlayerEntity sp) return sp;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
