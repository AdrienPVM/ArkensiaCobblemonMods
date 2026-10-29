package com.cheesemarket.server;

import com.cheesemarket.data.*;
import com.cheesemarket.manager.ShopManager;
import com.cheesemarket.network.ShopPayloads.*;
import com.google.gson.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.*;
import java.util.Collection;
import java.util.UUID;

/**
 * Processes all ShopActionPayload messages received from clients.
 * Dispatches to the appropriate CRUD/query method and sends back
 * ShopSyncPayload responses.
 */
public class ShopGuiHandler {

    public static void handle(ShopActionPayload payload, ServerPlayerEntity player) {
        String action = payload.action();

        // The channel is open to every client, so admin actions must be gated here:
        // item_save can write a shop item's commands, which shop_buy then runs as the
        // server console. Same bar as /cmadmin, which opens the editor.
        if (!action.startsWith("shop_buy:") && !action.startsWith("open_shop_player:")
                && !player.hasPermissionLevel(2)) {
            org.slf4j.LoggerFactory.getLogger("CheeseMarket").warn(
                "[CheeseMarket] Refused admin action from non-op {}: {}",
                player.getName().getString(), action.length() > 64 ? action.substring(0, 64) + "…" : action);
            return;
        }

        // ── Admin actions ──────────────────────────────────────────────────────

        if (action.equals("open_shop_list")) {
            sendShopList(player);
            return;
        }

        if (action.startsWith("open_shop:")) {
            sendShopEdit(player, action.substring(10));
            return;
        }

        if (action.startsWith("shop_create:")) {
            String id = action.substring(12).trim();
            if (id.isBlank() || ShopManager.getInstance().shopExists(id)) {
                sendError(player, "ID déjà utilisé ou invalide: " + id);
                return;
            }
            ShopData shop = ShopManager.getInstance().createShop(id);
            sendShopEdit(player, shop.getId());
            return;
        }

        if (action.startsWith("shop_delete:")) {
            ShopManager.getInstance().deleteShop(action.substring(12));
            sendShopList(player);
            return;
        }

        if (action.startsWith("shop_save:")) {
            String[] parts = action.substring(10).split("\\|", 2);
            if (parts.length < 2) return;
            String shopId = parts[0];
            ShopData shop = ShopManager.getInstance().getShop(shopId);
            if (shop == null) return;
            applyShopEdits(shop, parseJson(parts[1]));
            ShopManager.getInstance().saveShop(shop);
            sendShopEdit(player, shopId);
            return;
        }

        if (action.startsWith("open_set_list:")) {
            sendSetList(player, action.substring(14));
            return;
        }

        if (action.startsWith("open_set:")) {
            String[] p = action.substring(9).split(":", 2);
            if (p.length == 2) sendSetEdit(player, p[0], p[1]);
            return;
        }

        if (action.startsWith("set_create:")) {
            String shopId = action.substring(11);
            ShopData shop = ShopManager.getInstance().getShop(shopId);
            if (shop == null) return;
            ShopSet set = shop.addSet();
            ShopManager.getInstance().saveShop(shop);
            sendSetEdit(player, shopId, set.getId());
            return;
        }

        if (action.startsWith("set_delete:")) {
            String[] p = action.substring(11).split(":", 2);
            if (p.length < 2) return;
            ShopData shop = ShopManager.getInstance().getShop(p[0]);
            if (shop == null) return;
            shop.removeSet(p[1]);
            ShopManager.getInstance().saveShop(shop);
            sendSetList(player, p[0]);
            return;
        }

        if (action.startsWith("set_save:")) {
            String[] parts = action.substring(9).split("\\|", 2);
            if (parts.length < 2) return;
            String[] ids = parts[0].split(":", 2);
            if (ids.length < 2) return;
            ShopData shop = ShopManager.getInstance().getShop(ids[0]);
            if (shop == null) return;
            ShopSet set = shop.findSet(ids[1]);
            if (set == null) return;
            applySetEdits(set, parseJson(parts[1]));
            ShopManager.getInstance().saveShop(shop);
            sendSetEdit(player, ids[0], ids[1]);
            return;
        }

        if (action.startsWith("set_manual_toggle:")) {
            String[] p = action.substring(18).split(":", 2);
            if (p.length < 2) return;
            ShopData shop = ShopManager.getInstance().getShop(p[0]);
            if (shop == null) return;
            ShopSet set = shop.findSet(p[1]);
            if (set == null) return;
            RotationConfig rot = set.getRotation();
            if (rot.getMode() == RotationConfig.Mode.MANUAL)
                rot.setManualActive(!rot.isManualActive());
            ShopManager.getInstance().saveShop(shop);
            sendSetList(player, p[0]);
            return;
        }

        if (action.startsWith("open_item_list:")) {
            String[] p = action.substring(15).split(":", 2);
            if (p.length == 2) sendItemList(player, p[0], p[1]);
            return;
        }

        if (action.startsWith("open_item:")) {
            String[] p = action.substring(10).split(":", 3);
            if (p.length == 3) sendItemEdit(player, p[0], p[1], p[2]);
            return;
        }

        if (action.startsWith("item_create:")) {
            String[] p = action.substring(12).split(":", 2);
            if (p.length < 2) return;
            ShopData shop = ShopManager.getInstance().getShop(p[0]);
            if (shop == null) return;
            ShopSet set = shop.findSet(p[1]);
            if (set == null) return;
            ShopItem item = set.addItem();
            ShopManager.getInstance().saveShop(shop);
            sendItemEdit(player, p[0], p[1], item.getId());
            return;
        }

        if (action.startsWith("item_delete:")) {
            String[] p = action.substring(12).split(":", 3);
            if (p.length < 3) return;
            ShopData shop = ShopManager.getInstance().getShop(p[0]);
            if (shop == null) return;
            ShopSet set = shop.findSet(p[1]);
            if (set == null) return;
            set.removeItem(p[2]);
            ShopManager.getInstance().saveShop(shop);
            sendItemList(player, p[0], p[1]);
            return;
        }

        if (action.startsWith("item_toggle:")) {
            String[] p = action.substring(12).split(":", 3);
            if (p.length < 3) return;
            ShopData shop = ShopManager.getInstance().getShop(p[0]);
            if (shop == null) return;
            ShopSet set = shop.findSet(p[1]);
            if (set == null) return;
            ShopItem item = set.findItem(p[2]);
            if (item == null) return;
            item.setEnabled(!item.isEnabled());
            ShopManager.getInstance().saveShop(shop);
            sendItemList(player, p[0], p[1]);
            return;
        }

        if (action.startsWith("item_save:")) {
            String[] parts = action.substring(10).split("\\|", 2);
            if (parts.length < 2) return;
            String[] ids = parts[0].split(":", 3);
            if (ids.length < 3) return;
            ShopData shop = ShopManager.getInstance().getShop(ids[0]);
            if (shop == null) return;
            ShopSet set = shop.findSet(ids[1]);
            if (set == null) return;
            ShopItem item = set.findItem(ids[2]);
            if (item == null) return;
            applyItemEdits(item, parseJson(parts[1]));
            ShopManager.getInstance().saveShop(shop);
            sendItemEdit(player, ids[0], ids[1], ids[2]);
            return;
        }

        if (action.startsWith("item_output_add:")) {
            // item_output_add:shopId|setId|shopItemId|mcItemId|count  (uses | to avoid mcItemId colon clash)
            String[] p = action.substring(16).split("\\|", 5);
            if (p.length < 5) return;
            modifyEntryList(p[0], p[1], p[2], p[3], parseInt(p[4]), true, true);
            ShopManager.getInstance().saveShop(ShopManager.getInstance().getShop(p[0]));
            sendItemEdit(player, p[0], p[1], p[2]);
            return;
        }
        if (action.startsWith("item_output_remove:")) {
            String[] p = action.substring(19).split(":", 4);
            if (p.length < 4) return;
            removeEntry(p[0], p[1], p[2], parseInt(p[3]), true);
            ShopManager.getInstance().saveShop(ShopManager.getInstance().getShop(p[0]));
            sendItemEdit(player, p[0], p[1], p[2]);
            return;
        }
        if (action.startsWith("item_cost_add:")) {
            // item_cost_add:shopId|setId|shopItemId|mcItemId|count
            String[] p = action.substring(14).split("\\|", 5);
            if (p.length < 5) return;
            modifyEntryList(p[0], p[1], p[2], p[3], parseInt(p[4]), false, true);
            ShopManager.getInstance().saveShop(ShopManager.getInstance().getShop(p[0]));
            sendItemEdit(player, p[0], p[1], p[2]);
            return;
        }
        if (action.startsWith("item_cost_remove:")) {
            String[] p = action.substring(17).split(":", 4);
            if (p.length < 4) return;
            removeEntry(p[0], p[1], p[2], parseInt(p[3]), false);
            ShopManager.getInstance().saveShop(ShopManager.getInstance().getShop(p[0]));
            sendItemEdit(player, p[0], p[1], p[2]);
            return;
        }

        if (action.startsWith("item_reorder:")) {
            // item_reorder:shopId:setId:itemId:targetIndex
            String[] p = action.substring(13).split(":", 4);
            if (p.length < 4) return;
            ShopData shop = ShopManager.getInstance().getShop(p[0]);
            if (shop == null) return;
            ShopSet set = shop.findSet(p[1]);
            if (set == null) return;
            int targetIdx = parseInt(p[3]);
            // Move item to target index by updating sortOrder of all items
            java.util.List<ShopItem> items = set.getItems();
            ShopItem moving = set.findItem(p[2]);
            if (moving == null) return;
            items.remove(moving);
            int clampedIdx = Math.max(0, Math.min(targetIdx, items.size()));
            items.add(clampedIdx, moving);
            // Reassign sortOrder
            for (int i = 0; i < items.size(); i++) items.get(i).setSortOrder(i);
            ShopManager.getInstance().saveShop(shop);
            sendItemList(player, p[0], p[1]);
            return;
        }

        if (action.startsWith("picker_open:")) {
            // Send ALL items to client for client-side filtering by translated name
            String context = action.substring(12);
            sendAllPickerItems(player, context);
            return;
        }

        if (action.startsWith("picker_search:")) {
            String[] parts = action.substring(14).split("\\|", 2);
            String query = parts[0];
            String context = parts.length > 1 ? parts[1] : "";
            sendPickerResults(player, query, context);
            return;
        }

        // ── Player actions ─────────────────────────────────────────────────────

        if (action.startsWith("open_shop_player:")) {
            String shopId = action.substring(17);
            com.cheesemarket.data.ShopData shop = ShopManager.getInstance().getShop(shopId);
            if (shop == null) return;
            shop = MorphoShopRenderer.materialize(shop);
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new com.cheesemarket.network.ShopPayloads.ShopOpenPayload(false));
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new com.cheesemarket.network.ShopPayloads.ShopSyncPayload("SHOP_VIEW",
                    buildPlayerShopViewJson(shop, null, player)));
            return;
        }

        if (action.startsWith("shop_buy:")) {
            // shop_buy:shopId:setId:itemId:quantity (quantity optional, default 1)
            String[] p = action.substring(9).split(":", 4);
            if (p.length < 3) return;
            int qty = p.length >= 4 ? Math.max(1, Math.min(64, parseInt(p[3]))) : 1;
            handlePurchase(player, p[0], p[1], p[2], qty);
        }
    }

    // ── Sync senders ──────────────────────────────────────────────────────────

    private static void sendShopList(ServerPlayerEntity player) {
        JsonObject data = new JsonObject();
        JsonArray shops = new JsonArray();
        for (ShopData shop : ShopManager.getInstance().getAllShops()) {
            JsonObject s = new JsonObject();
            s.addProperty("id", shop.getId());
            s.addProperty("name", shop.getName());
            s.addProperty("displayMode", shop.getDisplayMode().name());
            s.addProperty("setCount", shop.getSets().size());
            shops.add(s);
        }
        data.add("shops", shops);
        sync(player, "SHOP_LIST", data);
    }

    private static void sendShopEdit(ServerPlayerEntity player, String shopId) {
        ShopData shop = ShopManager.getInstance().getShop(shopId);
        if (shop == null) { sendShopList(player); return; }
        sync(player, "SHOP_EDIT", shop.toJson());
    }

    private static void sendSetList(ServerPlayerEntity player, String shopId) {
        ShopData shop = ShopManager.getInstance().getShop(shopId);
        if (shop == null) { sendShopList(player); return; }
        JsonObject data = new JsonObject();
        data.addProperty("shopId", shopId);
        data.addProperty("shopName", shop.getName());
        JsonArray sets = new JsonArray();
        for (ShopSet set : shop.getSets()) {
            JsonObject s = new JsonObject();
            s.addProperty("id", set.getId());
            s.addProperty("name", set.getName());
            s.addProperty("active", set.isCurrentlyActive());
            s.addProperty("itemCount", set.getItems().size());
            s.addProperty("rotationLabel", set.getRotation().modeLabel());
            sets.add(s);
        }
        data.add("sets", sets);
        sync(player, "SET_LIST", data);
    }

    private static void sendSetEdit(ServerPlayerEntity player, String shopId, String setId) {
        ShopData shop = ShopManager.getInstance().getShop(shopId);
        if (shop == null) return;
        ShopSet set = shop.findSet(setId);
        if (set == null) { sendSetList(player, shopId); return; }
        JsonObject data = set.toJson();
        data.addProperty("shopId", shopId);
        data.addProperty("shopName", shop.getName());
        sync(player, "SET_EDIT", data);
    }

    private static void sendItemList(ServerPlayerEntity player, String shopId, String setId) {
        ShopData shop = ShopManager.getInstance().getShop(shopId);
        if (shop == null) return;
        ShopSet set = shop.findSet(setId);
        if (set == null) return;
        JsonObject data = new JsonObject();
        data.addProperty("shopId", shopId);
        data.addProperty("setId", setId);
        data.addProperty("shopName", shop.getName());
        data.addProperty("setName", set.getName());
        JsonArray items = new JsonArray();
        for (ShopItem item : set.getItems()) {
            JsonObject i = new JsonObject();
            i.addProperty("id", item.getId());
            i.addProperty("displayItem", item.getDisplayItem());
            i.addProperty("displayName", item.getEffectiveName());
            // Include full item string with components for correct icon rendering
            if (!item.getOutputs().isEmpty() && item.getOutputs().get(0).hasComponents()) {
                ItemEntry firstOut = item.getOutputs().get(0);
                i.addProperty("displayStack", firstOut.getItemId() + firstOut.getComponents());
            }
            i.addProperty("enabled", item.isEnabled());
            i.addProperty("outputsSummary", item.outputsSummary());
            i.addProperty("costsSummary", item.costsSummary());
            i.addProperty("stockMode", item.getStock().getMode().name());
            items.add(i);
        }
        data.add("items", items);
        sync(player, "ITEM_LIST", data);
    }

    private static void sendItemEdit(ServerPlayerEntity player, String shopId, String setId, String itemId) {
        ShopData shop = ShopManager.getInstance().getShop(shopId);
        if (shop == null) return;
        ShopSet set = shop.findSet(setId);
        if (set == null) return;
        ShopItem item = set.findItem(itemId);
        if (item == null) { sendItemList(player, shopId, setId); return; }
        JsonObject data = item.toJson();
        data.addProperty("shopId", shopId);
        data.addProperty("setId", setId);
        data.addProperty("shopName", shop.getName());
        data.addProperty("setName", set.getName());
        sync(player, "ITEM_EDIT", data);
    }

    private static void sendPickerResults(ServerPlayerEntity player, String query, String context) {
        JsonObject data = new JsonObject();
        data.addProperty("query", query);
        data.addProperty("context", context);
        JsonArray results = new JsonArray();
        // Walk all registered item IDs (MC registry) - filter by query
        net.minecraft.registry.Registries.ITEM.getIds().stream()
            .map(id -> id.toString())
            .filter(id -> { String q = query.toLowerCase(); if (q.isBlank()) return true; if (id.contains(q)) return true; String sn = id.contains(":") ? id.substring(id.indexOf(':') + 1).replace('_', ' ') : id; return sn.toLowerCase().contains(q); })
            .limit(100)
            .forEach(id -> {
                JsonObject r = new JsonObject();
                r.addProperty("id", id);
                String shortName = id.contains(":") ? id.substring(id.indexOf(':') + 1).replace('_', ' ') : id;
                r.addProperty("name", shortName);
                results.add(r);
            });
        data.add("results", results);
        sync(player, "ITEM_PICKER", data);
    }

    private static void sendAllPickerItems(ServerPlayerEntity player, String context) {
        JsonObject data = new JsonObject();
        data.addProperty("query", "");
        data.addProperty("context", context);
        data.addProperty("allItems", true);
        JsonArray results = new JsonArray();
        // Include translation key so client can match by translated name
        net.minecraft.registry.Registries.ITEM.stream()
            .sorted(java.util.Comparator.comparing(item ->
                net.minecraft.registry.Registries.ITEM.getId(item).toString()))
            .forEach(item -> {
                JsonObject r = new JsonObject();
                String id = net.minecraft.registry.Registries.ITEM.getId(item).toString();
                r.addProperty("id", id);
                // translationKey lets the client map to translated display name
                r.addProperty("translationKey", item.getTranslationKey());
                results.add(r);
            });
        data.add("results", results);
        sync(player, "ITEM_PICKER", data);
    }

    private static void sendError(ServerPlayerEntity player, String message) {
        JsonObject data = new JsonObject();
        data.addProperty("error", message);
        sync(player, "ERROR", data);
    }

    // ── Purchase ──────────────────────────────────────────────────────────────

    private static void handlePurchase(ServerPlayerEntity player, String shopId, String setId, String itemId, int quantity) {
        // Morpho-catalog shops do not persist their items, so the regular ShopManager
        // pipeline (which validates against the on-disk shop) cannot be used. Resolve
        // the synthetic item, deduct fromage manually and run the grant command.
        ShopData rawShop = ShopManager.getInstance().getShop(shopId);
        if (rawShop != null && rawShop.isMorphoCatalog()) {
            handleMorphoPurchase(player, rawShop, itemId);
            return;
        }

        ShopManager.PurchaseResult result =
            ShopManager.getInstance().canPurchase(shopId, setId, itemId, player.getUuid());

        if (result != ShopManager.PurchaseResult.OK) {
            String key = switch (result) {
                case OUT_OF_GLOBAL_STOCK -> "cheesemarket.gui.out_of_stock";
                case PLAYER_LIMIT_REACHED -> "cheesemarket.gui.limit_reached";
                case ITEM_DISABLED -> "cheesemarket.gui.item_disabled";
                case SET_INACTIVE -> "cheesemarket.gui.set_inactive";
                default -> "cheesemarket.gui.purchase_failed";
            };
            player.sendMessage(net.minecraft.text.Text.translatable(key).formatted(net.minecraft.util.Formatting.RED), false);
            JsonObject data = new JsonObject();
            data.addProperty("purchaseResult", result.name());
            data.addProperty("shopId", shopId);
            sync(player, "PURCHASE_RESULT", data);
            return;
        }

        // Check player inventory for costs
        ShopData shop = ShopManager.getInstance().getShop(shopId);
        if (shop == null) return;
        ShopSet set = shop.findSet(setId);
        if (set == null) return;
        ShopItem item = set.findItem(itemId);
        if (item == null) return;

        // Check if player has space for outputs before deducting costs
        if (!canFitOutputs(player, item, quantity)) {
            player.sendMessage(net.minecraft.text.Text.translatable("cheesemarket.gui.inventory_full").formatted(net.minecraft.util.Formatting.RED), false);
            JsonObject data2 = new JsonObject();
            data2.addProperty("purchaseResult", "INVENTORY_FULL");
            data2.addProperty("shopId", shopId);
            sync(player, "PURCHASE_RESULT", data2);
            return;
        }

        // Deduct costs from player inventory (multiplied by quantity)
        if (!deductCosts(player, item, quantity)) {
            player.sendMessage(net.minecraft.text.Text.translatable("cheesemarket.gui.insufficient_funds", quantity).formatted(net.minecraft.util.Formatting.RED), false);
            JsonObject data = new JsonObject();
            data.addProperty("purchaseResult", "INSUFFICIENT_FUNDS");
            data.addProperty("shopId", shopId);
            sync(player, "PURCHASE_RESULT", data);
            return;
        }

        // Give outputs (multiplied by quantity) — wrapped in try-catch for safety
        try {
            giveOutputs(player, item, quantity);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger("CheeseMarket").error(
                "[Market] CRITICAL: giveOutputs failed for player {} buying {} x{} — payment was already taken! Error: {}",
                player.getName().getString(), itemId, quantity, e.getMessage(), e);
            // Try to refund by giving back cost items via /give as last resort
            player.sendMessage(net.minecraft.text.Text.literal(
                "[Market] Erreur de livraison ! Contactez un admin pour un remboursement.")
                .formatted(net.minecraft.util.Formatting.RED), false);
        }

        // Force immediate inventory sync to client — use raw slot packets with window -2
        // (bypasses screen handler, works even inside EasyNPC dialog screens)
        try {
            for (int slot = 0; slot < player.getInventory().size(); slot++) {
                player.networkHandler.sendPacket(
                    new net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket(
                        -2, 0, slot, player.getInventory().getStack(slot)));
            }
        } catch (Exception ignored) {}

        // Execute server commands (multiplied by quantity)
        executeCommands(player, item, quantity);

        // Record purchase (single call with quantity — one disk write)
        String pName = player.getName().getString();
        ShopManager.getInstance().recordPurchase(shopId, setId, itemId, player.getUuid(), pName, quantity);

        // Chat message: success
        String qLabel = quantity > 1 ? " x"+quantity : "";
        player.sendMessage(net.minecraft.text.Text.translatable("cheesemarket.gui.purchase_success",
                item.getEffectiveName() + qLabel).formatted(net.minecraft.util.Formatting.GREEN), false);

        // Send purchase result FIRST so client closes confirm dialog instantly
        JsonObject data = new JsonObject();
        data.addProperty("purchaseResult", "OK");
        data.addProperty("shopId", shopId);
        sync(player, "PURCHASE_RESULT", data);

        // WS broadcast: notify dashboard of purchase
        try {
            if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cheesedash")) {
                JsonObject wsMsg = new JsonObject();
                wsMsg.addProperty("type", "market_purchase");
                wsMsg.addProperty("playerName", pName);
                wsMsg.addProperty("shopId", shopId);
                wsMsg.addProperty("itemName", item.getEffectiveName());
                wsMsg.addProperty("quantity", quantity);
                wsMsg.addProperty("timestamp", System.currentTimeMillis());
                com.cheesedash.DashboardRegistry.broadcast("market", wsMsg.toString());
            }
        } catch (NoClassDefFoundError ignored) {}

        // Refresh the shop view for the player (stock updates only — skip base64 images, client has them)
        com.cheesemarket.network.ShopPayloads.ShopSyncPayload viewRefresh =
            new com.cheesemarket.network.ShopPayloads.ShopSyncPayload("SHOP_VIEW",
                buildPlayerShopViewJson(shop, set, player, true));
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, viewRefresh);
    }

    public static String buildPlayerShopViewJson(com.cheesemarket.data.ShopData shop, com.cheesemarket.data.ShopSet currentSet, net.minecraft.server.network.ServerPlayerEntity player) {
        return buildPlayerShopViewJson(shop, currentSet, player, false);
    }

    public static String buildPlayerShopViewJson(com.cheesemarket.data.ShopData shop, com.cheesemarket.data.ShopSet currentSet, net.minecraft.server.network.ServerPlayerEntity player, boolean skipImages) {
        JsonObject data = new JsonObject();
        data.addProperty("shopId", shop.getId());
        data.addProperty("shopName", shop.getName());
        data.addProperty("description", shop.getDescription() != null ? shop.getDescription() : "");
        data.addProperty("displayMode", shop.getDisplayMode().name());
        data.addProperty("showWelcomeMessage", shop.isShowWelcomeMessage());
        data.addProperty("welcomeMessage", shop.getWelcomeMessage());
        data.addProperty("titleColor", shop.getTitleColor());
        data.addProperty("bgColor", shop.getBgColor() != null ? shop.getBgColor() : "1A1A2E");
        data.addProperty("borderColor", shop.getBorderColor() != null ? shop.getBorderColor() : "2A5A8A");
        data.addProperty("accentColor", shop.getAccentColor() != null ? shop.getAccentColor() : "4AC8EA");
        data.addProperty("btnBuyColor", shop.getBtnBuyColor());
        data.addProperty("btnBuyTextColor", shop.getBtnBuyTextColor());
        data.addProperty("btnCloseColor", shop.getBtnCloseColor());
        data.addProperty("btnCloseTextColor", shop.getBtnCloseTextColor());
        // Layout (null = default)
        if (shop.getLayout() != null) {
            data.add("layout", shop.getLayout().toJson());

            if (!skipImages) {
                // Embed custom background image as base64 if present
                for (var el : shop.getLayout().getElements()) {
                    if (el.getType() == com.cheesemarket.data.ShopLayout.ElementType.BACKGROUND) {
                        String src = el.getProps().has("src") ? el.getProps().get("src").getAsString() : "default";
                        if (!"default".equals(src) && !src.isEmpty()) {
                            byte[] imgBytes = com.cheesemarket.dashboard.AssetManager.getInstance().getAsset(shop.getId(), src);
                            if (imgBytes != null) {
                                data.addProperty("backgroundImageBase64", java.util.Base64.getEncoder().encodeToString(imgBytes));
                            }
                        }
                        break;
                    }
                }

                // Embed confirm background image as base64 if present
                if (shop.getLayout().getConfirmLayout() != null) {
                    String confirmBg = shop.getLayout().getConfirmLayout().getBgImage();
                    if (confirmBg != null && !confirmBg.isEmpty()) {
                        byte[] imgBytes = com.cheesemarket.dashboard.AssetManager.getInstance().getAsset(shop.getId(), confirmBg);
                        if (imgBytes != null) {
                            data.addProperty("confirmBgImageBase64", java.util.Base64.getEncoder().encodeToString(imgBytes));
                        }
                    }
                }
            }
        }
        com.google.gson.JsonArray sets = new com.google.gson.JsonArray();
        boolean morphoShop = shop.isMorphoCatalog();
        for (var set : shop.getActiveSets()) {
            com.google.gson.JsonObject s = new com.google.gson.JsonObject();
            s.addProperty("id", set.getId());
            s.addProperty("name", set.getName());
            com.google.gson.JsonArray items = new com.google.gson.JsonArray();
            for (var it : set.getEnabledItems()) {
                com.google.gson.JsonObject i = it.toJson();
                if (morphoShop) {
                    // Synthetic shop — bypass ShopManager (the items aren't on disk).
                    String morphoId = it.getId().startsWith("morpho_")
                        ? it.getId().substring("morpho_".length()) : it.getId();
                    boolean owned = false;
                    try {
                        Class<?> modCls = Class.forName("com.cheeseitems.CheeseItemsMod");
                        Object mod = modCls.getField("INSTANCE").get(null);
                        Object codex = mod.getClass().getMethod("getMorphoCodex").invoke(mod);
                        owned = (boolean) codex.getClass()
                            .getMethod("hasInCodex", java.util.UUID.class, String.class)
                            .invoke(codex, player.getUuid(), morphoId);
                    } catch (Exception ignored) {}
                    i.addProperty("stockDisplay", owned ? "Possédé" : "");
                    i.addProperty("canBuy", !owned);
                } else {
                    i.addProperty("stockDisplay", ShopManager.getInstance()
                        .stockDisplay(shop.getId(), set.getId(), it.getId(), player.getUuid()));
                    i.addProperty("canBuy", ShopManager.getInstance()
                        .canPurchase(shop.getId(), set.getId(), it.getId(), player.getUuid())
                        == ShopManager.PurchaseResult.OK);
                }
                items.add(i);
            }
            s.add("items", items);
            sets.add(s);
        }
        data.add("sets", sets);

        // Resolve cheeseitems:* items for client-side rendering (icon images as base64)
        if (CheeseItemsHook.isAvailable()) {
            JsonObject resolved = new JsonObject();
            Set<String> seen = new HashSet<>();
            for (var set : shop.getActiveSets()) {
                for (var it : set.getEnabledItems()) {
                    collectCheeseItemId(it.getDisplayItem(), seen);
                    for (ItemEntry e : it.getOutputs()) collectCheeseItemId(e.getItemId(), seen);
                    for (ItemEntry e : it.getCosts()) collectCheeseItemId(e.getItemId(), seen);
                }
            }
            org.slf4j.LoggerFactory.getLogger("CheeseMarket").info(
                "[Market] Resolving {} cheeseitems icons for shop view", seen.size());
            for (String ciFullId : seen) {
                String ciId = ciFullId.replace("cheeseitems:", "");
                JsonObject entry = new JsonObject();
                // Send icon image as base64 for client-side texture rendering
                byte[] imgBytes = CheeseItemsHook.getItemImage(ciFullId);
                if (imgBytes != null) {
                    entry.addProperty("image", java.util.Base64.getEncoder().encodeToString(imgBytes));
                    org.slf4j.LoggerFactory.getLogger("CheeseMarket").info(
                        "[Market]   {} → image {} bytes", ciId, imgBytes.length);
                } else {
                    org.slf4j.LoggerFactory.getLogger("CheeseMarket").warn(
                        "[Market]   {} → NO image found", ciId);
                }
                // Also send display name for tooltips
                String displayName = CheeseItemsHook.getDisplayName(ciFullId);
                if (displayName != null) entry.addProperty("displayName", displayName);
                // Morpho-catalog items expose an additional description shown in the lore.
                String description = CheeseItemsHook.getDescription(ciFullId);
                if (description != null && !description.isEmpty()) entry.addProperty("description", description);
                // Send base item as fallback
                String baseItem = CheeseItemsHook.resolveBaseItem(ciFullId);
                if (baseItem != null) entry.addProperty("baseItem", baseItem);
                if (entry.size() > 0) resolved.add(ciId, entry);
            }
            if (resolved.size() > 0) {
                data.add("resolvedCheeseItems", resolved);
                org.slf4j.LoggerFactory.getLogger("CheeseMarket").info(
                    "[Market] Added resolvedCheeseItems with {} entries to shop JSON", resolved.size());
            }
        }

        // ── Resolve tooltips for all item IDs (serialized Text JSON for exact vanilla rendering) ──
        {
            Set<String> tipIds = new HashSet<>();
            for (var s2 : shop.getActiveSets()) {
                for (var it2 : s2.getEnabledItems()) {
                    String di = it2.getDisplayItem();
                    if (di != null && !di.isBlank()) tipIds.add(di);
                    for (ItemEntry e : it2.getOutputs()) tipIds.add(e.getItemId());
                    for (ItemEntry e : it2.getCosts()) tipIds.add(e.getItemId());
                }
            }
            JsonObject tooltips = new JsonObject();
            var registries = player.getRegistryManager();
            for (String tipId : tipIds) {
                try {
                    net.minecraft.item.ItemStack stack = null;
                    // CheeseItems: build actual stack with full NBT
                    if (CheeseItemsHook.isAvailable() && CheeseItemsHook.isCheeseItem(tipId)) {
                        Object built = CheeseItemsHook.buildItemStack(tipId, 1);
                        if (built instanceof net.minecraft.item.ItemStack is) stack = is;
                    }
                    // Fromage: manual tooltip (virtual currency, not a real item)
                    if (stack == null && CheeseStoreHook.isFromage(tipId)) {
                        JsonArray fl = new JsonArray();
                        fl.add(net.minecraft.text.Text.Serialization.toJsonString(
                            net.minecraft.text.Text.literal("Cr\u00e9dit").formatted(net.minecraft.util.Formatting.GOLD), registries));
                        fl.add(net.minecraft.text.Text.Serialization.toJsonString(
                            net.minecraft.text.Text.literal(tipId).formatted(net.minecraft.util.Formatting.DARK_GRAY), registries));
                        tooltips.add(tipId, fl);
                        continue;
                    }
                    // Standard items: registry lookup
                    if (stack == null) {
                        net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(tipId);
                        if (id != null) {
                            net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(id);
                            if (item != net.minecraft.item.Items.AIR) {
                                stack = new net.minecraft.item.ItemStack(item);
                            }
                        }
                    }
                    if (stack != null && !stack.isEmpty()) {
                        java.util.List<net.minecraft.text.Text> lines = stack.getTooltip(
                            net.minecraft.item.Item.TooltipContext.create(player.getServerWorld()),
                            player,
                            net.minecraft.item.tooltip.TooltipType.ADVANCED
                        );
                        JsonArray la = new JsonArray();
                        for (net.minecraft.text.Text line : lines) {
                            la.add(net.minecraft.text.Text.Serialization.toJsonString(line, registries));
                        }
                        tooltips.add(tipId, la);
                    }
                } catch (Throwable e) {
                    org.slf4j.LoggerFactory.getLogger("CheeseMarket").warn(
                        "[Market] Failed to resolve tooltip for {}: {}", tipId, e.getMessage());
                }
            }
            if (tooltips.size() > 0) {
                data.add("resolvedTooltips", tooltips);
            }
        }

        return data.toString();
    }

    private static void collectCheeseItemId(String itemId, Set<String> out) {
        if (CheeseItemsHook.isCheeseItem(itemId)) out.add(itemId);
    }

    private static boolean deductCosts(ServerPlayerEntity player, ShopItem item, int quantity) {
        boolean hasCurrencyCosts = false;

        // Phase 0: Check fromage costs (CheeseStore premium currency)
        for (ItemEntry cost : item.getCosts()) {
            if (CheeseStoreHook.isFromage(cost.getItemId())) {
                long needed = (long) cost.getCount() * quantity;
                long balance = CheeseStoreHook.getBalance(player.getUuid());
                if (balance < needed) return false;
            }
        }

        // Phase 1: Check all non-currency, non-fromage costs
        for (ItemEntry cost : item.getCosts()) {
            if (CheeseStoreHook.isFromage(cost.getItemId())) continue;
            if (NumismaticHook.isAvailable() && NumismaticHook.isCurrency(cost.getItemId())) {
                hasCurrencyCosts = true;
                continue;
            }
            int found = countInInventory(player, cost.getItemId());
            if (found < cost.getCount() * quantity) return false;
        }

        // Phase 2: Check currency costs (inventory coins + purse)
        long totalBronzeFromPurse = 0;
        if (hasCurrencyCosts) {
            long purseValue = NumismaticHook.getPurseValue(player);
            long purseRemaining = purseValue;
            for (ItemEntry cost : item.getCosts()) {
                if (CheeseStoreHook.isFromage(cost.getItemId())) continue;
                if (!NumismaticHook.isCurrency(cost.getItemId())) continue;
                int needed = cost.getCount() * quantity;
                int inInventory = countInInventory(player, cost.getItemId());
                int shortfall = Math.max(0, needed - inInventory);
                if (shortfall > 0) {
                    long bronzeNeeded = NumismaticHook.toBronze(cost.getItemId(), shortfall);
                    if (bronzeNeeded > purseRemaining) return false;
                    purseRemaining -= bronzeNeeded;
                    totalBronzeFromPurse += bronzeNeeded;
                }
            }
        } else {
            // No numismatic hook — check currency items as normal inventory items
            for (ItemEntry cost : item.getCosts()) {
                if (CheeseStoreHook.isFromage(cost.getItemId())) continue;
                if (!NumismaticHook.isCurrency(cost.getItemId())) continue;
                int found = countInInventory(player, cost.getItemId());
                if (found < cost.getCount() * quantity) return false;
            }
        }

        // Phase 3a: Deduct fromage costs
        long totalFromageDeducted = 0;
        for (ItemEntry cost : item.getCosts()) {
            if (!CheeseStoreHook.isFromage(cost.getItemId())) continue;
            long needed = (long) cost.getCount() * quantity;
            CheeseStoreHook.deduct(player.getUuid(), player.getName().getString(), needed);
            totalFromageDeducted += needed;
        }
        if (totalFromageDeducted > 0) {
            long remaining = CheeseStoreHook.getBalance(player.getUuid());
            player.sendMessage(net.minecraft.text.Text.translatable(
                "cheesemarket.gui.fromage_deducted", totalFromageDeducted, remaining).formatted(net.minecraft.util.Formatting.GOLD), false);
        }

        // Phase 3b: Deduct non-currency, non-fromage items
        for (ItemEntry cost : item.getCosts()) {
            if (CheeseStoreHook.isFromage(cost.getItemId())) continue;
            if (NumismaticHook.isAvailable() && NumismaticHook.isCurrency(cost.getItemId())) continue;
            removeFromInventory(player, cost.getItemId(), cost.getCount() * quantity);
        }

        // Phase 4: Deduct currency items (inventory first, then purse)
        long bronzeFromPurse = 0;
        for (ItemEntry cost : item.getCosts()) {
            if (CheeseStoreHook.isFromage(cost.getItemId())) continue;
            if (!NumismaticHook.isCurrency(cost.getItemId())) continue;
            int needed = cost.getCount() * quantity;
            if (hasCurrencyCosts && NumismaticHook.isAvailable()) {
                int inInventory = countInInventory(player, cost.getItemId());
                int fromInventory = Math.min(inInventory, needed);
                if (fromInventory > 0) removeFromInventory(player, cost.getItemId(), fromInventory);
                int remaining = needed - fromInventory;
                if (remaining > 0) bronzeFromPurse += NumismaticHook.toBronze(cost.getItemId(), remaining);
            } else {
                removeFromInventory(player, cost.getItemId(), needed);
            }
        }
        if (bronzeFromPurse > 0) {
            NumismaticHook.deductFromPurse(player, bronzeFromPurse);
        }

        return true;
    }

    private static int countInInventory(ServerPlayerEntity player, String itemId) {
        if (CheeseStoreHook.isFromage(itemId)) return 0; // Handled separately
        // CheeseItems custom items — match by NBT tag
        if (CheeseItemsHook.isCheeseItem(itemId)) {
            String ciId = itemId.replace("cheeseitems:", "");
            int count = 0;
            for (int i = 0; i < player.getInventory().size(); i++) {
                var stack = player.getInventory().getStack(i);
                if (stack.isEmpty()) continue;
                var comp = stack.get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA);
                if (comp == null) continue;
                var nbt = comp.copyNbt();
                if (nbt.contains("cheeseitems") && ciId.equals(nbt.getString("cheeseitems")))
                    count += stack.getCount();
            }
            return count;
        }
        var registry = net.minecraft.registry.Registries.ITEM;
        var id = net.minecraft.util.Identifier.tryParse(itemId);
        if (id == null || !registry.containsId(id)) return 0;
        var mcItem = registry.get(id);
        int count = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            var stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() == mcItem) count += stack.getCount();
        }
        return count;
    }

    private static void removeFromInventory(ServerPlayerEntity player, String itemId, int amount) {
        if (CheeseStoreHook.isFromage(itemId)) return; // Handled separately
        // CheeseItems custom items — match by NBT tag
        if (CheeseItemsHook.isCheeseItem(itemId)) {
            String ciId = itemId.replace("cheeseitems:", "");
            int remaining = amount;
            for (int i = 0; i < player.getInventory().size() && remaining > 0; i++) {
                var stack = player.getInventory().getStack(i);
                if (stack.isEmpty()) continue;
                var comp = stack.get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA);
                if (comp == null) continue;
                var nbt = comp.copyNbt();
                if (nbt.contains("cheeseitems") && ciId.equals(nbt.getString("cheeseitems"))) {
                    int take = Math.min(stack.getCount(), remaining);
                    stack.decrement(take);
                    remaining -= take;
                    if (stack.isEmpty()) player.getInventory().setStack(i, net.minecraft.item.ItemStack.EMPTY);
                }
            }
            return;
        }
        var registry = net.minecraft.registry.Registries.ITEM;
        var id = net.minecraft.util.Identifier.tryParse(itemId);
        if (id == null || !registry.containsId(id)) return;
        var mcItem = registry.get(id);
        int remaining = amount;
        for (int i = 0; i < player.getInventory().size() && remaining > 0; i++) {
            var stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() == mcItem) {
                int take = Math.min(stack.getCount(), remaining);
                stack.decrement(take);
                remaining -= take;
                if (stack.isEmpty()) player.getInventory().setStack(i, net.minecraft.item.ItemStack.EMPTY);
            }
        }
    }

    /**
     * Check if the player's inventory has enough space for all output items.
     * Simulates insertions without modifying the actual inventory.
     */
    private static boolean canFitOutputs(ServerPlayerEntity player, ShopItem item, int quantity) {
        var registry = net.minecraft.registry.Registries.ITEM;
        // Copy current inventory slot contents to simulate
        var inv = player.getInventory();
        int mainSize = inv.main.size(); // 36 slots (0-35)
        net.minecraft.item.ItemStack[] simSlots = new net.minecraft.item.ItemStack[mainSize];
        for (int i = 0; i < mainSize; i++) {
            simSlots[i] = inv.main.get(i).copy();
        }

        for (ItemEntry output : item.getOutputs()) {
            String outputId = output.getItemId();
            int maxStack;
            if (CheeseItemsHook.isCheeseItem(outputId)) {
                maxStack = 64; // CheeseItems default
            } else {
                var id = net.minecraft.util.Identifier.tryParse(outputId);
                if (id == null || !registry.containsId(id)) continue;
                maxStack = registry.get(id).getMaxCount();
            }

            int remaining = output.getCount() * quantity;
            // Try to fit into existing stacks first, then empty slots
            for (int i = 0; i < mainSize && remaining > 0; i++) {
                if (simSlots[i].isEmpty()) continue;
                String slotId = net.minecraft.registry.Registries.ITEM.getId(simSlots[i].getItem()).toString();
                if (slotId.equals(outputId) && simSlots[i].getCount() < maxStack) {
                    int canAdd = Math.min(remaining, maxStack - simSlots[i].getCount());
                    simSlots[i].setCount(simSlots[i].getCount() + canAdd);
                    remaining -= canAdd;
                }
            }
            for (int i = 0; i < mainSize && remaining > 0; i++) {
                if (!simSlots[i].isEmpty()) continue;
                int canAdd = Math.min(remaining, maxStack);
                // Mark slot as occupied with a dummy stack
                var id = net.minecraft.util.Identifier.tryParse(outputId);
                if (id != null && registry.containsId(id)) {
                    simSlots[i] = new net.minecraft.item.ItemStack(registry.get(id), canAdd);
                } else {
                    // CheeseItems or unknown — use a placeholder
                    simSlots[i] = new net.minecraft.item.ItemStack(net.minecraft.item.Items.STONE, canAdd);
                }
                remaining -= canAdd;
            }
            if (remaining > 0) return false; // doesn't fit
        }
        return true;
    }

    private static void giveOutputs(ServerPlayerEntity player, ShopItem item, int quantity) {
        var registry = net.minecraft.registry.Registries.ITEM;
        for (ItemEntry output : item.getOutputs()) {
            String outputId = output.getItemId();

            // Items with component data — deliver via /give command (handles all MC 1.21.1 components)
            if (output.hasComponents()) {
                var server = player.getServer();
                if (server == null) continue;
                String fullItem = outputId + output.getComponents();
                String playerName = player.getName().getString();
                int remaining = output.getCount() * quantity;
                while (remaining > 0) {
                    int give = Math.min(remaining, 64);
                    String cmd = "give " + playerName + " " + fullItem + " " + give;
                    try {
                        server.getCommandManager().executeWithPrefix(
                            server.getCommandSource().withSilent(), cmd);
                    } catch (Exception e) {
                        org.slf4j.LoggerFactory.getLogger("CheeseMarket").warn(
                            "[CheeseMarket] Give-with-components failed: {}", cmd, e);
                        break;
                    }
                    remaining -= give;
                }
                continue;
            }

            // CheeseItems custom items — build via reflection hook
            if (CheeseItemsHook.isCheeseItem(outputId)) {
                int remaining = output.getCount() * quantity;
                while (remaining > 0) {
                    int give = Math.min(remaining, 64);
                    Object stackObj = CheeseItemsHook.buildItemStack(outputId, give);
                    if (stackObj instanceof net.minecraft.item.ItemStack ciStack) {
                        if (!player.getInventory().insertStack(ciStack)) {
                            player.dropItem(ciStack, false);
                        }
                    } else {
                        org.slf4j.LoggerFactory.getLogger("CheeseMarket").warn(
                            "[CheeseMarket] Failed to build CheeseItems stack for output: {}", outputId);
                        break;
                    }
                    remaining -= give;
                }
                continue;
            }

            var id = net.minecraft.util.Identifier.tryParse(outputId);
            if (id == null || !registry.containsId(id)) {
                org.slf4j.LoggerFactory.getLogger("CheeseMarket").warn(
                    "[Market] Item not in registry, skipping delivery: {} for player {}", outputId, player.getName().getString());
                continue;
            }
            var mcItem = registry.get(id);
            int remaining = output.getCount() * quantity;
            while (remaining > 0) {
                int give = Math.min(remaining, mcItem.getMaxCount());
                var stack = new net.minecraft.item.ItemStack(mcItem, give);
                if (!player.getInventory().insertStack(stack)) {
                    player.dropItem(stack, false);
                }
                remaining -= give;
            }
        }
    }

    private static void executeCommands(ServerPlayerEntity player, ShopItem item, int quantity) {
        if (item.getCommands().isEmpty()) return;
        var server = player.getServer();
        if (server == null) return;
        String playerName = player.getName().getString();
        for (com.cheesemarket.data.CommandEntry cmd : item.getCommands()) {
            String resolved = cmd.getCommand().replace("{player}", playerName);
            if (resolved.startsWith("/")) resolved = resolved.substring(1);
            int times = cmd.getCount() * quantity;
            for (int i = 0; i < times; i++) {
                try {
                    server.getCommandManager().executeWithPrefix(
                        server.getCommandSource().withSilent(), resolved);
                } catch (Exception e) {
                    org.slf4j.LoggerFactory.getLogger("CheeseMarket").warn(
                        "[CheeseMarket] Command failed for {}: {}", playerName, resolved, e);
                }
            }
        }
    }

    // ── Edit appliers ─────────────────────────────────────────────────────────

    private static void applyShopEdits(ShopData shop, JsonObject j) {
        if (j == null) return;
        if (j.has("name"))               shop.setName(j.get("name").getAsString());
        if (j.has("description"))        shop.setDescription(j.get("description").getAsString());
        if (j.has("displayMode")) {
            try { shop.setDisplayMode(ShopData.DisplayMode.valueOf(j.get("displayMode").getAsString())); }
            catch (Exception ignored) {}
        }
        if (j.has("showWelcomeMessage")) shop.setShowWelcomeMessage(j.get("showWelcomeMessage").getAsBoolean());
        if (j.has("welcomeMessage"))     shop.setWelcomeMessage(j.get("welcomeMessage").getAsString());
        if (j.has("titleColor"))         shop.setTitleColor(j.get("titleColor").getAsString());
        if (j.has("bgColor"))           shop.setBgColor(j.get("bgColor").getAsString());
        if (j.has("borderColor"))       shop.setBorderColor(j.get("borderColor").getAsString());
        if (j.has("accentColor"))       shop.setAccentColor(j.get("accentColor").getAsString());
    }

    private static void applySetEdits(ShopSet set, JsonObject j) {
        if (j == null) return;
        if (j.has("name")) set.setName(j.get("name").getAsString());
        if (j.has("rotation")) {
            JsonObject r = j.getAsJsonObject("rotation");
            RotationConfig rot = set.getRotation();
            if (r.has("mode")) {
                try { rot.setMode(RotationConfig.Mode.valueOf(r.get("mode").getAsString())); }
                catch (Exception ignored) {}
            }
            if (r.has("scheduleStart"))  rot.setScheduleStart(r.get("scheduleStart").getAsString());
            if (r.has("scheduleEnd"))    rot.setScheduleEnd(r.get("scheduleEnd").getAsString());
            if (r.has("manualActive"))   rot.setManualActive(r.get("manualActive").getAsBoolean());
        }
    }

    private static void applyItemEdits(ShopItem item, JsonObject j) {
        if (j == null) return;
        if (j.has("displayItem")) item.setDisplayItem(j.get("displayItem").getAsString());
        if (j.has("displayName")) item.setDisplayName(j.get("displayName").getAsString());
        if (j.has("enabled"))     item.setEnabled(j.get("enabled").getAsBoolean());
        if (j.has("sortOrder"))   item.setSortOrder(j.get("sortOrder").getAsInt());
        if (j.has("commands")) {
            item.getCommands().clear();
            for (JsonElement el : j.getAsJsonArray("commands"))
                item.getCommands().add(com.cheesemarket.data.CommandEntry.fromJson(el.getAsJsonObject()));
        }
        if (j.has("stock")) {
            JsonObject s = j.getAsJsonObject("stock");
            StockConfig stock = item.getStock();
            if (s.has("mode")) {
                try { stock.setMode(StockConfig.Mode.valueOf(s.get("mode").getAsString())); }
                catch (Exception ignored) {}
            }
            if (s.has("globalStock"))    stock.setGlobalStock(s.get("globalStock").getAsInt());
            if (s.has("perPlayerLimit")) stock.setPerPlayerLimit(s.get("perPlayerLimit").getAsInt());
            if (s.has("resetType")) {
                try { stock.setResetType(StockConfig.ResetType.valueOf(s.get("resetType").getAsString())); }
                catch (Exception ignored) {}
            }
            if (s.has("resetIntervalSeconds")) stock.setResetIntervalSeconds(s.get("resetIntervalSeconds").getAsLong());
        }
    }

    private static void modifyEntryList(String shopId, String setId, String itemId,
                                         String mcItemId, int count, boolean outputs, boolean add) {
        ShopData shop = ShopManager.getInstance().getShop(shopId);
        if (shop == null) return;
        ShopSet set = shop.findSet(setId);
        if (set == null) return;
        ShopItem item = set.findItem(itemId);
        if (item == null) return;
        if (add) {
            ItemEntry entry = new ItemEntry(mcItemId, count);
            if (outputs) item.getOutputs().add(entry);
            else         item.getCosts().add(entry);
        }
    }

    private static void removeEntry(String shopId, String setId, String itemId, int index, boolean outputs) {
        ShopData shop = ShopManager.getInstance().getShop(shopId);
        if (shop == null) return;
        ShopSet set = shop.findSet(setId);
        if (set == null) return;
        ShopItem item = set.findItem(itemId);
        if (item == null) return;
        var list = outputs ? item.getOutputs() : item.getCosts();
        if (index >= 0 && index < list.size()) list.remove(index);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static void sync(ServerPlayerEntity player, String view, JsonObject data) {
        ServerPlayNetworking.send(player, new ShopSyncPayload(view, data.toString()));
    }

    private static JsonObject parseJson(String s) {
        try { return JsonParser.parseString(s).getAsJsonObject(); }
        catch (Exception e) { return new JsonObject(); }
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 1; }
    }

    // ── Morpho catalog purchase flow (auto-populated shops) ──────────────────

    private static void handleMorphoPurchase(ServerPlayerEntity player, ShopData rawShop, String itemId) {
        if (itemId == null || !itemId.startsWith("morpho_")) {
            sendPurchaseResult(player, rawShop.getId(), "INVALID_ITEM");
            return;
        }
        String morphoId = itemId.substring("morpho_".length());
        // Resolve catalog entry via reflection (CheeseItems is a soft dep).
        CatalogEntryView entry = MorphoCatalogReflect.lookup(morphoId);
        if (entry == null) {
            sendPurchaseResult(player, rawShop.getId(), "ITEM_DISABLED");
            return;
        }
        // Already in codex? Refuse.
        if (MorphoCatalogReflect.hasInCodex(player.getUuid(), morphoId)) {
            player.sendMessage(net.minecraft.text.Text.literal("Vous possédez déjà cette morpho dans votre codex.")
                .formatted(net.minecraft.util.Formatting.YELLOW), false);
            sendPurchaseResult(player, rawShop.getId(), "PLAYER_LIMIT_REACHED");
            return;
        }
        // Deduct fromage
        long balance = CheeseStoreHook.getBalance(player.getUuid());
        if (balance < entry.priceCredits) {
            player.sendMessage(net.minecraft.text.Text.literal(
                "Solde insuffisant : " + balance + " / " + entry.priceCredits + " crédits.")
                .formatted(net.minecraft.util.Formatting.RED), false);
            sendPurchaseResult(player, rawShop.getId(), "INSUFFICIENT_FUNDS");
            return;
        }
        boolean ok = CheeseStoreHook.deduct(player.getUuid(), player.getName().getString(), entry.priceCredits);
        if (!ok) {
            sendPurchaseResult(player, rawShop.getId(), "INSUFFICIENT_FUNDS");
            return;
        }
        // Grant via /cheeseitem morpho grant <player> <morphoId>
        // The command itself sends the in-chat confirmation to the player,
        // so we only add the audio cue here to avoid a duplicate message.
        try {
            net.minecraft.server.MinecraftServer srv = player.getServer();
            if (srv != null) {
                srv.getCommandManager().executeWithPrefix(srv.getCommandSource(),
                    "cheeseitem morpho grant " + player.getName().getString() + " " + morphoId);
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger("CheeseMarket")
                .error("[Market] Failed to grant morpho {}: {}", morphoId, e.getMessage());
        }
        player.playSound(net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.4f);

        sendPurchaseResult(player, rawShop.getId(), "OK");

        // Refresh the materialised view so the player sees the item as already-bought.
        ShopData materialized = MorphoShopRenderer.materialize(rawShop);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
            new com.cheesemarket.network.ShopPayloads.ShopSyncPayload("SHOP_VIEW",
                buildPlayerShopViewJson(materialized, null, player, true)));
    }

    private static void sendPurchaseResult(ServerPlayerEntity player, String shopId, String result) {
        JsonObject data = new JsonObject();
        data.addProperty("purchaseResult", result);
        data.addProperty("shopId", shopId);
        sync(player, "PURCHASE_RESULT", data);
    }

    /** Tiny POJO carrying the data we need from a MorphoEntry without compile-time dep. */
    static final class CatalogEntryView {
        final String title;
        final long priceCredits;
        CatalogEntryView(String title, long priceCredits) {
            this.title = title;
            this.priceCredits = priceCredits;
        }
    }

    /** Minimal reflection helper for CheeseItems' MorphoCatalog + MorphoCodex. */
    private static final class MorphoCatalogReflect {
        private static boolean checked = false;
        private static java.lang.reflect.Method getInstanceM;
        private static java.lang.reflect.Method getM;
        private static java.lang.reflect.Method getTitleM;
        private static java.lang.reflect.Method getPriceM;
        private static java.lang.reflect.Method codexGetInstanceM;
        private static java.lang.reflect.Method codexHasInCodexM;
        private static Object codexInstance;

        static synchronized boolean ensure() {
            if (checked) return getInstanceM != null;
            checked = true;
            try {
                if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cheeseitems")) return false;
                Class<?> catCls = Class.forName("com.cheeseitems.morpho.MorphoCatalog");
                Class<?> entryCls = Class.forName("com.cheeseitems.morpho.MorphoEntry");
                Class<?> modCls = Class.forName("com.cheeseitems.CheeseItemsMod");
                getInstanceM = catCls.getMethod("getInstance");
                getM         = catCls.getMethod("get", String.class);
                getTitleM    = entryCls.getMethod("getTitle");
                getPriceM    = entryCls.getMethod("getPriceCredits");
                java.lang.reflect.Field instF = modCls.getField("INSTANCE");
                Object mod = instF.get(null);
                codexInstance = mod.getClass().getMethod("getMorphoCodex").invoke(mod);
                Class<?> codexCls = codexInstance.getClass();
                codexHasInCodexM = codexCls.getMethod("hasInCodex", java.util.UUID.class, String.class);
                return true;
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger("CheeseMarket")
                    .warn("[Market] Morpho purchase reflection unavailable: {}", e.getMessage());
                getInstanceM = null;
                return false;
            }
        }

        static CatalogEntryView lookup(String id) {
            if (!ensure()) return null;
            try {
                Object cat = getInstanceM.invoke(null);
                Object entry = getM.invoke(cat, id);
                if (entry == null) return null;
                String title = (String) getTitleM.invoke(entry);
                int price = (int) getPriceM.invoke(entry);
                return new CatalogEntryView(title == null ? id : title, price);
            } catch (Exception e) { return null; }
        }

        static boolean hasInCodex(java.util.UUID uuid, String id) {
            if (!ensure() || codexInstance == null) return false;
            try {
                return (boolean) codexHasInCodexM.invoke(codexInstance, uuid, id);
            } catch (Exception e) { return false; }
        }
    }
}
