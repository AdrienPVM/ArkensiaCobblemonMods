package com.cheesemarket.server;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Reflection-based hook into CheeseItems' ItemManager.
 * Resolves cheeseitems:* display items to their baseItem + customModelData
 * so ShopViewScreen can render the correct icon.
 */
public class CheeseItemsHook {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseMarket");

    private static boolean available = false;
    private static boolean checked = false;

    private static java.lang.reflect.Field instanceField;  // CheeseItemsMod.INSTANCE
    private static Method getItemManagerMethod;   // instance.getItemManager()
    private static Method getConfigMethod;        // instance.getConfig()
    private static Method getItemMethod;          // itemManager.getItem(String id)
    private static Method getBaseItemMethod;      // def.getBaseItem()
    private static Method getCmdMethod;           // config.getCustomModelData(String id)
    private static Method hasImageMethod;         // config.hasItemImage(String id)
    private static Method getItemImageMethod;     // config.getItemImage(String id) → byte[]
    private static Method buildItemStackMethod;   // itemManager.buildItemStack(def, count)
    private static Method getDisplayNameMethod;   // def.getDisplayName()

    private static void init() {
        if (checked) return;
        checked = true;
        try {
            if (!FabricLoader.getInstance().isModLoaded("cheeseitems")) {
                LOGGER.info("[Market] CheeseItems not found — custom item icon support disabled");
                return;
            }

            Class<?> modClass = Class.forName("com.cheeseitems.CheeseItemsMod");
            instanceField = modClass.getField("INSTANCE");

            Class<?> managerClass = Class.forName("com.cheeseitems.ItemManager");
            getItemManagerMethod = modClass.getMethod("getItemManager");
            getItemMethod = managerClass.getMethod("getItem", String.class);

            Class<?> configClass = Class.forName("com.cheeseitems.CheeseItemsConfig");
            getConfigMethod = modClass.getMethod("getConfig");
            getCmdMethod = configClass.getMethod("getCustomModelData", String.class);
            hasImageMethod = configClass.getMethod("hasItemImage", String.class);
            getItemImageMethod = configClass.getMethod("getItemImage", String.class);

            Class<?> defClass = Class.forName("com.cheeseitems.CustomItemDefinition");
            getBaseItemMethod = defClass.getMethod("getBaseItem");
            getDisplayNameMethod = defClass.getMethod("getDisplayName");
            buildItemStackMethod = managerClass.getMethod("buildItemStack", defClass, int.class);

            available = true;
            LOGGER.info("[Market] CheeseItems detected — custom item icon support enabled");
        } catch (Exception e) {
            LOGGER.warn("[Market] Failed to initialize CheeseItems hook: {}", e.getMessage());
        }
    }

    public static boolean isAvailable() {
        init();
        return available;
    }

    public static boolean isCheeseItem(String itemId) {
        return itemId != null && itemId.startsWith("cheeseitems:");
    }

    /** True if the id maps to a synthetic morpho-catalog entry instead of a real CheeseItems item. */
    private static boolean isMorphoCatalog(String ciId) {
        return ciId != null && ciId.startsWith("morpho_");
    }

    private static String morphoIdOf(String ciId) {
        return ciId.substring("morpho_".length());
    }

    /**
     * Resolve a cheeseitems:* item ID to its base Minecraft item ID.
     * @return the base item (e.g. "minecraft:paper") or null if not found
     */
    public static String resolveBaseItem(String cheeseItemId) {
        init();
        if (!available) return null;
        try {
            String ciId = cheeseItemId.replace("cheeseitems:", "");
            if (isMorphoCatalog(ciId)) return "minecraft:slime_ball";
            Object mod = instanceField.get(null);
            Object manager = getItemManagerMethod.invoke(mod);
            Object def = getItemMethod.invoke(manager, ciId);
            if (def == null) return null;
            return (String) getBaseItemMethod.invoke(def);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get the CustomModelData value for a cheeseitems item.
     * @return the CMD integer, or -1 if none
     */
    public static int resolveCustomModelData(String cheeseItemId) {
        init();
        if (!available) return -1;
        try {
            String ciId = cheeseItemId.replace("cheeseitems:", "");
            Object mod = instanceField.get(null);
            Object config = getConfigMethod.invoke(mod);
            boolean hasImage = (boolean) hasImageMethod.invoke(config, ciId);
            if (!hasImage) return -1;
            Integer cmd = (Integer) getCmdMethod.invoke(config, ciId);
            return cmd != null ? cmd : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Get the custom icon PNG bytes for a cheeseitems item.
     * @return byte array of the PNG, or null if none
     */
    public static byte[] getItemImage(String cheeseItemId) {
        init();
        if (!available) return null;
        try {
            String ciId = cheeseItemId.replace("cheeseitems:", "");
            if (isMorphoCatalog(ciId)) {
                Class<?> catCls = Class.forName("com.cheeseitems.morpho.MorphoCatalog");
                Object cat = catCls.getMethod("getInstance").invoke(null);
                return (byte[]) catCls.getMethod("getImage", String.class).invoke(cat, morphoIdOf(ciId));
            }
            Object mod = instanceField.get(null);
            Object config = getConfigMethod.invoke(mod);
            boolean hasImage = (boolean) hasImageMethod.invoke(config, ciId);
            if (!hasImage) return null;
            return (byte[]) getItemImageMethod.invoke(config, ciId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get the display name for a cheeseitems item.
     * @return the display name or null
     */
    public static String getDisplayName(String cheeseItemId) {
        init();
        if (!available) return null;
        try {
            String ciId = cheeseItemId.replace("cheeseitems:", "");
            if (isMorphoCatalog(ciId)) {
                Class<?> catCls = Class.forName("com.cheeseitems.morpho.MorphoCatalog");
                Object cat = catCls.getMethod("getInstance").invoke(null);
                Object entry = catCls.getMethod("get", String.class).invoke(cat, morphoIdOf(ciId));
                if (entry == null) return null;
                return (String) entry.getClass().getMethod("getTitle").invoke(entry);
            }
            Object mod = instanceField.get(null);
            Object manager = getItemManagerMethod.invoke(mod);
            Object def = getItemMethod.invoke(manager, ciId);
            if (def == null) return null;
            return (String) getDisplayNameMethod.invoke(def);
        } catch (Exception e) {
            return null;
        }
    }

    /** Description text for tooltip — only morpho-catalog items expose this. */
    public static String getDescription(String cheeseItemId) {
        init();
        if (!available) return null;
        try {
            String ciId = cheeseItemId.replace("cheeseitems:", "");
            if (!isMorphoCatalog(ciId)) return null;
            Class<?> catCls = Class.forName("com.cheeseitems.morpho.MorphoCatalog");
            Object cat = catCls.getMethod("getInstance").invoke(null);
            Object entry = catCls.getMethod("get", String.class).invoke(cat, morphoIdOf(ciId));
            if (entry == null) return null;
            return (String) entry.getClass().getMethod("getDescription").invoke(entry);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Build a proper CheeseItems ItemStack via reflection.
     * Must be called on the server thread.
     * @return the ItemStack, or null if not found
     */
    public static Object buildItemStack(String cheeseItemId, int count) {
        init();
        if (!available) return null;
        try {
            String ciId = cheeseItemId.replace("cheeseitems:", "");
            Object mod = instanceField.get(null);
            Object manager = getItemManagerMethod.invoke(mod);
            Object def = getItemMethod.invoke(manager, ciId);
            if (def == null) return null;
            return buildItemStackMethod.invoke(manager, def, count);
        } catch (Exception e) {
            LOGGER.warn("[Market] Failed to build CheeseItems stack for {}: {}", cheeseItemId, e.getMessage());
            return null;
        }
    }
}
