package com.cheeseraids.hooks;

import com.cheeseraids.CheeseRaidsMod;
import net.minecraft.entity.Entity;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Reflection bridge to Cobblemon's battle state, so we can wait for the boss battle to truly END
 * before rewarding/teleporting (the wild PokemonEntity is removed ~2s AFTER the battle ends, so
 * "entity gone AND no active battle for its id" is the robust victory contract).
 */
public final class CobblemonBattleHook {
    private CobblemonBattleHook() {}

    private static volatile boolean init = false;
    private static Object registry;
    private static Method getBattleM;

    private static synchronized void initApi() {
        if (init) return;
        try {
            Class<?> br = Class.forName("com.cobblemon.mod.common.battles.BattleRegistry");
            registry = br.getField("INSTANCE").get(null);
            getBattleM = br.getMethod("getBattle", UUID.class);
        } catch (Throwable t) {
            CheeseRaidsMod.LOGGER.warn("[CheeseRaids] BattleRegistry hook init failed: {}", t.getMessage());
        }
        init = true;
    }

    /**
     * The battle UUID this Pokémon entity is currently in, or null if not battling. Resolved by method
     * SHAPE (0 params, returns UUID, name contains "battle") rather than a hardcoded name, per the
     * project's anti-Yarn-name reflection rule — a miss degrades gracefully to the age-based KO fallback.
     */
    public static UUID getBattleId(Entity pokemonEntity) {
        try {
            for (Method m : pokemonEntity.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == UUID.class
                        && m.getName().toLowerCase().contains("battle")) {
                    Object r = m.invoke(pokemonEntity);
                    if (r instanceof UUID u) return u;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** True while the given battle is still registered (i.e. not yet fully ended). */
    public static boolean battleActive(UUID battleId) {
        if (battleId == null) return false;
        initApi();
        if (registry == null || getBattleM == null) return false;
        try {
            return getBattleM.invoke(registry, battleId) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
