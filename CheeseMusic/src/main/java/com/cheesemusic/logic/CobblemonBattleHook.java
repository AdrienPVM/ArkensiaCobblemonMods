package com.cheesemusic.logic;

import com.cheesemusic.CheeseMusicMod;
import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Soft-dependency hook for Cobblemon battle detection.
 * Uses reflection to check BattleRegistry for active battles involving a player.
 */
public class CobblemonBattleHook {

    private static boolean initialized = false;
    private static boolean available = false;

    private static Object battleRegistryInstance;
    private static Method getBattleMethod;

    public static void tryInit() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> battleRegistryClass = Class.forName("com.cobblemon.mod.common.battles.BattleRegistry");
            // BattleRegistry is a Kotlin object — INSTANCE field
            battleRegistryInstance = battleRegistryClass.getField("INSTANCE").get(null);

            // Find method: getBattleByParticipatingPlayerId(UUID) -> PokemonBattle?
            // Filter by name pattern to avoid picking wrong method
            for (Method m : battleRegistryClass.getDeclaredMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == UUID.class
                    && m.getReturnType() != void.class && m.getReturnType() != Void.class
                    && m.getName().toLowerCase().contains("battle")
                    && m.getName().toLowerCase().contains("player")) {
                    getBattleMethod = m;
                    break;
                }
            }

            // Fallback: any method with UUID param returning non-void
            if (getBattleMethod == null) {
                for (Method m : battleRegistryClass.getDeclaredMethods()) {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == UUID.class
                        && m.getReturnType() != void.class && m.getReturnType() != Void.class
                        && !m.getReturnType().isPrimitive()) {
                        getBattleMethod = m;
                        break;
                    }
                }
            }

            if (getBattleMethod == null) {
                CheeseMusicMod.LOGGER.info("[CheeseMusic] Cobblemon BattleRegistry found but no suitable method");
                return;
            }

            getBattleMethod.setAccessible(true);
            available = true;
            CheeseMusicMod.LOGGER.info("[CheeseMusic] Cobblemon detected — battle detection via {}()", getBattleMethod.getName());
        } catch (ClassNotFoundException e) {
            CheeseMusicMod.LOGGER.info("[CheeseMusic] Cobblemon not installed — battle detection disabled");
        } catch (Exception e) {
            CheeseMusicMod.LOGGER.warn("[CheeseMusic] Cobblemon hook failed: {}", e.getMessage());
        }
    }

    public static boolean isAvailable() { return available; }

    /**
     * Check if a player is currently in a Cobblemon battle.
     */
    public static boolean isInBattle(ServerPlayerEntity player) {
        if (!available) return false;
        try {
            Object battle = getBattleMethod.invoke(battleRegistryInstance, player.getUuid());
            return battle != null;
        } catch (Exception e) {
            return false;
        }
    }
}
