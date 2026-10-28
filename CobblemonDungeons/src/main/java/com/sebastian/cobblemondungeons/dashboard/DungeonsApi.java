package com.sebastian.cobblemondungeons.dashboard;

import com.cheesedash.WebDashboard;
import com.google.gson.*;
import com.sebastian.cobblemondungeons.config.*;
import com.sebastian.cobblemondungeons.dungeon.*;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * /api/v1/dungeons/* — REST API for CobblemonDungeons dashboard.
 */
public class DungeonsApi {

    public static void handle(HttpExchange ex, MinecraftServer server, String route) throws IOException {
        String method = ex.getRequestMethod();
        String query = ex.getRequestURI().getQuery();

        // GET /configs — list all dungeon configs
        if (route.equals("/configs") && method.equals("GET")) {
            handleListConfigs(ex);
            return;
        }

        // POST /configs — create new dungeon
        if (route.equals("/configs") && method.equals("POST")) {
            handleCreateConfig(ex, server);
            return;
        }

        // POST /configs/reload — hot-reload all configs
        if (route.equals("/configs/reload") && method.equals("POST")) {
            handleReloadConfigs(ex, server);
            return;
        }

        // GET /tickets
        if (route.equals("/tickets") && method.equals("GET")) {
            WebDashboard.respond(ex, 200, ConfigLoader.getTicketConfigJson());
            return;
        }

        // PUT /tickets
        if (route.equals("/tickets") && method.equals("PUT")) {
            handleUpdateTickets(ex);
            return;
        }

        // GET /trainers
        if (route.equals("/trainers") && method.equals("GET")) {
            handleListTrainers(ex);
            return;
        }

        // GET /active — all active dungeon runs
        if (route.equals("/active") && method.equals("GET")) {
            handleListActive(ex, server);
            return;
        }

        // POST /active/fail — force-fail a run
        if (route.equals("/active/fail") && method.equals("POST")) {
            handleForceFail(ex, server);
            return;
        }

        // POST /active/leave — force-leave a player
        if (route.equals("/active/leave") && method.equals("POST")) {
            handleForceLeave(ex, server);
            return;
        }

        // GET /stats
        if (route.equals("/stats") && method.equals("GET")) {
            handleStats(ex);
            return;
        }

        // GET /stats/history?n=50
        if (route.equals("/stats/history") && method.equals("GET")) {
            handleHistory(ex, query);
            return;
        }

        // GET /leaderboard — full leaderboard
        if (route.equals("/leaderboard") && method.equals("GET")) {
            handleLeaderboard(ex, null);
            return;
        }

        // GET /leaderboard/:dungeonId
        if (route.startsWith("/leaderboard/") && method.equals("GET")) {
            String id = URLDecoder.decode(route.substring(13), StandardCharsets.UTF_8);
            handleLeaderboard(ex, id);
            return;
        }

        // GET /config — main config (password masked)
        if (route.equals("/config") && method.equals("GET")) {
            handleGetMainConfig(ex);
            return;
        }

        // Routes with config ID: /configs/:id
        if (route.startsWith("/configs/")) {
            String rest = route.substring(9); // after "/configs/"
            if (rest.equals("reload")) {
                // already handled above
                WebDashboard.respond(ex, 404, "{\"error\":\"Use POST /configs/reload\"}");
                return;
            }
            String configId = URLDecoder.decode(rest, StandardCharsets.UTF_8);
            if (!isValidId(configId)) {
                WebDashboard.respond(ex, 400, "{\"error\":\"Invalid config ID\"}");
                return;
            }
            handleConfigRoutes(ex, server, configId, method);
            return;
        }

        // Routes with trainer ID: /trainers/:id
        if (route.startsWith("/trainers/")) {
            String trainerId = URLDecoder.decode(route.substring(10), StandardCharsets.UTF_8);
            if (!isValidId(trainerId)) {
                WebDashboard.respond(ex, 400, "{\"error\":\"Invalid trainer ID\"}");
                return;
            }
            handleTrainerRoutes(ex, trainerId, method);
            return;
        }

        WebDashboard.respond(ex, 404, "{\"error\":\"Unknown dungeons route: " + escapeJson(route) + "\"}");
    }

    // ── Config routes ───────────────────────────────────────────────────────────

    private static void handleListConfigs(HttpExchange ex) throws IOException {
        JsonArray arr = new JsonArray();
        for (DungeonConfig config : DungeonManager.getAllDungeonConfigs()) {
            arr.add(buildConfigSummary(config));
        }
        WebDashboard.respondJson(ex, arr);
    }

    private static void handleCreateConfig(HttpExchange ex, MinecraftServer server) throws IOException {
        String body = WebDashboard.readBody(ex);
        JsonObject data = parseJson(body);
        String id = data.has("id") ? data.get("id").getAsString().trim() : "";
        if (id.isEmpty()) {
            WebDashboard.respond(ex, 400, "{\"error\":\"id is required\"}");
            return;
        }
        if (DungeonManager.getDungeonConfig(id) != null) {
            WebDashboard.respond(ex, 409, "{\"error\":\"Dungeon ID already exists: " + escapeJson(id) + "\"}");
            return;
        }
        try {
            WebDashboard.executeOnServerThread(server, () -> {
                DungeonConfig config = new DungeonConfig();
                config.general.id = id;
                config.general.name = data.has("name") ? data.get("name").getAsString() : id;
                config.networking.teleportMode = "SAME_SERVER";
                DungeonManager.registerDungeon(config);
                ConfigLoader.saveDungeonConfig(config);
            });
            DungeonConfig created = DungeonManager.getDungeonConfig(id);
            WebDashboard.respondJson(ex, buildConfigDetail(created));
        } catch (Exception e) {
            WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private static void handleReloadConfigs(HttpExchange ex, MinecraftServer server) throws IOException {
        try {
            WebDashboard.executeOnServerThread(server, ConfigLoader::load);
            WebDashboard.respond(ex, 200, "{\"reloaded\":true}");
        } catch (Exception e) {
            WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private static void handleConfigRoutes(HttpExchange ex, MinecraftServer server, String configId, String method) throws IOException {
        DungeonConfig config = DungeonManager.getDungeonConfig(configId);

        // GET /configs/:id
        if (method.equals("GET")) {
            if (config == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Dungeon not found\"}"); return; }
            WebDashboard.respondJson(ex, buildConfigDetail(config));
            return;
        }

        // PUT /configs/:id — hot-write config JSON
        if (method.equals("PUT")) {
            if (config == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Dungeon not found\"}"); return; }
            String body = WebDashboard.readBody(ex);
            try {
                Gson gson = ConfigLoader.getGson();
                DungeonConfig updated = gson.fromJson(body, DungeonConfig.class);
                if (updated == null) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"Invalid config JSON\"}");
                    return;
                }
                // Preserve the original ID
                updated.general.id = configId;
                WebDashboard.executeOnServerThread(server, () -> {
                    DungeonManager.registerDungeon(updated);
                    ConfigLoader.saveDungeonConfig(updated);
                });
                WebDashboard.respondJson(ex, buildConfigDetail(updated));
            } catch (Exception e) {
                WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
            return;
        }

        // DELETE /configs/:id
        if (method.equals("DELETE")) {
            if (config == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Dungeon not found\"}"); return; }
            try {
                WebDashboard.executeOnServerThread(server, () -> {
                    ConfigLoader.deleteDungeonConfig(configId);
                    // Reload to clean up the registry
                    ConfigLoader.load();
                });
                WebDashboard.respond(ex, 200, "{\"deleted\":true}");
            } catch (Exception e) {
                WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
            return;
        }

        WebDashboard.respond(ex, 405, "{\"error\":\"Method not allowed\"}");
    }

    // ── Tickets ─────────────────────────────────────────────────────────────────

    private static void handleUpdateTickets(HttpExchange ex) throws IOException {
        String body = WebDashboard.readBody(ex);
        try {
            ConfigLoader.updateTicketConfig(body);
            ConfigLoader.saveTicketConfigPublic();
            WebDashboard.respond(ex, 200, ConfigLoader.getTicketConfigJson());
        } catch (Exception e) {
            WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    // ── Trainers ────────────────────────────────────────────────────────────────

    private static void handleListTrainers(HttpExchange ex) throws IOException {
        Path trainersDir = ConfigLoader.getTrainersDir();
        JsonArray arr = new JsonArray();
        if (Files.exists(trainersDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(trainersDir, "*.json")) {
                for (Path file : stream) {
                    JsonObject o = new JsonObject();
                    String filename = file.getFileName().toString();
                    o.addProperty("id", filename.replace(".json", ""));
                    o.addProperty("filename", filename);
                    arr.add(o);
                }
            }
        }
        WebDashboard.respondJson(ex, arr);
    }

    private static void handleTrainerRoutes(HttpExchange ex, String trainerId, String method) throws IOException {
        Path trainersDir = ConfigLoader.getTrainersDir();
        Path trainerFile = trainersDir.resolve(trainerId + ".json");

        // GET /trainers/:id
        if (method.equals("GET")) {
            if (!Files.exists(trainerFile)) {
                WebDashboard.respond(ex, 404, "{\"error\":\"Trainer file not found\"}");
                return;
            }
            String json = Files.readString(trainerFile);
            WebDashboard.respond(ex, 200, json);
            return;
        }

        // PUT /trainers/:id
        if (method.equals("PUT")) {
            String body = WebDashboard.readBody(ex);
            try {
                // Validate JSON
                JsonParser.parseString(body);
                Files.createDirectories(trainersDir);
                Files.writeString(trainerFile, body);
                WebDashboard.respond(ex, 200, body);
            } catch (JsonSyntaxException e) {
                WebDashboard.respond(ex, 400, "{\"error\":\"Invalid JSON: " + escapeJson(e.getMessage()) + "\"}");
            }
            return;
        }

        WebDashboard.respond(ex, 405, "{\"error\":\"Method not allowed\"}");
    }

    // ── Active runs ─────────────────────────────────────────────────────────────

    private static void handleListActive(HttpExchange ex, MinecraftServer server) throws IOException {
        JsonArray arr = new JsonArray();
        for (DungeonInstance instance : DungeonManager.getUniqueActiveInstances()) {
            arr.add(buildInstanceJson(instance, server));
        }
        JsonObject result = new JsonObject();
        result.add("instances", arr);
        result.addProperty("countdownCount", DungeonManager.getActiveCountdownCount());
        WebDashboard.respondJson(ex, result);
    }

    private static void handleForceFail(HttpExchange ex, MinecraftServer server) throws IOException {
        String body = WebDashboard.readBody(ex);
        JsonObject data = parseJson(body);
        String uuidStr = data.has("playerUuid") ? data.get("playerUuid").getAsString() : "";
        if (uuidStr.isEmpty()) {
            WebDashboard.respond(ex, 400, "{\"error\":\"playerUuid is required\"}");
            return;
        }
        try {
            UUID uuid = UUID.fromString(uuidStr);
            WebDashboard.executeOnServerThread(server, () -> {
                DungeonInstance instance = DungeonManager.getInstanceForPlayer(uuid);
                if (instance != null) {
                    DungeonManager.forceFailInstance(instance, server);
                }
            });
            WebDashboard.respond(ex, 200, "{\"failed\":true}");
        } catch (Exception e) {
            WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private static void handleForceLeave(HttpExchange ex, MinecraftServer server) throws IOException {
        String body = WebDashboard.readBody(ex);
        JsonObject data = parseJson(body);
        String uuidStr = data.has("playerUuid") ? data.get("playerUuid").getAsString() : "";
        if (uuidStr.isEmpty()) {
            WebDashboard.respond(ex, 400, "{\"error\":\"playerUuid is required\"}");
            return;
        }
        try {
            UUID uuid = UUID.fromString(uuidStr);
            WebDashboard.executeOnServerThread(server, () -> DungeonManager.forceLeavePlayer(uuid, server));
            WebDashboard.respond(ex, 200, "{\"left\":true}");
        } catch (Exception e) {
            WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    // ── Stats ───────────────────────────────────────────────────────────────────

    private static void handleStats(HttpExchange ex) throws IOException {
        List<DungeonManager.DungeonRunLog> history = DungeonManager.getRunHistory();
        int totalRuns = history.size();
        int successCount = 0;
        long totalDuration = 0;
        Map<String, int[]> byDungeon = new LinkedHashMap<>(); // [total, success]
        Map<Integer, int[]> byTier = new LinkedHashMap<>();

        for (DungeonManager.DungeonRunLog log : history) {
            if (log.success()) successCount++;
            long dur = log.endTime() - log.startTime();
            if (dur > 0) totalDuration += dur;

            byDungeon.computeIfAbsent(log.dungeonId(), k -> new int[2]);
            byDungeon.get(log.dungeonId())[0]++;
            if (log.success()) byDungeon.get(log.dungeonId())[1]++;

            byTier.computeIfAbsent(log.tier(), k -> new int[2]);
            byTier.get(log.tier())[0]++;
            if (log.success()) byTier.get(log.tier())[1]++;
        }

        JsonObject stats = new JsonObject();
        stats.addProperty("totalRuns", totalRuns);
        stats.addProperty("successCount", successCount);
        stats.addProperty("successRate", totalRuns > 0 ? Math.round(successCount * 100.0 / totalRuns) : 0);
        stats.addProperty("avgDurationSeconds", totalRuns > 0 ? totalDuration / totalRuns : 0);
        stats.addProperty("configCount", DungeonManager.getAllDungeonConfigs().size());
        stats.addProperty("activeInstances", DungeonManager.getUniqueActiveInstances().size());

        JsonObject byDungeonObj = new JsonObject();
        byDungeon.forEach((id, counts) -> {
            JsonObject o = new JsonObject();
            o.addProperty("total", counts[0]);
            o.addProperty("success", counts[1]);
            byDungeonObj.add(id, o);
        });
        stats.add("runsByDungeon", byDungeonObj);

        JsonObject byTierObj = new JsonObject();
        byTier.forEach((tier, counts) -> {
            JsonObject o = new JsonObject();
            o.addProperty("total", counts[0]);
            o.addProperty("success", counts[1]);
            byTierObj.add(String.valueOf(tier), o);
        });
        stats.add("runsByTier", byTierObj);

        WebDashboard.respondJson(ex, stats);
    }

    private static void handleHistory(HttpExchange ex, String query) throws IOException {
        int n = 50;
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("n=")) {
                    try { n = Math.min(1000, Math.max(1, Integer.parseInt(param.substring(2)))); } catch (Exception ignored) {}
                }
            }
        }
        List<DungeonManager.DungeonRunLog> history = DungeonManager.getRunHistory();
        JsonArray arr = new JsonArray();
        int count = 0;
        for (DungeonManager.DungeonRunLog log : history) {
            if (count >= n) break;
            arr.add(log.toJson());
            count++;
        }
        JsonObject result = new JsonObject();
        result.add("history", arr);
        result.addProperty("total", history.size());
        result.addProperty("returned", count);
        WebDashboard.respondJson(ex, result);
    }

    // ── Main config ─────────────────────────────────────────────────────────────

    private static void handleGetMainConfig(HttpExchange ex) throws IOException {
        MainConfig mc = ConfigLoader.getMainConfig();
        Gson gson = ConfigLoader.getGson();
        JsonObject o = gson.toJsonTree(mc).getAsJsonObject();
        // Mask database password if present
        if (o.has("database") && o.getAsJsonObject("database").has("password")) {
            o.getAsJsonObject("database").addProperty("password", "***");
        }
        WebDashboard.respondJson(ex, o);
    }

    // ── JSON builders ───────────────────────────────────────────────────────────

    private static JsonObject buildConfigSummary(DungeonConfig config) {
        JsonObject o = new JsonObject();
        o.addProperty("id", config.general.id);
        o.addProperty("name", config.general.name);
        o.addProperty("icon", config.general.guiIcon);
        o.addProperty("tier", config.scaling.tier);
        o.addProperty("teleportMode", config.networking.teleportMode);
        // Count active instances for this dungeon
        int activeCount = 0;
        int activePlayers = 0;
        for (DungeonInstance inst : DungeonManager.getUniqueActiveInstances()) {
            if (inst.getConfig().general.id.equals(config.general.id)) {
                activeCount++;
                activePlayers += inst.getPlayers().size();
            }
        }
        o.addProperty("activeInstances", activeCount);
        o.addProperty("activePlayers", activePlayers);
        // Slot info
        int totalSlots = 1 + config.additionalLocations.size();
        o.addProperty("slotsTotal", totalSlots);
        // Requirements summary
        o.addProperty("timeLimitSeconds", config.requirements.timeLimitSeconds);
        o.addProperty("partyLimit", config.requirements.dungeonsPartySizeLimit);
        return o;
    }

    private static JsonObject buildConfigDetail(DungeonConfig config) {
        Gson gson = ConfigLoader.getGson();
        return gson.toJsonTree(config).getAsJsonObject();
    }

    private static JsonObject buildInstanceJson(DungeonInstance instance, MinecraftServer server) {
        JsonObject o = new JsonObject();
        o.addProperty("dungeonId", instance.getConfig().general.id);
        o.addProperty("dungeonName", instance.getConfig().general.name);
        o.addProperty("tier", instance.getTier());
        o.addProperty("timeLeft", instance.getTimeLeft());
        o.addProperty("lives", instance.getLives());
        o.addProperty("alphasDefeated", instance.getAlphasDefeated());
        o.addProperty("alphasRequired", instance.getConfig().requirements.requiredAlphas);
        o.addProperty("gemsFound", instance.getGemsFound());
        o.addProperty("gemsRequired", instance.getConfig().requirements.requiredGems);
        o.addProperty("guardiansDefeated", instance.getGuardiansDefeated());
        o.addProperty("guardiansRequired", instance.getConfig().requirements.requiredGuardians);
        o.addProperty("stakesFound", instance.getStakesFound());
        o.addProperty("stakesRequired", instance.getConfig().requirements.requiredStakes);
        o.addProperty("bossSpawned", instance.isBossSpawned());
        o.addProperty("bossDefeated", instance.isBossDefeated());
        if (instance.getSpawnedBossSpecies() != null) {
            o.addProperty("bossSpecies", instance.getSpawnedBossSpecies());
        }
        o.addProperty("startTime", instance.getStartTime() / 1000L);

        JsonArray playersArr = new JsonArray();
        for (UUID uuid : instance.getPlayers()) {
            JsonObject po = new JsonObject();
            po.addProperty("uuid", uuid.toString());
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            po.addProperty("name", p != null ? p.getName().getString() : uuid.toString().substring(0, 8));
            po.addProperty("online", p != null);
            playersArr.add(po);
        }
        o.add("players", playersArr);

        return o;
    }

    // ── Utilities ───────────────────────────────────────────────────────────────

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

    // ── Leaderboard ───────────────────────────────────────────────────────────

    private static void handleLeaderboard(HttpExchange ex, String dungeonId) throws IOException {
        JsonObject result = new JsonObject();
        if (dungeonId != null) {
            // Single dungeon leaderboard
            var tierMap = DungeonManager.getDungeonLeaderboard(dungeonId);
            JsonObject tierObj = new JsonObject();
            for (var entry : tierMap.entrySet()) {
                JsonArray arr = new JsonArray();
                for (var e : entry.getValue()) arr.add(e.toJson());
                tierObj.add(String.valueOf(entry.getKey()), arr);
            }
            result.add(dungeonId, tierObj);
        } else {
            // Full leaderboard
            var full = DungeonManager.getFullLeaderboard();
            for (var dEntry : full.entrySet()) {
                JsonObject tierObj = new JsonObject();
                for (var tEntry : dEntry.getValue().entrySet()) {
                    JsonArray arr = new JsonArray();
                    for (var e : tEntry.getValue()) arr.add(e.toJson());
                    tierObj.add(String.valueOf(tEntry.getKey()), arr);
                }
                result.add(dEntry.getKey(), tierObj);
            }
        }
        WebDashboard.respondJson(ex, result);
    }
}
