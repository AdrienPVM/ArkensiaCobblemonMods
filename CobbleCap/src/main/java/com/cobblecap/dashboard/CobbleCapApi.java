package com.cobblecap.dashboard;

import com.cheesedash.WebDashboard;
import com.cobblecap.CobbleCapMod;
import com.cobblecap.data.*;
import com.cobblecap.logic.CobbleCapWebhook;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class CobbleCapApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");

    public static void handle(HttpExchange exchange, MinecraftServer server, String subRoute) throws IOException {
        try {
            String method = exchange.getRequestMethod();

            if (subRoute.startsWith("/players")) {
                handlePlayers(exchange, server, method, subRoute.substring("/players".length()));
            } else if (subRoute.startsWith("/champions")) {
                handleChampions(exchange, method);
            } else if (subRoute.equals("/config")) {
                handleConfig(exchange, method);
            } else if (subRoute.equals("/fireworks/test")) {
                if (!"POST".equals(method)) {
                    WebDashboard.respond(exchange, 405, "{\"error\":\"Method not allowed\"}");
                } else if (!WebDashboard.requireWrite(exchange)) {
                    // requireWrite already sent the 403 response
                } else {
                    handleFireworksTest(exchange, server);
                }
            } else if (subRoute.equals("/stats")) {
                if ("GET".equals(method)) handleStats(exchange);
                else WebDashboard.respond(exchange, 405, "{\"error\":\"Method not allowed\"}");
            } else {
                WebDashboard.respond(exchange, 404, "{\"error\":\"Not found\"}");
            }
        } catch (Exception e) {
            LOGGER.error("[CobbleCap API] Error handling {}: {}", subRoute, e.getMessage(), e);
            WebDashboard.respond(exchange, 500, "{\"error\":\"Internal server error\"}");
        }
    }

    // ---- Players ----

    private static void handlePlayers(HttpExchange exchange, MinecraftServer server, String method, String rest) throws IOException {
        if (!method.equals("GET") && !method.equals("POST")) {
            WebDashboard.respond(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        // GET /players — list all
        if (rest.isEmpty() || rest.equals("/")) {
            if (!"GET".equals(method)) {
                WebDashboard.respond(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            CapStorage storage = CobbleCapMod.INSTANCE.getCapStorage();
            JsonArray arr = new JsonArray();
            for (PlayerCapData pcd : storage.getAllPlayers()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("uuid", pcd.getUuid().toString());
                obj.addProperty("playerName", pcd.getPlayerName());
                obj.addProperty("capLevel", pcd.getCapLevel());
                obj.addProperty("championsBeatenCount", pcd.getChampionsBeaten().size());
                obj.addProperty("lastCapChange", pcd.getLastCapChange());
                obj.addProperty("expLocked", pcd.isExpLocked());
                arr.add(obj);
            }
            WebDashboard.respond(exchange, 200, arr.toString());
            return;
        }

        // /players/:uuid/cap or /players/:uuid/explock or /players/:uuid
        String sub = rest.substring(1); // strip leading /
        if (sub.endsWith("/explock")) {
            // POST /players/:uuid/explock — toggle explock
            String uuidStr = sub.substring(0, sub.lastIndexOf("/"));
            UUID uuid = parseUuid(uuidStr);
            if (uuid == null) {
                WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid UUID\"}");
                return;
            }
            if (!"POST".equals(method)) {
                WebDashboard.respond(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            handleToggleExpLock(exchange, server, uuid);
            return;
        }
        if (sub.endsWith("/cap")) {
            // POST /players/:uuid/cap
            String uuidStr = sub.substring(0, sub.lastIndexOf("/"));
            UUID uuid = parseUuid(uuidStr);
            if (uuid == null) {
                WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid UUID\"}");
                return;
            }
            if (!"POST".equals(method)) {
                WebDashboard.respond(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            handleSetCap(exchange, server, uuid);
        } else {
            // GET /players/:uuid
            if (!"GET".equals(method)) {
                WebDashboard.respond(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            UUID uuid = parseUuid(sub);
            if (uuid == null) {
                WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid UUID\"}");
                return;
            }
            handlePlayerDetail(exchange, uuid);
        }
    }

    private static void handlePlayerDetail(HttpExchange exchange, UUID uuid) throws IOException {
        CapStorage storage = CobbleCapMod.INSTANCE.getCapStorage();
        Optional<PlayerCapData> opt = storage.getCapData(uuid);
        if (opt.isEmpty()) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Player not found\"}");
            return;
        }
        PlayerCapData data = opt.get();
        JsonObject obj = new JsonObject();
        obj.addProperty("uuid", data.getUuid().toString());
        obj.addProperty("playerName", data.getPlayerName());
        obj.addProperty("capLevel", data.getCapLevel());
        JsonArray beaten = new JsonArray();
        for (String s : data.getChampionsBeaten()) beaten.add(s);
        obj.add("championsBeaten", beaten);
        obj.addProperty("lastCapChange", data.getLastCapChange());
        obj.addProperty("hudVisible", data.isHudVisible());
        obj.addProperty("hudPosition", data.getHudPosition());
        obj.addProperty("expLocked", data.isExpLocked());
        WebDashboard.respond(exchange, 200, obj.toString());
    }

    private static void handleSetCap(HttpExchange exchange, MinecraftServer server, UUID uuid) throws IOException {
        WebDashboard.requireWrite(exchange);
        JsonObject body = readBody(exchange);
        if (body == null || !body.has("capLevel")) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Missing capLevel\"}");
            return;
        }
        int capLevel = body.get("capLevel").getAsInt();
        if (capLevel < 1 || capLevel > 100) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"capLevel must be between 1 and 100\"}");
            return;
        }
        CapStorage storage = CobbleCapMod.INSTANCE.getCapStorage();
        try {
            WebDashboard.executeOnServerThread(server, () -> {
                storage.setCapLevel(uuid, capLevel);
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
                if (player != null) CobbleCapMod.syncToClient(player);
            });
        } catch (Exception e) {
            LOGGER.error("[CobbleCap API] Failed to set cap for {}: {}", uuid, e.getMessage(), e);
            WebDashboard.respond(exchange, 500, "{\"error\":\"Failed to set cap level\"}");
            return;
        }
        JsonObject resp = new JsonObject();
        resp.addProperty("updated", true);
        resp.addProperty("capLevel", capLevel);
        WebDashboard.respond(exchange, 200, resp.toString());
    }

    private static void handleToggleExpLock(HttpExchange exchange, MinecraftServer server, UUID uuid) throws IOException {
        WebDashboard.requireWrite(exchange);
        CapStorage storage = CobbleCapMod.INSTANCE.getCapStorage();
        Optional<PlayerCapData> opt = storage.getCapData(uuid);
        if (opt.isEmpty()) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Player not found\"}");
            return;
        }
        final boolean[] newState = new boolean[1];
        try {
            WebDashboard.executeOnServerThread(server, () -> {
                PlayerCapData data = opt.get();
                newState[0] = !data.isExpLocked();
                data.setExpLocked(newState[0]);
                storage.saveAsync();
            });
        } catch (Exception e) {
            WebDashboard.respond(exchange, 500, "{\"error\":\"Failed to toggle explock\"}");
            return;
        }
        JsonObject resp = new JsonObject();
        resp.addProperty("uuid", uuid.toString());
        resp.addProperty("expLocked", newState[0]);
        WebDashboard.respond(exchange, 200, resp.toString());
    }

    // ---- Champions ----

    private static void handleChampions(HttpExchange exchange, String method) throws IOException {
        ChampionConfig cfg = CobbleCapMod.INSTANCE.getChampionConfig();
        if ("GET".equals(method)) {
            JsonArray arr = new JsonArray();
            for (ChampionEntry entry : cfg.getChampions()) {
                arr.add(entry.toJson());
            }
            WebDashboard.respond(exchange, 200, arr.toString());
        } else if ("PUT".equals(method)) {
            WebDashboard.requireWrite(exchange);
            JsonArray bodyArr = readBodyArray(exchange);
            if (bodyArr == null) {
                WebDashboard.respond(exchange, 400, "{\"error\":\"Expected JSON array\"}");
                return;
            }
            List<ChampionEntry> list = new ArrayList<>();
            for (JsonElement el : bodyArr) {
                list.add(ChampionEntry.fromJson(el.getAsJsonObject()));
            }
            cfg.setChampions(list);
            JsonObject resp = new JsonObject();
            resp.addProperty("saved", true);
            resp.addProperty("count", list.size());
            WebDashboard.respond(exchange, 200, resp.toString());
        } else {
            WebDashboard.respond(exchange, 405, "{\"error\":\"Method not allowed\"}");
        }
    }

    // ---- Config ----

    private static void handleConfig(HttpExchange exchange, String method) throws IOException {
        CapConfig cfg = CobbleCapMod.INSTANCE.getCapConfig();
        if ("GET".equals(method)) {
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", cfg.isEnabled());
            obj.addProperty("startingCap", cfg.getStartingCap());
            obj.addProperty("discordWebhookUrl", cfg.getDiscordWebhookUrl());
            JsonArray bannedArr = new JsonArray();
            for (String s : cfg.getBannedSpecies()) bannedArr.add(s);
            obj.add("bannedSpecies", bannedArr);
            // Fireworks celebration config (new ranked champion)
            obj.addProperty("fireworksEnabled",     cfg.isFireworksEnabled());
            obj.addProperty("fireworksZoneId",      cfg.getFireworksZoneId());
            obj.addProperty("fireworksCount",       cfg.getFireworksCount());
            obj.addProperty("fireworksIntervalMs",  cfg.getFireworksIntervalMs());
            obj.addProperty("fireworksDurationSec", cfg.getFireworksDurationSec());
            // Available CheeseGuard zones for the dropdown (empty if CG absent).
            JsonArray zonesArr = new JsonArray();
            for (String id : com.cobblecap.logic.FireworksManager.listAvailableZones()) zonesArr.add(id);
            obj.add("availableZones", zonesArr);
            WebDashboard.respond(exchange, 200, obj.toString());
        } else if ("PUT".equals(method)) {
            WebDashboard.requireWrite(exchange);
            JsonObject body = readBody(exchange);
            if (body == null) {
                WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid JSON body\"}");
                return;
            }
            if (body.has("enabled")) {
                cfg.setEnabled(body.get("enabled").getAsBoolean());
            }
            if (body.has("discordWebhookUrl")) {
                String url = body.get("discordWebhookUrl").getAsString();
                cfg.setDiscordWebhookUrl(url);
                CobbleCapWebhook.setUrl(url);
            }
            if (body.has("bannedSpecies") && body.get("bannedSpecies").isJsonArray()) {
                List<String> species = new ArrayList<>();
                for (JsonElement el : body.getAsJsonArray("bannedSpecies")) {
                    species.add(el.getAsString());
                }
                cfg.setBannedSpecies(species);
            }
            if (body.has("fireworksEnabled"))    cfg.setFireworksEnabled(body.get("fireworksEnabled").getAsBoolean());
            if (body.has("fireworksZoneId"))     cfg.setFireworksZoneId(body.get("fireworksZoneId").getAsString());
            if (body.has("fireworksCount"))      cfg.setFireworksCount(body.get("fireworksCount").getAsInt());
            if (body.has("fireworksIntervalMs")) cfg.setFireworksIntervalMs(body.get("fireworksIntervalMs").getAsInt());
            if (body.has("fireworksDurationSec"))cfg.setFireworksDurationSec(body.get("fireworksDurationSec").getAsInt());
            WebDashboard.respond(exchange, 200, "{\"updated\":true}");
        } else {
            WebDashboard.respond(exchange, 405, "{\"error\":\"Method not allowed\"}");
        }
    }

    // ---- Fireworks test ----

    /**
     * Trigger an immediate fireworks show using the currently-saved config.
     * Bypasses the {@code fireworksEnabled} flag so admins can validate the
     * zone + visuals before turning the feature on for real champion events.
     * Requires the configured {@code fireworksZoneId} to resolve via CheeseGuard.
     */
    private static void handleFireworksTest(HttpExchange exchange, MinecraftServer server) throws IOException {
        var cfg = CobbleCapMod.INSTANCE.getCapConfig();
        if (cfg.getFireworksZoneId() == null || cfg.getFireworksZoneId().isBlank()) {
            WebDashboard.respond(exchange, 400,
                    "{\"error\":\"No zone configured — set fireworksZoneId first\"}");
            return;
        }
        try {
            WebDashboard.executeOnServerThread(server,
                    () -> com.cobblecap.logic.FireworksManager.triggerTestShow(server));
        } catch (Exception e) {
            WebDashboard.respond(exchange, 500,
                    "{\"error\":\"Failed to dispatch on server thread\"}");
            return;
        }
        JsonObject reply = new JsonObject();
        reply.addProperty("started", true);
        reply.addProperty("zoneId", cfg.getFireworksZoneId());
        reply.addProperty("count", cfg.getFireworksCount());
        reply.addProperty("intervalMs", cfg.getFireworksIntervalMs());
        reply.addProperty("durationSec", cfg.getFireworksDurationSec());
        WebDashboard.respond(exchange, 200, reply.toString());
    }

    // ---- Stats ----

    private static void handleStats(HttpExchange exchange) throws IOException {
        CapStorage storage = CobbleCapMod.INSTANCE.getCapStorage();
        TreeMap<Integer, Integer> tiers = new TreeMap<>();
        int total = 0;
        for (PlayerCapData pcd : storage.getAllPlayers()) {
            tiers.merge(pcd.getCapLevel(), 1, Integer::sum);
            total++;
        }
        JsonArray tiersArr = new JsonArray();
        for (Map.Entry<Integer, Integer> entry : tiers.entrySet()) {
            JsonObject tier = new JsonObject();
            tier.addProperty("cap", entry.getKey());
            tier.addProperty("count", entry.getValue());
            tiersArr.add(tier);
        }
        JsonObject resp = new JsonObject();
        resp.add("tiers", tiersArr);
        resp.addProperty("totalPlayers", total);
        WebDashboard.respond(exchange, 200, resp.toString());
    }

    // ---- Helpers ----

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static JsonObject readBody(HttpExchange exchange) {
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonArray readBodyArray(HttpExchange exchange) {
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonArray();
        } catch (Exception e) {
            return null;
        }
    }
}
