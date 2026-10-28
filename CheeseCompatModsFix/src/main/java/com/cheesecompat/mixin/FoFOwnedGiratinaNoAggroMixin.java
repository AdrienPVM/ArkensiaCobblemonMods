package com.cheesecompat.mixin;

import me.rufia.fightorflight.goals.PokemonAttackGoal;
import net.minecraft.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Empêche le comportement Warden (PokemonAttackGoal de fightorflight) sur les
 * Giratina possédés par un joueur.
 *
 * Voir CheeseCompatMod doc pour le diagnostic complet : l'AI agressive vient
 * de fightorflight, pas de cobblewarden_boss. Sans ce filtre, un Giratina
 * capturé lance des attaques.
 *
 * Distinction :
 *   - Boss spawné par CCMF/cobblewarden_boss → command tag "cheesecompat_boss"
 *     → goal autorisé.
 *   - Giratina avec un owner Cobblemon → goal canUse() forcé à false.
 *
 * <h3>v1.2.40 — fix: removed @Shadow</h3>
 *
 * <p>v1.2.39 used {@code @Shadow protected Entity pokemonEntity;} but the
 * actual field type in {@code PokemonAttackGoal} is {@code PokemonEntity}, not
 * {@code Entity}. Mixin @Shadow requires exact type match → crashed at server
 * boot with {@code InvalidMixinException: @Shadow field pokemonEntity was not
 * located in the target class}.</p>
 *
 * <p>Fix: drop @Shadow entirely. The mixin handler captures {@code this} (a
 * {@code PokemonAttackGoal} instance after mixin application) and reads the
 * {@code pokemonEntity} field via reflection. The {@code Field} is cached on
 * first call. No compile-time dependency on Cobblemon's
 * {@code PokemonEntity} class hierarchy is needed.</p>
 *
 * <h3>Logging</h3>
 *
 * <p>INFO once at reflection init (success vs. incomplete). INFO once per
 * unique Pokémon entity when the filter first activates. WARN once for runtime
 * failures (rate-limited).</p>
 */
@Mixin(value = PokemonAttackGoal.class, remap = false)
public abstract class FoFOwnedGiratinaNoAggroMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseCompatModsFix/GiratinaFilter");

    private static volatile boolean reflectInit = false;
    private static Field pokemonEntityField;          // PokemonAttackGoal.pokemonEntity (type PokemonEntity)
    private static Method getPokemonMethod;            // PokemonEntity#getPokemon() : Pokemon
    private static Method getSpeciesMethod;            // Pokemon#getSpecies() : Species
    private static Method getSpeciesNameMethod;        // Species#getName() : String
    private static Method getOwnerUuidMethod;          // Pokemon#getOwnerUUID() : UUID

    // Avoid logging the same entity twice
    private static final ConcurrentHashMap<UUID, Boolean> LOGGED_BLOCKS = new ConcurrentHashMap<>();
    private static final AtomicLong RUNTIME_FAILURES = new AtomicLong(0);
    private static final AtomicLong TOTAL_BLOCKS = new AtomicLong(0);

    private static synchronized void initReflect(Class<?> goalCls) {
        if (reflectInit) return;
        try {
            // 1. PokemonAttackGoal.pokemonEntity
            try {
                pokemonEntityField = goalCls.getDeclaredField("pokemonEntity");
                pokemonEntityField.setAccessible(true);
            } catch (NoSuchFieldException e) {
                // Walk superclass too
                Class<?> c = goalCls.getSuperclass();
                while (c != null && pokemonEntityField == null) {
                    try {
                        pokemonEntityField = c.getDeclaredField("pokemonEntity");
                        pokemonEntityField.setAccessible(true);
                    } catch (NoSuchFieldException ne) {
                        c = c.getSuperclass();
                    }
                }
            }
            if (pokemonEntityField == null) {
                LOGGER.warn("[CCMF] FoF Giratina filter — reflection init INCOMPLETE: pokemonEntity field NOT FOUND on {}. Filter will NOT activate.",
                        goalCls.getName());
                reflectInit = true;
                return;
            }

            // 2. Walk methods on PokemonEntity (return type of the field)
            Class<?> entityCls = pokemonEntityField.getType();
            for (Method m : entityCls.getMethods()) {
                if ("getPokemon".equals(m.getName()) && m.getParameterCount() == 0) {
                    getPokemonMethod = m;
                    break;
                }
            }
            if (getPokemonMethod != null) {
                Class<?> pokemonCls = getPokemonMethod.getReturnType();
                for (Method m : pokemonCls.getMethods()) {
                    if ("getSpecies".equals(m.getName()) && m.getParameterCount() == 0) {
                        getSpeciesMethod = m;
                    } else if ("getOwnerUUID".equals(m.getName()) && m.getParameterCount() == 0) {
                        getOwnerUuidMethod = m;
                    }
                }
                if (getSpeciesMethod != null) {
                    Class<?> speciesCls = getSpeciesMethod.getReturnType();
                    for (Method m : speciesCls.getMethods()) {
                        if ("getName".equals(m.getName()) && m.getParameterCount() == 0
                                && m.getReturnType() == String.class) {
                            getSpeciesNameMethod = m;
                            break;
                        }
                    }
                }
            }
            if (getPokemonMethod != null && getSpeciesMethod != null
                    && getSpeciesNameMethod != null && getOwnerUuidMethod != null) {
                LOGGER.info("[CCMF] FoF Giratina filter — reflection init OK (field type {}, Pokemon.getOwnerUUID + Species.getName found)",
                        entityCls.getSimpleName());
            } else {
                LOGGER.warn("[CCMF] FoF Giratina filter — reflection init INCOMPLETE on methods: getPokemon={}, getSpecies={}, getSpeciesName={}, getOwnerUUID={}",
                        getPokemonMethod != null, getSpeciesMethod != null,
                        getSpeciesNameMethod != null, getOwnerUuidMethod != null);
            }
        } catch (Throwable t) {
            LOGGER.error("[CCMF] FoF Giratina filter — reflection init THREW: {}", t.toString());
        }
        reflectInit = true;
    }

    @Inject(method = "method_6264", at = @At("HEAD"), cancellable = true, require = 0)
    private void cheesecompat$preventOwnedGiratinaAggro(CallbackInfoReturnable<Boolean> cir) {
        try {
            initReflect(this.getClass());
            if (pokemonEntityField == null) return;
            Object pokemonEntityObj = pokemonEntityField.get(this);
            if (!(pokemonEntityObj instanceof Entity entity)) return;

            // Fast-path: if entity has the boss command tag, allow aggression
            if (entity.getCommandTags().contains("cheesecompat_boss")) return;

            if (getPokemonMethod == null || getSpeciesNameMethod == null
                    || getOwnerUuidMethod == null) return;
            Object pokemon = getPokemonMethod.invoke(pokemonEntityObj);
            if (pokemon == null) return;
            Object species = getSpeciesMethod.invoke(pokemon);
            if (species == null) return;
            String name = (String) getSpeciesNameMethod.invoke(species);
            if (name == null || !name.equalsIgnoreCase("Giratina")) return;
            Object ownerUuid = getOwnerUuidMethod.invoke(pokemon);
            if (ownerUuid != null) {
                cir.setReturnValue(false);
                long total = TOTAL_BLOCKS.incrementAndGet();
                UUID entityUuid = entity.getUuid();
                if (LOGGED_BLOCKS.putIfAbsent(entityUuid, Boolean.TRUE) == null) {
                    LOGGER.info("[CCMF] FoF Giratina filter ACTIVATED for owned Giratina (entityUuid={}, ownerUuid={}, totalBlocksSinceStartup={})",
                            entityUuid, ownerUuid, total);
                }
            }
        } catch (Throwable t) {
            if (RUNTIME_FAILURES.incrementAndGet() == 1) {
                LOGGER.warn("[CCMF] FoF Giratina filter — runtime exception (filter still applies on next call): {}", t.toString());
            }
        }
    }
}
