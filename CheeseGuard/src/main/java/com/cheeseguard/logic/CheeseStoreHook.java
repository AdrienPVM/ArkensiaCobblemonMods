package com.cheeseguard.logic;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Reflection hook for CheeseStore WalletManager.
 * Allows CheeseGuard to check/deduct fromage currency without a hard dependency.
 */
public class CheeseStoreHook {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseGuard");
    private static boolean available;
    private static boolean initialized;
    private static Object walletManagerInstance;
    private static Method getBalanceMethod;
    private static Method deductMethod;

    private static void init() {
        if (initialized) return;
        initialized = true;
        try {
            if (!FabricLoader.getInstance().isModLoaded("cheesestore")) {
                available = false;
                return;
            }
            // Get WalletManager class and instance
            Class<?> wmClass = Class.forName("com.cheesestore.WalletManager");
            Method getInstanceMethod = wmClass.getMethod("getInstance");
            walletManagerInstance = getInstanceMethod.invoke(null);

            // Cache methods
            getBalanceMethod = wmClass.getMethod("getBalance", UUID.class);
            deductMethod = wmClass.getMethod("deduct", UUID.class, String.class, long.class);

            available = walletManagerInstance != null;
            if (available) {
                LOGGER.info("[CheeseGuard] CheeseStore hook initialized successfully");
            }
        } catch (Exception e) {
            LOGGER.warn("[CheeseGuard] CheeseStore hook failed to initialize: {}", e.getMessage());
            available = false;
        }
    }

    public static boolean isAvailable() {
        init();
        return available;
    }

    public static long getBalance(UUID playerUuid) {
        init();
        if (!available) return 0;
        try {
            return (long) getBalanceMethod.invoke(walletManagerInstance, playerUuid);
        } catch (Exception e) {
            LOGGER.error("[CheeseGuard] Failed to get CheeseStore balance", e);
            return 0;
        }
    }

    public static boolean deduct(UUID playerUuid, String playerName, long amount) {
        init();
        if (!available) return false;
        try {
            return (boolean) deductMethod.invoke(walletManagerInstance, playerUuid, playerName, amount);
        } catch (Exception e) {
            LOGGER.error("[CheeseGuard] Failed to deduct CheeseStore balance", e);
            return false;
        }
    }

    public static boolean canAfford(UUID playerUuid, long amount) {
        return getBalance(playerUuid) >= amount;
    }
}
