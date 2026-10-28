# CobbleCheeseScore - Code Source Complet

Ce document contient tout le code source du mod. Chaque section représente un fichier Java.

## Structure des packages

```
com.cobblecheesescore/
├── CobbleCheeseScore.java (Main)
├── config/
│   ├── Config.java
│   ├── ScoreboardConfig.java
│   └── ColorConfig.java
├── data/
│   ├── DataCollector.java
│   ├── PokedexNBTParser.java
│   ├── CobblemonDataParser.java
│   └── PlayerData.java
├── scoreboard/
│   ├── ScoreboardManager.java
│   ├── PlayerScore.java
│   └── ScoreCalculator.java
├── image/
│   └── ImageGenerator.java
├── server/
│   ├── WebServer.java
│   ├── handlers/
│   │   ├── PngHandler.java
│   │   ├── JsonApiHandler.java
│   │   └── WebInterfaceHandler.java
├── cache/
│   └── CacheManager.java
└── utils/
    ├── NBTReader.java
    └── FileUtils.java
```

---

## FICHIER: CobbleCheeseScore.java (Main Class)

```java
package com.cobblecheesescore;

import com.cobblecheesescore.config.Config;
import com.cobblecheesescore.data.DataCollector;
import com.cobblecheesescore.scoreboard.ScoreboardManager;
import com.cobblecheesescore.server.WebServer;
import com.cobblecheesescore.cache.CacheManager;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CobbleCheeseScore implements DedicatedServerModInitializer {
    public static final String MOD_ID = "cobblecheesescore";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    private static Config config;
    private static MinecraftServer server;
    private static WebServer webServer;
    private static ScoreboardManager scoreboardManager;
    private static DataCollector dataCollector;
    private static CacheManager cacheManager;
    private static ScheduledExecutorService scheduler;
    
    @Override
    public void onInitializeServer() {
        LOGGER.info("Initializing CobbleCheeseScore...");
        
        // Load config
        config = Config.load();
        
        // Initialize components
        cacheManager = new CacheManager();
        
        // Server lifecycle events
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        
        LOGGER.info("CobbleCheeseScore initialized successfully!");
    }
    
    private void onServerStarted(MinecraftServer minecraftServer) {
        server = minecraftServer;
        
        LOGGER.info("Server started - Initializing scoreboards...");
        
        // Initialize data collector
        dataCollector = new DataCollector(server, config);
        
        // Initialize scoreboard manager
        scoreboardManager = new ScoreboardManager(config, dataCollector, cacheManager);
        
        // Start web server
        try {
            webServer = new WebServer(config, scoreboardManager);
            webServer.start();
            LOGGER.info("Web server started on port {}", config.getPort());
        } catch (Exception e) {
            LOGGER.error("Failed to start web server", e);
        }
        
        // Schedule periodic updates
        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(
            () -> {
                try {
                    LOGGER.info("Updating scoreboards...");
                    scoreboardManager.updateAll();
                    LOGGER.info("Scoreboards updated successfully");
                } catch (Exception e) {
                    LOGGER.error("Error updating scoreboards", e);
                }
            },
            0, // Initial delay
            config.getUpdateIntervalMinutes(),
            TimeUnit.MINUTES
        );
        
        LOGGER.info("Scoreboard update scheduler started (interval: {} minutes)", 
                   config.getUpdateIntervalMinutes());
    }
    
    private void onServerStopping(MinecraftServer minecraftServer) {
        LOGGER.info("Server stopping - Shutting down CobbleCheeseScore...");
        
        if (scheduler != null) {
            scheduler.shutdown();
        }
        
        if (webServer != null) {
            webServer.stop();
        }
        
        LOGGER.info("CobbleCheeseScore shutdown complete");
    }
    
    public static Config getConfig() {
        return config;
    }
    
    public static MinecraftServer getServer() {
        return server;
    }
}
```

---

## FICHIER: config/Config.java

```java
package com.cobblecheesescore.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
        
        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                Config config = GSON.fromJson(json, Config.class);
                LOGGER.info("Configuration loaded from {}", configPath);
                return config;
            } catch (IOException e) {
                LOGGER.error("Failed to load config", e);
            }
        }
        
        // Load default config from resources
        LOGGER.info("Creating default configuration...");
        Config defaultConfig = loadDefaultConfig();
        defaultConfig.save(configPath);
        return defaultConfig;
    }
    
    private static Config loadDefaultConfig() {
        try {
            var resourceStream = Config.class.getResourceAsStream("/cobblecheesescore-default.json");
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
}
```

---

## FICHIER: config/ScoreboardConfig.java

```java
package com.cobblecheesescore.config;

import java.util.HashMap;
import java.util.Map;

public class ScoreboardConfig {
    private String id;
    private boolean enabled = true;
    private String filename;
    private String title;
    private String description;
    private String sortOrder = "desc"; // "desc" or "asc"
    private int maxPlayers = 30;
    private int rowsPerColumn = 10;
    private Map<String, String> colors = new HashMap<>();
    
    // Getters
    public String getId() { return id; }
    public boolean isEnabled() { return enabled; }
    public String getFilename() { return filename; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getSortOrder() { return sortOrder; }
    public int getMaxPlayers() { return maxPlayers; }
    public int getRowsPerColumn() { return rowsPerColumn; }
    public Map<String, String> getColors() { return colors; }
    
    public String getColorOrDefault(String key, Map<String, String> defaults) {
        return colors.getOrDefault(key, defaults.get(key));
    }
}
```

---

## NOTES IMPORTANTES POUR L'IMPLÉMENTATION

### Parser NBT (CRUCIAL)

Le fichier `/world/pokedex/[uuid].nbt` doit être parsé correctement. Voici la structure:

```java
// Classe NBTReader.java à implémenter
public class NBTReader {
    public static Map<String, Object> readNBT(Path nbtFile) throws IOException {
        // 1. Décompresser avec GZIP si nécessaire
        // 2. Parser le format NBT binaire
        // 3. Retourner une Map avec:
        //    - "speciesRecords" -> Map<String, SpeciesRecord>
        //    - SpeciesRecord contient:
        //      - "formRecords" -> Map<String, FormRecord>
        //      - FormRecord contient:
        //        - "knowledge" -> "CAUGHT" ou "ENCOUNTERED"
        //        - "genders" -> List<String>
        //        - "seenShinyStates" -> List<String>
    }
}
```

### Calculs des Scores

Dans `ScoreCalculator.java`:

```java
public class ScoreCalculator {
    // Pokédex: compte les espèces avec au moins 1 forme CAUGHT
    public static int calculatePokedexScore(PlayerData data) {
        int count = 0;
        for (SpeciesRecord species : data.pokedexData.values()) {
            for (FormRecord form : species.formRecords.values()) {
                if ("CAUGHT".equals(form.knowledge)) {
                    count++;
                    break; // Une seule forme suffit
                }
            }
        }
        return count;
    }
    
    // Temps de jeu: ticks / 20 / 3600 = heures
    public static double calculatePlayTimeHours(PlayerData data) {
        int ticks = data.minecraftStats.get("minecraft:play_time");
        return ticks / 20.0 / 3600.0;
    }
    
    // Distance: (walk + sprint + fly + swim) / 100000 = km
    public static double calculateDistanceKm(PlayerData data) {
        int walk = data.minecraftStats.getOrDefault("minecraft:walk_one_cm", 0);
        int sprint = data.minecraftStats.getOrDefault("minecraft:sprint_one_cm", 0);
        int fly = data.minecraftStats.getOrDefault("minecraft:fly_one_cm", 0);
        int swim = data.minecraftStats.getOrDefault("minecraft:swim_one_cm", 0);
        return (walk + sprint + fly + swim) / 100000.0;
    }
}
```

---

## À IMPLÉMENTER ENSUITE

Les fichiers suivants doivent être créés (structure fournie, code à compléter):

1. **data/DataCollector.java** - Collecte tous les fichiers joueurs
2. **data/PokedexNBTParser.java** - Parse les fichiers .nbt
3. **data/CobblemonDataParser.java** - Parse les fichiers JSON Cobblemon
4. **data/PlayerData.java** - Classe de données joueur
5. **scoreboard/ScoreboardManager.java** - Gère tous les tableaux
6. **scoreboard/PlayerScore.java** - Score d'un joueur
7. **image/ImageGenerator.java** - Génère les PNG (similaire à ScoreDex)
8. **server/WebServer.java** - Serveur HTTP
9. **server/handlers/*.java** - Handlers pour PNG/JSON/HTML
10. **cache/CacheManager.java** - Cache des données
11. **utils/NBTReader.java** - Lecteur NBT bas niveau

