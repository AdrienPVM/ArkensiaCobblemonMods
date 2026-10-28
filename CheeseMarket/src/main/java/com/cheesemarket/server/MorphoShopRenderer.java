package com.cheesemarket.server;

import com.cheesemarket.data.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Builds a synthetic ShopData snapshot at view time from the central CheeseItems
 * Morpho Catalog when {@link ShopData#isMorphoCatalog()} is true.
 *
 * The snapshot has a single set "Morphos" containing one ShopItem per catalog
 * entry flagged availableInNpcShop=true. Each item costs {@code priceCredits}
 * fromage and runs {@code /cheeseitem morpho grant {player} <morphoId>} on
 * purchase. The original shop is never mutated.
 *
 * CheeseItems is reached by reflection so the mod stays a soft dependency.
 */
public final class MorphoShopRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseMarket");
    private static final String FROMAGE_ID = "cheesestore:fromage";

    private static boolean checked = false;
    private static Method getInstanceM;
    private static Method getSortedM;
    private static Method getIdM;
    private static Method getTitleM;
    private static Method getDescM;
    private static Method getPriceCreditsM;
    private static Method isAvailableNpcM;

    private MorphoShopRenderer() {}

    /**
     * If {@code shop.isMorphoCatalog()} returns true, returns a synthetic copy of the
     * shop whose sets are auto-populated from the morpho catalog. Otherwise returns
     * the original shop unchanged.
     */
    public static ShopData materialize(ShopData shop) {
        if (shop == null || !shop.isMorphoCatalog()) return shop;
        if (!ensureReflection()) {
            LOGGER.warn("[Market] Shop '{}' has morphoCatalog=true but CheeseItems is not loaded — returning empty shop", shop.getId());
            return shop;
        }
        try {
            Object catalog = getInstanceM.invoke(null);
            @SuppressWarnings("unchecked")
            List<Object> entries = (List<Object>) getSortedM.invoke(catalog);

            // Build the synthetic shop by serialising/deserialising to avoid sharing mutable state.
            JsonObject json = shop.toJson();
            JsonArray sets = new JsonArray();

            JsonObject set = new JsonObject();
            set.addProperty("id", "morphos");
            set.addProperty("name", "Morphos");
            set.addProperty("sortOrder", 0);
            JsonObject rotation = new JsonObject();
            rotation.addProperty("scheduleType", "ALWAYS");
            set.add("rotation", rotation);

            JsonArray items = new JsonArray();
            int idx = 0;
            for (Object entry : entries) {
                Boolean availNpc = (Boolean) isAvailableNpcM.invoke(entry);
                if (availNpc == null || !availNpc) continue;
                String id     = (String) getIdM.invoke(entry);
                String title  = (String) getTitleM.invoke(entry);
                String desc   = (String) getDescM.invoke(entry);
                int    price  = (int) getPriceCreditsM.invoke(entry);

                JsonObject item = new JsonObject();
                item.addProperty("id", "morpho_" + id);
                // Route the icon + title through the cheeseitems:morpho_<id> pipeline so the
                // catalog image and title appear in the GUI tooltip (CheeseItemsHook resolves
                // these from MorphoCatalog when the id starts with "morpho_").
                item.addProperty("displayItem", "cheeseitems:morpho_" + id);
                item.addProperty("displayName", title == null ? id : title);
                item.addProperty("enabled", true);
                item.addProperty("sortOrder", idx++);

                // A non-empty outputs list is required for the confirmation dialog to open.
                // The dummy stack is never delivered — handleMorphoPurchase intercepts the
                // buy action and grants the morpho directly to the codex.
                JsonArray outputs = new JsonArray();
                JsonObject out = new JsonObject();
                out.addProperty("itemId", "cheeseitems:morpho_" + id);
                out.addProperty("count", 1);
                outputs.add(out);
                item.add("outputs", outputs);

                JsonArray costs = new JsonArray();
                JsonObject cost = new JsonObject();
                cost.addProperty("itemId", FROMAGE_ID);
                cost.addProperty("count", price);
                costs.add(cost);
                item.add("costs", costs);

                // Commands stay empty — handleMorphoPurchase runs `/cheeseitem morpho grant`.
                item.add("commands", new JsonArray());

                JsonObject stock = new JsonObject();
                stock.addProperty("perPlayerLimit", 1);
                stock.addProperty("globalStock", 0);
                item.add("stock", stock);

                if (desc != null && !desc.isEmpty()) {
                    item.addProperty("description", desc);
                }
                items.add(item);
            }
            set.add("items", items);
            sets.add(set);
            json.remove("sets");
            json.add("sets", sets);
            return ShopData.fromJson(json);
        } catch (Exception e) {
            LOGGER.error("[Market] Failed to materialise morpho catalog for shop {}: {}", shop.getId(), e.getMessage());
            return shop;
        }
    }

    private static boolean ensureReflection() {
        if (checked) return getInstanceM != null;
        checked = true;
        try {
            if (!FabricLoader.getInstance().isModLoaded("cheeseitems")) return false;
            Class<?> catCls = Class.forName("com.cheeseitems.morpho.MorphoCatalog");
            Class<?> entryCls = Class.forName("com.cheeseitems.morpho.MorphoEntry");
            getInstanceM     = catCls.getMethod("getInstance");
            getSortedM       = catCls.getMethod("getSortedForNpcShop");
            getIdM           = entryCls.getMethod("getId");
            getTitleM        = entryCls.getMethod("getTitle");
            getDescM         = entryCls.getMethod("getDescription");
            getPriceCreditsM = entryCls.getMethod("getPriceCredits");
            isAvailableNpcM  = entryCls.getMethod("isAvailableInNpcShop");
            LOGGER.info("[Market] Morpho catalog reflection bridge ready");
            return true;
        } catch (Exception e) {
            LOGGER.warn("[Market] Morpho catalog reflection unavailable: {}", e.getMessage());
            getInstanceM = null;
            return false;
        }
    }
}
