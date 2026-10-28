package com.cheesestore.dashboard;

import com.cheesedash.DashboardRegistry;
import com.cheesedash.WebDashboard;
import com.cheesestore.WalletManager;
import com.cheesestore.*;
import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API for CheeseStore.
 *
 * Admin routes (authenticated via CheeseDash /api/v1/store/*):
 *   GET    /products           — list all products
 *   POST   /products           — create product
 *   PUT    /products/:id       — update product
 *   DELETE /products/:id       — delete product
 *   POST   /products/:id/image — upload product image (base64 in JSON body)
 *   GET    /orders             — list all orders
 *   POST   /orders/:id/deliver — manual delivery
 *   GET    /stats              — store statistics
 *   GET    /wallets            — list all wallets
 *   GET    /wallets/:uuid      — single wallet
 *   PUT    /wallets/:uuid      — set balance
 *   GET    /config             — get config (masked keys)
 *   PUT    /config             — update config
 *
 * Public routes (no auth, served at /store/api/*):
 *   GET    /products           — list enabled products (public view)
 *   GET    /products/:id/image — get product image
 *   GET    /players            — list all known players
 *   GET    /config             — public store config (name, subtitle, stripe pubkey)
 *   POST   /checkout           — create Stripe Checkout Session
 *   POST   /verify             — verify Stripe session after redirect
 *   POST   /webhook            — Stripe webhook handler
 */
public class StoreApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseStore");

    // ══════════════════════════════════════════════════════════════════════════
    //  ADMIN API (authenticated)
    // ══════════════════════════════════════════════════════════════════════════

    public static void handle(HttpExchange exchange, MinecraftServer server, String subRoute) throws IOException {
        String method = exchange.getRequestMethod();
        CheeseStoreConfig config = CheeseStoreMod.INSTANCE.getConfig();
        DeliveryManager deliveryMgr = CheeseStoreMod.INSTANCE.getDeliveryManager();

        try {
            // GET /store/products
            if ((subRoute.equals("/products") || subRoute.equals("/products/")) && method.equals("GET")) {
                JsonArray arr = new JsonArray();
                for (StoreProduct p : config.getProducts()) arr.add(p.toJson());
                WebDashboard.respond(exchange, 200, arr.toString());
                return;
            }

            // POST /store/products
            if ((subRoute.equals("/products") || subRoute.equals("/products/")) && method.equals("POST")) {
                String body = WebDashboard.readBody(exchange);
                JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                StoreProduct product = StoreProduct.fromJson(obj);
                // Handle inline base64 image
                if (obj.has("imageBase64")) {
                    saveBase64Image(config, product.getId(), obj.get("imageBase64").getAsString());
                    product.setHasImage(true);
                }
                config.addProduct(product);
                broadcastUpdate("created", product);
                WebDashboard.respond(exchange, 201, product.toJson().toString());
                return;
            }

            // Routes with product ID: /products/:id...
            if (subRoute.startsWith("/products/")) {
                String rest = subRoute.substring("/products/".length());

                // POST /products/:id/image
                if (rest.endsWith("/image") && method.equals("POST")) {
                    String id = rest.replace("/image", "");
                    if (!isValidId(id)) { WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid ID\"}"); return; }
                    StoreProduct p = config.getProduct(id);
                    if (p == null) { WebDashboard.respond(exchange, 404, "{\"error\":\"Product not found\"}"); return; }
                    String body = WebDashboard.readBody(exchange);
                    JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                    if (obj.has("imageBase64")) {
                        saveBase64Image(config, id, obj.get("imageBase64").getAsString());
                        p.setHasImage(true);
                        config.save();
                    }
                    WebDashboard.respond(exchange, 200, "{\"ok\":true}");
                    return;
                }

                // GET /products/:id/image (admin)
                if (rest.endsWith("/image") && method.equals("GET")) {
                    String id = rest.replace("/image", "");
                    serveProductImage(exchange, config, id);
                    return;
                }

                String id = rest;
                if (!isValidId(id)) { WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid ID\"}"); return; }

                // PUT /products/:id
                if (method.equals("PUT")) {
                    StoreProduct p = config.getProduct(id);
                    if (p == null) { WebDashboard.respond(exchange, 404, "{\"error\":\"Product not found\"}"); return; }
                    String body = WebDashboard.readBody(exchange);
                    JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                    p.mergeFrom(obj);
                    if (obj.has("imageBase64")) {
                        saveBase64Image(config, id, obj.get("imageBase64").getAsString());
                        p.setHasImage(true);
                    }
                    config.updateProduct(p);
                    broadcastUpdate("updated", p);
                    WebDashboard.respond(exchange, 200, p.toJson().toString());
                    return;
                }

                // DELETE /products/:id
                if (method.equals("DELETE")) {
                    boolean removed = config.removeProduct(id);
                    if (!removed) { WebDashboard.respond(exchange, 404, "{\"error\":\"Product not found\"}"); return; }
                    JsonObject msg = new JsonObject();
                    msg.addProperty("type", "store_update");
                    msg.addProperty("action", "deleted");
                    msg.addProperty("productId", id);
                    DashboardRegistry.broadcast("store", msg.toString());
                    WebDashboard.respond(exchange, 200, "{\"deleted\":true}");
                    return;
                }
            }

            // ─── Gallery (admin) ─────────────────────────────────────────────

            // GET /store/gallery
            if ((subRoute.equals("/gallery") || subRoute.equals("/gallery/")) && method.equals("GET")) {
                JsonArray arr = new JsonArray();
                for (GalleryItem g : config.getGallerySorted()) arr.add(g.toJson());
                WebDashboard.respond(exchange, 200, arr.toString());
                return;
            }

            // POST /store/gallery — create
            if ((subRoute.equals("/gallery") || subRoute.equals("/gallery/")) && method.equals("POST")) {
                String body = WebDashboard.readBody(exchange);
                JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                GalleryItem item = GalleryItem.fromJson(obj);
                if (obj.has("imageBase64")) {
                    saveGalleryBase64Image(config, item.getId(), obj.get("imageBase64").getAsString());
                    item.setHasImage(true);
                }
                // Newly-created items go to the END of the non-new list (highest sortOrder).
                int maxOrder = 0;
                for (GalleryItem g : config.getGalleryItems()) maxOrder = Math.max(maxOrder, g.getSortOrder());
                item.setSortOrder(maxOrder + 1);
                config.addGalleryItem(item);
                broadcastGalleryUpdate("created", item);
                WebDashboard.respond(exchange, 201, item.toJson().toString());
                return;
            }

            // POST /store/gallery/reorder
            if (subRoute.equals("/gallery/reorder") && method.equals("POST")) {
                String body = WebDashboard.readBody(exchange);
                JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                if (!obj.has("ids")) { WebDashboard.respond(exchange, 400, "{\"error\":\"Missing ids\"}"); return; }
                List<String> ids = new java.util.ArrayList<>();
                for (JsonElement el : obj.getAsJsonArray("ids")) ids.add(el.getAsString());
                config.reorderGallery(ids);
                broadcastGalleryUpdate("reordered", null);
                WebDashboard.respond(exchange, 200, "{\"ok\":true}");
                return;
            }

            // /gallery/:id...
            if (subRoute.startsWith("/gallery/")) {
                String rest = subRoute.substring("/gallery/".length());

                if (rest.endsWith("/image") && method.equals("POST")) {
                    String id = rest.replace("/image", "");
                    if (!isValidId(id)) { WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid ID\"}"); return; }
                    GalleryItem g = config.getGalleryItem(id);
                    if (g == null) { WebDashboard.respond(exchange, 404, "{\"error\":\"Gallery item not found\"}"); return; }
                    String body = WebDashboard.readBody(exchange);
                    JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                    if (obj.has("imageBase64")) {
                        saveGalleryBase64Image(config, id, obj.get("imageBase64").getAsString());
                        g.setHasImage(true);
                        config.save();
                    }
                    WebDashboard.respond(exchange, 200, "{\"ok\":true}");
                    return;
                }

                if (rest.endsWith("/image") && method.equals("GET")) {
                    String id = rest.replace("/image", "");
                    serveGalleryImage(exchange, config, id);
                    return;
                }

                String id = rest;
                if (!isValidId(id)) { WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid ID\"}"); return; }

                if (method.equals("PUT")) {
                    GalleryItem g = config.getGalleryItem(id);
                    if (g == null) { WebDashboard.respond(exchange, 404, "{\"error\":\"Gallery item not found\"}"); return; }
                    String body = WebDashboard.readBody(exchange);
                    JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                    g.mergeFrom(obj);
                    if (obj.has("imageBase64")) {
                        saveGalleryBase64Image(config, id, obj.get("imageBase64").getAsString());
                        g.setHasImage(true);
                    }
                    config.updateGalleryItem(g);
                    broadcastGalleryUpdate("updated", g);
                    WebDashboard.respond(exchange, 200, g.toJson().toString());
                    return;
                }

                if (method.equals("DELETE")) {
                    boolean removed = config.removeGalleryItem(id);
                    if (!removed) { WebDashboard.respond(exchange, 404, "{\"error\":\"Gallery item not found\"}"); return; }
                    JsonObject msg = new JsonObject();
                    msg.addProperty("type", "store_gallery_update");
                    msg.addProperty("action", "deleted");
                    msg.addProperty("itemId", id);
                    DashboardRegistry.broadcast("store", msg.toString());
                    WebDashboard.respond(exchange, 200, "{\"deleted\":true}");
                    return;
                }
            }

            // GET /store/orders
            if (subRoute.equals("/orders") && method.equals("GET")) {
                JsonArray arr = new JsonArray();
                List<PendingDelivery> all = deliveryMgr.getAll();
                // Return newest first
                for (int i = all.size() - 1; i >= 0; i--) {
                    arr.add(all.get(i).toJson());
                }
                WebDashboard.respond(exchange, 200, arr.toString());
                return;
            }

            // POST /store/orders/:id/deliver
            if (subRoute.startsWith("/orders/") && subRoute.endsWith("/deliver") && method.equals("POST")) {
                String orderId = subRoute.substring("/orders/".length(), subRoute.length() - "/deliver".length());
                try {
                    boolean[] result = {false};
                    WebDashboard.executeOnServerThread(server, () -> {
                        result[0] = deliveryMgr.manualDeliver(server, orderId);
                    });
                    if (result[0]) {
                        WebDashboard.respond(exchange, 200, "{\"delivered\":true}");
                    } else {
                        WebDashboard.respond(exchange, 400, "{\"error\":\"Delivery failed or already delivered\"}");
                    }
                } catch (Exception e) {
                    WebDashboard.respond(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
                }
                return;
            }

            // GET /store/wallets
            if (subRoute.equals("/wallets") && method.equals("GET")) {
                WalletManager walletMgr = WalletManager.getInstance();
                JsonArray arr = new JsonArray();
                for (Map.Entry<UUID, WalletManager.WalletEntry> entry : walletMgr.getAll().entrySet()) {
                    WalletManager.WalletEntry w = entry.getValue();
                    JsonObject obj = new JsonObject();
                    obj.addProperty("uuid", entry.getKey().toString());
                    obj.addProperty("playerName", w.getPlayerName());
                    obj.addProperty("balance", w.getBalance());
                    obj.addProperty("lastUpdated", w.getLastUpdated());
                    arr.add(obj);
                }
                WebDashboard.respond(exchange, 200, arr.toString());
                return;
            }

            // Routes with wallet UUID: /wallets/:uuid
            if (subRoute.startsWith("/wallets/")) {
                String walletUuid = subRoute.substring("/wallets/".length());
                if (!isValidId(walletUuid)) { WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid UUID\"}"); return; }

                UUID uuid;
                try {
                    uuid = UUID.fromString(walletUuid);
                } catch (IllegalArgumentException ex) {
                    WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid UUID format\"}");
                    return;
                }

                WalletManager walletMgr = WalletManager.getInstance();

                // GET /store/wallets/:uuid
                if (method.equals("GET")) {
                    WalletManager.WalletEntry w = walletMgr.getAll().get(uuid);
                    if (w == null) { WebDashboard.respond(exchange, 404, "{\"error\":\"Wallet not found\"}"); return; }
                    JsonObject obj = new JsonObject();
                    obj.addProperty("uuid", uuid.toString());
                    obj.addProperty("playerName", w.getPlayerName());
                    obj.addProperty("balance", w.getBalance());
                    obj.addProperty("lastUpdated", w.getLastUpdated());
                    WebDashboard.respond(exchange, 200, obj.toString());
                    return;
                }

                // PUT /store/wallets/:uuid
                if (method.equals("PUT")) {
                    String body = WebDashboard.readBody(exchange);
                    JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                    long balance = obj.has("balance") ? obj.get("balance").getAsLong() : 0;
                    String playerName = obj.has("playerName") ? obj.get("playerName").getAsString() : "";
                    if (playerName.isEmpty()) {
                        WalletManager.WalletEntry existing = walletMgr.getAll().get(uuid);
                        playerName = existing != null ? existing.getPlayerName() : uuid.toString().substring(0, 8);
                    }
                    long oldBalance = walletMgr.getBalance(uuid);
                    walletMgr.setBalance(uuid, playerName, balance);
                    long newBalance = Math.max(0, balance);
                    // Notify player if online
                    long diff = newBalance - oldBalance;
                    if (diff != 0) {
                        final UUID fUuid = uuid;
                        final long fDiff = diff;
                        final long fNewBal = newBalance;
                        try {
                            WebDashboard.executeOnServerThread(server, () -> {
                                ServerPlayerEntity target = server.getPlayerManager().getPlayer(fUuid);
                                if (target != null) {
                                    if (fDiff > 0) {
                                        target.sendMessage(net.minecraft.text.Text.translatable("cheesestore.notify.received", fDiff, fNewBal)
                                                .formatted(net.minecraft.util.Formatting.GREEN), false);
                                    } else {
                                        target.sendMessage(net.minecraft.text.Text.translatable("cheesestore.notify.deducted", Math.abs(fDiff), fNewBal)
                                                .formatted(net.minecraft.util.Formatting.RED), false);
                                    }
                                }
                            });
                        } catch (Exception ignored) {}
                    }
                    JsonObject resp = new JsonObject();
                    resp.addProperty("uuid", uuid.toString());
                    resp.addProperty("playerName", playerName);
                    resp.addProperty("balance", newBalance);
                    resp.addProperty("lastUpdated", System.currentTimeMillis());
                    WebDashboard.respond(exchange, 200, resp.toString());
                    return;
                }
            }

            // GET /store/stats
            if (subRoute.equals("/stats") && method.equals("GET")) {
                WalletManager walletMgr = WalletManager.getInstance();
                Map<UUID, WalletManager.WalletEntry> allWallets = walletMgr.getAll();
                long totalFromage = 0;
                for (WalletManager.WalletEntry w : allWallets.values()) {
                    totalFromage += w.getBalance();
                }

                JsonObject stats = new JsonObject();
                stats.addProperty("totalProducts", config.getProducts().size());
                stats.addProperty("enabledProducts", config.getEnabledProducts().size());
                stats.addProperty("totalOrders", deliveryMgr.getAll().size());
                stats.addProperty("pendingOrders", deliveryMgr.countByStatus(PendingDelivery.Status.PENDING));
                stats.addProperty("deliveredOrders", deliveryMgr.countByStatus(PendingDelivery.Status.DELIVERED));
                stats.addProperty("failedOrders", deliveryMgr.countByStatus(PendingDelivery.Status.FAILED));
                stats.addProperty("totalRevenueCents", deliveryMgr.totalRevenueCents());
                stats.addProperty("totalFromage", totalFromage);
                stats.addProperty("walletCount", allWallets.size());
                WebDashboard.respond(exchange, 200, stats.toString());
                return;
            }

            // GET /store/config
            if (subRoute.equals("/config") && method.equals("GET")) {
                JsonObject cfg = new JsonObject();
                cfg.addProperty("stripePublishableKey", config.getStripePublishableKey());
                cfg.addProperty("stripeSecretKey", maskKey(config.getStripeSecretKey()));
                cfg.addProperty("stripeWebhookSecret", maskKey(config.getStripeWebhookSecret()));
                cfg.addProperty("storeName", config.getStoreName());
                cfg.addProperty("storeSubtitle", config.getStoreSubtitle());
                cfg.addProperty("storeBaseUrl", config.getStoreBaseUrl());
                cfg.addProperty("storeEnabled", config.isStoreEnabled());
                cfg.addProperty("currencyName", config.getCurrencyName());
                WebDashboard.respond(exchange, 200, cfg.toString());
                return;
            }

            // PUT /store/config
            if (subRoute.equals("/config") && method.equals("PUT")) {
                String body = WebDashboard.readBody(exchange);
                JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                if (obj.has("stripePublishableKey")) config.setStripePublishableKey(obj.get("stripePublishableKey").getAsString());
                if (obj.has("stripeSecretKey")) {
                    String sk = obj.get("stripeSecretKey").getAsString();
                    if (!sk.contains("***")) config.setStripeSecretKey(sk); // Don't save masked value
                }
                if (obj.has("stripeWebhookSecret")) {
                    String ws = obj.get("stripeWebhookSecret").getAsString();
                    if (!ws.contains("***")) config.setStripeWebhookSecret(ws);
                }
                if (obj.has("storeName")) config.setStoreName(obj.get("storeName").getAsString());
                if (obj.has("storeSubtitle")) config.setStoreSubtitle(obj.get("storeSubtitle").getAsString());
                if (obj.has("storeBaseUrl")) config.setStoreBaseUrl(obj.get("storeBaseUrl").getAsString());
                if (obj.has("storeEnabled")) config.setStoreEnabled(obj.get("storeEnabled").getAsBoolean());
                if (obj.has("currencyName")) config.setCurrencyName(obj.get("currencyName").getAsString());
                config.save();
                CheeseStoreMod.INSTANCE.refreshStripeClient();
                WebDashboard.respond(exchange, 200, "{\"ok\":true}");
                return;
            }

            // ─── Cosmetics (admin CRUD) ───────────────────────────────────────

            CosmeticManager cosmeticMgr = CosmeticManager.getInstance();

            // GET /store/cosmetics — list all cosmetics
            if ((subRoute.equals("/cosmetics") || subRoute.equals("/cosmetics/")) && method.equals("GET")) {
                JsonArray arr = new JsonArray();
                for (CosmeticItem c : cosmeticMgr.getAll()) arr.add(c.toJson());
                WebDashboard.respond(exchange, 200, arr.toString());
                return;
            }

            // POST /store/cosmetics — upsert (create or update by id)
            if ((subRoute.equals("/cosmetics") || subRoute.equals("/cosmetics/")) && method.equals("POST")) {
                String body = WebDashboard.readBody(exchange);
                JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                CosmeticItem incoming = CosmeticItem.fromJson(obj);
                CosmeticItem saved = cosmeticMgr.upsert(incoming);
                // Handle inline base64 image (same pipeline as products)
                if (obj.has("imageBase64")) {
                    saveCosmeticBase64Image(cosmeticMgr, saved.getId(), obj.get("imageBase64").getAsString());
                    saved.setHasImage(true);
                    cosmeticMgr.save();
                }
                broadcastCosmeticUpdate("upserted", saved);
                WebDashboard.respond(exchange, 200, saved.toJson().toString());
                return;
            }

            // Routes with cosmetic ID: /cosmetics/:id...
            if (subRoute.startsWith("/cosmetics/")) {
                String rest = subRoute.substring("/cosmetics/".length());

                // POST /cosmetics/:id/image
                if (rest.endsWith("/image") && method.equals("POST")) {
                    String id = rest.substring(0, rest.length() - "/image".length());
                    if (!isValidId(id)) { WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid ID\"}"); return; }
                    CosmeticItem c = cosmeticMgr.get(id);
                    if (c == null) { WebDashboard.respond(exchange, 404, "{\"error\":\"Cosmetic not found\"}"); return; }
                    String body = WebDashboard.readBody(exchange);
                    JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                    if (obj.has("imageBase64")) {
                        saveCosmeticBase64Image(cosmeticMgr, id, obj.get("imageBase64").getAsString());
                        c.setHasImage(true);
                        cosmeticMgr.save();
                    }
                    WebDashboard.respond(exchange, 200, "{\"ok\":true}");
                    return;
                }

                // GET /cosmetics/:id/image (admin)
                if (rest.endsWith("/image") && method.equals("GET")) {
                    String id = rest.substring(0, rest.length() - "/image".length());
                    serveCosmeticImage(exchange, id);
                    return;
                }

                String id = rest;
                if (!isValidId(id)) { WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid ID\"}"); return; }

                // PUT /cosmetics/:id — partial update
                if (method.equals("PUT")) {
                    CosmeticItem c = cosmeticMgr.get(id);
                    if (c == null) { WebDashboard.respond(exchange, 404, "{\"error\":\"Cosmetic not found\"}"); return; }
                    String body = WebDashboard.readBody(exchange);
                    JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                    c.mergeFrom(obj);
                    if (obj.has("imageBase64")) {
                        saveCosmeticBase64Image(cosmeticMgr, id, obj.get("imageBase64").getAsString());
                        c.setHasImage(true);
                    }
                    cosmeticMgr.update(c);
                    broadcastCosmeticUpdate("updated", c);
                    WebDashboard.respond(exchange, 200, c.toJson().toString());
                    return;
                }

                // DELETE /cosmetics/:id
                if (method.equals("DELETE")) {
                    boolean removed = cosmeticMgr.remove(id);
                    if (!removed) { WebDashboard.respond(exchange, 404, "{\"error\":\"Cosmetic not found\"}"); return; }
                    JsonObject msg = new JsonObject();
                    msg.addProperty("type", "store_cosmetic_update");
                    msg.addProperty("action", "deleted");
                    msg.addProperty("cosmeticId", id);
                    DashboardRegistry.broadcast("store", msg.toString());
                    WebDashboard.respond(exchange, 200, "{\"deleted\":true}");
                    return;
                }
            }

            // ─── Playable cosmetics (the com.cheesestore.cosmetic system) ─────
            //
            //  Served under /playcosmetics to avoid colliding with the legacy
            //  CosmeticItem CRUD above (which already owns /cosmetics on both the
            //  admin and public surfaces, and is consumed by the live CheeseDash
            //  admin UI + storefront JS). Same auth model (admin-gated via
            //  CheeseDash), same style as the routes above.
            if (subRoute.equals("/playcosmetics") || subRoute.equals("/playcosmetics/")
                    || subRoute.startsWith("/playcosmetics/")) {
                if (handleAdminPlayCosmetics(exchange, server, method, subRoute)) return;
            }

            WebDashboard.respond(exchange, 404, "{\"error\":\"Not found\"}");
        } catch (Exception e) {
            LOGGER.error("[CheeseStore API] Error handling {}: {}", subRoute, e.getMessage(), e);
            WebDashboard.respond(exchange, 500, "{\"error\":\"Internal server error\"}");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PLAYABLE COSMETICS — admin CRUD (the com.cheesestore.cosmetic system)
    //
    //  Routes (admin, authenticated by CheeseDash before handle() is reached):
    //    GET    /api/v1/store/playcosmetics            — list all (cosmetics:[...])
    //    POST   /api/v1/store/playcosmetics            — create
    //    PUT    /api/v1/store/playcosmetics/{id}       — update (partial merge)
    //    DELETE /api/v1/store/playcosmetics/{id}       — delete
    //    POST   /api/v1/store/playcosmetics/reorder    — {ids:[...]}
    //    POST   /api/v1/store/playcosmetics/grant      — {player|uuid, id}
    //    GET    /api/v1/store/playcosmetics/enums      — dropdown source lists
    // ══════════════════════════════════════════════════════════════════════════

    /** Returns true if the request was handled, false to fall through to the 404. */
    private static boolean handleAdminPlayCosmetics(HttpExchange exchange, MinecraftServer server,
                                                    String method, String subRoute) throws IOException {
        com.cheesestore.cosmetic.CosmeticManager mgr = com.cheesestore.cosmetic.CosmeticManager.getInstance();
        if (mgr == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"Cosmetic system not ready\"}");
            return true;
        }

        // GET /playcosmetics — list all
        if ((subRoute.equals("/playcosmetics") || subRoute.equals("/playcosmetics/")) && method.equals("GET")) {
            JsonArray arr = new JsonArray();
            for (com.cheesestore.cosmetic.CosmeticEntry c : mgr.getAll()) arr.add(c.toJson());
            JsonObject out = new JsonObject();
            out.add("cosmetics", arr);
            WebDashboard.respond(exchange, 200, out.toString());
            return true;
        }

        // POST /playcosmetics — create
        if ((subRoute.equals("/playcosmetics") || subRoute.equals("/playcosmetics/")) && method.equals("POST")) {
            String body = WebDashboard.readBody(exchange);
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            com.cheesestore.cosmetic.CosmeticEntry incoming = com.cheesestore.cosmetic.CosmeticEntry.fromJson(obj);
            if (incoming.getId().isEmpty()) {
                WebDashboard.respond(exchange, 400, "{\"error\":\"id required\"}");
                return true;
            }
            if (!isValidId(incoming.getId())) {
                WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid id\"}");
                return true;
            }
            if (!com.cheesestore.cosmetic.CosmeticEntry.Category.isValid(incoming.getCategory())) {
                WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid category\"}");
                return true;
            }
            mgr.add(incoming); // replaces any existing entry with the same id
            broadcastPlayCosmeticUpdate("created", incoming);
            WebDashboard.respond(exchange, 201, incoming.toJson().toString());
            return true;
        }

        // POST /playcosmetics/reorder — {ids:[...]}
        if (subRoute.equals("/playcosmetics/reorder") && method.equals("POST")) {
            String body = WebDashboard.readBody(exchange);
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            if (!obj.has("ids") || !obj.get("ids").isJsonArray()) {
                WebDashboard.respond(exchange, 400, "{\"error\":\"Missing ids\"}");
                return true;
            }
            List<String> ids = new java.util.ArrayList<>();
            for (JsonElement el : obj.getAsJsonArray("ids")) ids.add(el.getAsString());
            mgr.reorder(ids);
            broadcastPlayCosmeticUpdate("reordered", null);
            WebDashboard.respond(exchange, 200, "{\"ok\":true}");
            return true;
        }

        // POST /playcosmetics/grant — {player|uuid, id}
        if (subRoute.equals("/playcosmetics/grant") && method.equals("POST")) {
            String body = WebDashboard.readBody(exchange);
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            String cosmeticId = obj.has("id") ? obj.get("id").getAsString() : null;
            if (cosmeticId == null || !isValidId(cosmeticId)) {
                WebDashboard.respond(exchange, 400, "{\"error\":\"id required\"}");
                return true;
            }
            if (mgr.get(cosmeticId) == null) {
                WebDashboard.respond(exchange, 404, "{\"error\":\"Cosmetic not found\"}");
                return true;
            }
            UUID target = resolvePlayerUuid(server, obj);
            if (target == null) {
                WebDashboard.respond(exchange, 400, "{\"error\":\"Could not resolve player (provide uuid or known player name)\"}");
                return true;
            }
            com.cheesestore.cosmetic.OwnershipManager.getInstance().grant(target, cosmeticId);
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("uuid", target.toString());
            out.addProperty("id", cosmeticId);
            WebDashboard.respond(exchange, 200, out.toString());
            return true;
        }

        // GET /playcosmetics/enums — dropdown source lists for the dashboard
        if (subRoute.equals("/playcosmetics/enums") && method.equals("GET")) {
            WebDashboard.respond(exchange, 200, buildEnumsJson().toString());
            return true;
        }

        // /playcosmetics/{id} — PUT (update) / DELETE
        if (subRoute.startsWith("/playcosmetics/")) {
            String id = subRoute.substring("/playcosmetics/".length());
            if (!isValidId(id)) { WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid ID\"}"); return true; }

            if (method.equals("PUT")) {
                com.cheesestore.cosmetic.CosmeticEntry c = mgr.get(id);
                if (c == null) { WebDashboard.respond(exchange, 404, "{\"error\":\"Cosmetic not found\"}"); return true; }
                String body = WebDashboard.readBody(exchange);
                JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                c.mergeFrom(obj);
                mgr.update(c);
                broadcastPlayCosmeticUpdate("updated", c);
                WebDashboard.respond(exchange, 200, c.toJson().toString());
                return true;
            }

            if (method.equals("DELETE")) {
                boolean removed = mgr.remove(id);
                if (!removed) { WebDashboard.respond(exchange, 404, "{\"error\":\"Cosmetic not found\"}"); return true; }
                JsonObject msg = new JsonObject();
                msg.addProperty("type", "store_playcosmetic_update");
                msg.addProperty("action", "deleted");
                msg.addProperty("cosmeticId", id);
                DashboardRegistry.broadcast("store", msg.toString());
                WebDashboard.respond(exchange, 200, "{\"deleted\":true}");
                return true;
            }
        }

        return false; // not a play-cosmetic route we handle → fall through to 404
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PUBLIC API (no auth — served at /store/api/*)
    // ══════════════════════════════════════════════════════════════════════════

    public static void handlePublic(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod();
        CheeseStoreConfig config = CheeseStoreMod.INSTANCE.getConfig();

        try {
            // GET /products — enabled products only
            if ((path.equals("/products") || path.equals("/products/")) && method.equals("GET")) {
                if (!config.isStoreEnabled()) {
                    WebDashboard.respond(exchange, 503, "{\"error\":\"Store is disabled\"}");
                    return;
                }
                JsonArray arr = new JsonArray();
                for (StoreProduct p : config.getEnabledProducts()) arr.add(p.toPublicJson());
                WebDashboard.respond(exchange, 200, arr.toString());
                return;
            }

            // GET /products/:id/image
            if (path.startsWith("/products/") && path.endsWith("/image") && method.equals("GET")) {
                String id = path.substring("/products/".length(), path.length() - "/image".length());
                serveProductImage(exchange, config, id);
                return;
            }

            // GET /gallery — public gallery, sorted (new first, then sortOrder)
            if ((path.equals("/gallery") || path.equals("/gallery/")) && method.equals("GET")) {
                if (!config.isStoreEnabled()) {
                    WebDashboard.respond(exchange, 503, "{\"error\":\"Store is disabled\"}");
                    return;
                }
                JsonArray arr = new JsonArray();
                for (GalleryItem g : config.getGallerySorted()) arr.add(g.toPublicJson());
                WebDashboard.respond(exchange, 200, arr.toString());
                return;
            }

            // GET /gallery/:id/image
            if (path.startsWith("/gallery/") && path.endsWith("/image") && method.equals("GET")) {
                String id = path.substring("/gallery/".length(), path.length() - "/image".length());
                serveGalleryImage(exchange, config, id);
                return;
            }

            // GET /players — all known players for dropdown
            if (path.equals("/players") && method.equals("GET")) {
                JsonArray players = loadPlayerList();
                WebDashboard.respond(exchange, 200, players.toString());
                return;
            }

            // GET /config — public store config
            if (path.equals("/config") && method.equals("GET")) {
                JsonObject cfg = new JsonObject();
                cfg.addProperty("storeName", config.getStoreName());
                cfg.addProperty("storeSubtitle", config.getStoreSubtitle());
                cfg.addProperty("stripePublishableKey", config.getStripePublishableKey());
                cfg.addProperty("storeEnabled", config.isStoreEnabled());
                cfg.addProperty("currencyName", config.getCurrencyName());
                cfg.addProperty("oauthEnabled", true);
                WebDashboard.respond(exchange, 200, cfg.toString());
                return;
            }

            // OAuth flow — start, callback, me, logout, link-code
            if (path.startsWith("/oauth/")) {
                if (com.cheesestore.auth.GoogleOAuth.dispatch(exchange, path)) return;
            }

            // GET /morpho/list — catalog entries flagged availableOnWebsite
            if (path.equals("/morpho/list") && method.equals("GET")) {
                handleMorphoList(exchange);
                return;
            }
            // GET /morpho/list/:id/image — full-resolution original (used by lightbox).
            if (path.startsWith("/morpho/list/") && path.endsWith("/image") && method.equals("GET")) {
                String id = path.substring("/morpho/list/".length(), path.length() - "/image".length());
                serveCatalogImage(exchange, id);
                return;
            }
            // GET /morpho/list/:id/thumb — downscaled (≤480px) version for gallery cards.
            if (path.startsWith("/morpho/list/") && path.endsWith("/thumb") && method.equals("GET")) {
                String id = path.substring("/morpho/list/".length(), path.length() - "/thumb".length());
                serveCatalogThumb(exchange, id);
                return;
            }
            // POST /morpho/buy — debit wallet + add to codex (requires OAuth + linked account)
            if (path.equals("/morpho/buy") && method.equals("POST")) {
                handleMorphoBuy(exchange);
                return;
            }

            // GET /cosmetics — enabled cosmetics for the website
            if ((path.equals("/cosmetics") || path.equals("/cosmetics/")) && method.equals("GET")) {
                if (!config.isStoreEnabled()) {
                    WebDashboard.respond(exchange, 503, "{\"error\":\"Store is disabled\"}");
                    return;
                }
                JsonArray arr = new JsonArray();
                for (CosmeticItem c : CosmeticManager.getInstance().getEnabled()) arr.add(c.toPublicJson());
                WebDashboard.respond(exchange, 200, arr.toString());
                return;
            }
            // GET /cosmetics/image?id=xxx — cosmetic preview image (reuses product image pipeline)
            if (path.equals("/cosmetics/image") && method.equals("GET")) {
                String id = queryParam(exchange, "id");
                serveCosmeticImage(exchange, id);
                return;
            }
            // GET /cosmetics/:id/image — alternate path form (same image)
            if (path.startsWith("/cosmetics/") && path.endsWith("/image") && method.equals("GET")) {
                String id = path.substring("/cosmetics/".length(), path.length() - "/image".length());
                serveCosmeticImage(exchange, id);
                return;
            }
            // POST /cosmetics/buy — debit credits + run applyCommands (requires linked account)
            if (path.equals("/cosmetics/buy") && method.equals("POST")) {
                handleCosmeticBuy(exchange);
                return;
            }

            // POST /checkout — create Stripe Hosted Checkout Session (redirect mode)
            if (path.equals("/checkout") && method.equals("POST")) {
                handleCheckout(exchange, config);
                return;
            }

            // GET /session-status?session_id=xxx — check payment status after return
            if (path.equals("/session-status") && method.equals("GET")) {
                handleSessionStatus(exchange, config);
                return;
            }

            // POST /webhook — Stripe webhook
            if (path.equals("/webhook") && method.equals("POST")) {
                handleWebhook(exchange, config);
                return;
            }

            // ─── Playable cosmetics (public) ──────────────────────────────────
            //  GET  /store/api/playcosmetics       — enabled entries only
            //  POST /store/api/playcosmetics/{id}/buy — debit credits + grant ownership
            if ((path.equals("/playcosmetics") || path.equals("/playcosmetics/")) && method.equals("GET")) {
                if (!config.isStoreEnabled()) {
                    WebDashboard.respond(exchange, 503, "{\"error\":\"Store is disabled\"}");
                    return;
                }
                com.cheesestore.cosmetic.CosmeticManager mgr = com.cheesestore.cosmetic.CosmeticManager.getInstance();
                JsonArray arr = new JsonArray();
                if (mgr != null) {
                    for (com.cheesestore.cosmetic.CosmeticEntry c : mgr.getEnabled()) arr.add(c.toJson());
                }
                WebDashboard.respond(exchange, 200, arr.toString());
                return;
            }
            if (path.startsWith("/playcosmetics/") && path.endsWith("/buy") && method.equals("POST")) {
                String id = path.substring("/playcosmetics/".length(), path.length() - "/buy".length());
                handlePlayCosmeticBuy(exchange, id);
                return;
            }

            WebDashboard.respond(exchange, 404, "{\"error\":\"Not found\"}");
        } catch (Exception e) {
            LOGGER.error("[CheeseStore Public API] Error handling {}: {}", path, e.getMessage(), e);
            WebDashboard.respond(exchange, 500, "{\"error\":\"Internal server error\"}");
        }
    }

    // ── Checkout ─────────────────────────────────────────────────────────────

    private static void handleCheckout(HttpExchange exchange, CheeseStoreConfig config) throws IOException {
        if (!checkSameOriginPost(exchange)) {
            WebDashboard.respond(exchange, 403, "{\"error\":\"forbidden_origin\"}");
            return;
        }
        if (!rateLimitOk("checkout:" + publicClientIp(exchange), 8, 60_000L)) {
            WebDashboard.respond(exchange, 429, "{\"error\":\"too_many_requests\"}");
            return;
        }
        StripeClient stripe = CheeseStoreMod.INSTANCE.getStripeClient();
        if (stripe == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"Stripe not configured\"}");
            return;
        }

        String body = readBody(exchange);
        JsonObject req = JsonParser.parseString(body).getAsJsonObject();

        String productId = req.has("productId") ? req.get("productId").getAsString() : null;
        String playerUUID = req.has("playerUUID") ? req.get("playerUUID").getAsString() : null;
        String playerName = req.has("playerName") ? req.get("playerName").getAsString() : null;

        if (productId == null || playerUUID == null || playerName == null) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Missing productId, playerUUID, or playerName\"}");
            return;
        }

        StoreProduct product = config.getProduct(productId);
        if (product == null || !product.isEnabled()) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Product not found or disabled\"}");
            return;
        }

        // Validate storeBaseUrl — block checkout if misconfigured
        String baseUrl = config.getStoreBaseUrl();
        if (baseUrl.isEmpty() || baseUrl.contains("localhost")) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"Store base URL is not configured. Set storeBaseUrl in config or CheeseDash admin.\"}");
            return;
        }

        // Hosted Checkout: return checkoutUrl, user is redirected to Stripe
        String returnUrl = baseUrl + "/store/";
        JsonObject result = stripe.createCheckoutSession(
            product, playerName, playerUUID, returnUrl);

        if (result.has("error")) {
            WebDashboard.respond(exchange, 502, result.toString());
            return;
        }

        WebDashboard.respond(exchange, 200, result.toString());
    }

    // ── Session Status (after embedded checkout return) ──────────────────────

    private static void handleSessionStatus(HttpExchange exchange, CheeseStoreConfig config) throws IOException {
        StripeClient stripe = CheeseStoreMod.INSTANCE.getStripeClient();
        if (stripe == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"Stripe not configured\"}");
            return;
        }

        // Extract session_id from query string
        String query = exchange.getRequestURI().getQuery();
        String sessionId = null;
        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if ("session_id".equals(kv[0]) && kv.length == 2) {
                    sessionId = kv[1];
                    break;
                }
            }
        }

        if (sessionId == null || sessionId.isEmpty()) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Missing session_id\"}");
            return;
        }

        // Retrieve session from Stripe
        JsonObject session = stripe.retrieveCheckoutSession(sessionId);
        if (session == null) {
            WebDashboard.respond(exchange, 502, "{\"error\":\"Failed to retrieve session\"}");
            return;
        }

        String status = session.has("status") ? session.get("status").getAsString() : "";

        // If payment complete, process delivery (idempotent)
        if ("complete".equals(status)) {
            processPayment(session, config);
        }

        JsonObject resp = new JsonObject();
        resp.addProperty("status", status);
        WebDashboard.respond(exchange, 200, resp.toString());
    }

    // ── Webhook ──────────────────────────────────────────────────────────────

    private static void handleWebhook(HttpExchange exchange, CheeseStoreConfig config) throws IOException {
        String payload = readBody(exchange);
        String sigHeader = exchange.getRequestHeaders().getFirst("Stripe-Signature");
        String webhookSecret = config.getStripeWebhookSecret();

        // Hard requirement: refuse to process payments without a verified signature.
        // Without a secret, anyone could POST a fake "checkout.session.completed" payload
        // and grant themselves credits/products for free.
        if (webhookSecret == null || webhookSecret.isEmpty()) {
            LOGGER.error("[CheeseStore] Webhook received but no secret configured — rejecting");
            WebDashboard.respond(exchange, 503, "{\"error\":\"Webhook secret not configured\"}");
            return;
        }
        if (!StripeClient.verifyWebhookSignature(payload, sigHeader, webhookSecret)) {
            LOGGER.warn("[CheeseStore] Webhook signature verification failed");
            WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid signature\"}");
            return;
        }

        JsonObject event = JsonParser.parseString(payload).getAsJsonObject();
        String type = event.has("type") ? event.get("type").getAsString() : "";

        if ("checkout.session.completed".equals(type)) {
            JsonObject session = event.getAsJsonObject("data").getAsJsonObject("object");
            processPayment(session, config);
        }

        WebDashboard.respond(exchange, 200, "{\"received\":true}");
    }

    // ── Payment Processing ───────────────────────────────────────────────────

    private static boolean processPayment(JsonObject session, CheeseStoreConfig config) {
        JsonObject metadata = session.has("metadata") ? session.getAsJsonObject("metadata") : null;
        if (metadata == null) return false;

        String orderId = session.get("id").getAsString();
        String playerUUID = metadata.has("player_uuid") ? metadata.get("player_uuid").getAsString() : null;
        String playerName = metadata.has("player_name") ? metadata.get("player_name").getAsString() : null;
        String productId = metadata.has("product_id") ? metadata.get("product_id").getAsString() : null;

        if (playerUUID == null || playerName == null || productId == null) {
            LOGGER.error("[CheeseStore] Missing metadata in session {}", orderId);
            return false;
        }

        StoreProduct product = config.getProduct(productId);
        if (product == null) {
            LOGGER.error("[CheeseStore] Product {} not found for session {}", productId, orderId);
            return false;
        }

        DeliveryManager mgr = CheeseStoreMod.INSTANCE.getDeliveryManager();
        boolean created = mgr.createDelivery(orderId, playerUUID, playerName, product);

        if (created) {
            // Try to deliver immediately if player is online
            MinecraftServer server = CheeseStoreMod.INSTANCE.getServer();
            if (server != null) {
                server.execute(() -> mgr.deliverPending(server, playerUUID));
            }

            // Broadcast to dashboard
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "store_purchase");
            msg.addProperty("playerName", playerName);
            msg.addProperty("productName", product.getName());
            msg.addProperty("priceCents", product.getPriceCents());
            DashboardRegistry.broadcast("store", msg.toString());
        }

        return created;
    }

    // ── Player List ──────────────────────────────────────────────────────────

    private static JsonArray loadPlayerList() {
        JsonArray players = new JsonArray();
        try {
            Path gameDir = FabricLoader.getInstance().getGameDir();
            Path usercache = gameDir.resolve("usercache.json");
            if (Files.exists(usercache)) {
                String json = Files.readString(usercache);
                JsonArray cache = JsonParser.parseString(json).getAsJsonArray();
                for (JsonElement el : cache) {
                    JsonObject entry = el.getAsJsonObject();
                    JsonObject player = new JsonObject();
                    player.addProperty("name", entry.get("name").getAsString());
                    player.addProperty("uuid", entry.get("uuid").getAsString());
                    players.add(player);
                }
            }
        } catch (Exception e) {
            LOGGER.error("[CheeseStore] Failed to load player list: {}", e.getMessage());
        }

        // Also add currently online players (may have fresher names)
        MinecraftServer server = CheeseStoreMod.INSTANCE.getServer();
        if (server != null) {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                String uuid = p.getUuidAsString();
                boolean exists = false;
                for (JsonElement el : players) {
                    if (uuid.equals(el.getAsJsonObject().get("uuid").getAsString())) {
                        // Update name if different
                        el.getAsJsonObject().addProperty("name", p.getName().getString());
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    JsonObject po = new JsonObject();
                    po.addProperty("name", p.getName().getString());
                    po.addProperty("uuid", uuid);
                    players.add(po);
                }
            }
        }

        return players;
    }

    // ── Image Serving ────────────────────────────────────────────────────────

    private static void serveProductImage(HttpExchange exchange, CheeseStoreConfig config, String id) throws IOException {
        if (!isValidId(id)) { WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid ID\"}"); return; }
        java.nio.file.Path imgPath = config.getAssetsDir().resolve(id + ".png");
        serveImageWithRevalidation(exchange, imgPath);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void saveBase64Image(CheeseStoreConfig config, String id, String base64) throws IOException {
        // Strip data URI prefix if present
        String raw = base64;
        if (raw.contains(",")) raw = raw.substring(raw.indexOf(",") + 1);
        byte[] data = Base64.getDecoder().decode(raw);
        config.saveProductImage(id, data);
    }

    private static void saveGalleryBase64Image(CheeseStoreConfig config, String id, String base64) throws IOException {
        String raw = base64;
        if (raw.contains(",")) raw = raw.substring(raw.indexOf(",") + 1);
        byte[] data = Base64.getDecoder().decode(raw);
        config.saveGalleryImage(id, data);
    }

    private static void serveGalleryImage(HttpExchange exchange, CheeseStoreConfig config, String id) throws IOException {
        if (!isValidId(id)) { WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid ID\"}"); return; }
        java.nio.file.Path imgPath = config.getAssetsDir().resolve("gallery").resolve(id + ".png");
        serveImageWithRevalidation(exchange, imgPath);
    }

    /** @deprecated use {@link WebDashboard#servePngWithRevalidation} directly. */
    private static void serveImageWithRevalidation(HttpExchange exchange, java.nio.file.Path path) throws IOException {
        WebDashboard.servePngWithRevalidation(exchange, path);
    }

    private static void broadcastGalleryUpdate(String action, GalleryItem item) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "store_gallery_update");
        msg.addProperty("action", action);
        if (item != null) msg.add("item", item.toJson());
        DashboardRegistry.broadcast("store", msg.toString());
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String maskKey(String key) {
        if (key == null || key.length() < 12) return "***";
        return key.substring(0, 8) + "***" + key.substring(key.length() - 4);
    }

    private static boolean isValidId(String id) {
        return id != null && !id.isEmpty() && !id.contains("..")
            && !id.contains("/") && !id.contains("\\")
            && !id.contains("%") && id.length() < 128;
    }

    /**
     * Per-IP rate limit for public mutating endpoints (buy / checkout / link-code).
     * Returns true and records the hit if the IP is under the limit, false if exceeded.
     * Each bucket allows {@code maxHits} requests per {@code windowMs} window.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> rlBuckets =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean rateLimitOk(String key, int maxHits, long windowMs) {
        long now = System.currentTimeMillis();
        long[] bucket = rlBuckets.compute(key, (k, v) -> {
            if (v == null || now - v[0] > windowMs) return new long[]{ now, 1 };
            v[1]++;
            return v;
        });
        return bucket[1] <= maxHits;
    }

    public static String publicClientIp(HttpExchange exchange) {
        String direct = exchange.getRemoteAddress().getAddress().getHostAddress();
        // Only trust X-Forwarded-For when the immediate peer is itself a known
        // reverse proxy. Otherwise any visitor can spoof their IP via this header
        // and bypass per-IP rate limits on /buy /checkout /link-code.
        if (isTrustedProxy(direct)) {
            String fwd = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (fwd != null && !fwd.isEmpty()) {
                // The leftmost address is the original client; everything after
                // is each hop in the proxy chain.
                return fwd.split(",")[0].trim();
            }
        }
        return direct;
    }

    /** Treat loopback + RFC1918 ranges as trusted proxy peers. Adjust if the
     *  real reverse proxy lives on a different subnet. */
    private static boolean isTrustedProxy(String ip) {
        if (ip == null) return false;
        if (ip.equals("127.0.0.1") || ip.equals("::1") || ip.startsWith("::ffff:127.")) return true;
        if (ip.startsWith("10.")) return true;
        if (ip.startsWith("192.168.")) return true;
        if (ip.startsWith("172.")) {
            // 172.16.0.0 – 172.31.255.255
            try {
                int second = Integer.parseInt(ip.split("\\.")[1]);
                if (second >= 16 && second <= 31) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    /** Defence-in-depth CSRF check for state-changing endpoints. Modern browsers
     *  enforce SameSite=Lax which already blocks cross-site POST cookies, but
     *  validating Origin/Referer adds a belt to the suspenders. Returns true
     *  when the request is allowed. */
    static boolean checkSameOriginPost(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String referer = exchange.getRequestHeaders().getFirst("Referer");
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null) return false;
        // Accept if Origin matches the Host (browser-set, can't be spoofed
        // cross-site without an XHR pre-flight that would fail CORS).
        if (origin != null && (origin.endsWith("//" + host) || origin.contains("://" + host + ":")
                || origin.contains("://" + host + "/") || origin.endsWith("://" + host))) {
            return true;
        }
        // Fallback to Referer (covers some browsers that elide Origin on
        // same-origin POST). Accept if it begins with our scheme://host.
        if (referer != null && (referer.contains("://" + host + "/")
                || referer.contains("://" + host + ":"))) {
            return true;
        }
        // No Origin and no Referer is suspicious. Accept only when both are
        // null (some privacy-strict setups strip them on same-origin too).
        return origin == null && referer == null;
    }

    private static void broadcastUpdate(String action, StoreProduct product) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "store_update");
        msg.addProperty("action", action);
        msg.add("product", product.toJson());
        DashboardRegistry.broadcast("store", msg.toString());
    }

    // ── Morpho catalog public endpoints ──────────────────────────────────────

    private static void handleMorphoList(HttpExchange exchange) throws IOException {
        if (!CheeseStoreMod.INSTANCE.getConfig().isStoreEnabled()) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"Store is disabled\"}");
            return;
        }
        JsonArray arr = new JsonArray();
        for (com.cheeseitems.morpho.MorphoEntry e : com.cheeseitems.morpho.MorphoCatalog.getInstance().getSortedForWebsite()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.getId());
            o.addProperty("title", e.getTitle());
            o.addProperty("description", e.getDescription());
            o.addProperty("species", e.getSpecies());
            o.addProperty("priceCredits", e.getPriceCredits());
            o.addProperty("hasImage", e.hasImage());
            o.addProperty("isNew", e.isNew());
            o.addProperty("terrestrial", e.isTerrestrial());
            o.addProperty("aquatic", e.isAquatic());
            o.addProperty("aerial", e.isAerial());
            o.addProperty("legendary", e.isLegendary());
            o.addProperty("shiny", e.isShiny());
            arr.add(o);
        }
        WebDashboard.respond(exchange, 200, arr.toString());
    }

    private static void serveCatalogImage(HttpExchange exchange, String id) throws IOException {
        if (!isValidId(id)) { WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid id\"}"); return; }
        java.nio.file.Path imgPath = com.cheeseitems.morpho.MorphoCatalog.getInstance().getImagePath(id);
        serveImageWithRevalidation(exchange, imgPath);
    }

    private static void serveCatalogThumb(HttpExchange exchange, String id) throws IOException {
        if (!isValidId(id)) { WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid id\"}"); return; }
        java.nio.file.Path original = com.cheeseitems.morpho.MorphoCatalog.getInstance().getImagePath(id);
        java.nio.file.Path thumb = com.cheeseitems.morpho.MorphoThumbnail.getOrCreate(original);
        serveImageWithRevalidation(exchange, thumb);
    }

    private static void handleMorphoBuy(HttpExchange exchange) throws IOException {
        // 0. CSRF defence — debits the wallet, must be a same-origin POST.
        if (!checkSameOriginPost(exchange)) {
            WebDashboard.respond(exchange, 403, "{\"error\":\"forbidden_origin\"}");
            return;
        }
        // Per-IP rate limit — block automated abuse / brute-force grant attempts.
        if (!rateLimitOk("buy:" + publicClientIp(exchange), 10, 60_000L)) {
            WebDashboard.respond(exchange, 429, "{\"error\":\"too_many_requests\"}");
            return;
        }
        // 1. Auth (Google OAuth cookie)
        com.cheesestore.auth.GoogleOAuth.Session sess =
                com.cheesestore.auth.GoogleOAuth.currentSession(exchange);
        if (sess == null) { WebDashboard.respond(exchange, 401, "{\"error\":\"not_authenticated\"}"); return; }

        // 2. Linked account? (sess.email holds the session id under the new auth scheme)
        com.cheesestore.auth.AccountLinker.Link link =
                com.cheesestore.auth.AccountLinker.getInstance().findBySession(sess.email);
        if (link == null) {
            WebDashboard.respond(exchange, 403, "{\"error\":\"not_linked\"}");
            return;
        }

        // 3. Body & catalog lookup — defensive ID validation (no traversal/injection).
        String body = readBody(exchange);
        JsonObject req = JsonParser.parseString(body).getAsJsonObject();
        String morphoId = req.has("morphoId") ? req.get("morphoId").getAsString() : "";
        if (!isValidId(morphoId)) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"morphoId required\"}");
            return;
        }
        com.cheeseitems.morpho.MorphoEntry entry =
                com.cheeseitems.morpho.MorphoCatalog.getInstance().get(morphoId);
        if (entry == null || !entry.isAvailableOnWebsite()) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"morpho_not_found\"}");
            return;
        }

        // 4. Already in codex?
        com.cheeseitems.morpho.MorphoCodex codex =
                com.cheeseitems.CheeseItemsMod.INSTANCE.getMorphoCodex();
        if (codex.hasInCodex(link.minecraftUuid, morphoId)) {
            WebDashboard.respond(exchange, 409, "{\"error\":\"already_owned\"}");
            return;
        }

        // 5. Wallet balance check
        long price = entry.getPriceCredits();
        long balance = WalletManager.getInstance().getBalance(link.minecraftUuid);
        if (balance < price) {
            JsonObject out = new JsonObject();
            out.addProperty("error", "insufficient_credits");
            out.addProperty("currentBalance", balance);
            out.addProperty("required", price);
            WebDashboard.respond(exchange, 402, out.toString());
            return;
        }

        // 6. Debit + addToCodex on the server thread.
        net.minecraft.server.MinecraftServer mcServer = CheeseStoreMod.INSTANCE.getServer();
        if (mcServer == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"server_not_ready\"}");
            return;
        }
        try {
            WebDashboard.executeOnServerThread(mcServer, () -> {
                // Re-check ownership on the MC thread to close the TOCTOU window:
                // two simultaneous /morpho/buy calls for the same morpho would each
                // pass the HTTP-thread check above; this guard prevents double debit.
                if (codex.hasInCodex(link.minecraftUuid, morphoId)) {
                    throw new RuntimeException("already_owned");
                }
                long bal = WalletManager.getInstance().getBalance(link.minecraftUuid);
                if (bal < price) throw new RuntimeException("insufficient_credits");
                WalletManager.getInstance().setBalance(
                        link.minecraftUuid, link.minecraftName, bal - price);
                codex.addToCodex(link.minecraftUuid,
                        new com.cheeseitems.morpho.MorphoCodexEntry(
                                morphoId, entry.getSpecies(), entry.getForm(), entry.isShiny(),
                                System.currentTimeMillis()));
                net.minecraft.server.network.ServerPlayerEntity p =
                        mcServer.getPlayerManager().getPlayer(link.minecraftUuid);
                if (p != null) {
                    com.cheeseitems.morpho.MorphoManager mm =
                            com.cheeseitems.CheeseItemsMod.INSTANCE.getMorphoManager();
                    if (mm != null) mm.sendSync(p);
                    p.sendMessage(net.minecraft.text.Text.literal("🧬 ")
                            .append(net.minecraft.text.Text.literal(entry.getTitle()))
                            .append(net.minecraft.text.Text.literal(" — ajouté à votre codex !"))
                            .formatted(net.minecraft.util.Formatting.GREEN), false);
                }
            });
        } catch (Exception e) {
            String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            if ("insufficient_credits".equals(msg)) {
                WebDashboard.respond(exchange, 402, "{\"error\":\"insufficient_credits\"}");
            } else if ("already_owned".equals(msg)) {
                WebDashboard.respond(exchange, 409, "{\"error\":\"already_owned\"}");
            } else {
                LOGGER.error("[CheeseStore] morpho_buy failed: {}", msg);
                WebDashboard.respond(exchange, 500, "{\"error\":\"buy_failed\"}");
            }
            return;
        }

        long newBalance = WalletManager.getInstance().getBalance(link.minecraftUuid);
        JsonObject out = new JsonObject();
        out.addProperty("success", true);
        out.addProperty("newBalance", newBalance);
        out.addProperty("morphoId", morphoId);
        WebDashboard.respond(exchange, 200, out.toString());
    }

    // ── Cosmetics helpers ─────────────────────────────────────────────────────

    private static void saveCosmeticBase64Image(CosmeticManager mgr, String id, String base64) throws IOException {
        String raw = base64;
        if (raw.contains(",")) raw = raw.substring(raw.indexOf(",") + 1);
        byte[] data = Base64.getDecoder().decode(raw);
        mgr.saveImage(id, data);
    }

    private static void serveCosmeticImage(HttpExchange exchange, String id) throws IOException {
        if (!isValidId(id)) { WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid ID\"}"); return; }
        java.nio.file.Path imgPath = CosmeticManager.getInstance().imagePath(id);
        serveImageWithRevalidation(exchange, imgPath);
    }

    private static void broadcastCosmeticUpdate(String action, CosmeticItem cosmetic) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "store_cosmetic_update");
        msg.addProperty("action", action);
        if (cosmetic != null) msg.add("cosmetic", cosmetic.toJson());
        DashboardRegistry.broadcast("store", msg.toString());
    }

    private static String queryParam(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (key.equals(kv[0]) && kv.length == 2) {
                return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * POST /store/api/cosmetics/buy — buy a cosmetic with in-game credits.
     *
     * Mirrors {@link #handleMorphoBuy}: same-origin CSRF guard, per-IP rate limit,
     * OAuth session → linked Minecraft account, wallet balance check, then an atomic
     * debit + applyCommands execution on the server thread (re-checks balance inside
     * the lambda to close the TOCTOU window). Cosmetics are repeatable purchases, so
     * there is no "already owned" guard — the only idempotency is the atomic debit.
     */
    private static void handleCosmeticBuy(HttpExchange exchange) throws IOException {
        // 0. CSRF — debits the wallet, must be a same-origin POST.
        if (!checkSameOriginPost(exchange)) {
            WebDashboard.respond(exchange, 403, "{\"error\":\"forbidden_origin\"}");
            return;
        }
        // Per-IP rate limit.
        if (!rateLimitOk("buy:" + publicClientIp(exchange), 10, 60_000L)) {
            WebDashboard.respond(exchange, 429, "{\"error\":\"too_many_requests\"}");
            return;
        }
        // 1. Auth (session cookie issued by the account-link flow).
        com.cheesestore.auth.GoogleOAuth.Session sess =
                com.cheesestore.auth.GoogleOAuth.currentSession(exchange);
        if (sess == null) { WebDashboard.respond(exchange, 401, "{\"error\":\"not_authenticated\"}"); return; }

        // 2. Linked account?
        com.cheesestore.auth.AccountLinker.Link link =
                com.cheesestore.auth.AccountLinker.getInstance().findBySession(sess.email);
        if (link == null) {
            WebDashboard.respond(exchange, 403, "{\"error\":\"not_linked\"}");
            return;
        }

        // 3. Body & catalog lookup.
        String body = readBody(exchange);
        JsonObject req = JsonParser.parseString(body).getAsJsonObject();
        String cosmeticId = req.has("cosmeticId") ? req.get("cosmeticId").getAsString() : "";
        if (!isValidId(cosmeticId)) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"cosmeticId required\"}");
            return;
        }
        CosmeticItem cosmetic = CosmeticManager.getInstance().get(cosmeticId);
        if (cosmetic == null || !cosmetic.isEnabled()) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"cosmetic_not_found\"}");
            return;
        }

        // 4. Wallet balance check (HTTP-thread pre-check).
        long price = cosmetic.getPriceCredits();
        long balance = WalletManager.getInstance().getBalance(link.minecraftUuid);
        if (balance < price) {
            JsonObject out = new JsonObject();
            out.addProperty("error", "insufficient_credits");
            out.addProperty("currentBalance", balance);
            out.addProperty("required", price);
            WebDashboard.respond(exchange, 402, out.toString());
            return;
        }

        // 5. Atomic debit + applyCommands on the server thread.
        MinecraftServer mcServer = CheeseStoreMod.INSTANCE.getServer();
        if (mcServer == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"server_not_ready\"}");
            return;
        }
        try {
            WebDashboard.executeOnServerThread(mcServer, () -> {
                // Re-check balance on the MC thread to close the TOCTOU window:
                // two simultaneous buys would each pass the HTTP-thread check above.
                long bal = WalletManager.getInstance().getBalance(link.minecraftUuid);
                if (bal < price) throw new RuntimeException("insufficient_credits");
                WalletManager.getInstance().setBalance(
                        link.minecraftUuid, link.minecraftName, bal - price);
                // Run applyCommands from the server console ({player} → linked name).
                for (String cmd : cosmetic.getApplyCommands()) {
                    String resolved = cmd.replace("{player}", link.minecraftName);
                    mcServer.getCommandManager().executeWithPrefix(mcServer.getCommandSource(), resolved);
                }
                ServerPlayerEntity p = mcServer.getPlayerManager().getPlayer(link.minecraftUuid);
                if (p != null) {
                    p.sendMessage(net.minecraft.text.Text.literal("✨ ")
                            .append(net.minecraft.text.Text.literal(cosmetic.getName()))
                            .append(net.minecraft.text.Text.literal(" — cosmétique débloqué !"))
                            .formatted(net.minecraft.util.Formatting.LIGHT_PURPLE), false);
                }
            });
        } catch (Exception e) {
            String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            if ("insufficient_credits".equals(msg)) {
                WebDashboard.respond(exchange, 402, "{\"error\":\"insufficient_credits\"}");
            } else {
                LOGGER.error("[CheeseStore] cosmetic_buy failed: {}", msg);
                WebDashboard.respond(exchange, 500, "{\"error\":\"buy_failed\"}");
            }
            return;
        }

        // Broadcast purchase to the dashboard feed.
        JsonObject ev = new JsonObject();
        ev.addProperty("type", "store_cosmetic_purchase");
        ev.addProperty("playerName", link.minecraftName);
        ev.addProperty("cosmeticName", cosmetic.getName());
        ev.addProperty("priceCredits", price);
        DashboardRegistry.broadcast("store", ev.toString());

        long newBalance = WalletManager.getInstance().getBalance(link.minecraftUuid);
        JsonObject out = new JsonObject();
        out.addProperty("success", true);
        out.addProperty("newBalance", newBalance);
        out.addProperty("cosmeticId", cosmeticId);
        WebDashboard.respond(exchange, 200, out.toString());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PLAYABLE COSMETICS — helpers (com.cheesestore.cosmetic system)
    // ══════════════════════════════════════════════════════════════════════════

    private static void broadcastPlayCosmeticUpdate(String action, com.cheesestore.cosmetic.CosmeticEntry entry) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "store_playcosmetic_update");
        msg.addProperty("action", action);
        if (entry != null) msg.add("cosmetic", entry.toJson());
        DashboardRegistry.broadcast("store", msg.toString());
    }

    /**
     * Resolve the target player for a /grant call. Accepts an explicit {@code uuid}
     * (preferred) or a {@code player} name resolved against online players + the
     * usercache. Returns null if nothing matches.
     */
    private static UUID resolvePlayerUuid(MinecraftServer server, JsonObject obj) {
        // 1. Explicit uuid wins.
        if (obj.has("uuid") && !obj.get("uuid").isJsonNull()) {
            String raw = obj.get("uuid").getAsString();
            try { return UUID.fromString(raw); } catch (IllegalArgumentException ignored) {}
        }
        // 2. Resolve by player name.
        String name = obj.has("player") && !obj.get("player").isJsonNull()
                ? obj.get("player").getAsString() : null;
        if (name == null || name.isEmpty()) return null;

        // 2a. Online players (freshest names).
        if (server != null) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name);
            if (p != null) return p.getUuid();
        }
        // 2b. Usercache fallback.
        for (JsonElement el : loadPlayerList()) {
            JsonObject po = el.getAsJsonObject();
            if (po.has("name") && name.equalsIgnoreCase(po.get("name").getAsString())) {
                try { return UUID.fromString(po.get("uuid").getAsString()); }
                catch (Exception ignored) {}
            }
        }
        return null;
    }

    /**
     * POST /store/api/playcosmetics/{id}/buy — buy a playable cosmetic with credits.
     *
     * Mirrors {@link #handleCosmeticBuy}/{@link #handleMorphoBuy}: same-origin CSRF
     * guard, per-IP rate limit, OAuth session → linked Minecraft account, wallet
     * balance check, then an atomic debit + ownership grant on the server thread
     * (re-checks balance inside the lambda to close the TOCTOU window). Idempotent
     * on ownership: re-buying an already-owned cosmetic returns 409 without a debit.
     */
    private static void handlePlayCosmeticBuy(HttpExchange exchange, String cosmeticId) throws IOException {
        // 0. CSRF — debits the wallet, must be a same-origin POST.
        if (!checkSameOriginPost(exchange)) {
            WebDashboard.respond(exchange, 403, "{\"error\":\"forbidden_origin\"}");
            return;
        }
        // Per-IP rate limit.
        if (!rateLimitOk("buy:" + publicClientIp(exchange), 10, 60_000L)) {
            WebDashboard.respond(exchange, 429, "{\"error\":\"too_many_requests\"}");
            return;
        }
        // 1. Auth (session cookie issued by the account-link flow).
        com.cheesestore.auth.GoogleOAuth.Session sess =
                com.cheesestore.auth.GoogleOAuth.currentSession(exchange);
        if (sess == null) { WebDashboard.respond(exchange, 401, "{\"error\":\"not_authenticated\"}"); return; }

        // 2. Linked account?
        com.cheesestore.auth.AccountLinker.Link link =
                com.cheesestore.auth.AccountLinker.getInstance().findBySession(sess.email);
        if (link == null) {
            WebDashboard.respond(exchange, 403, "{\"error\":\"not_linked\"}");
            return;
        }

        // 3. Catalog lookup (id comes from the path; still validate defensively).
        if (!isValidId(cosmeticId)) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"cosmeticId required\"}");
            return;
        }
        com.cheesestore.cosmetic.CosmeticManager catalog = com.cheesestore.cosmetic.CosmeticManager.getInstance();
        com.cheesestore.cosmetic.CosmeticEntry cosmetic = catalog != null ? catalog.get(cosmeticId) : null;
        if (cosmetic == null || !cosmetic.isEnabled()) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"cosmetic_not_found\"}");
            return;
        }

        com.cheesestore.cosmetic.OwnershipManager ownership = com.cheesestore.cosmetic.OwnershipManager.getInstance();

        // 4. Already owned? (HTTP-thread pre-check.)
        if (ownership.owns(link.minecraftUuid, cosmeticId)) {
            WebDashboard.respond(exchange, 409, "{\"error\":\"already_owned\"}");
            return;
        }

        // 5. Wallet balance check (HTTP-thread pre-check).
        long price = cosmetic.getPrice();
        long balance = WalletManager.getInstance().getBalance(link.minecraftUuid);
        if (balance < price) {
            JsonObject out = new JsonObject();
            out.addProperty("error", "insufficient_credits");
            out.addProperty("currentBalance", balance);
            out.addProperty("required", price);
            WebDashboard.respond(exchange, 402, out.toString());
            return;
        }

        // 6. Atomic debit + grant ownership on the server thread.
        MinecraftServer mcServer = CheeseStoreMod.INSTANCE.getServer();
        if (mcServer == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"server_not_ready\"}");
            return;
        }
        try {
            WebDashboard.executeOnServerThread(mcServer, () -> {
                // Re-check on the MC thread to close the TOCTOU window: two
                // simultaneous buys would each pass the HTTP-thread checks above.
                if (ownership.owns(link.minecraftUuid, cosmeticId)) {
                    throw new RuntimeException("already_owned");
                }
                long bal = WalletManager.getInstance().getBalance(link.minecraftUuid);
                if (bal < price) throw new RuntimeException("insufficient_credits");
                WalletManager.getInstance().setBalance(
                        link.minecraftUuid, link.minecraftName, bal - price);
                ownership.grant(link.minecraftUuid, cosmeticId);
                ServerPlayerEntity p = mcServer.getPlayerManager().getPlayer(link.minecraftUuid);
                if (p != null) {
                    p.sendMessage(net.minecraft.text.Text.literal("✨ ")
                            .append(net.minecraft.text.Text.literal(cosmetic.getTitle()))
                            .append(net.minecraft.text.Text.literal(" — cosmétique débloqué !"))
                            .formatted(net.minecraft.util.Formatting.LIGHT_PURPLE), false);
                }
            });
        } catch (Exception e) {
            String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            if ("insufficient_credits".equals(msg)) {
                WebDashboard.respond(exchange, 402, "{\"error\":\"insufficient_credits\"}");
            } else if ("already_owned".equals(msg)) {
                WebDashboard.respond(exchange, 409, "{\"error\":\"already_owned\"}");
            } else {
                LOGGER.error("[CheeseStore] playcosmetic_buy failed: {}", msg);
                WebDashboard.respond(exchange, 500, "{\"error\":\"buy_failed\"}");
            }
            return;
        }

        // Broadcast purchase to the dashboard feed.
        JsonObject ev = new JsonObject();
        ev.addProperty("type", "store_playcosmetic_purchase");
        ev.addProperty("playerName", link.minecraftName);
        ev.addProperty("cosmeticName", cosmetic.getTitle());
        ev.addProperty("priceCredits", price);
        DashboardRegistry.broadcast("store", ev.toString());

        long newBalance = WalletManager.getInstance().getBalance(link.minecraftUuid);
        JsonObject out = new JsonObject();
        out.addProperty("success", true);
        out.addProperty("newBalance", newBalance);
        out.addProperty("cosmeticId", cosmeticId);
        WebDashboard.respond(exchange, 200, out.toString());
    }

    // ── Enums for dashboard dropdowns (admin never types raw MC ids) ───────────

    private static JsonArray arrOf(String... values) {
        JsonArray a = new JsonArray();
        for (String v : values) a.add(v);
        return a;
    }

    /**
     * GET /api/v1/store/playcosmetics/enums — curated source lists so the dashboard
     * can render dropdowns and the admin never has to type a raw Minecraft id.
     */
    private static JsonObject buildEnumsJson() {
        JsonObject out = new JsonObject();

        // 8 categories.
        out.add("categories", arrOf(
                "TRAIL", "KILL_EFFECT", "DEATH_EFFECT", "CHAT_TAG", "NAME_COLOR", "PET", "MOUNT", "CAPE"));

        // ~40 useful vanilla particles for trails / kill / death effects.
        out.add("particles", arrOf(
                "minecraft:flame", "minecraft:soul_fire_flame", "minecraft:soul",
                "minecraft:smoke", "minecraft:large_smoke", "minecraft:campfire_cosy_smoke",
                "minecraft:cloud", "minecraft:poof", "minecraft:explosion", "minecraft:explosion_emitter",
                "minecraft:heart", "minecraft:angry_villager", "minecraft:happy_villager",
                "minecraft:crit", "minecraft:enchanted_hit", "minecraft:damage_indicator",
                "minecraft:dragon_breath", "minecraft:dolphin", "minecraft:bubble", "minecraft:splash",
                "minecraft:end_rod", "minecraft:firework", "minecraft:totem_of_undying",
                "minecraft:portal", "minecraft:reverse_portal", "minecraft:witch",
                "minecraft:enchant", "minecraft:nautilus", "minecraft:glow", "minecraft:wax_on",
                "minecraft:electric_spark", "minecraft:scrape", "minecraft:snowflake",
                "minecraft:cherry_leaves", "minecraft:spore_blossom_air", "minecraft:falling_dust",
                "minecraft:dripping_lava", "minecraft:dripping_honey", "minecraft:ash",
                "minecraft:sculk_soul", "minecraft:sculk_charge_pop", "minecraft:note"));

        // Effect kinds for KILL_EFFECT / DEATH_EFFECT.
        out.add("effects", arrOf(
                "firework", "lightning", "explosion", "souls", "totem", "blood"));

        // ~20 friendly pet mobs + rideable MOUNT mobs (horse/donkey/mule/camel/skeleton_horse).
        out.add("mobs", arrOf(
                "minecraft:allay", "minecraft:bee", "minecraft:cat", "minecraft:parrot",
                "minecraft:fox", "minecraft:wolf", "minecraft:axolotl", "minecraft:vex",
                "minecraft:bat", "minecraft:rabbit", "minecraft:ocelot", "minecraft:panda",
                "minecraft:chicken", "minecraft:pig", "minecraft:sheep", "minecraft:cow",
                "minecraft:frog", "minecraft:turtle", "minecraft:sniffer", "minecraft:armadillo",
                "minecraft:strider", "minecraft:goat", "minecraft:mooshroom", "minecraft:tadpole",
                "minecraft:horse", "minecraft:donkey", "minecraft:mule", "minecraft:camel",
                "minecraft:skeleton_horse"));

        // 16 Minecraft chat colours (Formatting names, lower-case).
        out.add("colors", arrOf(
                "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
                "gold", "gray", "dark_gray", "blue", "green", "aqua", "red",
                "light_purple", "yellow", "white"));

        // ~60 vanilla items good as icons / previews.
        out.add("iconItems", arrOf(
                "minecraft:blaze_powder", "minecraft:fire_charge", "minecraft:soul_torch",
                "minecraft:torch", "minecraft:lantern", "minecraft:soul_lantern",
                "minecraft:gunpowder", "minecraft:tnt", "minecraft:firework_rocket",
                "minecraft:lightning_rod", "minecraft:totem_of_undying", "minecraft:nether_star",
                "minecraft:end_crystal", "minecraft:dragon_breath", "minecraft:dragon_head",
                "minecraft:ghast_tear", "minecraft:phantom_membrane", "minecraft:ender_pearl",
                "minecraft:ender_eye", "minecraft:chorus_fruit", "minecraft:popped_chorus_fruit",
                "minecraft:amethyst_shard", "minecraft:glow_berries", "minecraft:glowstone_dust",
                "minecraft:redstone", "minecraft:nether_wart", "minecraft:diamond",
                "minecraft:emerald", "minecraft:gold_ingot", "minecraft:iron_ingot",
                "minecraft:netherite_ingot", "minecraft:lapis_lazuli", "minecraft:quartz",
                "minecraft:bone", "minecraft:rotten_flesh", "minecraft:spider_eye",
                "minecraft:wither_rose", "minecraft:poppy", "minecraft:dandelion",
                "minecraft:cherry_leaves", "minecraft:azalea", "minecraft:sweet_berries",
                "minecraft:honey_bottle", "minecraft:honeycomb", "minecraft:slime_ball",
                "minecraft:magma_cream", "minecraft:prismarine_crystals", "minecraft:prismarine_shard",
                "minecraft:nautilus_shell", "minecraft:heart_of_the_sea", "minecraft:turtle_helmet",
                "minecraft:feather", "minecraft:elytra", "minecraft:music_disc_pigstep",
                "minecraft:allay_spawn_egg", "minecraft:cat_spawn_egg", "minecraft:wolf_spawn_egg",
                "minecraft:fox_spawn_egg", "minecraft:parrot_spawn_egg", "minecraft:bee_spawn_egg",
                "minecraft:axolotl_spawn_egg", "minecraft:name_tag", "minecraft:paper",
                "minecraft:writable_book", "minecraft:nether_star", "minecraft:beacon"));

        // Trail shapes.
        out.add("shapes", arrOf("trail", "halo", "spiral", "orbit"));

        // ~15 useful vanilla sounds.
        out.add("sounds", arrOf(
                "minecraft:entity.firework_rocket.blast",
                "minecraft:entity.firework_rocket.large_blast",
                "minecraft:entity.lightning_bolt.thunder",
                "minecraft:entity.generic.explode",
                "minecraft:entity.player.levelup",
                "minecraft:entity.experience_orb.pickup",
                "minecraft:item.totem.use",
                "minecraft:entity.ender_dragon.growl",
                "minecraft:entity.wither.spawn",
                "minecraft:block.bell.use",
                "minecraft:block.amethyst_block.chime",
                "minecraft:entity.allay.item_thrown",
                "minecraft:ui.toast.challenge_complete",
                "minecraft:entity.player.attack.crit",
                "minecraft:block.note_block.pling"));

        return out;
    }
}
