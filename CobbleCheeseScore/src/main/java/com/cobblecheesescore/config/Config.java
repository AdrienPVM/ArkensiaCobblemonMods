package com.cobblecheesescore.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Config {
    private static final Logger LOGGER = LoggerFactory.getLogger("CobbleCheeseScore");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    private int port = 44310;
    private int updateIntervalMinutes = 10;
    private String timeZone = "Europe/Paris";
    private boolean autoDetectDataFolder = true;
    private String manualDataFolderPath = "";
    private int defaultMaxPlayers = 30;
    private int defaultRowsPerColumn = 10;
    private boolean showLastUpdate = true;
    private String lastUpdateText = "Dernière mise à jour :";
    private boolean enableWebInterface = true;
    private boolean enableJsonApi = true;
    private boolean enableDiscordWebhook = false;
    private String discordWebhookUrl = "";
    private boolean enableHistory = true;
    private int historyRetentionDays = 30;
    
    private Map<String, String> defaultColors = new HashMap<>();
    private List<ScoreboardConfig> scoreboards = new ArrayList<>();
    
    public static Config load() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("cobblecheesescore.json");

        Config config;
        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                config = GSON.fromJson(json, Config.class);
                LOGGER.info("Configuration loaded from {}", configPath);
            } catch (IOException e) {
                LOGGER.error("Failed to load config", e);
                config = loadDefaultConfig();
            }
        } else {
            LOGGER.info("Creating default configuration...");
            config = loadDefaultConfig();
        }
        // Inject Conseil 4 boards if absent — they're fed by CobbleCap's JSON
        // exports and should appear in every install without manual config edit.
        boolean added = config.ensureConseil4Boards();
        if (added || !Files.exists(configPath)) {
            config.save(configPath);
        }
        return config;
    }

    /** Add the four Conseil 4 leaderboard entries to {@link #scoreboards} if
     *  missing. Returns true if any were added (config should be re-saved).
     *  As of 2026-05-17, Conseil 4 has dual modes:
     *    Classique ⚔ : conseil4_first_finish (gold), conseil4_fastest_time (cyan)
     *    Compétitif 🏆: conseil4_ranked_champions (purple), conseil4_ranked_failures (red).
     *  The failures board's title is dynamic (current champion's name). */
    private boolean ensureConseil4Boards() {
        if (scoreboards == null) scoreboards = new ArrayList<>();
        boolean modified = false;
        modified |= seedBoard("conseil4_first_finish", "conseil4_first.png",
                "Conseil 4 ⚔ Premiers vainqueurs",
                "Mode Classique — ordre d'accession au titre de Maître de la Ligue",
                "asc", "#FFD700");
        modified |= seedBoard("conseil4_fastest_time", "conseil4_fastest.png",
                "Conseil 4 ⚔ Temps les plus rapides",
                "Mode Classique — meilleurs chronos de la Tour du Conseil",
                "asc", "#22d3ee");
        modified |= seedBoard("conseil4_ranked_champions", "conseil4_ranked_champions.png",
                "Conseil 4 🏆 Champions en titre",
                "Mode Compétitif — historique des Maîtres de la Ligue (le tenant en haut)",
                "asc", "#8B5CF6");
        modified |= seedBoard("conseil4_ranked_failures", "conseil4_ranked_failures.png",
                "Conseil 4 🏆 Échecs face au Maître",
                "Mode Compétitif — joueurs ayant échoué contre le tenant en titre (reset à chaque changement)",
                "desc", "#ef4444");
        return modified;
    }

    private boolean seedBoard(String id, String filename, String title, String desc,
                                String sortOrder, String titleBg) {
        if (getScoreboardById(id) != null) return false;
        ScoreboardConfig sc = new ScoreboardConfig();
        sc.setId(id);
        sc.setEnabled(true);
        sc.setFilename(filename);
        sc.setTitle(title);
        sc.setDescription(desc);
        sc.setSortOrder(sortOrder);
        sc.setMaxPlayers(30);
        sc.setRowsPerColumn(10);
        sc.setColors(new HashMap<>(Map.of("titleBackground", titleBg)));
        scoreboards.add(sc);
        LOGGER.info("Seeded scoreboard: {}", id);
        return true;
    }
    
    private static Config loadDefaultConfig() {
        try {
            InputStream resourceStream = Config.class.getResourceAsStream("/cobblecheesescore-default.json");
            if (resourceStream != null) {
                String json = new String(resourceStream.readAllBytes());
                return GSON.fromJson(json, Config.class);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load default config from resources", e);
        }
        
        // Fallback to empty config
        return new Config();
    }
    
    public void save(Path path) {
        try {
            Files.createDirectories(path.getParent());
            String json = GSON.toJson(this);
            Files.writeString(path, json);
            LOGGER.info("Configuration saved to {}", path);
        } catch (IOException e) {
            LOGGER.error("Failed to save config", e);
        }
    }
    
    // Getters
    public int getPort() { return port; }
    public int getUpdateIntervalMinutes() { return updateIntervalMinutes; }
    public String getTimeZone() { return timeZone; }
    public boolean isAutoDetectDataFolder() { return autoDetectDataFolder; }
    public String getManualDataFolderPath() { return manualDataFolderPath; }
    public int getDefaultMaxPlayers() { return defaultMaxPlayers; }
    public int getDefaultRowsPerColumn() { return defaultRowsPerColumn; }
    public boolean isShowLastUpdate() { return showLastUpdate; }
    public String getLastUpdateText() { return lastUpdateText; }
    public boolean isEnableWebInterface() { return enableWebInterface; }
    public boolean isEnableJsonApi() { return enableJsonApi; }
    public boolean isEnableDiscordWebhook() { return enableDiscordWebhook; }
    public String getDiscordWebhookUrl() { return discordWebhookUrl; }
    public boolean isEnableHistory() { return enableHistory; }
    public int getHistoryRetentionDays() { return historyRetentionDays; }
    public Map<String, String> getDefaultColors() { return defaultColors; }
    public List<ScoreboardConfig> getScoreboards() { return scoreboards; }
    
    public ScoreboardConfig getScoreboardById(String id) {
        return scoreboards.stream()
            .filter(s -> s.getId().equals(id))
            .findFirst()
            .orElse(null);
    }
}
