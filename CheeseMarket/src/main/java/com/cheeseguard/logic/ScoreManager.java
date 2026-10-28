package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.EventParticipant;
import com.cheeseguard.data.ServerEvent;
import com.google.gson.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Persistent score storage. Saves event results, minigame bests, and per-player stats.
 * Data is stored in config/cheeseguard_scores.json for external reading (scoreboard mods).
 */
public class ScoreManager {

    // Event results: eventId -> list of participant scores
    private final Map<String, List<ScoreEntry>> eventResults = new LinkedHashMap<>();

    // Minigame all-time bests: minigameId -> sorted list (best first)
    private final Map<String, List<ScoreEntry>> minigameBests = new LinkedHashMap<>();

    // Per-player aggregate stats
    private final Map<UUID, PlayerStats> playerStats = new HashMap<>();

    private Path configPath;

    public void init(Path serverDir) {
        configPath = serverDir.resolve("config").resolve("cheeseguard_scores.json");
        load();
    }

    // ── Record a completed event result ──────────────────────────────────────

    public void recordEventResult(ServerEvent event) {
        String key = event.getId() + "_" + System.currentTimeMillis();
        List<ScoreEntry> entries = new ArrayList<>();

        List<EventParticipant> sorted = new ArrayList<>(event.getParticipants().values());
        sorted.sort(Comparator.comparingInt(EventParticipant::getScore).reversed());

        int rank = 1;
        for (EventParticipant p : sorted) {
            if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
            entries.add(new ScoreEntry(p.getPlayerUuid(), p.getPlayerName(), p.getScore(), rank));
            updatePlayerStats(p.getPlayerUuid(), p.getPlayerName(), p.getScore(), rank == 1);
            rank++;
        }

        eventResults.put(key, entries);

        // Trim to last 100 events
        while (eventResults.size() > 100) {
            eventResults.remove(eventResults.keySet().iterator().next());
        }
        save();
    }

    // ── Record a minigame score ──────────────────────────────────────────────

    public void recordMinigameScore(String minigameId, UUID playerUuid, String playerName, int score) {
        List<ScoreEntry> bests = minigameBests.computeIfAbsent(minigameId, k -> new ArrayList<>());
        bests.add(new ScoreEntry(playerUuid, playerName, score, 0));
        bests.sort(Comparator.comparingInt(ScoreEntry::score).reversed());

        // Keep top 50 per minigame
        if (bests.size() > 50) bests.subList(50, bests.size()).clear();

        // Re-rank
        for (int i = 0; i < bests.size(); i++) {
            bests.set(i, new ScoreEntry(bests.get(i).uuid, bests.get(i).name, bests.get(i).score, i + 1));
        }

        updatePlayerStats(playerUuid, playerName, score, false);
        save();
    }

    // ── Player stats ─────────────────────────────────────────────────────────

    private void updatePlayerStats(UUID uuid, String name, int score, boolean won) {
        PlayerStats stats = playerStats.computeIfAbsent(uuid, k -> new PlayerStats(name));
        stats.name = name;
        stats.eventsParticipated++;
        if (won) stats.eventsWon++;
        stats.totalScore += score;
    }

    public PlayerStats getPlayerStats(UUID uuid) { return playerStats.get(uuid); }

    // ── Getters ──────────────────────────────────────────────────────────────

    public List<ScoreEntry> getEventResult(String eventKey) { return eventResults.getOrDefault(eventKey, List.of()); }
    public List<ScoreEntry> getMinigameBests(String minigameId) { return minigameBests.getOrDefault(minigameId, List.of()); }
    public Map<String, List<ScoreEntry>> getAllEventResults() { return eventResults; }
    public Map<String, List<ScoreEntry>> getAllMinigameBests() { return minigameBests; }

    // ── Persistence ──────────────────────────────────────────────────────────

    public void save() {
        try {
            JsonObject root = new JsonObject();

            // Events
            JsonObject eventsObj = new JsonObject();
            for (var entry : eventResults.entrySet()) {
                JsonArray arr = new JsonArray();
                for (ScoreEntry s : entry.getValue()) arr.add(s.toJson());
                eventsObj.add(entry.getKey(), arr);
            }
            root.add("events", eventsObj);

            // Minigames
            JsonObject mgObj = new JsonObject();
            for (var entry : minigameBests.entrySet()) {
                JsonArray arr = new JsonArray();
                for (ScoreEntry s : entry.getValue()) arr.add(s.toJson());
                mgObj.add(entry.getKey(), arr);
            }
            root.add("minigames", mgObj);

            // Players
            JsonObject plObj = new JsonObject();
            for (var entry : playerStats.entrySet()) {
                plObj.add(entry.getKey().toString(), entry.getValue().toJson());
            }
            root.add("players", plObj);

            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (IOException e) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to save scores: {}", e.getMessage());
        }
    }

    public void load() {
        eventResults.clear();
        minigameBests.clear();
        playerStats.clear();
        if (!Files.exists(configPath)) return;

        try {
            JsonObject root = JsonParser.parseString(Files.readString(configPath)).getAsJsonObject();

            if (root.has("events")) {
                for (var entry : root.getAsJsonObject("events").entrySet()) {
                    List<ScoreEntry> list = new ArrayList<>();
                    for (JsonElement el : entry.getValue().getAsJsonArray()) {
                        list.add(ScoreEntry.fromJson(el.getAsJsonObject()));
                    }
                    eventResults.put(entry.getKey(), list);
                }
            }

            if (root.has("minigames")) {
                for (var entry : root.getAsJsonObject("minigames").entrySet()) {
                    List<ScoreEntry> list = new ArrayList<>();
                    for (JsonElement el : entry.getValue().getAsJsonArray()) {
                        list.add(ScoreEntry.fromJson(el.getAsJsonObject()));
                    }
                    minigameBests.put(entry.getKey(), list);
                }
            }

            if (root.has("players")) {
                for (var entry : root.getAsJsonObject("players").entrySet()) {
                    UUID uuid = UUID.fromString(entry.getKey());
                    playerStats.put(uuid, PlayerStats.fromJson(entry.getValue().getAsJsonObject()));
                }
            }
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to load scores: {}", e.getMessage());
        }
    }

    // ── Inner records ────────────────────────────────────────────────────────

    public record ScoreEntry(UUID uuid, String name, int score, int rank) {
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", uuid.toString());
            o.addProperty("name", name);
            o.addProperty("score", score);
            o.addProperty("rank", rank);
            return o;
        }

        public static ScoreEntry fromJson(JsonObject o) {
            return new ScoreEntry(
                UUID.fromString(o.get("uuid").getAsString()),
                o.get("name").getAsString(),
                o.get("score").getAsInt(),
                o.has("rank") ? o.get("rank").getAsInt() : 0
            );
        }
    }

    public static class PlayerStats {
        String name;
        int eventsParticipated = 0;
        int eventsWon = 0;
        int totalScore = 0;

        public PlayerStats(String name) { this.name = name; }

        public String getName() { return name; }
        public int getEventsParticipated() { return eventsParticipated; }
        public int getEventsWon() { return eventsWon; }
        public int getTotalScore() { return totalScore; }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            o.addProperty("participated", eventsParticipated);
            o.addProperty("won", eventsWon);
            o.addProperty("totalScore", totalScore);
            return o;
        }

        public static PlayerStats fromJson(JsonObject o) {
            PlayerStats s = new PlayerStats(o.get("name").getAsString());
            if (o.has("participated")) s.eventsParticipated = o.get("participated").getAsInt();
            if (o.has("won")) s.eventsWon = o.get("won").getAsInt();
            if (o.has("totalScore")) s.totalScore = o.get("totalScore").getAsInt();
            return s;
        }
    }
}
