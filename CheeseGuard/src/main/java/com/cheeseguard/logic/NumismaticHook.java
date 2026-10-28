package com.cheeseguard.logic;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflection-based hook into Numismatic Overhaul's purse system.
 * Allows CheeseGuard to check/deduct currency from the player's purse
 * without a compile-time dependency on Numismatic Overhaul.
 *
 * Currency values: BRONZE=1, SILVER=100, GOLD=10000
 */
public class NumismaticHook {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseGuard");
    private static boolean available = false;
    private static boolean checked = false;

    // Cached reflection handles
    private static Object currencyKey;       // ModComponents.CURRENCY (ComponentKey)
    private static Method keyGetMethod;      // ComponentKey.get(provider)
    private static Method getValueMethod;    // CurrencyComponent.getValue()
    private static Method modifyMethod;      // CurrencyComponent.modify(long)

    /** Check if Numismatic Overhaul is loaded and cache reflection handles. */
    private static void init() {
        if (checked) return;
        checked = true;
        try {
            if (!FabricLoader.getInstance().isModLoaded("numismatic-overhaul")) {
                LOGGER.info("[CheeseGuard] Numismatic Overhaul not found — purse support disabled");
                return;
            }

            Class<?> modComponentsClass = Class.forName("com.glisco.numismaticoverhaul.ModComponents");
            Field currencyField = modComponentsClass.getField("CURRENCY");
            currencyKey = currencyField.get(null);

            Class<?> componentKeyClass = currencyKey.getClass();
            keyGetMethod = componentKeyClass.getMethod("get", Object.class);

            Class<?> currencyComponentClass = Class.forName("com.glisco.numismaticoverhaul.currency.CurrencyComponent");
            getValueMethod = currencyComponentClass.getMethod("getValue");
            modifyMethod = currencyComponentClass.getMethod("modify", long.class);

            available = true;
            LOGGER.info("[CheeseGuard] Numismatic Overhaul detected — purse support enabled");
        } catch (Exception e) {
            LOGGER.warn("[CheeseGuard] Failed to initialize Numismatic Overhaul hook: {}", e.getMessage());
        }
    }

    /** Whether Numismatic Overhaul is available. */
    public static boolean isAvailable() {
        init();
        return available;
    }

    /** Get the player's purse balance in bronze units. Returns 0 if unavailable. */
    public static long getBalance(ServerPlayerEntity player) {
        init();
        if (!available) return 0;
        try {
            Object component = keyGetMethod.invoke(currencyKey, player);
            return (long) getValueMethod.invoke(component);
        } catch (Exception e) {
            LOGGER.warn("[CheeseGuard] Failed to get purse value: {}", e.getMessage());
            return 0;
        }
    }

    /** Deduct bronze units from the player's purse. */
    public static void deduct(ServerPlayerEntity player, long bronzeAmount) {
        init();
        if (!available || bronzeAmount <= 0) return;
        try {
            Object component = keyGetMethod.invoke(currencyKey, player);
            modifyMethod.invoke(component, -bronzeAmount);
            LOGGER.debug("[CheeseGuard] Deducted {} bronze from {}'s purse", bronzeAmount, player.getName().getString());
        } catch (Exception e) {
            LOGGER.warn("[CheeseGuard] Failed to deduct from purse: {}", e.getMessage());
        }
    }

    /** Check if the player can afford the given bronze amount. */
    public static boolean canAfford(ServerPlayerEntity player, long bronzeAmount) {
        return getBalance(player) >= bronzeAmount;
    }
}
