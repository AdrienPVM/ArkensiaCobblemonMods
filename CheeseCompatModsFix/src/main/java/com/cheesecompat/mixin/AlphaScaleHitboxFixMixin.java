package com.cheesecompat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * Fixes CobblemonAlphas pokémon being silently uncatchable — pokeballs bounce
 * off without triggering the capture animation, no chat message, no log.
 *
 * <h3>Root cause</h3>
 * <p>Alphas spawn with some corrupt internal entity state that prevents
 * {@code EmptyPokeBallEntity.onEntityHit} from firing. The exact field/state
 * is unknown after extensive bytecode investigation: Cobblemon's onEntityHit
 * code paths all send a chat message when blocking, but the symptom is
 * silent. The vanilla {@code /data merge entity ... {Pokemon:{ScaleModifier:2.5f}}}
 * command (with the SAME scale value, no actual change) makes the alpha
 * catchable — proving the bug is some entity state that {@code readNbt()}
 * resets, NOT a scale/hitbox mismatch.</p>
 *
 * <h3>Fix</h3>
 * <p>On the first tick of an entity flagged {@code IS_ALPHA: true} in its
 * {@code Pokemon.PersistentData}, perform an NBT round-trip
 * ({@code writeNbt} → {@code readNbt}) which mimics what {@code /data merge}
 * does internally. Side effect: the corrupt state is reset, the alpha
 * becomes catchable. Visual scale (2.5×), AABB, IS_ALPHA flag, IVs, abilities
 * and loot drops are all preserved unchanged.</p>
 *
 * <p>For non-alpha pokémon this mixin is a fast no-op (one boolean check,
 * one PersistentData read).</p>
 *
 * <p>{@code @Pseudo} + {@code remap = false} let the mixin target a Cobblemon
 * class outside our Yarn-mapped namespace; {@code method = "method_5773"} is
 * the intermediary name for {@code Entity.tick()} (Yarn names aren't
 * auto-translated when {@code remap = false}).</p>
 */
@Pseudo
@Mixin(targets = "com.cobblemon.mod.common.entity.pokemon.PokemonEntity", remap = false)
public abstract class AlphaScaleHitboxFixMixin {

    @Unique
    private boolean cheesecompat$alphaFixApplied = false;

    @Unique
    private static volatile Method cheesecompat$getPokemonMethod;
    @Unique
    private static volatile Method cheesecompat$getPersistentDataMethod;
    @Unique
    private static volatile boolean cheesecompat$reflectionInitFailed = false;

    @Inject(method = "method_5773", at = @At("HEAD"), require = 0, remap = false)
    private void cheesecompat$applyAlphaCatchabilityFix(CallbackInfo ci) {
        if (cheesecompat$alphaFixApplied || cheesecompat$reflectionInitFailed) return;
        try {
            // Skip captured alphas. Sending one out from a pokeball creates a fresh
            // PokemonEntity via Cobblemon's Pokemon.sendOut() which does NOT carry
            // the AlphaSpawner state corruption. Running the writeNbt/readNbt
            // round-trip on a sent-out alpha resets transient send-out fields
            // (renderer/animation/team-link state) and the entity never renders —
            // the player throws the ball, the pokémon never appears.
            // Wild alphas have no owner UUID; only those need the fix.
            net.minecraft.entity.Entity self = (net.minecraft.entity.Entity) (Object) this;
            if (self instanceof net.minecraft.entity.passive.TameableEntity tameable
                    && tameable.getOwnerUuid() != null) {
                cheesecompat$alphaFixApplied = true;
                return;
            }

            if (!cheesecompat$ensureReflection()) {
                cheesecompat$reflectionInitFailed = true;
                cheesecompat$alphaFixApplied = true;
                return;
            }

            Object pokemon = cheesecompat$getPokemonMethod.invoke(this);
            if (pokemon == null) return; // not yet attached — wait for next tick

            cheesecompat$alphaFixApplied = true;

            // Detect IS_ALPHA flag (CobblemonAlphas-specific PersistentData boolean)
            Object nbt = cheesecompat$getPersistentDataMethod.invoke(pokemon);
            if (!(nbt instanceof net.minecraft.nbt.NbtCompound nbtComp)) return;
            if (!nbtComp.getBoolean("IS_ALPHA")) return;

            // NBT round-trip: serialize and re-read full entity state.
            // This is what /data merge does internally and is the only
            // mechanism known to reset the corrupt state preventing capture.
            net.minecraft.nbt.NbtCompound full = new net.minecraft.nbt.NbtCompound();
            self.writeNbt(full);
            self.readNbt(full);
        } catch (Throwable t) {
            // Never break the entity tick — silently disable on any failure.
            cheesecompat$alphaFixApplied = true;
            cheesecompat$reflectionInitFailed = true;
        }
    }

    @Unique
    private static boolean cheesecompat$ensureReflection() {
        if (cheesecompat$getPokemonMethod != null && cheesecompat$getPersistentDataMethod != null) {
            return true;
        }
        synchronized (AlphaScaleHitboxFixMixin.class) {
            if (cheesecompat$getPokemonMethod != null && cheesecompat$getPersistentDataMethod != null) {
                return true;
            }
            try {
                Class<?> entityCls = Class.forName(
                    "com.cobblemon.mod.common.entity.pokemon.PokemonEntity");
                for (Method m : entityCls.getMethods()) {
                    if ("getPokemon".equals(m.getName()) && m.getParameterCount() == 0) {
                        cheesecompat$getPokemonMethod = m;
                        break;
                    }
                }
                if (cheesecompat$getPokemonMethod == null) return false;

                Class<?> pokemonCls = cheesecompat$getPokemonMethod.getReturnType();
                for (Method m : pokemonCls.getMethods()) {
                    if ("getPersistentData".equals(m.getName()) && m.getParameterCount() == 0) {
                        cheesecompat$getPersistentDataMethod = m;
                        break;
                    }
                }
                return cheesecompat$getPersistentDataMethod != null;
            } catch (Throwable t) {
                return false;
            }
        }
    }
}
