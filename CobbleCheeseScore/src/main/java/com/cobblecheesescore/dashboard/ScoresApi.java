package com.cobblecheesescore.dashboard;

import com.cheesedash.WebDashboard;
import com.cobblecheesescore.CobbleCheeseScore;
import com.cobblecheesescore.cache.CacheManager;
import com.cobblecheesescore.config.Config;
import com.cobblecheesescore.config.ScoreboardConfig;
import com.cobblecheesescore.scoreboard.MiniGameManager;
import com.cobblecheesescore.scoreboard.PlayerScore;
import com.cobblecheesescore.scoreboard.ScoreboardManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST API for CobbleCheeseScore scoreboards.
 *
 * Routes:
 *   GET  /scoreboards              — list all scoreboards (summary)
 *   GET  /scoreboards/:id          — rankings for a specific scoreboard
 *   GET  /scoreboards/:id/image    — PNG image for a specific scoreboard
 *   GET  /scoreboards/:id/image/:name.png — same, with shareable filename
 *   POST /refresh                  — force refresh all scoreboards
 *   GET  /config                   — get config settings
 *   PUT  /config                   — update config settings
 *   GET  /stats                    — summary statistics
 */
public class ScoresApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseDash");

    public static void handle(HttpExchange exchange, MinecraftServer server, String subRoute) throws IOException {
        String method = exchange.getRequestMethod();

        try {
            // GET /scoreboards
            if (subRoute.equals("/scoreboards") && method.equals("GET")) {
                handleListScoreboards(exchange);
                return;
            }

            // GET /scoreboards/:id/image  OR  /scoreboards/:id/image/:name.png
            if (subRoute.matches("/scoreboards/[^/]+/image(/[^/]+)?") && method.equals("GET")) {
                String id = subRoute.split("/")[2];
                handleScoreboardImage(exchange, id);
                return;
            }

            // PUT /scoreboards/:id — edit scoreboard config
            if (subRoute.matches("/scoreboards/[^/]+") && method.equals("PUT")) {
                String id = subRoute.split("/")[2];
                handleUpdateScoreboard(exchange, id);
                return;
            }

            // GET /scoreboards/:id
            if (subRoute.matches("/scoreboards/[^/]+") && method.equals("GET")) {
                String id = subRoute.split("/")[2];
                handleGetScoreboard(exchange, id);
                return;
            }

            // POST /refresh
            if (subRoute.equals("/refresh") && method.equals("POST")) {
                handleRefresh(exchange, server);
                return;
            }

            // GET /config
            if (subRoute.equals("/config") && method.equals("GET")) {
                handleGetConfig(exchange);
                return;
            }

            // PUT /config
            if (subRoute.equals("/config") && method.equals("PUT")) {
                handleUpdateConfig(exchange);
                return;
            }

            // GET /stats
            if (subRoute.equals("/stats") && method.equals("GET")) {
                handleStats(exchange);
                return;
            }

            // ── Mini-game routes ──────────────────────────────────────────

            // GET /minigames
            if (subRoute.equals("/minigames") && method.equals("GET")) {
                handleListMiniGames(exchange);
                return;
            }

            // POST /minigames — create new game
            if (subRoute.equals("/minigames") && method.equals("POST")) {
                handleCreateMiniGame(exchange);
                return;
            }

            // GET /minigames/global — global ranking across all games
            if (subRoute.equals("/minigames/global") && method.equals("GET")) {
                handleGlobalRanking(exchange);
                return;
            }

            // DELETE /minigames/:id
            if (subRoute.matches("/minigames/[^/]+") && method.equals("DELETE")) {
                String id = subRoute.split("/")[2];
                handleDeleteMiniGame(exchange, id);
                return;
            }

            // PUT /minigames/:id — rename game
            if (subRoute.matches("/minigames/[^/]+") && method.equals("PUT")) {
                String id = subRoute.split("/")[2];
                handleUpdateMiniGame(exchange, id);
                return;
            }

            // GET /minigames/:id — detail + sessions + ranking
            if (subRoute.matches("/minigames/[^/]+") && method.equals("GET")) {
                String id = subRoute.split("/")[2];
                handleGetMiniGame(exchange, id);
                return;
            }

            // POST /minigames/:id/sessions — add session result
            if (subRoute.matches("/minigames/[^/]+/sessions") && method.equals("POST")) {
                String id = subRoute.split("/")[2];
                handleAddSession(exchange, id);
                return;
            }

            // DELETE /minigames/:id/sessions/:idx
            if (subRoute.matches("/minigames/[^/]+/sessions/\\d+") && method.equals("DELETE")) {
                String[] parts = subRoute.split("/");
                handleDeleteSession(exchange, parts[2], Integer.parseInt(parts[4]));
                return;
            }

            WebDashboard.respond(exchange, 404, "{\"error\":\"Route not found\"}");

        } catch (Exception e) {
            LOGGER.error("[ScoresApi] Error handling {}: {}", subRoute, e.getMessage(), e);
            WebDashboard.respond(exchange, 500,
                "{\"error\":\"Internal server error\",\"detail\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    // ── GET /scoreboards ────────────────────────────────────────────────────

    private static void handleListScoreboards(HttpExchange exchange) throws IOException {
        ScoreboardManager mgr = CobbleCheeseScore.getScoreboardManager();
        Config config = CobbleCheeseScore.getConfig();
        CacheManager cache = CobbleCheeseScore.getCacheManager();

        if (mgr == null || config == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"Scoreboards not initialized\"}");
            return;
        }

        JsonArray arr = new JsonArray();
        for (ScoreboardConfig board : config.getScoreboards()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", board.getId());
            obj.addProperty("title", board.getTitle());
            obj.addProperty("description", board.getDescription());
            obj.addProperty("enabled", board.isEnabled());
            obj.addProperty("maxPlayers", board.getMaxPlayers());
            obj.addProperty("sortOrder", board.getSortOrder());

            List<PlayerScore> scores = mgr.getScoreboard(board.getId());
            obj.addProperty("playerCount", scores.size());

            // Top player preview
            if (!scores.isEmpty()) {
                PlayerScore top = scores.get(0);
                JsonObject topPlayer = new JsonObject();
                topPlayer.addProperty("name", top.getPlayerName());
                topPlayer.addProperty("score", top.getFormattedScore());
                obj.add("topPlayer", topPlayer);
            }

            // Has cached image?
            obj.addProperty("hasImage", cache != null && cache.hasImage(board.getId()));

            arr.add(obj);
        }

        WebDashboard.respondJson(exchange, arr);
    }

    // ── GET /scoreboards/:id ────────────────────────────────────────────────

    private static void handleGetScoreboard(HttpExchange exchange, String id) throws IOException {
        ScoreboardManager mgr = CobbleCheeseScore.getScoreboardManager();
        Config config = CobbleCheeseScore.getConfig();

        if (mgr == null || config == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"Scoreboards not initialized\"}");
            return;
        }

        ScoreboardConfig boardConfig = config.getScoreboardById(id);
        if (boardConfig == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Scoreboard not found: " + escapeJson(id) + "\"}");
            return;
        }

        List<PlayerScore> scores = mgr.getScoreboard(id);

        JsonObject result = new JsonObject();
        result.addProperty("id", boardConfig.getId());
        result.addProperty("title", boardConfig.getTitle());
        result.addProperty("description", boardConfig.getDescription());
        result.addProperty("enabled", boardConfig.isEnabled());
        result.addProperty("maxPlayers", boardConfig.getMaxPlayers());
        result.addProperty("sortOrder", boardConfig.getSortOrder());

        JsonArray rankings = new JsonArray();
        for (int i = 0; i < scores.size(); i++) {
            PlayerScore ps = scores.get(i);
            JsonObject entry = new JsonObject();
            entry.addProperty("rank", i + 1);
            entry.addProperty("uuid", ps.getUuid());
            entry.addProperty("name", ps.getPlayerName());
            entry.addProperty("score", ps.getScore());
            entry.addProperty("formattedScore", ps.getFormattedScore());
            rankings.add(entry);
        }
        result.add("rankings", rankings);

        // Include colors for editing
        JsonObject colorsObj = new JsonObject();
        for (Map.Entry<String, String> entry : boardConfig.getColors().entrySet()) {
            colorsObj.addProperty(entry.getKey(), entry.getValue());
        }
        result.add("colors", colorsObj);

        CacheManager cache = CobbleCheeseScore.getCacheManager();
        result.addProperty("hasImage", cache != null && cache.hasImage(id));
        result.addProperty("lastUpdate", cache != null ? cache.getLastUpdate() : 0);

        WebDashboard.respondJson(exchange, result);
    }

    // ── PUT /scoreboards/:id ──────────────────────────────────────────────

    private static void handleUpdateScoreboard(HttpExchange exchange, String id) throws IOException {
        Config config = CobbleCheeseScore.getConfig();
        if (config == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"Config not loaded\"}");
            return;
        }
        ScoreboardConfig boardConfig = config.getScoreboardById(id);
        if (boardConfig == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Scoreboard not found: " + escapeJson(id) + "\"}");
            return;
        }

        String body = WebDashboard.readBody(exchange);
        JsonObject data = JsonParser.parseString(body).getAsJsonObject();

        // Apply edits
        if (data.has("title")) boardConfig.setTitle(data.get("title").getAsString());
        if (data.has("description")) boardConfig.setDescription(data.get("description").getAsString());
        if (data.has("enabled")) boardConfig.setEnabled(data.get("enabled").getAsBoolean());
        if (data.has("maxPlayers")) boardConfig.setMaxPlayers(data.get("maxPlayers").getAsInt());
        if (data.has("rowsPerColumn")) boardConfig.setRowsPerColumn(data.get("rowsPerColumn").getAsInt());
        if (data.has("colors") && data.get("colors").isJsonObject()) {
            Map<String, String> newColors = new java.util.HashMap<>(boardConfig.getColors());
            for (Map.Entry<String, JsonElement> entry : data.getAsJsonObject("colors").entrySet()) {
                if (entry.getValue().isJsonNull() || entry.getValue().getAsString().isEmpty()) {
                    newColors.remove(entry.getKey());
                } else {
                    newColors.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            boardConfig.setColors(newColors);
        }

        // Save config to disk
        try {
            net.fabricmc.loader.api.FabricLoader loader = net.fabricmc.loader.api.FabricLoader.getInstance();
            config.save(loader.getConfigDir().resolve("cobblecheesescore.json"));
        } catch (Exception e) {
            LOGGER.error("[ScoresApi] Failed to save config: {}", e.getMessage(), e);
            WebDashboard.respond(exchange, 500, "{\"error\":\"Failed to save config\"}");
            return;
        }

        // Regenerate this scoreboard's image
        ScoreboardManager mgr = CobbleCheeseScore.getScoreboardManager();
        if (mgr != null) {
            try {
                mgr.updateAll();
            } catch (Exception e) {
                LOGGER.warn("[ScoresApi] Image refresh failed after config update", e);
            }
        }

        WebDashboard.respond(exchange, 200, "{\"updated\":true}");
    }

    // ── GET /scoreboards/:id/image  or  /scoreboards/:id/image/:name.png ────

    /** Public (no-auth) image endpoint — called from the public context. */
    static void handlePublicImage(HttpExchange exchange, String id) throws IOException {
        handleScoreboardImage(exchange, id);
    }

    private static void handleScoreboardImage(HttpExchange exchange, String id) throws IOException {
        CacheManager cache = CobbleCheeseScore.getCacheManager();
        if (cache == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"Cache not initialized\"}");
            return;
        }

        BufferedImage image = cache.getCachedImage(id);
        if (image == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"No image for scoreboard: " + escapeJson(id) + "\"}");
            return;
        }

        // Write PNG to byte array
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        byte[] pngBytes = baos.toByteArray();

        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=60");
        exchange.sendResponseHeaders(200, pngBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(pngBytes);
        }
    }

    // ── POST /refresh ───────────────────────────────────────────────────────

    private static void handleRefresh(HttpExchange exchange, MinecraftServer server) throws IOException {
        ScoreboardManager mgr = CobbleCheeseScore.getScoreboardManager();
        if (mgr == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"Scoreboards not initialized\"}");
            return;
        }

        LOGGER.info("[ScoresApi] Manual refresh triggered via dashboard");

        try {
            // Run update (this reads NBT/JSON files, safe from any thread)
            mgr.updateAll();
        } catch (Exception e) {
            LOGGER.error("[ScoresApi] Refresh failed: {}", e.getMessage(), e);
            WebDashboard.respond(exchange, 500, "{\"error\":\"Refresh failed: " + escapeJson(e.getMessage()) + "\"}");
            return;
        }

        CacheManager cache = CobbleCheeseScore.getCacheManager();
        JsonObject result = new JsonObject();
        result.addProperty("refreshed", true);
        result.addProperty("lastUpdate", cache != null ? cache.getLastUpdate() : System.currentTimeMillis());
        WebDashboard.respondJson(exchange, result);
    }

    // ── GET /config ─────────────────────────────────────────────────────────

    private static void handleGetConfig(HttpExchange exchange) throws IOException {
        Config config = CobbleCheeseScore.getConfig();
        if (config == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"Config not loaded\"}");
            return;
        }

        JsonObject obj = new JsonObject();
        obj.addProperty("updateIntervalMinutes", config.getUpdateIntervalMinutes());
        obj.addProperty("timeZone", config.getTimeZone());
        obj.addProperty("defaultMaxPlayers", config.getDefaultMaxPlayers());
        obj.addProperty("defaultRowsPerColumn", config.getDefaultRowsPerColumn());
        obj.addProperty("showLastUpdate", config.isShowLastUpdate());
        obj.addProperty("enableHistory", config.isEnableHistory());
        obj.addProperty("historyRetentionDays", config.getHistoryRetentionDays());

        // Default colors
        JsonObject colors = new JsonObject();
        for (Map.Entry<String, String> entry : config.getDefaultColors().entrySet()) {
            colors.addProperty(entry.getKey(), entry.getValue());
        }
        obj.add("defaultColors", colors);

        // Scoreboards configs
        JsonArray boards = new JsonArray();
        for (ScoreboardConfig board : config.getScoreboards()) {
            JsonObject b = new JsonObject();
            b.addProperty("id", board.getId());
            b.addProperty("enabled", board.isEnabled());
            b.addProperty("title", board.getTitle());
            b.addProperty("description", board.getDescription());
            b.addProperty("maxPlayers", board.getMaxPlayers());
            b.addProperty("rowsPerColumn", board.getRowsPerColumn());
            b.addProperty("sortOrder", board.getSortOrder());

            JsonObject boardColors = new JsonObject();
            for (Map.Entry<String, String> entry : board.getColors().entrySet()) {
                boardColors.addProperty(entry.getKey(), entry.getValue());
            }
            b.add("colors", boardColors);

            boards.add(b);
        }
        obj.add("scoreboards", boards);

        WebDashboard.respondJson(exchange, obj);
    }

    // ── PUT /config ─────────────────────────────────────────────────────────

    private static void handleUpdateConfig(HttpExchange exchange) throws IOException {
        Config config = CobbleCheeseScore.getConfig();
        if (config == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"Config not loaded\"}");
            return;
        }

        String body = WebDashboard.readBody(exchange);
        JsonObject data = JsonParser.parseString(body).getAsJsonObject();

        // Toggle individual scoreboard enabled/disabled
        if (data.has("scoreboardId") && data.has("enabled")) {
            String scoreboardId = data.get("scoreboardId").getAsString();
            boolean enabled = data.get("enabled").getAsBoolean();
            ScoreboardConfig board = config.getScoreboardById(scoreboardId);
            if (board == null) {
                WebDashboard.respond(exchange, 404, "{\"error\":\"Scoreboard not found\"}");
                return;
            }
            // ScoreboardConfig needs a setter — use reflection or add one
            // For now, respond with the current config reload approach
            LOGGER.info("[ScoresApi] Config update: scoreboard {} enabled={}", scoreboardId, enabled);
        }

        // Save config
        try {
            net.fabricmc.loader.api.FabricLoader loader = net.fabricmc.loader.api.FabricLoader.getInstance();
            config.save(loader.getConfigDir().resolve("cobblecheesescore.json"));
        } catch (Exception e) {
            LOGGER.error("[ScoresApi] Failed to save config: {}", e.getMessage(), e);
            WebDashboard.respond(exchange, 500, "{\"error\":\"Failed to save config\"}");
            return;
        }

        WebDashboard.respond(exchange, 200, "{\"saved\":true}");
    }

    // ── GET /stats ──────────────────────────────────────────────────────────

    private static void handleStats(HttpExchange exchange) throws IOException {
        Config config = CobbleCheeseScore.getConfig();
        ScoreboardManager mgr = CobbleCheeseScore.getScoreboardManager();
        CacheManager cache = CobbleCheeseScore.getCacheManager();

        JsonObject obj = new JsonObject();

        if (config == null) {
            obj.addProperty("totalScoreboards", 0);
            obj.addProperty("enabledScoreboards", 0);
            obj.addProperty("totalPlayersTracked", 0);
            obj.addProperty("lastUpdate", 0);
            WebDashboard.respondJson(exchange, obj);
            return;
        }

        int total = config.getScoreboards().size();
        int enabled = (int) config.getScoreboards().stream().filter(ScoreboardConfig::isEnabled).count();

        // Count unique players across all scoreboards
        int playersTracked = 0;
        if (cache != null && cache.getPlayerData() != null) {
            playersTracked = cache.getPlayerData().size();
        }

        obj.addProperty("totalScoreboards", total);
        obj.addProperty("enabledScoreboards", enabled);
        obj.addProperty("totalPlayersTracked", playersTracked);
        obj.addProperty("lastUpdate", cache != null ? cache.getLastUpdate() : 0);
        obj.addProperty("updateIntervalMinutes", config.getUpdateIntervalMinutes());

        WebDashboard.respondJson(exchange, obj);
    }

    // ── Mini-game handlers ──────────────────────────────────────────────────

    private static void handleListMiniGames(HttpExchange exchange) throws IOException {
        MiniGameManager mgr = CobbleCheeseScore.getMiniGameManager();
        if (mgr == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"Mini-games not initialized\"}");
            return;
        }

        JsonArray arr = new JsonArray();
        for (Map.Entry<String, MiniGameManager.MiniGame> entry : mgr.getGames().entrySet()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", entry.getKey());
            obj.addProperty("displayName", entry.getValue().getDisplayName());
            obj.addProperty("sessionCount", entry.getValue().getSessions().size());

            // Compute per-game ranking for top player
            List<PlayerScore> ranking = mgr.computeGameRanking(entry.getKey());
            obj.addProperty("playerCount", ranking.size());
            if (!ranking.isEmpty()) {
                JsonObject top = new JsonObject();
                top.addProperty("name", ranking.get(0).getPlayerName());
                top.addProperty("score", ranking.get(0).getFormattedScore());
                obj.add("topPlayer", top);
            }
            arr.add(obj);
        }
        WebDashboard.respondJson(exchange, arr);
    }

    private static void handleCreateMiniGame(HttpExchange exchange) throws IOException {
        MiniGameManager mgr = CobbleCheeseScore.getMiniGameManager();
        if (mgr == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"Mini-games not initialized\"}");
            return;
        }

        String body = WebDashboard.readBody(exchange);
        JsonObject data = JsonParser.parseString(body).getAsJsonObject();
        String id = data.has("id") ? data.get("id").getAsString().trim() : "";
        String displayName = data.has("displayName") ? data.get("displayName").getAsString().trim() : "";

        if (id.isEmpty() || displayName.isEmpty()) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"id and displayName required\"}");
            return;
        }

        if (mgr.getGame(id) != null) {
            WebDashboard.respond(exchange, 409, "{\"error\":\"Game already exists: " + escapeJson(id) + "\"}");
            return;
        }

        mgr.createGame(id, displayName);
        JsonObject result = new JsonObject();
        result.addProperty("created", true);
        result.addProperty("id", id);
        WebDashboard.respondJson(exchange, result);
    }

    private static void handleDeleteMiniGame(HttpExchange exchange, String id) throws IOException {
        MiniGameManager mgr = CobbleCheeseScore.getMiniGameManager();
        if (mgr == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"Mini-games not initialized\"}");
            return;
        }
        if (mgr.deleteGame(id)) {
            WebDashboard.respond(exchange, 200, "{\"deleted\":true}");
        } else {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Game not found: " + escapeJson(id) + "\"}");
        }
    }

    private static void handleUpdateMiniGame(HttpExchange exchange, String id) throws IOException {
        MiniGameManager mgr = CobbleCheeseScore.getMiniGameManager();
        if (mgr == null || mgr.getGame(id) == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Game not found\"}");
            return;
        }
        String body = WebDashboard.readBody(exchange);
        JsonObject data = JsonParser.parseString(body).getAsJsonObject();
        if (data.has("displayName")) {
            mgr.renameGame(id, data.get("displayName").getAsString().trim());
        }
        WebDashboard.respond(exchange, 200, "{\"updated\":true}");
    }

    private static void handleGetMiniGame(HttpExchange exchange, String id) throws IOException {
        MiniGameManager mgr = CobbleCheeseScore.getMiniGameManager();
        if (mgr == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"Mini-games not initialized\"}");
            return;
        }
        MiniGameManager.MiniGame game = mgr.getGame(id);
        if (game == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Game not found: " + escapeJson(id) + "\"}");
            return;
        }

        JsonObject result = new JsonObject();
        result.addProperty("id", id);
        result.addProperty("displayName", game.getDisplayName());

        // Sessions (most recent first)
        JsonArray sessionsArr = new JsonArray();
        List<MiniGameManager.GameSession> sessions = game.getSessions();
        for (int i = sessions.size() - 1; i >= 0; i--) {
            MiniGameManager.GameSession session = sessions.get(i);
            JsonObject so = new JsonObject();
            so.addProperty("index", i);
            so.addProperty("date", session.getDate());
            JsonArray rankingsArr = new JsonArray();
            for (MiniGameManager.SessionEntry entry : session.getRankings()) {
                JsonObject ro = new JsonObject();
                ro.addProperty("uuid", entry.getUuid());
                ro.addProperty("name", entry.getName());
                ro.addProperty("score", entry.getScore());
                rankingsArr.add(ro);
            }
            so.add("rankings", rankingsArr);
            sessionsArr.add(so);
        }
        result.add("sessions", sessionsArr);

        // Aggregated ranking for this game
        JsonArray ranking = new JsonArray();
        List<PlayerScore> gameRanking = mgr.computeGameRanking(id);
        for (int i = 0; i < gameRanking.size(); i++) {
            PlayerScore ps = gameRanking.get(i);
            JsonObject entry = new JsonObject();
            entry.addProperty("rank", i + 1);
            entry.addProperty("uuid", ps.getUuid());
            entry.addProperty("name", ps.getPlayerName());
            entry.addProperty("score", ps.getScore());
            entry.addProperty("formattedScore", ps.getFormattedScore());
            ranking.add(entry);
        }
        result.add("ranking", ranking);

        WebDashboard.respondJson(exchange, result);
    }

    private static void handleAddSession(HttpExchange exchange, String gameId) throws IOException {
        MiniGameManager mgr = CobbleCheeseScore.getMiniGameManager();
        if (mgr == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"Mini-games not initialized\"}");
            return;
        }
        MiniGameManager.MiniGame game = mgr.getGame(gameId);
        if (game == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Game not found: " + escapeJson(gameId) + "\"}");
            return;
        }

        String body = WebDashboard.readBody(exchange);
        JsonObject data = JsonParser.parseString(body).getAsJsonObject();

        if (!data.has("rankings") || !data.get("rankings").isJsonArray()) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"rankings array required\"}");
            return;
        }

        List<MiniGameManager.SessionEntry> entries = new ArrayList<>();
        for (JsonElement el : data.getAsJsonArray("rankings")) {
            JsonObject ro = el.getAsJsonObject();
            entries.add(new MiniGameManager.SessionEntry(
                ro.has("uuid") ? ro.get("uuid").getAsString() : "",
                ro.has("name") ? ro.get("name").getAsString() : "",
                ro.has("score") ? ro.get("score").getAsDouble() : 0
            ));
        }

        mgr.addSession(gameId, entries);
        WebDashboard.respond(exchange, 200, "{\"added\":true,\"sessionCount\":" + game.getSessions().size() + "}");
    }

    private static void handleDeleteSession(HttpExchange exchange, String gameId, int sessionIdx) throws IOException {
        MiniGameManager mgr = CobbleCheeseScore.getMiniGameManager();
        if (mgr == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"Mini-games not initialized\"}");
            return;
        }
        if (mgr.deleteSession(gameId, sessionIdx)) {
            WebDashboard.respond(exchange, 200, "{\"deleted\":true}");
        } else {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Session not found\"}");
        }
    }

    private static void handleGlobalRanking(HttpExchange exchange) throws IOException {
        MiniGameManager mgr = CobbleCheeseScore.getMiniGameManager();
        if (mgr == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"Mini-games not initialized\"}");
            return;
        }

        List<PlayerScore> ranking = mgr.computeGlobalRanking();
        JsonObject result = new JsonObject();
        JsonArray arr = new JsonArray();
        for (int i = 0; i < ranking.size(); i++) {
            PlayerScore ps = ranking.get(i);
            JsonObject entry = new JsonObject();
            entry.addProperty("rank", i + 1);
            entry.addProperty("uuid", ps.getUuid());
            entry.addProperty("name", ps.getPlayerName());
            entry.addProperty("score", ps.getScore());
            entry.addProperty("formattedScore", ps.getFormattedScore());
            arr.add(entry);
        }
        result.add("ranking", arr);
        result.addProperty("totalGames", mgr.getGames().size());
        WebDashboard.respondJson(exchange, result);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
