package com.cheesemarket.server;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflection-based hook into Numismatic Overhaul's purse system.
 * Allows CheeseMarket to check/deduct currency from the player's purse
 * without a compile-time dependency on Numismatic Overhaul.
 *
 * Currency values: BRONZE=1, SILVER=100, GOLD=10000
 */
public class NumismaticHook {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseMarket");
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
                LOGGER.info("[Market] Numismatic Overhaul not found — purse support disabled");
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
            LOGGER.info("[Market] Numismatic Overhaul detected — purse support enabled");
        } catch (Exception e) {
            LOGGER.warn("[Market] Failed to initialize Numismatic Overhaul hook: {}", e.getMessage());
        }
    }

    /** Whether Numismatic Overhaul is available. */
    public static boolean isAvailable() {
        init();
        return available;
    }

    /** Check if an itemId is a Numismatic Overhaul currency item. */
    public static boolean isCurrency(String itemId) {
        if (itemId == null) return false;
        return itemId.equals("numismatic-overhaul:bronze_coin")
            || itemId.equals("numismatic-overhaul:silver_coin")
            || itemId.equals("numismatic-overhaul:gold_coin");
    }

    /** Convert a count of a specific currency item to bronze units. */
    public static long toBronze(String itemId, int count) {
        return switch (itemId) {
            case "numismatic-overhaul:gold_coin" -> (long) count * 10000L;
            case "numismatic-overhaul:silver_coin" -> (long) count * 100L;
            default -> count; // bronze_coin or unknown
        };
    }

    /** Get the player's purse balance in bronze units. Returns 0 if unavailable. */
    public static long getPurseValue(ServerPlayerEntity player) {
        init();
        if (!available) return 0;
        try {
            Object component = keyGetMethod.invoke(currencyKey, player);
            return (long) getValueMethod.invoke(component);
        } catch (Exception e) {
            LOGGER.warn("[Market] Failed to get purse value: {}", e.getMessage());
            return 0;
        }
    }

    /** Deduct bronze units from the player's purse. */
    public static void deductFromPurse(ServerPlayerEntity player, long bronzeAmount) {
        init();
        if (!available || bronzeAmount <= 0) return;
        try {
            Object component = keyGetMethod.invoke(currencyKey, player);
            modifyMethod.invoke(component, -bronzeAmount);
            LOGGER.debug("[Market] Deducted {} bronze from {}'s purse", bronzeAmount, player.getName().getString());
        } catch (Exception e) {
            LOGGER.warn("[Market] Failed to deduct from purse: {}", e.getMessage());
        }
    }
}
