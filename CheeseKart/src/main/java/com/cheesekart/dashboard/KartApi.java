package com.cheesekart.dashboard;

import com.cheesedash.WebDashboard;
import com.cheesekart.*;
import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class KartApi {

    public static void handle(HttpExchange exchange, MinecraftServer server, String subRoute) throws IOException {
        String method = exchange.getRequestMethod();
        String path = subRoute.startsWith("/") ? subRoute : "/" + subRoute;

        try {
            // ── Presets CRUD ──────────────────────────────────────────────────
            if (path.equals("/presets") && method.equals("GET")) {
                handleListPresets(exchange);
            } else if (path.equals("/presets") && method.equals("POST")) {
                WebDashboard.requireWrite(exchange);
                handleCreatePreset(exchange);
            } else if (path.startsWith("/presets/") && method.equals("GET")) {
                String id = path.substring("/presets/".length());
                handleGetPreset(exchange, id);
            } else if (path.startsWith("/presets/") && method.equals("PUT")) {
                WebDashboard.requireWrite(exchange);
                String id = path.substring("/presets/".length());
                handleUpdatePreset(exchange, id, server);
            } else if (path.startsWith("/presets/") && method.equals("DELETE")) {
                WebDashboard.requireWrite(exchange);
                String id = path.substring("/presets/".length());
                handleDeletePreset(exchange, id);
            }
            // ── Active sessions ───────────────────────────────────────────────
            else if (path.equals("/active") && method.equals("GET")) {
                handleListActive(exchange);
            } else if (path.equals("/start") && method.equals("POST")) {
                WebDashboard.requireWrite(exchange);
                handleStart(exchange, server);
            } else if (path.equals("/stop") && method.equals("POST")) {
                WebDashboard.requireWrite(exchange);
                handleStop(exchange, server);
            } else if (path.equals("/stopall") && method.equals("POST")) {
                WebDashboard.requireWrite(exchange);
                handleStopAll(exchange);
            } else {
                WebDashboard.respond(exchange, 404, "{\"error\":\"Unknown kart route: " + path + "\"}");
            }
        } catch (Exception e) {
            WebDashboard.respond(exchange, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    // ── Presets ───────────────────────────────────────────────────────────────

    private static void handleListPresets(HttpExchange exchange) throws IOException {
        JsonArray arr = new JsonArray();
        CheeseKartMod.INSTANCE.getConfig().getPresets().values().forEach(p -> arr.add(p.toJson()));
        WebDashboard.respond(exchange, 200, arr.toString());
    }

    private static void handleGetPreset(HttpExchange exchange, String id) throws IOException {
        KartPreset preset = CheeseKartMod.INSTANCE.getConfig().getPreset(id);
        if (preset == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Preset not found: " + escapeJson(id) + "\"}");
            return;
        }
        WebDashboard.respond(exchange, 200, preset.toJson().toString());
    }

    private static void handleCreatePreset(HttpExchange exchange) throws IOException {
        JsonObject body = readBody(exchange);
        if (!body.has("id") || !body.has("name")) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Missing id or name\"}");
            return;
        }
        String id = body.get("id").getAsString().trim();
        if (id.isEmpty() || !id.matches("[a-z0-9_-]+")) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid ID (lowercase alphanumeric, -, _ only)\"}");
            return;
        }
        if (CheeseKartMod.INSTANCE.getConfig().getPreset(id) != null) {
            WebDashboard.respond(exchange, 409, "{\"error\":\"Preset already exists: " + escapeJson(id) + "\"}");
            return;
        }
        KartPreset preset = KartPreset.fromJson(body);
        preset.setId(id);
        CheeseKartMod.INSTANCE.getConfig().putPreset(preset);
        WebDashboard.respond(exchange, 201, preset.toJson().toString());
    }

    private static void handleUpdatePreset(HttpExchange exchange, String id, MinecraftServer server) throws Exception {
        KartPreset preset = CheeseKartMod.INSTANCE.getConfig().getPreset(id);
        if (preset == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Preset not found: " + escapeJson(id) + "\"}");
            return;
        }
        JsonObject body = readBody(exchange);
        preset.mergeFrom(body);
        CheeseKartMod.INSTANCE.getConfig().putPreset(preset);

        // Re-apply to all active sessions using this preset (real-time tuning)
        WebDashboard.executeOnServerThread(server, () -> {
            CheeseKartMod.INSTANCE.getKartManager().reapplyPreset(id);
        });

        WebDashboard.respond(exchange, 200, preset.toJson().toString());
    }

    private static void handleDeletePreset(HttpExchange exchange, String id) throws IOException {
        if (!CheeseKartMod.INSTANCE.getConfig().removePreset(id)) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Preset not found: " + escapeJson(id) + "\"}");
            return;
        }
        WebDashboard.respond(exchange, 200, "{\"deleted\":true}");
    }

    // ── Active sessions ──────────────────────────────────────────────────────

    private static void handleListActive(HttpExchange exchange) throws IOException {
        JsonArray arr = new JsonArray();
        CheeseKartMod.INSTANCE.getKartManager().getSessions().forEach((uuid, session) -> {
            JsonObject o = new JsonObject();
            o.addProperty("playerName", session.playerName());
            o.addProperty("playerUuid", uuid.toString());
            o.addProperty("presetId", session.presetId());
            o.addProperty("species", session.species());
            o.addProperty("locked", session.locked());
            o.addProperty("durationMs", session.durationMs());
            o.addProperty("durationFormatted", formatDuration(session.durationMs()));
            arr.add(o);
        });
        WebDashboard.respond(exchange, 200, arr.toString());
    }

    private static void handleStart(HttpExchange exchange, MinecraftServer server) throws Exception {
        JsonObject body = readBody(exchange);
        if (!body.has("playerName") || !body.has("presetId")) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Missing playerName or presetId\"}");
            return;
        }
        String playerName = body.get("playerName").getAsString();
        String presetId = body.get("presetId").getAsString();
        String species = body.has("species") ? body.get("species").getAsString() : null;

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Player not found: " + escapeJson(playerName) + "\"}");
            return;
        }
        KartPreset preset = CheeseKartMod.INSTANCE.getConfig().getPreset(presetId);
        if (preset == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Preset not found: " + escapeJson(presetId) + "\"}");
            return;
        }

        // Must run on MC thread
        WebDashboard.executeOnServerThread(server, () -> {
            CheeseKartMod.INSTANCE.getKartManager().startKart(player, preset, species);
        });
        WebDashboard.respond(exchange, 200, "{\"started\":true}");
    }

    private static void handleStop(HttpExchange exchange, MinecraftServer server) throws Exception {
        JsonObject body = readBody(exchange);
        if (!body.has("playerName")) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Missing playerName\"}");
            return;
        }
        String playerName = body.get("playerName").getAsString();
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Player not found: " + escapeJson(playerName) + "\"}");
            return;
        }
        WebDashboard.executeOnServerThread(server, () -> {
            CheeseKartMod.INSTANCE.getKartManager().stopKart(player);
        });
        WebDashboard.respond(exchange, 200, "{\"stopped\":true}");
    }

    private static void handleStopAll(HttpExchange exchange) throws Exception {
        WebDashboard.executeOnServerThread(
                CheeseKartMod.INSTANCE.getKartManager().getServer(), () -> {
            CheeseKartMod.INSTANCE.getKartManager().stopAll();
        });
        WebDashboard.respond(exchange, 200, "{\"stoppedAll\":true}");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static JsonObject readBody(HttpExchange exchange) throws IOException {
        String json = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static String escapeJson(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String formatDuration(long ms) {
        long sec = TimeUnit.MILLISECONDS.toSeconds(ms);
        if (sec < 60) return sec + "s";
        long min = sec / 60;
        return min + "m " + (sec % 60) + "s";
    }
}
