package com.cheeseguard.dashboard;

import com.cheesedash.WebDashboard;
import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.RaceTrackConfig;
import com.cheeseguard.logic.RaceTrackScanner;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Dashboard API for race track scanning and configuration.
 *
 * Routes:
 *   POST /races/scan                — scan a zone for block markers
 *   POST /races/heightmap           — top-down block color map (PNG base64)
 *   GET  /races/:eventId/track      — get track config
 *   PUT  /races/:eventId/track      — update track config (settings + checkpoint orders)
 *   DELETE /races/:eventId/track    — delete track config
 */
public class RacesApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseGuard");

    public static void handle(HttpExchange exchange, CheeseGuardMod mod, String subRoute) throws IOException {
        String method = exchange.getRequestMethod();
        MinecraftServer server = mod.getServer();

        // ── POST /races/scan ──────────────────────────────────────────────
        if (subRoute.equals("/races/scan") && method.equals("POST")) {
            handleScan(exchange, server);
            return;
        }

        // ── POST /races/heightmap ────────────────────────────────────────
        if (subRoute.equals("/races/heightmap") && method.equals("POST")) {
            handleHeightmap(exchange, server);
            return;
        }

        // ── /races/:eventId/track ─────────────────────────────────────────
        if (subRoute.matches("/races/[^/]+/track")) {
            String eventId = subRoute.split("/")[2];
            if (!isValidId(eventId)) {
                WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid event ID\"}");
                return;
            }

            switch (method) {
                case "GET" -> handleGetTrack(exchange, eventId);
                case "PUT" -> handlePutTrack(exchange, eventId);
                case "DELETE" -> handleDeleteTrack(exchange, eventId);
                default -> WebDashboard.respond(exchange, 405, "{\"error\":\"Method not allowed\"}");
            }
            return;
        }

        WebDashboard.respond(exchange, 404, "{\"error\":\"Race route not found: " + subRoute + "\"}");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POST /races/scan
    // Body: { "zoneId": "race_arena", "blockMappings": { "minecraft:blue_wool": "checkpoint", ... } }
    // Optional: { "eventId": "..." } to auto-save to config
    // ═══════════════════════════════════════════════════════════════════════════

    private static void handleScan(HttpExchange exchange, MinecraftServer server) throws IOException {
        if (!WebDashboard.requireWrite(exchange)) return;

        String body = WebDashboard.readBody(exchange);
        JsonObject data = JsonParser.parseString(body).getAsJsonObject();

        String zoneId = data.has("zoneId") ? data.get("zoneId").getAsString() : null;
        if (zoneId == null || zoneId.isEmpty()) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Missing zoneId\"}");
            return;
        }
        if (!isValidId(zoneId)) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid zoneId\"}");
            return;
        }

        // Parse block mappings
        Map<String, String> blockMappings = new LinkedHashMap<>();
        if (data.has("blockMappings")) {
            data.getAsJsonObject("blockMappings").entrySet()
                .forEach(e -> blockMappings.put(e.getKey(), e.getValue().getAsString()));
        }
        if (blockMappings.isEmpty()) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"blockMappings is empty\"}");
            return;
        }

        // Validate at least one checkpoint mapping exists
        boolean hasCheckpoint = blockMappings.containsValue("checkpoint");
        if (!hasCheckpoint) {
            WebDashboard.respond(exchange, 400,
                "{\"error\":\"blockMappings must contain at least one 'checkpoint' mapping\"}");
            return;
        }

        // Run scan on server thread (with extended timeout for large zones)
        CompletableFuture<RaceTrackScanner.ScanResult> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                RaceTrackScanner.ScanResult result = RaceTrackScanner.scan(server, zoneId, blockMappings);
                future.complete(result);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            RaceTrackScanner.ScanResult result = future.get(30, TimeUnit.SECONDS);

            // Auto-save to config if eventId provided
            String eventId = data.has("eventId") ? data.get("eventId").getAsString() : null;
            if (eventId != null && !eventId.isEmpty() && isValidId(eventId)) {
                RaceTrackConfig config = RaceTrackConfig.load(eventId);
                if (config == null) {
                    config = new RaceTrackConfig(eventId);
                }
                config.setZoneId(zoneId);
                config.setBlockMappings(blockMappings);
                RaceTrackScanner.applyToConfig(config, result);
                config.save();
            }

            WebDashboard.respondJson(exchange, result.toJson());

        } catch (Exception e) {
            String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            LOGGER.error("[Dashboard] Race scan failed: {}", msg, e);
            WebDashboard.respond(exchange, 500,
                "{\"error\":\"Scan failed: " + (msg != null ? msg.replace("\"", "'") : "unknown") + "\"}");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POST /races/heightmap
    // Body: { "zoneId": "race_arena" }
    // Returns: { width, height, minX, minZ, image: "data:image/png;base64,..." }
    // ═══════════════════════════════════════════════════════════════════════════

    private static void handleHeightmap(HttpExchange exchange, MinecraftServer server) throws IOException {
        String body = WebDashboard.readBody(exchange);
        JsonObject data = JsonParser.parseString(body).getAsJsonObject();

        String zoneId = data.has("zoneId") ? data.get("zoneId").getAsString() : null;
        if (zoneId == null || zoneId.isEmpty()) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Missing zoneId\"}");
            return;
        }
        if (!isValidId(zoneId)) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid zoneId\"}");
            return;
        }

        CompletableFuture<RaceTrackScanner.HeightmapResult> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                RaceTrackScanner.HeightmapResult result = RaceTrackScanner.scanHeightmap(server, zoneId);
                future.complete(result);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            RaceTrackScanner.HeightmapResult result = future.get(30, TimeUnit.SECONDS);
            WebDashboard.respondJson(exchange, result.toJson());
        } catch (Exception e) {
            String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            LOGGER.error("[Dashboard] Heightmap scan failed: {}", msg, e);
            WebDashboard.respond(exchange, 500,
                "{\"error\":\"Heightmap failed: " + (msg != null ? msg.replace("\"", "'") : "unknown") + "\"}");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GET /races/:eventId/track
    // ═══════════════════════════════════════════════════════════════════════════

    private static void handleGetTrack(HttpExchange exchange, String eventId) throws IOException {
        RaceTrackConfig config = RaceTrackConfig.load(eventId);
        if (config == null) {
            // Return empty config (not an error — track just hasn't been configured yet)
            RaceTrackConfig empty = new RaceTrackConfig(eventId);
            WebDashboard.respondJson(exchange, empty.toJson());
            return;
        }
        WebDashboard.respondJson(exchange, config.toJson());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUT /races/:eventId/track
    // Body: partial config updates (settings, checkpoint orders, powerup type configs)
    // ═══════════════════════════════════════════════════════════════════════════

    private static void handlePutTrack(HttpExchange exchange, String eventId) throws IOException {
        if (!WebDashboard.requireWrite(exchange)) return;

        String body = WebDashboard.readBody(exchange);
        JsonObject data = JsonParser.parseString(body).getAsJsonObject();

        RaceTrackConfig config = RaceTrackConfig.load(eventId);
        if (config == null) {
            config = new RaceTrackConfig(eventId);
        }

        config.mergeSettings(data);
        config.save();

        WebDashboard.respondJson(exchange, config.toJson());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DELETE /races/:eventId/track
    // ═══════════════════════════════════════════════════════════════════════════

    private static void handleDeleteTrack(HttpExchange exchange, String eventId) throws IOException {
        if (!WebDashboard.requireWrite(exchange)) return;

        RaceTrackConfig.delete(eventId);
        WebDashboard.respond(exchange, 200, "{\"ok\":true}");
    }

    // ═══════════════════════════════════════════════════════════════════════════

    private static boolean isValidId(String id) {
        return id != null && !id.isEmpty() && !id.contains("..")
            && !id.contains("/") && !id.contains("\\")
            && !id.contains("%") && id.length() < 128;
    }
}
