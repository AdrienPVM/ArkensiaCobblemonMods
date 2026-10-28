package com.cheeseitems.dashboard;

import com.cheesedash.DashboardRegistry;
import com.cheesedash.WebDashboard;
import com.cheeseitems.CheeseItemsMod;
import com.cheeseitems.morpho.MorphoCatalog;
import com.cheeseitems.morpho.MorphoCodex;
import com.cheeseitems.morpho.MorphoCodexEntry;
import com.cheeseitems.morpho.MorphoEntry;
import com.cheeseitems.morpho.MorphoManager;
import com.cheeseitems.morpho.MorphoThumbnail;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * REST API for the central Morpho Catalog.
 *
 *  Admin (auth):
 *    GET    /morphos                → list (sorted)
 *    POST   /morphos                → create
 *    GET    /morphos/:id            → detail
 *    PUT    /morphos/:id            → update (merge)
 *    DELETE /morphos/:id            → delete + image
 *    POST   /morphos/:id/image      → upload PNG (base64)
 *    GET    /morphos/:id/image      → serve PNG
 *    DELETE /morphos/:id/image      → delete PNG
 *    POST   /morphos/reorder        → body {ids:[...]}
 *    POST   /morphos/grant          → body {playerUuid, morphoId}
 */
public class MorphoCatalogApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseDash");

    public static void handle(HttpExchange exchange, MinecraftServer server, String subRoute) throws IOException {
        String method = exchange.getRequestMethod();
        try {
            // POST /morphos/reorder
            if (subRoute.equals("/reorder") && method.equals("POST")) {
                handleReorder(exchange);
                return;
            }
            // POST /morphos/grant
            if (subRoute.equals("/grant") && method.equals("POST")) {
                handleGrant(exchange, server);
                return;
            }
            // GET / PUT /morphos/global
            if (subRoute.equals("/global")) {
                if (method.equals("GET"))  { handleGlobalGet(exchange);  return; }
                if (method.equals("PUT"))  { handleGlobalPut(exchange);  return; }
            }
            // GET / POST /morphos
            if (subRoute.isEmpty() || subRoute.equals("/")) {
                if (method.equals("GET"))  { handleList(exchange); return; }
                if (method.equals("POST")) { handleCreate(exchange); return; }
            }
            if (subRoute.startsWith("/")) {
                String rest = subRoute.substring(1);
                // /:id/image (upload/serve/delete)
                if (rest.endsWith("/image")) {
                    String id = rest.substring(0, rest.length() - "/image".length());
                    if (!isValidId(id)) { WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid id\"}"); return; }
                    if (method.equals("POST"))   { handleImageUpload(exchange, id); return; }
                    if (method.equals("GET"))    { handleImageServe(exchange, id); return; }
                    if (method.equals("DELETE")) { handleImageDelete(exchange, id); return; }
                }
                // /:id
                String id = rest;
                if (!isValidId(id)) { WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid id\"}"); return; }
                if (method.equals("GET"))    { handleGet(exchange, id); return; }
                if (method.equals("PUT"))    { handleUpdate(exchange, id); return; }
                if (method.equals("DELETE")) { handleDelete(exchange, id, server); return; }
            }
            WebDashboard.respond(exchange, 404, "{\"error\":\"Not found\"}");
        } catch (Exception e) {
            LOGGER.error("[Morphos API] {}: {}", subRoute, e.getMessage(), e);
            WebDashboard.respond(exchange, 500, "{\"error\":\"Internal server error\"}");
        }
    }

    /**
     * Public (no-auth) image serve, used by:
     *  - the public store website (fetched from the browser)
     *  - the Minecraft client to display catalog images in the in-game Codex GUI
     * Path: /cheeseitems/api/morpho-image/<id>.png
     */
    public static void handlePublicImage(HttpExchange exchange, String id) throws IOException {
        if (!isValidId(id)) { WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid id\"}"); return; }
        WebDashboard.servePngWithRevalidation(exchange, MorphoCatalog.getInstance().getImagePath(id));
    }

    /**
     * Public (no-auth) thumbnail serve. Same audience as {@link #handlePublicImage} but
     * returns a ≤480px downscale (lossless PNG) — saves bandwidth on small renders like
     * the in-game Codex GUI (~32-64px) and the admin morpho table (64px row thumb).
     */
    public static void handlePublicThumb(HttpExchange exchange, String id) throws IOException {
        if (!isValidId(id)) { WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid id\"}"); return; }
        java.nio.file.Path original = MorphoCatalog.getInstance().getImagePath(id);
        java.nio.file.Path thumb = MorphoThumbnail.getOrCreate(original);
        WebDashboard.servePngWithRevalidation(exchange, thumb);
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    private static void handleList(HttpExchange exchange) throws IOException {
        JsonArray arr = new JsonArray();
        for (MorphoEntry e : MorphoCatalog.getInstance().getSorted()) arr.add(e.toJson());
        WebDashboard.respond(exchange, 200, arr.toString());
    }

    private static void handleGet(HttpExchange exchange, String id) throws IOException {
        MorphoEntry e = MorphoCatalog.getInstance().get(id);
        if (e == null) { WebDashboard.respond(exchange, 404, "{\"error\":\"Not found\"}"); return; }
        WebDashboard.respond(exchange, 200, e.toJson().toString());
    }

    private static void handleCreate(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject obj = body.isEmpty() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
        // Use the same merge logic as PUT so EVERY persisted field is captured —
        // commandsOnTransform/Revert, habitat flags, legendary, custom endurance.
        // Earlier hand-rolled if-chain silently dropped commands when duplicating.
        MorphoEntry e = new MorphoEntry(null);
        e.mergeFrom(obj);
        MorphoCatalog.getInstance().add(e);
        broadcastUpdate(e.getId(), "created");
        LOGGER.info("[Morphos API] Created morpho: {} ({})", e.getId(), e.getTitle());
        WebDashboard.respond(exchange, 201, e.toJson().toString());
    }

    private static void handleUpdate(HttpExchange exchange, String id) throws IOException {
        MorphoCatalog cat = MorphoCatalog.getInstance();
        if (!cat.exists(id)) { WebDashboard.respond(exchange, 404, "{\"error\":\"Not found\"}"); return; }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
        cat.update(id, obj);
        broadcastUpdate(id, "updated");
        WebDashboard.respond(exchange, 200, cat.get(id).toJson().toString());
    }

    private static void handleDelete(HttpExchange exchange, String id, MinecraftServer server) throws IOException {
        if (!MorphoCatalog.getInstance().remove(id)) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Not found\"}");
            return;
        }
        broadcastUpdate(id, "deleted");
        LOGGER.info("[Morphos API] Deleted morpho: {}", id);

        // Resync every online player so the orphan disappears from their codex live.
        // The sync logic in MorphoManager prunes catalog-less entries automatically.
        if (server != null) {
            try {
                WebDashboard.executeOnServerThread(server, () -> {
                    MorphoManager mm = CheeseItemsMod.INSTANCE.getMorphoManager();
                    if (mm == null) return;
                    for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                        mm.sendSync(p);
                    }
                });
            } catch (Exception ignored) { /* prune still runs on next manual sync */ }
        }
        WebDashboard.respond(exchange, 200, "{\"deleted\":true}");
    }

    private static void handleGlobalGet(HttpExchange exchange) throws IOException {
        WebDashboard.respond(exchange, 200,
            MorphoCatalog.getInstance().getGlobalEndurance().toJson().toString());
    }

    private static void handleGlobalPut(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject patch = JsonParser.parseString(body).getAsJsonObject();
        MorphoCatalog.getInstance().updateGlobalEndurance(patch);
        WebDashboard.respond(exchange, 200,
            MorphoCatalog.getInstance().getGlobalEndurance().toJson().toString());
    }

    private static void handleReorder(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
        if (!obj.has("ids")) { WebDashboard.respond(exchange, 400, "{\"error\":\"ids required\"}"); return; }
        List<String> ids = new ArrayList<>();
        for (var el : obj.getAsJsonArray("ids")) ids.add(el.getAsString());
        MorphoCatalog.getInstance().reorder(ids);
        broadcastUpdate(null, "reordered");
        WebDashboard.respond(exchange, 200, "{\"ok\":true}");
    }

    private static void handleGrant(HttpExchange exchange, MinecraftServer server) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
        String playerUuidStr = obj.has("playerUuid") ? obj.get("playerUuid").getAsString() : "";
        String playerName    = obj.has("playerName") ? obj.get("playerName").getAsString() : "";
        String morphoId      = obj.has("morphoId")   ? obj.get("morphoId").getAsString()   : "";
        if ((playerUuidStr.isEmpty() && playerName.isEmpty()) || morphoId.isEmpty()) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"playerUuid or playerName, and morphoId, required\"}");
            return;
        }
        MorphoEntry entry = MorphoCatalog.getInstance().get(morphoId);
        if (entry == null) { WebDashboard.respond(exchange, 404, "{\"error\":\"Morpho not found\"}"); return; }
        UUID uuid;
        if (!playerUuidStr.isEmpty()) {
            try { uuid = UUID.fromString(playerUuidStr); }
            catch (IllegalArgumentException ex) { WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid uuid\"}"); return; }
        } else {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(playerName);
            if (p != null) {
                uuid = p.getUuid();
            } else {
                // Try offline cache
                var profile = server.getUserCache() != null
                        ? server.getUserCache().findByName(playerName).orElse(null) : null;
                if (profile == null) {
                    WebDashboard.respond(exchange, 404, "{\"error\":\"Player not found: " + playerName + "\"}");
                    return;
                }
                uuid = profile.getId();
            }
        }

        MorphoCodex codex = CheeseItemsMod.INSTANCE.getMorphoCodex();
        if (codex.hasInCodex(uuid, morphoId)) {
            WebDashboard.respond(exchange, 409, "{\"error\":\"Already in codex\"}");
            return;
        }
        try {
            WebDashboard.executeOnServerThread(server, () -> {
                codex.addToCodex(uuid, new MorphoCodexEntry(
                        morphoId, entry.getSpecies(), entry.getForm(), entry.isShiny(),
                        System.currentTimeMillis()));
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
                if (p != null) {
                    MorphoManager mm = CheeseItemsMod.INSTANCE.getMorphoManager();
                    if (mm != null) mm.sendSync(p);
                }
            });
        } catch (Exception e) {
            WebDashboard.respond(exchange, 500, "{\"error\":\"Grant failed\"}");
            return;
        }
        LOGGER.info("[Morphos API] Granted morpho {} to {}", morphoId, uuid);
        WebDashboard.respond(exchange, 200, "{\"granted\":true}");
    }

    // ── Image handlers ───────────────────────────────────────────────────────

    private static void handleImageUpload(HttpExchange exchange, String id) throws IOException {
        if (!MorphoCatalog.getInstance().exists(id)) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Not found\"}");
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
        byte[] data = Base64.getDecoder().decode(raw);
        MorphoCatalog.getInstance().saveImage(id, data);
        broadcastUpdate(id, "image_updated");
        WebDashboard.respond(exchange, 200, "{\"uploaded\":true}");
    }

    private static void handleImageServe(HttpExchange exchange, String id) throws IOException {
        WebDashboard.servePngWithRevalidation(exchange, MorphoCatalog.getInstance().getImagePath(id));
    }

    private static void handleImageDelete(HttpExchange exchange, String id) throws IOException {
        MorphoCatalog.getInstance().deleteImage(id);
        broadcastUpdate(id, "image_deleted");
        WebDashboard.respond(exchange, 200, "{\"deleted\":true}");
    }

    // ── Utils ────────────────────────────────────────────────────────────────

    private static void broadcastUpdate(String id, String action) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "morpho_update");
        if (id != null) msg.addProperty("morphoId", id);
        msg.addProperty("action", action);
        DashboardRegistry.broadcast("morphos", msg.toString());
    }

    private static boolean isValidId(String id) {
        return id != null && !id.isEmpty() && !id.contains("..") && !id.contains("/")
                && !id.contains("\\") && !id.contains("%") && id.length() < 128;
    }
}
