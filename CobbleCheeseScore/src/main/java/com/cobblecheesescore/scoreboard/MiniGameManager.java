package com.cobblecheesescore.scoreboard;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Manages mini-game results persistence and global ranking computation.
 *
 * Data model:
 *   games: { gameId -> MiniGame }
 *   MiniGame: { displayName, sessions[] }
 *   Session: { date (epoch ms), rankings: [{ uuid, name, score }] }
 *
 * Global ranking: top 1 = 3pts, top 2 = 2pts, top 3 = 1pt per session.
 */
public class MiniGameManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("CobbleCheeseScore");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_SESSIONS_PER_GAME = 500;

    private final Map<String, MiniGame> games = new LinkedHashMap<>();
    private final Path dataFile;
    private final MiniGameLegends legends;

    public MiniGameManager() {
        this.dataFile = FabricLoader.getInstance().getConfigDir().resolve("cobblecheesescore_minigames.json");
        this.legends = new MiniGameLegends();
        load();
    }

    public MiniGameLegends getLegends() { return legends; }

    // ── Data classes ────────────────────────────────────────────────────

    public static class MiniGame {
        private String displayName;
        private final List<GameSession> sessions = new ArrayList<>();

        public MiniGame(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String name) { this.displayName = name; }
        public List<GameSession> getSessions() { return sessions; }
    }

    public static class GameSession {
        private final long date;
        private final List<SessionEntry> rankings;

        public GameSession(long date, List<SessionEntry> rankings) {
            this.date = date;
            this.rankings = rankings;
        }

        public long getDate() { return date; }
        public List<SessionEntry> getRankings() { return rankings; }
    }

    public static class SessionEntry {
        private final String uuid;
        private final String name;
        private final double score;

        public SessionEntry(String uuid, String name, double score) {
            this.uuid = uuid;
            this.name = name;
            this.score = score;
        }

        public String getUuid() { return uuid; }
        public String getName() { return name; }
        public double getScore() { return score; }
    }

    // ── Persistence ─────────────────────────────────────────────────────

    private void load() {
        if (!Files.exists(dataFile)) return;
        try {
            String json = Files.readString(dataFile);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject gamesObj = root.has("games") ? root.getAsJsonObject("games") : new JsonObject();

            for (Map.Entry<String, JsonElement> entry : gamesObj.entrySet()) {
                String gameId = entry.getKey();
                JsonObject go = entry.getValue().getAsJsonObject();
                MiniGame mg = new MiniGame(go.has("displayName") ? go.get("displayName").getAsString() : gameId);

                if (go.has("sessions")) {
                    for (JsonElement se : go.getAsJsonArray("sessions")) {
                        JsonObject so = se.getAsJsonObject();
                        long date = so.get("date").getAsLong();
                        List<SessionEntry> rankings = new ArrayList<>();
                        if (so.has("rankings")) {
                            for (JsonElement re : so.getAsJsonArray("rankings")) {
                                JsonObject ro = re.getAsJsonObject();
                                rankings.add(new SessionEntry(
                                    ro.get("uuid").getAsString(),
                                    ro.get("name").getAsString(),
                                    ro.get("score").getAsDouble()
                                ));
                            }
                        }
                        mg.sessions.add(new GameSession(date, rankings));
                    }
                }
                games.put(gameId, mg);
            }

            LOGGER.info("[MiniGame] Loaded {} games", games.size());
        } catch (Exception e) {
            LOGGER.error("[MiniGame] Failed to load data", e);
        }
    }

    public void save() {
        try {
            JsonObject root = new JsonObject();
            JsonObject gamesObj = new JsonObject();

            for (Map.Entry<String, MiniGame> entry : games.entrySet()) {
                JsonObject go = new JsonObject();
                MiniGame mg = entry.getValue();
                go.addProperty("displayName", mg.displayName);

                JsonArray sessionsArr = new JsonArray();
                for (GameSession session : mg.sessions) {
                    JsonObject so = new JsonObject();
                    so.addProperty("date", session.date);
                    JsonArray rankingsArr = new JsonArray();
                    for (SessionEntry se : session.rankings) {
                        JsonObject ro = new JsonObject();
                        ro.addProperty("uuid", se.uuid);
                        ro.addProperty("name", se.name);
                        ro.addProperty("score", se.score);
                        rankingsArr.add(ro);
                    }
                    so.add("rankings", rankingsArr);
                    sessionsArr.add(so);
                }
                go.add("sessions", sessionsArr);
                gamesObj.add(entry.getKey(), go);
            }

            root.add("games", gamesObj);
            Files.createDirectories(dataFile.getParent());
            Files.writeString(dataFile, GSON.toJson(root));
        } catch (IOException e) {
            LOGGER.error("[MiniGame] Failed to save data", e);
        }
    }

    // ── Public API ──────────────────────────────────────────────────────

    public Map<String, MiniGame> getGames() {
        return Collections.unmodifiableMap(games);
    }

    public MiniGame getGame(String gameId) {
        return games.get(gameId);
    }

    public MiniGame createGame(String gameId, String displayName) {
        MiniGame mg = new MiniGame(displayName);
        games.put(gameId, mg);
        save();
        return mg;
    }

    public boolean deleteGame(String gameId) {
        if (games.remove(gameId) != null) {
            save();
            return true;
        }
        return false;
    }

    public void renameGame(String gameId, String newName) {
        MiniGame mg = games.get(gameId);
        if (mg != null) {
            mg.setDisplayName(newName);
            save();
        }
    }

    /**
     * Record a game session result. Rankings should already be sorted by score descending.
     */
    public void addSession(String gameId, List<SessionEntry> rankings) {
        MiniGame mg = games.get(gameId);
        if (mg == null) return;

        long now = System.currentTimeMillis();
        mg.sessions.add(new GameSession(now, rankings));

        // Cap sessions
        while (mg.sessions.size() > MAX_SESSIONS_PER_GAME) {
            mg.sessions.remove(0);
        }

        save();

        // Hall of Fame: permanent, never-pruned record (separate file)
        try {
            legends.recordSession(gameId, mg.getDisplayName(), now, rankings);
        } catch (Exception e) {
            LOGGER.warn("[MiniGame] Legends record failed for {}: {}", gameId, e.getMessage());
        }
    }

    public boolean deleteSession(String gameId, int sessionIndex) {
        MiniGame mg = games.get(gameId);
        if (mg == null || sessionIndex < 0 || sessionIndex >= mg.sessions.size()) return false;
        mg.sessions.remove(sessionIndex);
        save();
        return true;
    }

    /**
     * Compute per-game aggregate: total wins (top 1), total podiums (top 3), total points.
     */
    public List<PlayerScore> computeGameRanking(String gameId) {
        MiniGame mg = games.get(gameId);
        if (mg == null) return List.of();
        return computePointsFromSessions(mg.sessions);
    }

    /**
     * Compute global ranking: aggregate points across ALL games.
     */
    public List<PlayerScore> computeGlobalRanking() {
        List<GameSession> allSessions = new ArrayList<>();
        for (MiniGame mg : games.values()) {
            allSessions.addAll(mg.sessions);
        }
        return computePointsFromSessions(allSessions);
    }

    private List<PlayerScore> computePointsFromSessions(List<GameSession> sessions) {
        // uuid -> {name, totalPoints}
        Map<String, int[]> pointsMap = new LinkedHashMap<>();
        Map<String, String> nameMap = new HashMap<>();

        for (GameSession session : sessions) {
            List<SessionEntry> rankings = session.rankings;
            for (int i = 0; i < rankings.size() && i < 3; i++) {
                SessionEntry entry = rankings.get(i);
                int points = i == 0 ? 3 : i == 1 ? 2 : 1;
                pointsMap.computeIfAbsent(entry.uuid, k -> new int[]{0})[0] += points;
                nameMap.put(entry.uuid, entry.name);
            }
        }

        List<PlayerScore> result = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : pointsMap.entrySet()) {
            int pts = entry.getValue()[0];
            result.add(new PlayerScore(
                entry.getKey(),
                nameMap.get(entry.getKey()),
                pts,
                pts + " pts",
                false
            ));
        }

        Collections.sort(result);
        return result;
    }
}
