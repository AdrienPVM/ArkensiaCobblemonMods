package com.cobblecheesescore.scoreboard;

import com.cobblecheesescore.config.Config;
import com.cobblecheesescore.config.ScoreboardConfig;
import com.cobblecheesescore.data.DataCollector;
import com.cobblecheesescore.data.PlayerData;
import com.cobblecheesescore.cache.CacheManager;
import com.cobblecheesescore.image.ImageGenerator;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ScoreboardManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("CobbleCheeseScore");
    
    private final Config config;
    private final DataCollector dataCollector;
    private final CacheManager cache;
    private final ImageGenerator imageGenerator;
    private final Map<String, List<PlayerScore>> scoreboards = new HashMap<>();
    
    public ScoreboardManager(Config config, DataCollector dataCollector, CacheManager cache) {
        this.config = config;
        this.dataCollector = dataCollector;
        this.cache = cache;
        this.imageGenerator = new ImageGenerator(config);
    }
    
    public void updateAll() {
        LOGGER.info("Collecting player data...");
        Map<String, PlayerData> allData = dataCollector.collectAllPlayerData();

        if (allData.isEmpty()) {
            // Still proceed — Conseil 4 boards don't depend on PlayerData and
            // should render even with zero players online/with data.
            LOGGER.warn("No player data found — continuing to render external-source boards (Conseil 4)");
            allData = Collections.emptyMap();
        } else {
            LOGGER.info("Collected data for {} players", allData.size());
            cache.setPlayerData(allData);
        }
        
        // Calculate each scoreboard
        for (ScoreboardConfig boardConfig : config.getScoreboards()) {
            if (!boardConfig.isEnabled()) {
                LOGGER.debug("Skipping disabled scoreboard: {}", boardConfig.getId());
                continue;
            }
            
            try {
                List<PlayerScore> scores = calculateScoreboard(boardConfig, allData);
                scoreboards.put(boardConfig.getId(), scores);

                // Generate image (passes dynamic title for boards that have one,
                // e.g. conseil4_ranked_failures shows the current champion's name).
                String dyn = dynamicTitles.get(boardConfig.getId());
                BufferedImage image = imageGenerator.generateScoreboard(boardConfig, scores, dyn);
                cache.cacheImage(boardConfig.getId(), image);
                
                LOGGER.info("Updated scoreboard: {} ({} players)", 
                           boardConfig.getId(), scores.size());
            } catch (Exception e) {
                LOGGER.error("Failed to update scoreboard: {}", boardConfig.getId(), e);
            }
        }
        
        cache.setLastUpdate(System.currentTimeMillis());
        LOGGER.info("All scoreboards updated successfully");
    }
    
    private List<PlayerScore> calculateScoreboard(ScoreboardConfig boardConfig, Map<String, PlayerData> allData) {
        // Special-case: Conseil 4 boards are NOT derived from PlayerData. They
        // come from a ranked JSON file written by CobbleCap (it ranks players
        // by completion order or by best chrono time). Short-circuit here.
        String id = boardConfig.getId();
        if (id.startsWith("conseil4_")) {
            return loadConseil4Board(id, boardConfig);
        }

        List<PlayerScore> scores = new ArrayList<>();
        boolean ascending = "asc".equals(boardConfig.getSortOrder());

        for (PlayerData data : allData.values()) {
            double score = calculateScore(boardConfig.getId(), data);

            // Skip players with 0 score (except for survivor where 0 deaths is good)
            if (score == 0 && !"survivor".equals(boardConfig.getId())) {
                continue;
            }

            String formatted = formatScore(boardConfig.getId(), score, data);

            scores.add(new PlayerScore(
                data.getUuid(),
                data.getPlayerName(),
                score,
                formatted,
                ascending
            ));
        }

        // Sort
        Collections.sort(scores);

        // Limit to maxPlayers
        int maxPlayers = boardConfig.getMaxPlayers();
        if (scores.size() > maxPlayers) {
            scores = new ArrayList<>(scores.subList(0, maxPlayers));
        }

        return scores;
    }

    /** Cache of dynamic titles read from Conseil 4 ranked-failures export.
     *  Keyed by scoreboard id; image generator reads via {@link #getDynamicTitle}. */
    private final java.util.Map<String, String> dynamicTitles = new java.util.concurrent.ConcurrentHashMap<>();

    public String getDynamicTitle(String boardId) {
        return dynamicTitles.get(boardId);
    }

    /**
     * Load a Conseil 4 ranking board directly from CobbleCap's JSON export.
     * Supports 4 board ids (dual-mode split 2026-05-17):
     *   conseil4_first_finish      — CLASSIC: first finishers (rank asc)
     *   conseil4_fastest_time      — CLASSIC: fastest chronos (bestTimeMs asc)
     *   conseil4_ranked_champions  — RANKED: champion history (startedAt desc → newest on top)
     *   conseil4_ranked_failures   — RANKED: losses vs current champion (failures desc)
     *
     * The "ranking" array is pre-sorted by CobbleCap; we just turn each entry
     * into a PlayerScore. For the failures board we also capture the
     * dynamicTitle field so the rendered tile shows the current champion's name.
     */
    private List<PlayerScore> loadConseil4Board(String id, ScoreboardConfig boardConfig) {
        String fileName = switch (id) {
            case "conseil4_first_finish"      -> "cobblecheesescore_external_conseil4_first.json";
            case "conseil4_fastest_time"      -> "cobblecheesescore_external_conseil4_fastest.json";
            case "conseil4_ranked_champions"  -> "cobblecheesescore_external_conseil4_ranked_champions.json";
            case "conseil4_ranked_failures"   -> "cobblecheesescore_external_conseil4_ranked_failures.json";
            default -> null;
        };
        if (fileName == null) {
            LOGGER.warn("Unknown Conseil 4 board ID: {}", id);
            return Collections.emptyList();
        }
        Path path = FabricLoader.getInstance().getConfigDir().resolve(fileName);
        if (!Files.exists(path)) {
            LOGGER.debug("Conseil 4 export file missing: {} — empty board", path);
            return Collections.emptyList();
        }
        try (Reader r = Files.newBufferedReader(path)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            // Pick up the dynamic title if present (only the failures board sets one).
            if (root.has("dynamicTitle")) {
                dynamicTitles.put(id, root.get("dynamicTitle").getAsString());
            } else {
                dynamicTitles.remove(id);
            }
            if (!root.has("ranking")) return Collections.emptyList();
            JsonArray ranking = root.getAsJsonArray("ranking");
            List<PlayerScore> out = new ArrayList<>(ranking.size());
            boolean isFastest   = "conseil4_fastest_time".equals(id);
            boolean isChampions = "conseil4_ranked_champions".equals(id);
            boolean isFailures  = "conseil4_ranked_failures".equals(id);
            for (JsonElement el : ranking) {
                JsonObject o = el.getAsJsonObject();
                String uuid = o.has("playerUuid") ? o.get("playerUuid").getAsString() : "";
                String name = o.has("playerName") ? o.get("playerName").getAsString() : "?";
                int rank = o.has("rank") ? o.get("rank").getAsInt() : (out.size() + 1);
                double score; String formatted; boolean ascending;
                if (isFastest) {
                    long bestMs = o.has("bestTimeMs") ? o.get("bestTimeMs").getAsLong() : 0L;
                    score = bestMs / 1000.0;
                    formatted = o.has("bestTimeFormatted") ? o.get("bestTimeFormatted").getAsString()
                                                            : String.format("%.0fs", score);
                    ascending = true;
                } else if (isChampions) {
                    // Format: "#N · <date>" — CobbleCap's pre-sort puts current champion at rank 1.
                    long startedAt = o.has("startedAt") ? o.get("startedAt").getAsLong()
                                                         : o.has("completedAt") ? o.get("completedAt").getAsLong() : 0L;
                    score = rank;
                    String date = startedAt > 0
                            ? new java.text.SimpleDateFormat("dd/MM/yyyy").format(new java.util.Date(startedAt))
                            : "";
                    boolean isCurrent = o.has("isCurrent") && o.get("isCurrent").getAsBoolean();
                    formatted = (isCurrent ? "👑 " : "") + date;
                    ascending = true;
                } else if (isFailures) {
                    int fails = o.has("failures") ? o.get("failures").getAsInt() : 0;
                    score = fails;
                    formatted = fails + " échec" + (fails > 1 ? "s" : "");
                    ascending = false; // higher fail count first
                } else {
                    // first finishers — score = rank, ascending = earliest first
                    score = rank;
                    formatted = "#" + rank;
                    ascending = true;
                }
                out.add(new PlayerScore(uuid, name, score, formatted, ascending));
            }
            Collections.sort(out);
            int max = boardConfig.getMaxPlayers();
            if (out.size() > max) out = new ArrayList<>(out.subList(0, max));
            return out;
        } catch (Exception e) {
            LOGGER.warn("Failed to load Conseil 4 board {}: {}", id, e.getMessage());
            return Collections.emptyList();
        }
    }
    
    private double calculateScore(String scoreboardId, PlayerData data) {
        return switch (scoreboardId) {
            case "pokedex_completed" -> data.getPokedexCaught();
            case "shiny_hunter" -> data.getShinyCaptureCount();
            case "play_time" -> data.getPlayTimeHours();
            case "battle_elite" -> data.getTotalBattleVictoryCount();
            case "trainer_tamer" -> data.getPvWBattleVictoryCount();
            case "distance_traveled" -> data.getDistanceKm();
            case "evolutionist" -> data.getTotalEvolvedCount();
            case "pokeball_crafter" -> data.getPokeballsCrafted();
            case "pvp_champion" -> data.getPvPBattleVictoryCount();
            case "trader" -> data.getTotalTradedCount();
            case "survivor" -> data.getDeathCount();
            case "legendary_hunter" -> data.getLegendaryCaptureCount();
            
            // Types
            case "type_normal" -> data.getTypeCaptures("normal");
            case "type_water" -> data.getTypeCaptures("water");
            case "type_fire" -> data.getTypeCaptures("fire");
            case "type_grass" -> data.getTypeCaptures("grass");
            case "type_electric" -> data.getTypeCaptures("electric");
            case "type_ice" -> data.getTypeCaptures("ice");
            case "type_fighting" -> data.getTypeCaptures("fighting");
            case "type_poison" -> data.getTypeCaptures("poison");
            case "type_ground" -> data.getTypeCaptures("ground");
            case "type_flying" -> data.getTypeCaptures("flying");
            case "type_psychic" -> data.getTypeCaptures("psychic");
            case "type_bug" -> data.getTypeCaptures("bug");
            case "type_rock" -> data.getTypeCaptures("rock");
            case "type_ghost" -> data.getTypeCaptures("ghost");
            case "type_dragon" -> data.getTypeCaptures("dragon");
            case "type_dark" -> data.getTypeCaptures("dark");
            case "type_steel" -> data.getTypeCaptures("steel");
            case "type_fairy" -> data.getTypeCaptures("fairy");
            
            default -> {
                LOGGER.warn("Unknown scoreboard ID: {}", scoreboardId);
                yield 0;
            }
        };
    }
    
    private String formatScore(String scoreboardId, double score, PlayerData data) {
        return switch (scoreboardId) {
            case "pokedex_completed" -> {
                int caught = (int) score;
                int total = 1025; // Total Pokémon
                double percent = (caught / (double) total) * 100;
                yield String.format("%d/%d (%.0f%%)", caught, total, percent);
            }
            case "play_time" -> String.format("%.1fh", score);
            case "distance_traveled" -> String.format("%.1f km", score);
            case "survivor" -> String.format("%d morts", (int) score);
            default -> String.format("%d", (int) score);
        };
    }
    
    public List<PlayerScore> getScoreboard(String id) {
        return scoreboards.getOrDefault(id, new ArrayList<>());
    }
    
    public Map<String, List<PlayerScore>> getAllScoreboards() {
        return new HashMap<>(scoreboards);
    }
}
