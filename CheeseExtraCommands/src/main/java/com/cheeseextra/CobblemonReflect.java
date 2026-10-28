package com.cheeseextra;

import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Reflection-based access to Cobblemon party, EVs, IVs, species.
 * Same pattern as CobbleCap's CobblemonHook — no compile-time dependency.
 */
public class CobblemonReflect {

    private static final Logger LOGGER = LoggerFactory.getLogger("cheeseextra");

    // ── Generic reflection helper ──────────────────────────────────────────

    public static Object invokeGetter(Object target, String... candidates) {
        if (target == null) return null;
        for (String name : candidates) {
            try {
                Method m = target.getClass().getMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Exception ignored) {}
            // Try declared methods too
            try {
                Method m = target.getClass().getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ── Get player party as list of Pokemon objects ────────────────────────

    public static List<Object> getPartyPokemon(ServerPlayerEntity player) {
        List<Object> result = new ArrayList<>();
        try {
            // Cobblemon is a Kotlin object: Cobblemon.INSTANCE.getStorage() -> PokemonStoreManager
            Class<?> cobblemonClass = Class.forName("com.cobblemon.mod.common.Cobblemon");
            Object instance = cobblemonClass.getDeclaredField("INSTANCE").get(null);

            // Get storage — scan for method returning PokemonStoreManager
            Object storage = null;
            for (Method m : cobblemonClass.getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType().getSimpleName().equals("PokemonStoreManager")) {
                    try { storage = m.invoke(instance); break; } catch (Exception ignored) {}
                }
            }
            if (storage == null) {
                LOGGER.warn("[CheeseExtra] Could not get PokemonStoreManager from Cobblemon.INSTANCE");
                return result;
            }

            // Get party — scan for method that returns a Party (not PC) and takes ServerPlayerEntity
            Object party = null;
            for (Method m : storage.getClass().getMethods()) {
                if (m.getParameterCount() == 1 && m.getReturnType().getSimpleName().contains("Party")) {
                    Class<?> paramType = m.getParameterTypes()[0];
                    if (paramType.isAssignableFrom(ServerPlayerEntity.class)) {
                        try { party = m.invoke(storage, player); if (party != null) break; } catch (Exception ignored) {}
                    }
                }
            }
            // Fallback: try with UUID, still filtering for Party return type
            if (party == null) {
                for (Method m : storage.getClass().getMethods()) {
                    if (m.getParameterCount() == 1 && m.getReturnType().getSimpleName().contains("Party")
                            && m.getParameterTypes()[0] == java.util.UUID.class) {
                        try { party = m.invoke(storage, player.getUuid()); if (party != null) break; } catch (Exception ignored) {}
                    }
                }
            }
            if (party == null) {
                LOGGER.warn("[CheeseExtra] Could not get party for player {}", player.getName().getString());
                return result;
            }

            LOGGER.info("[CheeseExtra] Got party object: {}", party.getClass().getSimpleName());

            // Get pokemon from party — try Iterable first (PartyStore implements Iterable)
            if (party instanceof Iterable<?> it) {
                for (Object poke : it) {
                    if (poke != null) result.add(poke);
                }
            } else {
                // Fallback: try toGappyList or similar
                for (String methodName : new String[]{"toGappyList", "getAll", "getPokemon"}) {
                    Object list = invokeGetter(party, methodName);
                    if (list instanceof Iterable<?> it2) {
                        for (Object poke : it2) {
                            if (poke != null) result.add(poke);
                        }
                        break;
                    }
                }
            }

            LOGGER.info("[CheeseExtra] Party for {}: {} pokemon found", player.getName().getString(), result.size());
        } catch (Exception e) {
            LOGGER.error("[CheeseExtra] getPartyPokemon error: {}", e.getMessage(), e);
        }
        return result;
    }

    // ── Get species showdown ID (lowercase) from a Pokemon object ─────────

    public static String getSpeciesId(Object pokemon) {
        try {
            Object species = invokeGetter(pokemon, "getSpecies");
            if (species == null) return "";
            Object showdownId = invokeGetter(species, "getShowdownId", "showdownId", "getName", "name");
            return showdownId != null ? showdownId.toString().toLowerCase() : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ── Get EV values (array of 6 ints: HP, Atk, Def, SpAtk, SpDef, Spd) ─

    public static int[] getEvs(Object pokemon) {
        return getStatStore(pokemon, "EVs");
    }

    /**
     * Generic stat store reader for EVs and IVs.
     *
     * Cobblemon API (decompiled from 1.6.1):
     *   - Stats is a Java enum: HP, ATTACK, DEFENCE, SPECIAL_ATTACK, SPECIAL_DEFENCE, SPEED
     *   - PokemonStats has: getOrDefault(Stat) -> int, get(Stat) -> Integer
     *   - PokemonStats implements Iterable<Map.Entry<Stat, Integer>>
     *   - Pokemon has getEvs() -> EVs, getIvs() -> IVs (both extend PokemonStats)
     */
    private static int[] getStatStore(Object pokemon, String storeName) {
        int[] values = new int[6];
        try {
            // Step 1: Get the store object (EVs or IVs) — scan by return type name
            Object store = null;
            for (Method m : pokemon.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType().getSimpleName().equals(storeName)) {
                    try { store = m.invoke(pokemon); break; } catch (Exception ignored) {}
                }
            }
            if (store == null) {
                LOGGER.warn("[CheeseExtra] Could not get {} from pokemon object (class: {})", storeName, pokemon.getClass().getName());
                return values;
            }

            // Step 2: Get the 6 Stat enum constants from Stats enum
            Class<?> statsClass = Class.forName("com.cobblemon.mod.common.api.pokemon.stats.Stats");
            Object[] enumConstants = statsClass.getEnumConstants();
            if (enumConstants == null || enumConstants.length < 6) {
                LOGGER.warn("[CheeseExtra] Stats enum has {} constants, expected >= 6", enumConstants == null ? 0 : enumConstants.length);
                return values;
            }

            // Stats enum order: HP(0), ATTACK(1), DEFENCE(2), SPECIAL_ATTACK(3), SPECIAL_DEFENCE(4), SPEED(5), EVASION(6), ACCURACY(7)
            // We want the first 6

            // Step 3: Find getOrDefault(Stat) method on the store
            // It's defined in PokemonStats: getOrDefault(Stat) -> int
            Method getOrDefault = null;
            Class<?> statInterface = Class.forName("com.cobblemon.mod.common.api.pokemon.stats.Stat");
            for (Method m : store.getClass().getMethods()) {
                if (m.getParameterCount() == 1 && m.getReturnType() == int.class) {
                    if (statInterface.isAssignableFrom(m.getParameterTypes()[0])) {
                        getOrDefault = m;
                        break;
                    }
                }
            }

            if (getOrDefault != null) {
                for (int i = 0; i < 6 && i < enumConstants.length; i++) {
                    try {
                        values[i] = (int) getOrDefault.invoke(store, enumConstants[i]);
                    } catch (Exception e) {
                        LOGGER.debug("[CheeseExtra] getOrDefault failed for stat {}: {}", i, e.getMessage());
                    }
                }
            } else {
                // Fallback: iterate the store (PokemonStats implements Iterable<Map.Entry<Stat, Integer>>)
                LOGGER.debug("[CheeseExtra] getOrDefault not found, falling back to iteration");
                if (store instanceof Iterable<?> iterable) {
                    for (Object entry : iterable) {
                        if (entry instanceof java.util.Map.Entry<?,?> mapEntry) {
                            Object stat = mapEntry.getKey();
                            Object val = mapEntry.getValue();
                            // Match stat to index by enum ordinal
                            if (stat instanceof Enum<?> e && val instanceof Number n) {
                                int ordinal = e.ordinal();
                                if (ordinal >= 0 && ordinal < 6) {
                                    values[ordinal] = n.intValue();
                                }
                            }
                        }
                    }
                }
            }

            LOGGER.info("[CheeseExtra] {} values: HP={} ATK={} DEF={} SPA={} SPD={} SPE={}",
                    storeName, values[0], values[1], values[2], values[3], values[4], values[5]);
        } catch (Exception e) {
            LOGGER.error("[CheeseExtra] getStatStore({}) error: {}", storeName, e.getMessage(), e);
        }
        return values;
    }

    // ── Get IV values (same structure as EVs) ─────────────────────────────

    public static int[] getIvs(Object pokemon) {
        return getStatStore(pokemon, "IVs");
    }

    // ── Get caught ball type from a Pokemon ───────────────────────────────

    public static String getCaughtBall(Object pokemon) {
        try {
            Object ball = invokeGetter(pokemon, "getCaughtBall", "getPokeball");
            if (ball == null) return "";
            Object name = invokeGetter(ball, "getName", "name", "toString");
            return name != null ? name.toString().toLowerCase() : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ── Check if Cobblemon is available ───────────────────────────────────

    public static boolean isAvailable() {
        try {
            Class.forName("com.cobblemon.mod.common.Cobblemon");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
