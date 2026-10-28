package com.cheesemarket.server;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Reflection-based hook into CheeseStore's WalletManager.
 * Allows CheeseMarket to check/deduct "fromage" (premium currency)
 * without a compile-time dependency on CheeseStore.
 *
 * Cost entries with itemId "cheesestore:fromage" are treated as fromage costs.
 */
public class CheeseStoreHook {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseMarket");
    public static final String FROMAGE_ITEM_ID = "cheesestore:fromage";

    private static boolean available = false;
    private static boolean checked = false;

    // Cached reflection handles
    private static Method getInstanceMethod;
    private static Method getBalanceMethod;
    private static Method deductMethod;

    private static void init() {
        if (checked) return;
        checked = true;
        try {
            if (!FabricLoader.getInstance().isModLoaded("cheesestore")) {
                LOGGER.info("[Market] CheeseStore not found — fromage support disabled");
                return;
            }

            Class<?> walletClass = Class.forName("com.cheesestore.WalletManager");
            getInstanceMethod = walletClass.getMethod("getInstance");
            getBalanceMethod = walletClass.getMethod("getBalance", UUID.class);
            deductMethod = walletClass.getMethod("deduct", UUID.class, String.class, long.class);

            available = true;
            LOGGER.info("[Market] CheeseStore detected — fromage support enabled");
        } catch (Exception e) {
            LOGGER.warn("[Market] Failed to initialize CheeseStore hook: {}", e.getMessage());
        }
    }

    public static boolean isAvailable() {
        init();
        return available;
    }

    public static boolean isFromage(String itemId) {
        return FROMAGE_ITEM_ID.equals(itemId);
    }

    public static long getBalance(UUID playerUuid) {
        init();
        if (!available) return 0;
        try {
            Object wallet = getInstanceMethod.invoke(null);
            return (long) getBalanceMethod.invoke(wallet, playerUuid);
        } catch (Exception e) {
            LOGGER.warn("[Market] Failed to get fromage balance: {}", e.getMessage());
            return 0;
        }
    }

    public static boolean deduct(UUID playerUuid, String playerName, long amount) {
        init();
        if (!available || amount <= 0) return false;
        try {
            Object wallet = getInstanceMethod.invoke(null);
            return (boolean) deductMethod.invoke(wallet, playerUuid, playerName, amount);
        } catch (Exception e) {
            LOGGER.warn("[Market] Failed to deduct fromage: {}", e.getMessage());
            return false;
        }
    }
}
