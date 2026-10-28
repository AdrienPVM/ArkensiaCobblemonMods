package com.cheesekart;

import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Soft-dependency hook for CobbleShift mod.
 * Uses reflection so CheeseKart compiles and runs without CobbleShift.
 */
public class CobbleShiftProxy {
    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseKart");

    private static boolean initialized = false;
    private static boolean available = false;
    private static Object transformManagerInstance;
    private static Method transformMethod;  // transform(ServerPlayerEntity, String) -> boolean
    private static Method revertMethod;     // revert(ServerPlayerEntity)

    public static void tryInit() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> modClass = Class.forName("com.cobbleshift.CobbleShiftMod");
            Object instance = modClass.getField("INSTANCE").get(null);
            if (instance == null) {
                LOGGER.debug("[CheeseKart] CobbleShift INSTANCE is null — not yet initialized");
                initialized = false; // retry later
                return;
            }
            Method getTm = modClass.getMethod("getTransformManager");
            transformManagerInstance = getTm.invoke(instance);
            if (transformManagerInstance == null) {
                LOGGER.debug("[CheeseKart] CobbleShift TransformManager is null");
                initialized = false;
                return;
            }
            Class<?> tmClass = transformManagerInstance.getClass();
            transformMethod = tmClass.getMethod("transform", ServerPlayerEntity.class, String.class);
            revertMethod = tmClass.getMethod("revert", ServerPlayerEntity.class);
            available = true;
            LOGGER.info("[CheeseKart] CobbleShift integration active");
        } catch (ClassNotFoundException e) {
            LOGGER.info("[CheeseKart] CobbleShift not installed — transform features disabled");
        } catch (Exception e) {
            LOGGER.warn("[CheeseKart] Failed to hook CobbleShift: {}", e.getMessage());
        }
    }

    public static boolean isAvailable() {
        if (!initialized) tryInit();
        return available;
    }

    public static boolean transform(ServerPlayerEntity player, String species) {
        if (!isAvailable()) return false;
        try {
            return (boolean) transformMethod.invoke(transformManagerInstance, player, species);
        } catch (Exception e) {
            LOGGER.debug("[CheeseKart] CobbleShift transform failed: {}", e.getMessage());
            return false;
        }
    }

    public static void revert(ServerPlayerEntity player) {
        if (!isAvailable()) return;
        try {
            revertMethod.invoke(transformManagerInstance, player);
        } catch (Exception e) {
            LOGGER.debug("[CheeseKart] CobbleShift revert failed: {}", e.getMessage());
        }
    }
}
