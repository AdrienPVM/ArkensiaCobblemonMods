package com.cheesemarket.dashboard;

import com.cheesedash.WebDashboard;
import com.cheesemarket.data.*;
import com.cheesemarket.dashboard.AssetManager;
import com.cheesemarket.manager.ShopManager;
import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * /api/v1/market/* — CRUD for shops, sets, items, transactions, stats.
 */
public class MarketApi {

    public static void handle(HttpExchange ex, MinecraftServer server, String route) throws IOException {
        String method = ex.getRequestMethod();
        ShopManager mgr = ShopManager.getInstance();
        if (mgr == null) {
            WebDashboard.respond(ex, 503, "{\"error\":\"ShopManager unavailable\"}");
            return;
        }

        String query = ex.getRequestURI().getQuery();

        AssetManager assets = AssetManager.getInstance();

        // ── Templates ──────────────────────────────────────────────────────
        if (route.equals("/templates") && method.equals("GET")) {
            JsonArray arr = new JsonArray();
            for (String name : assets.listTemplates()) arr.add(name);
            WebDashboard.respondJson(ex, arr);
            return;
        }
        if (route.equals("/templates") && method.equals("POST")) {
            String body = WebDashboard.readBody(ex);
            JsonObject data = parseJson(body);
            String name = data.has("name") ? data.get("name").getAsString().trim() : "";
            if (name.isEmpty()) { WebDashboard.respond(ex, 400, "{\"error\":\"Name required\"}"); return; }
            if (!data.has("layout") || !data.get("layout").isJsonObject()) {
                WebDashboard.respond(ex, 400, "{\"error\":\"Layout required\"}"); return;
            }
            ShopLayout layout = ShopLayout.fromJson(data.getAsJsonObject("layout"));
            String err = assets.saveTemplate(name, layout);
            if (err != null) { WebDashboard.respond(ex, 400, "{\"error\":\"" + escapeJson(err) + "\"}"); return; }
            WebDashboard.respond(ex, 200, "{\"saved\":true}");
            return;
        }
        if (route.startsWith("/templates/") && method.equals("GET")) {
            String tplName = route.substring(11);
            ShopLayout tpl = assets.loadTemplate(tplName);
            if (tpl == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Template not found\"}"); return; }
            WebDashboard.respondJson(ex, tpl.toJson());
            return;
        }
        if (route.startsWith("/templates/") && method.equals("DELETE")) {
            String tplName = route.substring(11);
            if (assets.deleteTemplate(tplName)) WebDashboard.respond(ex, 200, "{\"deleted\":true}");
            else WebDashboard.respond(ex, 404, "{\"error\":\"Template not found\"}");
            return;
        }

        // ── Serve asset images ─────────────────────────────────────────────
        if (route.startsWith("/assets/") && method.equals("GET")) {
            String rest = route.substring(8);
            int slash = rest.indexOf('/');
            if (slash <= 0) { WebDashboard.respond(ex, 400, "{\"error\":\"Invalid path\"}"); return; }
            String shopId = rest.substring(0, slash);
            String assetName = rest.substring(slash + 1);
            byte[] data = assets.getAsset(shopId, assetName);
            if (data == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Asset not found\"}"); return; }
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(data); }
            return;
        }

        // GET /stats
        if (route.equals("/stats") && method.equals("GET")) {
            handleStats(ex, mgr);
            return;
        }

        // GET /transactions?shopId=X&n=50
        if (route.equals("/transactions") && method.equals("GET")) {
            handleTransactions(ex, mgr, query);
            return;
        }

        // GET /shops — list all
        if (route.equals("/shops") && method.equals("GET")) {
            JsonArray arr = new JsonArray();
            for (ShopData shop : mgr.getAllShops()) {
                arr.add(buildShopSummary(shop));
            }
            WebDashboard.respondJson(ex, arr);
            return;
        }

        // POST /shops — create
        if (route.equals("/shops") && method.equals("POST")) {
            String body = WebDashboard.readBody(ex);
            JsonObject data = parseJson(body);
            String id = data.has("id") ? data.get("id").getAsString().trim() : "";
            if (!id.isEmpty() && mgr.shopExists(id)) {
                WebDashboard.respond(ex, 409, "{\"error\":\"Shop ID already exists: " + escapeJson(id) + "\"}");
                return;
            }
            final String finalId = id;
            try {
                final JsonObject[] result = new JsonObject[1];
                WebDashboard.executeOnServerThread(server, () -> {
                    ShopData shop = mgr.createShop(finalId.isEmpty() ? null : finalId);
                    if (data.has("name")) {
                        shop.setName(data.get("name").getAsString());
                        mgr.saveShop(shop);
                    }
                    result[0] = buildShopDetail(shop, mgr);
                });
                WebDashboard.respondJson(ex, result[0]);
            } catch (Exception e) {
                WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
            return;
        }

        // Routes with shop ID: /shops/:shopId/...
        if (route.startsWith("/shops/")) {
            String rest = route.substring(7);
            int slashIdx = rest.indexOf('/');
            String shopId = slashIdx > 0 ? rest.substring(0, slashIdx) : rest;
            String sub = slashIdx > 0 ? rest.substring(slashIdx) : "";
            shopId = URLDecoder.decode(shopId, StandardCharsets.UTF_8);

            if (!isValidId(shopId)) {
                WebDashboard.respond(ex, 400, "{\"error\":\"Invalid shop ID\"}");
                return;
            }

            ShopData shop = mgr.getShop(shopId);

            // GET /shops/:id — detail
            if (sub.isEmpty() && method.equals("GET")) {
                if (shop == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Shop not found\"}"); return; }
                WebDashboard.respondJson(ex, buildShopDetail(shop, mgr));
                return;
            }

            // PUT /shops/:id — update config
            if (sub.isEmpty() && method.equals("PUT")) {
                if (shop == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Shop not found\"}"); return; }
                String body = WebDashboard.readBody(ex);
                JsonObject data = parseJson(body);
                final ShopData s = shop;
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        applyShopEdits(s, data);
                        mgr.saveShop(s);
                    });
                    WebDashboard.respondJson(ex, buildShopDetail(s, mgr));
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                }
                return;
            }

            // DELETE /shops/:id
            if (sub.isEmpty() && method.equals("DELETE")) {
                if (shop == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Shop not found\"}"); return; }
                final String sid = shopId;
                try {
                    WebDashboard.executeOnServerThread(server, () -> mgr.deleteShop(sid));
                    assets.deleteShopAssets(sid);
                    WebDashboard.respond(ex, 200, "{\"deleted\":true}");
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                }
                return;
            }

            // ── Layout sub-routes ──────────────────────────────────────────
            // GET /shops/:id/layout
            if (sub.equals("/layout") && method.equals("GET")) {
                if (shop == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Shop not found\"}"); return; }
                ShopLayout layout = shop.getLayout();
                if (layout == null) layout = ShopLayout.buildDefault();
                WebDashboard.respondJson(ex, layout.toJson());
                return;
            }
            // PUT /shops/:id/layout
            if (sub.equals("/layout") && method.equals("PUT")) {
                if (shop == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Shop not found\"}"); return; }
                String body = WebDashboard.readBody(ex);
                JsonObject data = parseJson(body);
                final ShopData s = shop;
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        if (data.has("elements") || data.has("itemCard")) {
                            ShopLayout layout = ShopLayout.fromJson(data);
                            // Validate: max 20 elements, coords bounded
                            if (layout.getElements().size() > 20) {
                                throw new RuntimeException("Maximum 20 elements");
                            }
                            s.setLayout(layout);
                        } else {
                            s.setLayout(null); // reset to default
                        }
                        mgr.saveShop(s);
                    });
                    ShopLayout result = s.getLayout();
                    if (result == null) result = ShopLayout.buildDefault();
                    WebDashboard.respondJson(ex, result.toJson());
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                }
                return;
            }

            // ── Asset sub-routes ──────────────────────────────────────────
            // GET /shops/:id/assets — list
            if (sub.equals("/assets") && method.equals("GET")) {
                if (shop == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Shop not found\"}"); return; }
                JsonArray arr = new JsonArray();
                for (String name : assets.listAssets(shopId)) arr.add(name);
                WebDashboard.respondJson(ex, arr);
                return;
            }
            // POST /shops/:id/assets — upload (base64 in JSON body)
            if (sub.equals("/assets") && method.equals("POST")) {
                if (shop == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Shop not found\"}"); return; }
                String body = WebDashboard.readBody(ex);
                JsonObject data = parseJson(body);
                String assetName = data.has("name") ? data.get("name").getAsString().trim() : "";
                String base64Data = data.has("data") ? data.get("data").getAsString() : "";
                if (assetName.isEmpty()) { WebDashboard.respond(ex, 400, "{\"error\":\"Name required\"}"); return; }
                if (base64Data.isEmpty()) { WebDashboard.respond(ex, 400, "{\"error\":\"Data required\"}"); return; }
                // Strip data URL prefix if present
                if (base64Data.contains(",")) base64Data = base64Data.substring(base64Data.indexOf(",") + 1);
                try {
                    byte[] bytes = Base64.getDecoder().decode(base64Data);
                    String err = assets.uploadAsset(shopId, assetName, bytes);
                    if (err != null) { WebDashboard.respond(ex, 400, "{\"error\":\"" + escapeJson(err) + "\"}"); return; }
                    WebDashboard.respond(ex, 200, "{\"uploaded\":true,\"name\":\"" + escapeJson(assetName) + "\"}");
                } catch (Exception e) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"Invalid base64 data\"}");
                }
                return;
            }
            // DELETE /shops/:id/assets/:name
            if (sub.startsWith("/assets/") && method.equals("DELETE")) {
                if (shop == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Shop not found\"}"); return; }
                String assetName = sub.substring(8);
                if (assets.deleteAsset(shopId, assetName)) WebDashboard.respond(ex, 200, "{\"deleted\":true}");
                else WebDashboard.respond(ex, 404, "{\"error\":\"Asset not found\"}");
                return;
            }

            // POST /shops/:id/sets — create set
            if (sub.equals("/sets") && method.equals("POST")) {
                if (shop == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Shop not found\"}"); return; }
                String body = WebDashboard.readBody(ex);
                JsonObject data = parseJson(body);
                final ShopData s = shop;
                try {
                    final JsonObject[] result = new JsonObject[1];
                    WebDashboard.executeOnServerThread(server, () -> {
                        ShopSet set = s.addSet();
                        if (data.has("name")) set.setName(data.get("name").getAsString());
                        mgr.saveShop(s);
                        result[0] = set.toJson();
                    });
                    WebDashboard.respondJson(ex, result[0]);
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                }
                return;
            }

            // Routes with set ID: /shops/:shopId/sets/:setId/...
            if (sub.startsWith("/sets/")) {
                if (shop == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Shop not found\"}"); return; }
                handleSetRoutes(ex, server, mgr, shop, sub.substring(6), method);
                return;
            }
        }

        WebDashboard.respond(ex, 404, "{\"error\":\"Unknown market route: " + escapeJson(route) + "\"}");
    }

    // ── Set & Item sub-routes ────────────────────────────────────────────────

    private static void handleSetRoutes(HttpExchange ex, MinecraftServer server, ShopManager mgr,
                                         ShopData shop, String rest, String method) throws IOException {
        int slashIdx = rest.indexOf('/');
        String setId = slashIdx > 0 ? rest.substring(0, slashIdx) : rest;
        String sub = slashIdx > 0 ? rest.substring(slashIdx) : "";

        if (!isValidId(setId)) {
            WebDashboard.respond(ex, 400, "{\"error\":\"Invalid set ID\"}");
            return;
        }

        ShopSet set = shop.findSet(setId);

        // PUT /shops/:shopId/sets/:setId
        if (sub.isEmpty() && method.equals("PUT")) {
            if (set == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Set not found\"}"); return; }
            String body = WebDashboard.readBody(ex);
            JsonObject data = parseJson(body);
            try {
                WebDashboard.executeOnServerThread(server, () -> {
                    applySetEdits(set, data);
                    mgr.saveShop(shop);
                });
                WebDashboard.respondJson(ex, set.toJson());
            } catch (Exception e) {
                WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
            return;
        }

        // DELETE /shops/:shopId/sets/:setId
        if (sub.isEmpty() && method.equals("DELETE")) {
            if (set == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Set not found\"}"); return; }
            try {
                WebDashboard.executeOnServerThread(server, () -> {
                    shop.removeSet(setId);
                    mgr.saveShop(shop);
                });
                WebDashboard.respond(ex, 200, "{\"deleted\":true}");
            } catch (Exception e) {
                WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
            return;
        }

        // POST /shops/:shopId/sets/:setId/items — create item
        if (sub.equals("/items") && method.equals("POST")) {
            if (set == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Set not found\"}"); return; }
            String body = WebDashboard.readBody(ex);
            JsonObject data = parseJson(body);
            try {
                final JsonObject[] result = new JsonObject[1];
                WebDashboard.executeOnServerThread(server, () -> {
                    ShopItem item = set.addItem();
                    applyItemEdits(item, data);
                    mgr.saveShop(shop);
                    result[0] = item.toJson();
                });
                WebDashboard.respondJson(ex, result[0]);
            } catch (Exception e) {
                WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
            return;
        }

        // Routes with item ID: /shops/:shopId/sets/:setId/items/:itemId/...
        if (sub.startsWith("/items/")) {
            if (set == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Set not found\"}"); return; }
            handleItemRoutes(ex, server, mgr, shop, set, sub.substring(7), method);
            return;
        }

        WebDashboard.respond(ex, 404, "{\"error\":\"Unknown set route\"}");
    }

    private static void handleItemRoutes(HttpExchange ex, MinecraftServer server, ShopManager mgr,
                                          ShopData shop, ShopSet set, String rest, String method) throws IOException {
        int slashIdx = rest.indexOf('/');
        String itemId = slashIdx > 0 ? rest.substring(0, slashIdx) : rest;
        String sub = slashIdx > 0 ? rest.substring(slashIdx) : "";

        if (!isValidId(itemId)) {
            WebDashboard.respond(ex, 400, "{\"error\":\"Invalid item ID\"}");
            return;
        }

        ShopItem item = set.findItem(itemId);
        if (item == null) {
            WebDashboard.respond(ex, 404, "{\"error\":\"Item not found\"}");
            return;
        }

        // PUT /shops/:shopId/sets/:setId/items/:itemId
        if (sub.isEmpty() && method.equals("PUT")) {
            String body = WebDashboard.readBody(ex);
            JsonObject data = parseJson(body);
            try {
                WebDashboard.executeOnServerThread(server, () -> {
                    applyItemEdits(item, data);
                    mgr.saveShop(shop);
                });
                WebDashboard.respondJson(ex, item.toJson());
            } catch (Exception e) {
                WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
            return;
        }

        // DELETE /shops/:shopId/sets/:setId/items/:itemId
        if (sub.isEmpty() && method.equals("DELETE")) {
            try {
                WebDashboard.executeOnServerThread(server, () -> {
                    set.removeItem(itemId);
                    mgr.saveShop(shop);
                });
                WebDashboard.respond(ex, 200, "{\"deleted\":true}");
            } catch (Exception e) {
                WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
            return;
        }

        // POST /shops/:shopId/sets/:setId/items/:itemId/toggle
        if (sub.equals("/toggle") && method.equals("POST")) {
            try {
                WebDashboard.executeOnServerThread(server, () -> {
                    item.setEnabled(!item.isEnabled());
                    mgr.saveShop(shop);
                });
                JsonObject r = new JsonObject();
                r.addProperty("enabled", item.isEnabled());
                WebDashboard.respondJson(ex, r);
            } catch (Exception e) {
                WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
            return;
        }

        // POST /shops/:shopId/sets/:setId/items/:itemId/reset-stock
        if (sub.equals("/reset-stock") && method.equals("POST")) {
            WebDashboard.respond(ex, 200, "{\"reset\":true}");
            return;
        }

        WebDashboard.respond(ex, 404, "{\"error\":\"Unknown item route\"}");
    }

    // ── Transactions ─────────────────────────────────────────────────────────

    private static void handleTransactions(HttpExchange ex, ShopManager mgr, String query) throws IOException {
        String shopFilter = null;
        int n = 50;
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("shopId="))
                    shopFilter = URLDecoder.decode(param.substring(7), StandardCharsets.UTF_8);
                if (param.startsWith("n=")) {
                    try { n = Math.min(500, Math.max(1, Integer.parseInt(param.substring(2)))); } catch (Exception ignored) {}
                }
            }
        }

        List<JsonObject> txs = mgr.getTransactions();
        JsonArray arr = new JsonArray();
        int count = 0;
        for (JsonObject tx : txs) {
            if (count >= n) break;
            if (shopFilter != null && !shopFilter.equals(tx.has("shopId") ? tx.get("shopId").getAsString() : "")) continue;
            arr.add(tx);
            count++;
        }

        JsonObject result = new JsonObject();
        result.add("transactions", arr);
        result.addProperty("total", txs.size());
        result.addProperty("returned", count);
        WebDashboard.respondJson(ex, result);
    }

    // ── Stats ────────────────────────────────────────────────────────────────

    private static void handleStats(HttpExchange ex, ShopManager mgr) throws IOException {
        int shopCount = 0, totalSets = 0, totalItems = 0, enabledItems = 0;
        for (ShopData shop : mgr.getAllShops()) {
            shopCount++;
            for (ShopSet set : shop.getSets()) {
                totalSets++;
                for (ShopItem item : set.getItems()) {
                    totalItems++;
                    if (item.isEnabled()) enabledItems++;
                }
            }
        }
        JsonObject stats = new JsonObject();
        stats.addProperty("shopCount", shopCount);
        stats.addProperty("totalSets", totalSets);
        stats.addProperty("totalItems", totalItems);
        stats.addProperty("enabledItems", enabledItems);
        stats.addProperty("transactionCount", mgr.getTransactions().size());
        WebDashboard.respondJson(ex, stats);
    }

    // ── JSON builders ────────────────────────────────────────────────────────

    private static JsonObject buildShopSummary(ShopData shop) {
        JsonObject o = new JsonObject();
        o.addProperty("id", shop.getId());
        o.addProperty("name", shop.getName());
        o.addProperty("description", shop.getDescription());
        o.addProperty("displayMode", shop.getDisplayMode().name());
        int setCount = shop.getSets().size();
        int itemCount = 0;
        for (ShopSet set : shop.getSets()) itemCount += set.getItems().size();
        o.addProperty("setCount", setCount);
        o.addProperty("itemCount", itemCount);
        return o;
    }

    private static JsonObject buildShopDetail(ShopData shop, ShopManager mgr) {
        JsonObject o = shop.toJson();
        // Enrich sets with runtime info
        JsonArray enrichedSets = new JsonArray();
        for (ShopSet set : shop.getSets()) {
            JsonObject so = set.toJson();
            so.addProperty("active", set.isCurrentlyActive());
            so.addProperty("rotationLabel", set.getRotation().modeLabel());
            // Enrich items (sorted by sortOrder for dashboard display)
            JsonArray enrichedItems = new JsonArray();
            List<ShopItem> sortedItems = new ArrayList<>(set.getItems());
            sortedItems.sort((a, b) -> Integer.compare(a.getSortOrder(), b.getSortOrder()));
            for (ShopItem item : sortedItems) {
                JsonObject io = item.toJson();
                io.addProperty("outputsSummary", item.outputsSummary());
                io.addProperty("costsSummary", item.costsSummary());
                io.addProperty("commandsSummary", item.commandsSummary());
                io.addProperty("effectiveName", item.getEffectiveName());
                enrichedItems.add(io);
            }
            so.add("items", enrichedItems);
            enrichedSets.add(so);
        }
        o.add("sets", enrichedSets);
        return o;
    }

    // ── Edit appliers ────────────────────────────────────────────────────────

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
        if (j.has("bgColor"))            shop.setBgColor(j.get("bgColor").getAsString());
        if (j.has("borderColor"))        shop.setBorderColor(j.get("borderColor").getAsString());
        if (j.has("accentColor"))        shop.setAccentColor(j.get("accentColor").getAsString());
        if (j.has("btnBuyColor"))        shop.setBtnBuyColor(j.get("btnBuyColor").getAsString());
        if (j.has("btnBuyTextColor"))    shop.setBtnBuyTextColor(j.get("btnBuyTextColor").getAsString());
        if (j.has("btnCloseColor"))      shop.setBtnCloseColor(j.get("btnCloseColor").getAsString());
        if (j.has("btnCloseTextColor"))  shop.setBtnCloseTextColor(j.get("btnCloseTextColor").getAsString());
        if (j.has("morphoCatalog"))      shop.setMorphoCatalog(j.get("morphoCatalog").getAsBoolean());
        if (j.has("layout")) {
            if (j.get("layout").isJsonNull()) {
                shop.setLayout(null);
            } else if (j.get("layout").isJsonObject()) {
                shop.setLayout(ShopLayout.fromJson(j.getAsJsonObject("layout")));
            }
        }
    }

    private static void applySetEdits(ShopSet set, JsonObject j) {
        if (j == null) return;
        if (j.has("name")) set.setName(j.get("name").getAsString());
        if (j.has("sortOrder")) set.setSortOrder(j.get("sortOrder").getAsInt());
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
        if (j.has("outputs")) {
            item.getOutputs().clear();
            for (JsonElement el : j.getAsJsonArray("outputs"))
                item.getOutputs().add(ItemEntry.fromJson(el.getAsJsonObject()));
        }
        if (j.has("costs")) {
            item.getCosts().clear();
            for (JsonElement el : j.getAsJsonArray("costs"))
                item.getCosts().add(ItemEntry.fromJson(el.getAsJsonObject()));
        }
        if (j.has("commands")) {
            item.getCommands().clear();
            for (JsonElement el : j.getAsJsonArray("commands"))
                item.getCommands().add(CommandEntry.fromJson(el.getAsJsonObject()));
        }
        if (j.has("stock")) {
            JsonObject s = j.getAsJsonObject("stock");
            StockConfig stock = item.getStock();
            if (s.has("mode")) {
                try { stock.setMode(StockConfig.Mode.valueOf(s.get("mode").getAsString())); }
                catch (Exception ignored) {}
            }
            if (s.has("globalStock"))          stock.setGlobalStock(s.get("globalStock").getAsInt());
            if (s.has("perPlayerLimit"))        stock.setPerPlayerLimit(s.get("perPlayerLimit").getAsInt());
            if (s.has("resetType")) {
                try { stock.setResetType(StockConfig.ResetType.valueOf(s.get("resetType").getAsString())); }
                catch (Exception ignored) {}
            }
            if (s.has("resetIntervalSeconds")) stock.setResetIntervalSeconds(s.get("resetIntervalSeconds").getAsLong());
        }
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    private static boolean isValidId(String id) {
        return id != null && !id.isEmpty() && !id.contains("..") && !id.contains("/")
                && !id.contains("\\") && !id.contains("%") && id.length() < 128;
    }

    private static String escapeJson(String s) {
        if (s == null) return "Unknown error";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static JsonObject parseJson(String s) {
        try { return JsonParser.parseString(s).getAsJsonObject(); }
        catch (Exception e) { return new JsonObject(); }
    }
}
