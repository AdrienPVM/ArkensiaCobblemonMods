package com.cobblecheesescore.scoreboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Persistent, never-pruned hall-of-fame archive of every recorded mini-game
 * session. Mirrors the role of CobbleCap's LegendsStorage: while sessions in
 * cobblecheesescore_minigames.json are capped at MAX_SESSIONS_PER_GAME and
 * rotate, this file keeps the full chronological record permanently.
 */
public class MiniGameLegends {

    private static final Logger LOGGER = LoggerFactory.getLogger("CobbleCheeseScore");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path dataFile;
    private final List<LegendRecord> records = new CopyOnWriteArrayList<>();

    public MiniGameLegends() {
        this.dataFile = FabricLoader.getInstance().getConfigDir().resolve("cobblecheesescore_legends.json");
        load();
    }

    public static class LegendRecord {
        public final String gameId;
        public final String gameName;
        public final long date;
        public final String winnerUuid;
        public final String winnerName;
        public final double winnerScore;
        public final boolean firstEverForGame;
        public final List<RankingEntry> podium; // top 1-3

        public LegendRecord(String gameId, String gameName, long date,
                             String winnerUuid, String winnerName, double winnerScore,
                             boolean firstEverForGame, List<RankingEntry> podium) {
            this.gameId = gameId;
            this.gameName = gameName;
            this.date = date;
            this.winnerUuid = winnerUuid;
            this.winnerName = winnerName;
            this.winnerScore = winnerScore;
            this.firstEverForGame = firstEverForGame;
            this.podium = podium;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("gameId", gameId);
            o.addProperty("gameName", gameName);
            o.addProperty("date", date);
            o.addProperty("winnerUuid", winnerUuid);
            o.addProperty("winnerName", winnerName);
            o.addProperty("winnerScore", winnerScore);
            o.addProperty("firstEverForGame", firstEverForGame);
            JsonArray arr = new JsonArray();
            for (RankingEntry r : podium) arr.add(r.toJson());
            o.add("podium", arr);
            return o;
        }

        static LegendRecord fromJson(JsonObject o) {
            List<RankingEntry> podium = new ArrayList<>();
            if (o.has("podium")) {
                for (var el : o.getAsJsonArray("podium")) {
                    podium.add(RankingEntry.fromJson(el.getAsJsonObject()));
                }
            }
            return new LegendRecord(
                    o.get("gameId").getAsString(),
                    o.has("gameName") ? o.get("gameName").getAsString() : o.get("gameId").getAsString(),
                    o.get("date").getAsLong(),
                    o.get("winnerUuid").getAsString(),
                    o.get("winnerName").getAsString(),
                    o.has("winnerScore") ? o.get("winnerScore").getAsDouble() : 0,
                    o.has("firstEverForGame") && o.get("firstEverForGame").getAsBoolean(),
                    podium
            );
        }
    }

    public static class RankingEntry {
        public final int rank;
        public final String uuid;
        public final String name;
        public final double score;

        public RankingEntry(int rank, String uuid, String name, double score) {
            this.rank = rank; this.uuid = uuid; this.name = name; this.score = score;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("rank", rank);
            o.addProperty("uuid", uuid);
            o.addProperty("name", name);
            o.addProperty("score", score);
            return o;
        }

        static RankingEntry fromJson(JsonObject o) {
            return new RankingEntry(
                    o.get("rank").getAsInt(),
                    o.get("uuid").getAsString(),
                    o.get("name").getAsString(),
                    o.has("score") ? o.get("score").getAsDouble() : 0
            );
        }
    }

    private void load() {
        if (!Files.exists(dataFile)) return;
        try {
            String json = Files.readString(dataFile);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("records")) return;
            for (var el : root.getAsJsonArray("records")) {
                try {
                    records.add(LegendRecord.fromJson(el.getAsJsonObject()));
                } catch (Exception ex) {
                    LOGGER.warn("[CCS-Legends] Skip malformed legend: {}", ex.getMessage());
                }
            }
            LOGGER.info("[CCS-Legends] Loaded {} record(s)", records.size());
        } catch (Exception e) {
            LOGGER.error("[CCS-Legends] Failed to load legends: {}", e.getMessage(), e);
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(dataFile.getParent());
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (LegendRecord r : records) arr.add(r.toJson());
            root.add("records", arr);
            Path tmp = dataFile.resolveSibling("cobblecheesescore_legends.json.tmp");
            Files.writeString(tmp, GSON.toJson(root));
            try {
                Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomic) {
                Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("[CCS-Legends] Failed to save legends: {}", e.getMessage(), e);
        }
    }

    /** Returns true if no record yet exists for the given gameId. */
    public boolean isFirstEverForGame(String gameId) {
        for (LegendRecord r : records) if (r.gameId.equals(gameId)) return false;
        return true;
    }

    public void recordSession(String gameId, String gameName, long date,
                               List<MiniGameManager.SessionEntry> rankings) {
        if (rankings == null || rankings.isEmpty()) return;
        boolean first = isFirstEverForGame(gameId);
        MiniGameManager.SessionEntry winner = rankings.get(0);
        List<RankingEntry> podium = new ArrayList<>();
        for (int i = 0; i < Math.min(3, rankings.size()); i++) {
            MiniGameManager.SessionEntry e = rankings.get(i);
            podium.add(new RankingEntry(i + 1, e.getUuid(), e.getName(), e.getScore()));
        }
        records.add(new LegendRecord(gameId, gameName, date,
                winner.getUuid(), winner.getName(), winner.getScore(), first, podium));
        save();
        LOGGER.info("[CCS-Legends] Recorded {} session #{} — winner: {} ({}){}",
                gameName, records.size(), winner.getName(), winner.getScore(),
                first ? " [FIRST EVER for this game]" : "");
    }

    public List<LegendRecord> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(records));
    }

    public List<LegendRecord> getByGameId(String gameId) {
        List<LegendRecord> out = new ArrayList<>();
        for (LegendRecord r : records) if (r.gameId.equals(gameId)) out.add(r);
        return out;
    }
}
