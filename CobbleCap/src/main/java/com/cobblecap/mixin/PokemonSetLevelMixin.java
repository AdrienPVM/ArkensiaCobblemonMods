package com.cobblecap.mixin;

import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblecap.CobbleCapMod;
import com.cobblecap.data.PlayerCapData;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

/**
 * Safety net: intercepts ALL Pokemon.setLevel() calls, including from mods that
 * bypass Cobblemon's event system (e.g. Cobblemon Utility+ MasterCandyItem).
 * Enforces both the level cap and the explock toggle.
 * Also clamps experience to prevent XP accumulation beyond the cap level.
 */
@Mixin(value = Pokemon.class, remap = false)
public abstract class PokemonSetLevelMixin {

    @Inject(method = "setLevel", at = @At("HEAD"), cancellable = true)
    private void onSetLevel(int newLevel, CallbackInfo ci) {
        try {
            // Allow external mods to temporarily bypass level enforcement
            // (e.g. Battle Tower forced levels via CheeseCompat)
            if (CobbleCapMod.LEVEL_FORCE_ACTIVE.get()) return;

            Pokemon self = (Pokemon) (Object) this;
            int currentLevel = self.getLevel();

            // Only block level INCREASES (allow decreases for admin/debug)
            if (newLevel <= currentLevel) return;

            // Resolve the owning player via reflection (avoids intermediary mapping issues)
            ServerPlayerEntity player = null;
            for (Method m : self.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && ServerPlayerEntity.class.isAssignableFrom(m.getReturnType())) {
                    Object result = m.invoke(self);
                    if (result instanceof ServerPlayerEntity sp) {
                        player = sp;
                        break;
                    }
                }
            }
            if (player == null) return;

            // Not initialized yet? Skip
            if (CobbleCapMod.INSTANCE == null || CobbleCapMod.INSTANCE.getCapStorage() == null) return;

            UUID playerUuid = player.getUuid();
            Optional<PlayerCapData> dataOpt = CobbleCapMod.INSTANCE.getCapStorage().getCapData(playerUuid);
            if (dataOpt.isEmpty()) return;

            PlayerCapData data = dataOpt.get();

            // ── ExpLock: block ALL level increases (works even if cap system disabled) ──
            if (data.isExpLocked()) {
                clampExperience(self, currentLevel);
                ci.cancel();
                return;
            }

            // ── Level cap: block level beyond cap (only if cap system enabled) ──
            if (!CobbleCapMod.INSTANCE.getCapConfig().isEnabled()) return;
            int cap = data.getCapLevel();
            if (newLevel > cap) {
                clampExperience(self, cap);
                ci.cancel();
            }
        } catch (Exception ignored) {
            // Fail open — never crash the game
        }
    }

    /**
     * Clamp the Pokemon's experience to the exact amount needed for the given level.
     * This prevents XP accumulation beyond the cap — without this, the Pokemon's
     * internal XP total keeps growing even though setLevel is blocked, causing
     * level-up messages and visual glitches on the next XP trigger.
     */
    private void clampExperience(Pokemon pokemon, int targetLevel) {
        try {
            int xpForTargetLevel = pokemon.getExperienceGroup().getExperience(targetLevel);
            int currentXp = pokemon.getExperience();
            if (currentXp > xpForTargetLevel) {
                // Use setExperience$common to set raw XP without triggering level recalculation
                Method setExp = Pokemon.class.getMethod("setExperience$common", int.class);
                setExp.invoke(pokemon, xpForTargetLevel);
            }
        } catch (Exception e) {
            // Fail silently — XP clamping is best-effort
        }
    }
}
