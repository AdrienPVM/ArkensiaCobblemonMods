package com.cheeseitems.integration;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Reflection-based hook into CheeseStore so the in-game Morpho Codex can show
 * the player's premium-currency balance without a compile-time dependency.
 *
 * CheeseStore is declared as a "suggests" in fabric.mod.json — when it is absent,
 * {@link #getBalance(UUID)} returns -1 and the GUI hides the balance line.
 */
public final class CheeseStoreHook {

    private static final Logger LOGGER = LoggerFactory.getLogger("cheeseitems");

    private static boolean checked = false;
    private static boolean available = false;

    private static Method walletGetInstance;
    private static Method walletGetBalance;
    private static java.lang.reflect.Field modInstanceField;
    private static Method modGetConfig;
    private static Method configGetCurrencyName;

    private CheeseStoreHook() {}

    private static void init() {
        if (checked) return;
        checked = true;
        try {
            if (!FabricLoader.getInstance().isModLoaded("cheesestore")) return;
            Class<?> walletCls = Class.forName("com.cheesestore.WalletManager");
            walletGetInstance = walletCls.getMethod("getInstance");
            walletGetBalance  = walletCls.getMethod("getBalance", UUID.class);

            // Currency name is a nice-to-have — wrap in its own try so the balance
            // path still works if the field/method shapes change.
            try {
                Class<?> modCls = Class.forName("com.cheesestore.CheeseStoreMod");
                modInstanceField = modCls.getField("INSTANCE");
                modGetConfig = modCls.getMethod("getConfig");
                Class<?> cfgCls = Class.forName("com.cheesestore.CheeseStoreConfig");
                configGetCurrencyName = cfgCls.getMethod("getCurrencyName");
            } catch (Throwable ignored) {
                modInstanceField = null;
            }

            available = true;
            LOGGER.info("[CheeseItems] CheeseStore detected — codex balance display enabled");
        } catch (Throwable t) {
            LOGGER.info("[CheeseItems] CheeseStore not available: {}", t.getMessage());
        }
    }

    /** @return balance in credits, or -1 if CheeseStore is absent / errored. */
    public static long getBalance(UUID playerUuid) {
        init();
        if (!available) return -1L;
        try {
            Object wallet = walletGetInstance.invoke(null);
            Object result = walletGetBalance.invoke(wallet, playerUuid);
            return (long) result;
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** @return the configured currency name (e.g. "crédit"), or empty when unknown. */
    public static String getCurrencyName() {
        init();
        if (!available || modInstanceField == null || modGetConfig == null || configGetCurrencyName == null) return "";
        try {
            Object mod = modInstanceField.get(null);
            if (mod == null) return "";
            Object cfg = modGetConfig.invoke(mod);
            Object name = configGetCurrencyName.invoke(cfg);
            return name == null ? "" : name.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
