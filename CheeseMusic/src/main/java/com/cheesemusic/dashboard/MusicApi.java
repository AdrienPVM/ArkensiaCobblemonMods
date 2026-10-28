package com.cheesemusic.dashboard;

import com.cheesedash.WebDashboard;
import com.cheesemusic.CheeseMusicConfig;
import com.cheesemusic.CheeseMusicMod;
import com.cheesemusic.MusicAssignment;
import com.cheesemusic.logic.CheeseGuardHook;
import com.cheesemusic.logic.SoundScanner;
import com.cheesemusic.logic.ZoneMusicTracker;
import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MusicApi {

    public static void handle(HttpExchange exchange, MinecraftServer server, String subRoute) throws IOException {
        String method = exchange.getRequestMethod();
        String path = subRoute.startsWith("/") ? subRoute : "/" + subRoute;

        CheeseMusicMod mod = CheeseMusicMod.getInstance();
        CheeseMusicConfig config = mod.getConfig();

        try {
            // ── Assignments CRUD ──────────────────────────────────────────────
            if (path.equals("/assignments") && method.equals("GET")) {
                JsonArray arr = new JsonArray();
                for (var entry : config.getAllAssignments().entrySet()) {
                    arr.add(entry.getValue().toJson());
                }
                WebDashboard.respond(exchange, 200, arr.toString());

            } else if (path.equals("/assignments") && method.equals("POST")) {
                WebDashboard.requireWrite(exchange);
                JsonObject body = readBody(exchange);
                MusicAssignment assignment = MusicAssignment.fromJson(body);
                if (assignment.getZoneId() == null || assignment.getZoneId().isBlank()) {
                    WebDashboard.respond(exchange, 400, "{\"error\":\"Missing zoneId\"}");
                    return;
                }
                config.putAssignment(assignment.getZoneId(), assignment);
                config.save();
                WebDashboard.respond(exchange, 200, assignment.toJson().toString());

            } else if (path.startsWith("/assignments/") && method.equals("PUT")) {
                WebDashboard.requireWrite(exchange);
                String zoneId = decodePath(path.substring("/assignments/".length()));
                MusicAssignment existing = config.getAssignment(zoneId);
                if (existing == null) {
                    WebDashboard.respond(exchange, 404, "{\"error\":\"Assignment not found\"}");
                    return;
                }
                JsonObject body = readBody(exchange);
                MusicAssignment updated = MusicAssignment.fromJson(body);
                config.putAssignment(zoneId, updated);
                config.save();
                WebDashboard.respond(exchange, 200, updated.toJson().toString());

            } else if (path.startsWith("/assignments/") && method.equals("DELETE")) {
                WebDashboard.requireWrite(exchange);
                String zoneId = decodePath(path.substring("/assignments/".length()));
                if (config.getAssignment(zoneId) == null) {
                    WebDashboard.respond(exchange, 404, "{\"error\":\"Assignment not found\"}");
                    return;
                }
                config.removeAssignment(zoneId);
                config.save();
                WebDashboard.respond(exchange, 200, "{\"ok\":true}");
            }

            // ── Tracks ────────────────────────────────────────────────────────
            else if (path.equals("/tracks") && method.equals("GET")) {
                SoundScanner scanner = mod.getSoundScanner();
                JsonArray arr = new JsonArray();
                if (scanner != null) {
                    for (String track : scanner.getAvailableTracks()) {
                        arr.add(track);
                    }
                }
                WebDashboard.respond(exchange, 200, arr.toString());
            }

            // POST /tracks/upload — upload .ogg file
            else if (path.equals("/tracks/upload") && method.equals("POST")) {
                WebDashboard.requireWrite(exchange);
                SoundScanner scanner = mod.getSoundScanner();
                if (scanner == null) {
                    WebDashboard.respond(exchange, 503, "{\"error\":\"SoundScanner not ready\"}");
                    return;
                }

                // Read filename from query param
                String query = exchange.getRequestURI().getQuery();
                String fileName = null;
                if (query != null) {
                    for (String param : query.split("&")) {
                        if (param.startsWith("name=")) {
                            fileName = URLDecoder.decode(param.substring(5), StandardCharsets.UTF_8);
                        }
                    }
                }
                if (fileName == null || !fileName.endsWith(".ogg") || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
                    WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid filename. Must be a .ogg file.\"}");
                    return;
                }

                byte[] data = exchange.getRequestBody().readAllBytes();
                if (data.length == 0) {
                    WebDashboard.respond(exchange, 400, "{\"error\":\"Empty file\"}");
                    return;
                }
                if (data.length > 50 * 1024 * 1024) { // 50MB max
                    WebDashboard.respond(exchange, 400, "{\"error\":\"File too large (max 50MB)\"}");
                    return;
                }

                String trackName = scanner.addTrack(fileName, data);
                CheeseMusicMod.LOGGER.info("[CheeseMusic] Track uploaded: {} -> {}", fileName, trackName);
                WebDashboard.respond(exchange, 200, "{\"ok\":true,\"track\":\"" + trackName + "\"}");
            }

            // DELETE /tracks/:name — delete .ogg file
            else if (path.startsWith("/tracks/") && method.equals("DELETE")) {
                WebDashboard.requireWrite(exchange);
                String trackName = decodePath(path.substring("/tracks/".length()));
                if (trackName.isEmpty() || trackName.equals("upload") || trackName.contains("..") || trackName.contains("/") || trackName.contains("\\")) {
                    WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid track name\"}");
                    return;
                }
                SoundScanner scanner = mod.getSoundScanner();
                if (scanner == null) {
                    WebDashboard.respond(exchange, 503, "{\"error\":\"SoundScanner not ready\"}");
                    return;
                }
                scanner.deleteTrack(trackName);
                CheeseMusicMod.LOGGER.info("[CheeseMusic] Track deleted: {}", trackName);
                WebDashboard.respond(exchange, 200, "{\"ok\":true}");
            }

            // ── Zones (from CheeseGuard) ──────────────────────────────────────
            else if (path.equals("/zones") && method.equals("GET")) {
                List<CheeseGuardHook.ZoneInfo> zones = CheeseGuardHook.getAllZones();
                JsonArray arr = new JsonArray();
                for (CheeseGuardHook.ZoneInfo zone : zones) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("id", zone.id());
                    obj.addProperty("name", zone.name());
                    obj.addProperty("priority", zone.priority());
                    JsonArray tags = new JsonArray();
                    zone.tags().forEach(tags::add);
                    obj.add("tags", tags);
                    arr.add(obj);
                }
                WebDashboard.respond(exchange, 200, arr.toString());
            }

            // ── Config ────────────────────────────────────────────────────────
            else if (path.equals("/config") && method.equals("GET")) {
                JsonObject obj = new JsonObject();
                obj.addProperty("enabled", config.isEnabled());
                obj.addProperty("defaultFadeInTicks", config.getDefaultFadeInTicks());
                obj.addProperty("defaultFadeOutTicks", config.getDefaultFadeOutTicks());
                obj.addProperty("checkIntervalTicks", config.getCheckIntervalTicks());
                obj.addProperty("defaultVolume", config.getDefaultVolume());
                WebDashboard.respond(exchange, 200, obj.toString());

            } else if (path.equals("/config") && method.equals("PUT")) {
                WebDashboard.requireWrite(exchange);
                JsonObject body = readBody(exchange);
                if (body.has("enabled")) config.setEnabled(body.get("enabled").getAsBoolean());
                if (body.has("defaultFadeInTicks")) config.setDefaultFadeInTicks(body.get("defaultFadeInTicks").getAsInt());
                if (body.has("defaultFadeOutTicks")) config.setDefaultFadeOutTicks(body.get("defaultFadeOutTicks").getAsInt());
                if (body.has("checkIntervalTicks")) config.setCheckIntervalTicks(body.get("checkIntervalTicks").getAsInt());
                if (body.has("defaultVolume")) config.setDefaultVolume(body.get("defaultVolume").getAsFloat());
                config.save();
                WebDashboard.respond(exchange, 200, "{\"ok\":true}");
            }

            // ── Status ────────────────────────────────────────────────────────
            else if (path.equals("/status") && method.equals("GET")) {
                ZoneMusicTracker tracker = mod.getTracker();
                JsonArray arr = new JsonArray();
                if (tracker != null) {
                    Map<UUID, String> active = tracker.getActiveZoneMusic();
                    for (var entry : active.entrySet()) {
                        JsonObject obj = new JsonObject();
                        obj.addProperty("playerUuid", entry.getKey().toString());
                        obj.addProperty("zoneId", entry.getValue());
                        var player = server.getPlayerManager().getPlayer(entry.getKey());
                        obj.addProperty("playerName", player != null ? player.getName().getString() : "?");
                        arr.add(obj);
                    }
                }
                WebDashboard.respond(exchange, 200, arr.toString());
            }

            else {
                WebDashboard.respond(exchange, 404, "{\"error\":\"Not found\"}");
            }
        } catch (Exception e) {
            CheeseMusicMod.LOGGER.error("[CheeseMusic] API error: {}", e.getMessage(), e);
            WebDashboard.respond(exchange, 500, "{\"error\":\"Internal error\"}");
        }
    }

    private static JsonObject readBody(HttpExchange exchange) throws Exception {
        try (var reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static String decodePath(String param) {
        return URLDecoder.decode(param, StandardCharsets.UTF_8);
    }
}
