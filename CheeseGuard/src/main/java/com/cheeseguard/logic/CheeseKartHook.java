package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.Method;
import java.util.Collection;

/**
 * Soft-dependency hook for CheeseKart mod.
 * Uses reflection to call CheeseKart's KartManager without compile-time dependency.
 * Handles: KART_START_ALL, KART_STOP_ALL
 */
public class CheeseKartHook {

    private static boolean initialized = false;
    private static boolean available = false;

    // Cached reflection targets
    private static Object kartManagerInstance;
    private static Object configInstance;
    private static Method getPresetMethod;   // CheeseKartConfig.getPreset(String) -> KartPreset
    private static Method startKartMethod;   // KartManager.startKart(ServerPlayerEntity, KartPreset, String) -> boolean
    private static Method stopKartMethod;    // KartManager.stopKart(ServerPlayerEntity) -> void

    /**
     * Attempt to initialize CheeseKart reflection hooks.
     * Call once during EventManager.init().
     */
    public static void tryInit() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> modClass = Class.forName("com.cheesekart.CheeseKartMod");
            Object modInstance = modClass.getField("INSTANCE").get(null);
            if (modInstance == null) {
                CheeseGuardMod.LOGGER.info("[CG] CheeseKart detected but not yet initialized");
                initialized = false; // retry later
                return;
            }

            Method getKartManager = modClass.getMethod("getKartManager");
            kartManagerInstance = getKartManager.invoke(modInstance);

            Method getConfig = modClass.getMethod("getConfig");
            configInstance = getConfig.invoke(modInstance);

            Class<?> configClass = configInstance.getClass();
            getPresetMethod = configClass.getMethod("getPreset", String.class);

            Class<?> kmClass = kartManagerInstance.getClass();
            // Find KartPreset class
            Class<?> presetClass = Class.forName("com.cheesekart.KartPreset");
            startKartMethod = kmClass.getMethod("startKart", ServerPlayerEntity.class, presetClass, String.class);
            stopKartMethod = kmClass.getMethod("stopKart", ServerPlayerEntity.class);

            available = true;
            CheeseGuardMod.LOGGER.info("[CG] CheeseKart detected — kart effects enabled");
        } catch (ClassNotFoundException e) {
            CheeseGuardMod.LOGGER.info("[CG] CheeseKart not installed — kart effects disabled");
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] CheeseKart hook failed: {}", e.getMessage());
        }
    }

    public static boolean isAvailable() {
        if (!initialized) tryInit();
        return available;
    }

    /**
     * Start kart mode for all participants.
     * @param participants event participants
     * @param presetId the preset ID to use
     * @param speciesOverride optional species override (can be null or empty)
     */
    public static void startAll(Collection<ServerPlayerEntity> participants, String presetId, String speciesOverride) {
        if (!isAvailable()) {
            CheeseGuardMod.LOGGER.warn("[CG] Cannot start karts — CheeseKart not available");
            return;
        }
        if (presetId == null || presetId.isBlank()) {
            CheeseGuardMod.LOGGER.warn("[CG] KART_START_ALL: no presetId specified");
            return;
        }
        try {
            Object preset = getPresetMethod.invoke(configInstance, presetId);
            if (preset == null) {
                CheeseGuardMod.LOGGER.warn("[CG] KART_START_ALL: preset not found: {}", presetId);
                return;
            }
            String species = (speciesOverride != null && !speciesOverride.isBlank()) ? speciesOverride : null;
            for (ServerPlayerEntity player : participants) {
                try {
                    startKartMethod.invoke(kartManagerInstance, player, preset, species);
                } catch (Exception e) {
                    CheeseGuardMod.LOGGER.debug("[CG] Kart start failed for {}: {}", player.getName().getString(), e.getMessage());
                }
            }
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] KART_START_ALL error: {}", e.getMessage());
        }
    }

    /**
     * Stop kart mode for all participants.
     */
    public static void stopAll(Collection<ServerPlayerEntity> participants) {
        if (!isAvailable()) return;
        for (ServerPlayerEntity player : participants) {
            try {
                stopKartMethod.invoke(kartManagerInstance, player);
            } catch (Exception e) {
                CheeseGuardMod.LOGGER.debug("[CG] Kart stop failed for {}: {}", player.getName().getString(), e.getMessage());
            }
        }
    }
}
