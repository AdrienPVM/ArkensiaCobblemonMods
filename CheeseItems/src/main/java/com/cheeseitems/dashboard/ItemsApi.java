package com.cheeseitems.dashboard;

import com.cheesedash.DashboardRegistry;
import com.cheesedash.WebDashboard;
import com.cheeseitems.CheeseItemsMod;
import com.cheeseitems.CustomItemDefinition;
import com.cheeseitems.ItemManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * REST API for CheeseItems.
 *
 * GET    /items                → list all items
 * GET    /items/:id            → detail
 * POST   /items                → create
 * PUT    /items/:id            → update (merge)
 * DELETE /items/:id            → delete
 * POST   /items/:id/give       → give to player
 * POST   /items/reload         → reload config from disk
 * GET    /items/enums           → categories, rarities, potionEffects
 */
public class ItemsApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseDash");

    public static void handle(HttpExchange exchange, MinecraftServer server, String subRoute) throws IOException {
        String method = exchange.getRequestMethod();

        // Anything other than a GET mutates state, so it needs OPERATOR or above.
        // requireWrite sends the 403 itself and reports the verdict as a boolean, so the
        // result has to be acted on: ignoring it would answer 403 and then do the work
        // anyway. Public, unauthenticated routes live in handlePublic* and are unaffected.
        if (!method.equals("GET") && !WebDashboard.requireWrite(exchange)) return;

        try {
            // GET /items/enums
            if (subRoute.equals("/enums") && method.equals("GET")) {
                handleEnums(exchange);
                return;
            }

            // POST /items/reload
            if (subRoute.equals("/reload") && method.equals("POST")) {
                handleReload(exchange, server);
                return;
            }

            // GET /items
            if (subRoute.equals("") || subRoute.equals("/")) {
                if (method.equals("GET")) {
                    handleList(exchange);
                    return;
                }
                if (method.equals("POST")) {
                    handleCreate(exchange, server);
                    return;
                }
            }

            // Routes with item ID: /items/:id or /items/:id/give
            if (subRoute.startsWith("/")) {
                String rest = subRoute.substring(1);

                // POST /items/:id/image
                if (rest.endsWith("/image") && method.equals("POST")) {
                    String id = rest.substring(0, rest.length() - "/image".length());
                    handleImageUpload(exchange, id);
                    return;
                }

                // GET /items/:id/image
                if (rest.endsWith("/image") && method.equals("GET")) {
                    String id = rest.substring(0, rest.length() - "/image".length());
                    handleImageServe(exchange, id);
                    return;
                }

                // DELETE /items/:id/image
                if (rest.endsWith("/image") && method.equals("DELETE")) {
                    String id = rest.substring(0, rest.length() - "/image".length());
                    handleImageDelete(exchange, id);
                    return;
                }

                // POST /items/:id/give
                if (rest.contains("/give")) {
                    String id = rest.replace("/give", "");
                    if (method.equals("POST")) {
                        handleGive(exchange, server, id);
                        return;
                    }
                }

                // GET/PUT/DELETE /items/:id
                String id = rest;
                if (!isValidId(id)) {
                    WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid item ID\"}");
                    return;
                }

                if (method.equals("GET")) {
                    handleGet(exchange, id);
                    return;
                }
                if (method.equals("PUT")) {
                    handleUpdate(exchange, server, id);
                    return;
                }
                if (method.equals("DELETE")) {
                    handleDelete(exchange, server, id);
                    return;
                }
            }

            WebDashboard.respond(exchange, 404, "{\"error\":\"Not found\"}");
        } catch (Exception e) {
            LOGGER.error("[CheeseItems API] Error handling {}: {}", subRoute, e.getMessage(), e);
            WebDashboard.respond(exchange, 500, "{\"error\":\"Internal server error\"}");
        }
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    private static void handleList(HttpExchange exchange) throws IOException {
        ItemManager mgr = CheeseItemsMod.INSTANCE.getItemManager();
        JsonArray arr = new JsonArray();
        for (CustomItemDefinition def : mgr.getAllItems()) {
            arr.add(buildSummary(def));
        }
        WebDashboard.respond(exchange, 200, arr.toString());
    }

    private static void handleGet(HttpExchange exchange, String id) throws IOException {
        ItemManager mgr = CheeseItemsMod.INSTANCE.getItemManager();
        CustomItemDefinition def = mgr.getItem(id);
        if (def == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Item not found: " + escapeJson(id) + "\"}");
            return;
        }
        JsonObject json = def.toJson();
        json.addProperty("hasCustomIcon", CheeseItemsMod.INSTANCE.getConfig().hasItemImage(id));
        WebDashboard.respond(exchange, 200, json.toString());
    }

    private static void handleCreate(HttpExchange exchange, MinecraftServer server) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject obj = JsonParser.parseString(body).getAsJsonObject();

        // Generate ID if not provided
        if (!obj.has("id") || obj.get("id").getAsString().isBlank()) {
            obj.addProperty("id", "item_" + UUID.randomUUID().toString().substring(0, 5));
        }

        String id = obj.get("id").getAsString();
        ItemManager mgr = CheeseItemsMod.INSTANCE.getItemManager();

        // Check duplicate
        if (mgr.getItem(id) != null) {
            WebDashboard.respond(exchange, 409, "{\"error\":\"Item already exists: " + escapeJson(id) + "\"}");
            return;
        }

        CustomItemDefinition def = CustomItemDefinition.fromJson(obj);
        mgr.createItem(def);

        // WS broadcast
        broadcastUpdate(def, "created");

        LOGGER.info("[CheeseItems API] Created item: {}", id);
        WebDashboard.respond(exchange, 201, def.toJson().toString());
    }

    private static void handleUpdate(HttpExchange exchange, MinecraftServer server, String id) throws IOException {
        ItemManager mgr = CheeseItemsMod.INSTANCE.getItemManager();
        CustomItemDefinition def = mgr.getItem(id);
        if (def == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Item not found: " + escapeJson(id) + "\"}");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject obj = JsonParser.parseString(body).getAsJsonObject();

        // Merge update on server thread (hot-reload accesses inventories)
        try {
            WebDashboard.executeOnServerThread(server, () -> {
                def.mergeFrom(obj);
                mgr.updateItem(def);
            });
        } catch (Exception e) {
            LOGGER.error("[CheeseItems API] Failed to update item {}: {}", id, e.getMessage());
            WebDashboard.respond(exchange, 500, "{\"error\":\"Update failed\"}");
            return;
        }

        // WS broadcast
        broadcastUpdate(def, "updated");

        LOGGER.info("[CheeseItems API] Updated item: {}", id);
        WebDashboard.respond(exchange, 200, def.toJson().toString());
    }

    private static void handleDelete(HttpExchange exchange, MinecraftServer server, String id) throws IOException {
        ItemManager mgr = CheeseItemsMod.INSTANCE.getItemManager();
        if (mgr.getItem(id) == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Item not found: " + escapeJson(id) + "\"}");
            return;
        }

        boolean removed = mgr.deleteItem(id);
        if (!removed) {
            WebDashboard.respond(exchange, 500, "{\"error\":\"Failed to delete\"}");
            return;
        }

        // Delete associated image + CustomModelData
        CheeseItemsMod.INSTANCE.getConfig().deleteItemImage(id);
        CheeseItemsMod.INSTANCE.getConfig().removeCustomModelData(id);
        CheeseItemsMod.INSTANCE.getConfig().save();
        CheeseItemsMod.INSTANCE.regenerateAndPushPack();

        // WS broadcast
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "item_update");
        msg.addProperty("itemId", id);
        msg.addProperty("action", "deleted");
        DashboardRegistry.broadcast("items", msg.toString());

        LOGGER.info("[CheeseItems API] Deleted item: {}", id);
        WebDashboard.respond(exchange, 200, "{\"deleted\":true}");
    }

    private static void handleGive(HttpExchange exchange, MinecraftServer server, String id) throws IOException {
        ItemManager mgr = CheeseItemsMod.INSTANCE.getItemManager();
        CustomItemDefinition def = mgr.getItem(id);
        if (def == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Item not found: " + escapeJson(id) + "\"}");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
        String playerName = obj.has("playerName") ? obj.get("playerName").getAsString() : "";
        int quantity = obj.has("quantity") ? obj.get("quantity").getAsInt() : 1;

        if (playerName.isEmpty()) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"playerName required\"}");
            return;
        }

        try {
            WebDashboard.executeOnServerThread(server, () -> {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
                if (player == null) throw new RuntimeException("Player not found: " + playerName);
                ItemStack stack = mgr.buildItemStack(def, quantity);
                if (!player.getInventory().insertStack(stack)) {
                    player.dropItem(stack, false);
                }
            });
        } catch (Exception e) {
            String errMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            WebDashboard.respond(exchange, 400, "{\"error\":\"" + escapeJson(errMsg) + "\"}");
            return;
        }

        LOGGER.info("[CheeseItems API] Gave {}x {} to {}", quantity, id, playerName);
        WebDashboard.respond(exchange, 200, "{\"given\":true,\"quantity\":" + quantity + "}");
    }

    private static void handleReload(HttpExchange exchange, MinecraftServer server) throws IOException {
        try {
            WebDashboard.executeOnServerThread(server, () ->
                    CheeseItemsMod.INSTANCE.getItemManager().reload()
            );
        } catch (Exception e) {
            WebDashboard.respond(exchange, 500, "{\"error\":\"Reload failed\"}");
            return;
        }

        LOGGER.info("[CheeseItems API] Config reloaded");
        WebDashboard.respond(exchange, 200, "{\"reloaded\":true}");
    }

    private static void handleEnums(HttpExchange exchange) throws IOException {
        JsonObject result = new JsonObject();

        // Common potion effects
        JsonArray potions = new JsonArray();
        String[] effects = {
                "minecraft:speed", "minecraft:slowness", "minecraft:haste",
                "minecraft:mining_fatigue", "minecraft:strength", "minecraft:instant_health",
                "minecraft:instant_damage", "minecraft:jump_boost", "minecraft:nausea",
                "minecraft:regeneration", "minecraft:resistance", "minecraft:fire_resistance",
                "minecraft:water_breathing", "minecraft:invisibility", "minecraft:blindness",
                "minecraft:night_vision", "minecraft:hunger", "minecraft:weakness",
                "minecraft:poison", "minecraft:wither", "minecraft:health_boost",
                "minecraft:absorption", "minecraft:saturation", "minecraft:glowing",
                "minecraft:levitation", "minecraft:luck", "minecraft:unluck",
                "minecraft:slow_falling", "minecraft:conduit_power", "minecraft:dolphins_grace",
                "minecraft:bad_omen", "minecraft:hero_of_the_village", "minecraft:darkness",
                "minecraft:wind_charged", "minecraft:weaving", "minecraft:oozing",
                "minecraft:infested"
        };
        for (String e : effects) potions.add(e);
        result.add("potionEffects", potions);

        WebDashboard.respond(exchange, 200, result.toString());
    }

    // ── Image handlers ─────────────────────────────────────────────────────

    private static void handleImageUpload(HttpExchange exchange, String id) throws IOException {
        if (!isValidId(id)) { WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid ID\"}"); return; }
        ItemManager mgr = CheeseItemsMod.INSTANCE.getItemManager();
        if (mgr.getItem(id) == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Item not found\"}");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
        if (!obj.has("imageBase64")) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"imageBase64 required\"}");
            return;
        }
        String raw = obj.get("imageBase64").getAsString();
        if (raw.contains(",")) raw = raw.substring(raw.indexOf(",") + 1);
        byte[] data = java.util.Base64.getDecoder().decode(raw);
        CheeseItemsMod.INSTANCE.getConfig().saveItemImage(id, data);
        // Assign CustomModelData + regenerate resource pack + hot-reload visuals
        CheeseItemsMod.INSTANCE.getConfig().getOrAssignCustomModelData(id);
        CheeseItemsMod.INSTANCE.getConfig().save();
        CheeseItemsMod.INSTANCE.regenerateAndPushPack();
        try {
            if (CheeseItemsMod.INSTANCE.getServer() != null) {
                WebDashboard.executeOnServerThread(CheeseItemsMod.INSTANCE.getServer(),
                        () -> CheeseItemsMod.INSTANCE.getItemManager().refreshAllPlayers());
            }
        } catch (Exception ignored) {}
        LOGGER.info("[CheeseItems API] Image uploaded for item: {}", id);
        WebDashboard.respond(exchange, 200, "{\"uploaded\":true}");
    }

    private static void handleImageServe(HttpExchange exchange, String id) throws IOException {
        if (!isValidId(id)) { WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid ID\"}"); return; }
        byte[] data = CheeseItemsMod.INSTANCE.getConfig().getItemImage(id);
        if (data == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Image not found\"}");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=60");
        exchange.sendResponseHeaders(200, data.length);
        try (java.io.OutputStream os = exchange.getResponseBody()) { os.write(data); }
    }

    private static void handleImageDelete(HttpExchange exchange, String id) throws IOException {
        if (!isValidId(id)) { WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid ID\"}"); return; }
        CheeseItemsMod.INSTANCE.getConfig().deleteItemImage(id);
        CheeseItemsMod.INSTANCE.getConfig().removeCustomModelData(id);
        CheeseItemsMod.INSTANCE.getConfig().save();
        CheeseItemsMod.INSTANCE.regenerateAndPushPack();
        try {
            if (CheeseItemsMod.INSTANCE.getServer() != null) {
                WebDashboard.executeOnServerThread(CheeseItemsMod.INSTANCE.getServer(),
                        () -> CheeseItemsMod.INSTANCE.getItemManager().refreshAllPlayers());
            }
        } catch (Exception ignored) {}
        LOGGER.info("[CheeseItems API] Image deleted for item: {}", id);
        WebDashboard.respond(exchange, 200, "{\"deleted\":true}");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static JsonObject buildSummary(CustomItemDefinition def) {
        JsonObject o = new JsonObject();
        o.addProperty("id", def.getId());
        o.addProperty("displayName", def.getDisplayName());
        o.addProperty("nameColor", def.getNameColor());
        o.addProperty("baseItem", def.getBaseItem());
        o.addProperty("enabled", def.isEnabled());
        o.addProperty("glowing", def.isGlowing());
        o.addProperty("soulbound", def.isSoulbound());
        o.addProperty("consumable", def.isConsumable());
        o.addProperty("droppable", def.isDroppable());
        o.addProperty("unbreakable", def.isUnbreakable());
        o.addProperty("disappearOnDeath", def.isDisappearOnDeath());
        o.addProperty("maxUses", def.getMaxUses());
        o.addProperty("cooldownTicks", def.getCooldownTicks());
        o.addProperty("actionCount", def.getUseActions().size());
        o.addProperty("effectCount",
                def.getOnUseEffects().size() + def.getPassiveEffects().size() + def.getOnPickupEffects().size());
        o.addProperty("hasCustomIcon", CheeseItemsMod.INSTANCE.getConfig().hasItemImage(def.getId()));
        return o;
    }

    private static void broadcastUpdate(CustomItemDefinition def, String action) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "item_update");
        msg.addProperty("itemId", def.getId());
        msg.addProperty("itemName", def.getDisplayName());
        msg.addProperty("action", action);
        DashboardRegistry.broadcast("items", msg.toString());
    }

    private static boolean isValidId(String id) {
        return id != null && !id.isEmpty() && !id.contains("..") && !id.contains("/")
                && !id.contains("\\") && !id.contains("%") && id.length() < 128;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
