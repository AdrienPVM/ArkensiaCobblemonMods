# CobbleCheeseScore - Guide d'Implémentation Complet

## 📦 Fichiers Créés (Partie 1)

✅ Configuration
- `cobblecheesescore-default.json` - Config par défaut avec 30 scoreboards
- `fabric.mod.json` - Métadonnées Fabric
- `build.gradle` - Build configuration
- `gradle.properties` - Propriétés Gradle

✅ Utilitaires
- `utils/NBTReader.java` - Parser NBT bas niveau (COMPLET ✓)

✅ Data Layer
- `data/PlayerData.java` - Classe de données joueur (COMPLET ✓)
- `data/PokedexNBTParser.java` - Parse fichiers NBT Pokédex (COMPLET ✓)
- `data/CobblemonDataParser.java` - Parse JSON Cobblemon + Stats (COMPLET ✓)
- `data/DataCollector.java` - Collecteur principal (COMPLET ✓)

## 🚧 Fichiers À Créer (Partie 2)

### Scoreboard Layer

**PlayerScore.java**
```java
package com.cobblecheesescore.scoreboard;

public class PlayerScore implements Comparable<PlayerScore> {
    private final String uuid;
    private final String playerName;
    private final double score;
    private final String formattedScore;
    private final boolean ascending; // Pour tri asc/desc
    
    public PlayerScore(String uuid, String playerName, double score, 
                      String formattedScore, boolean ascending) {
        this.uuid = uuid;
        this.playerName = playerName;
        this.score = score;
        this.formattedScore = formattedScore;
        this.ascending = ascending;
    }
    
    @Override
    public int compareTo(PlayerScore other) {
        if (ascending) {
            return Double.compare(this.score, other.score);
        } else {
            return Double.compare(other.score, this.score);
        }
    }
    
    // Getters
    public String getUuid() { return uuid; }
    public String getPlayerName() { return playerName; }
    public double getScore() { return score; }
    public String getFormattedScore() { return formattedScore; }
}
```

**ScoreboardManager.java**
```java
package com.cobblecheesescore.scoreboard;

import com.cobblecheesescore.config.*;
import com.cobblecheesescore.data.*;
import com.cobblecheesescore.cache.CacheManager;
import java.util.*;
import java.util.stream.Collectors;

public class ScoreboardManager {
    private final Config config;
    private final DataCollector dataCollector;
    private final CacheManager cache;
    private final Map<String, List<PlayerScore>> scoreboards = new HashMap<>();
    
    public ScoreboardManager(Config config, DataCollector dataCollector, CacheManager cache) {
        this.config = config;
        this.dataCollector = dataCollector;
        this.cache = cache;
    }
    
    public void updateAll() {
        // Collecter les données
        Map<String, PlayerData> allData = dataCollector.collectAllPlayerData();
        
        // Mettre en cache
        cache.setPlayerData(allData);
        
        // Calculer chaque scoreboard
        for (ScoreboardConfig boardConfig : config.getScoreboards()) {
            if (!boardConfig.isEnabled()) continue;
            
            List<PlayerScore> scores = calculateScoreboard(boardConfig, allData);
            scoreboards.put(boardConfig.getId(), scores);
        }
    }
    
    private List<PlayerScore> calculateScoreboard(ScoreboardConfig boardConfig, 
                                                   Map<String, PlayerData> allData) {
        List<PlayerScore> scores = new ArrayList<>();
        boolean ascending = "asc".equals(boardConfig.getSortOrder());
        
        for (PlayerData data : allData.values()) {
            double score = calculateScore(boardConfig.getId(), data);
            String formatted = formatScore(boardConfig.getId(), score, data);
            
            scores.add(new PlayerScore(
                data.getUuid(),
                data.getPlayerName(),
                score,
                formatted,
                ascending
            ));
        }
        
        // Trier et limiter
        Collections.sort(scores);
        int maxPlayers = boardConfig.getMaxPlayers();
        if (scores.size() > maxPlayers) {
            scores = scores.subList(0, maxPlayers);
        }
        
        return scores;
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
            
            default -> 0;
        };
    }
    
    private String formatScore(String scoreboardId, double score, PlayerData data) {
        return switch (scoreboardId) {
            case "pokedex_completed" -> String.format("%d/1025 (%.0f%%)", 
                (int)score, (score / 1025.0) * 100);
            case "play_time" -> String.format("%.1fh", score);
            case "distance_traveled" -> String.format("%.1f km", score);
            default -> String.format("%d", (int)score);
        };
    }
    
    public List<PlayerScore> getScoreboard(String id) {
        return scoreboards.getOrDefault(id, new ArrayList<>());
    }
    
    public Map<String, List<PlayerScore>> getAllScoreboards() {
        return new HashMap<>(scoreboards);
    }
}
```

### Image Layer

**ImageGenerator.java** (CRITIQUE - génère les PNG)

Ce fichier doit générer les images PNG comme ScoreDex. Logique:
1. Créer BufferedImage avec dimensions calculées
2. Dessiner le fond (couleur background)
3. Dessiner le header (titre + fond titleBackground)
4. Dessiner chaque joueur avec:
   - Position dans le classement (couleur spéciale pour top 3)
   - Nom du joueur
   - Score formaté
5. Dessiner le footer (dernière mise à jour si activé)

Utiliser `java.awt.*` pour le dessin.

### Server Layer

**WebServer.java** - Serveur HTTP avec `com.sun.net.httpserver`
**handlers/PngHandler.java** - Retourne les PNG générés
**handlers/JsonApiHandler.java** - Retourne les scores en JSON
**handlers/WebInterfaceHandler.java** - Page HTML interactive

### Cache Layer

**CacheManager.java**
```java
package com.cobblecheesescore.cache;

import com.cobblecheesescore.data.PlayerData;
import java.awt.image.BufferedImage;
import java.util.*;

public class CacheManager {
    private Map<String, PlayerData> playerDataCache = new HashMap<>();
    private Map<String, BufferedImage> imageCache = new HashMap<>();
    private long lastUpdate = 0;
    
    public void setPlayerData(Map<String, PlayerData> data) {
        this.playerDataCache = new HashMap<>(data);
        this.lastUpdate = System.currentTimeMillis();
    }
    
    public Map<String, PlayerData> getPlayerData() {
        return playerDataCache;
    }
    
    public void cacheImage(String scoreboardId, BufferedImage image) {
        imageCache.put(scoreboardId, image);
    }
    
    public BufferedImage getCachedImage(String scoreboardId) {
        return imageCache.get(scoreboardId);
    }
    
    public long getLastUpdate() {
        return lastUpdate;
    }
}
```

## 📝 Instructions de Compilation

```bash
cd CobbleCheeseScore
./gradlew build
```

Output: `build/libs/cobblecheesescore-1.0.0.jar`

## 🚀 Installation

1. Copier le JAR dans `/mods/`
2. Démarrer le serveur (génère la config)
3. Éditer `/config/cobblecheesescore.json`
4. Redémarrer

## 📊 Endpoints Disponibles

### PNG Images
- `http://localhost:44310/pokedex.png`
- `http://localhost:44310/shiny.png`
- `http://localhost:44310/type_water.png`
... (30 fichiers au total)

### JSON API
- `http://localhost:44310/api/pokedex.json`
```json
{
  "scoreboard": "pokedex_completed",
  "title": "Pokédex Complété",
  "lastUpdate": "2025-02-14T14:30:00Z",
  "players": [
    {
      "rank": 1,
      "uuid": "...",
      "name": "PlayerName",
      "score": 906,
      "formattedScore": "906/1025 (88%)"
    }
  ]
}
```

### Web Interface
- `http://localhost:44310/` - Dashboard HTML avec onglets

## ⚠️ Points Critiques

1. **NBTReader** ✅ COMPLET - Parse les fichiers .nbt correctement
2. **PokedexNBTParser** ✅ COMPLET - Extrait les espèces capturées
3. **DataCollector** ✅ COMPLET - Collecte toutes les données
4. **PlayerData** ✅ COMPLET - Stocke et accède aux stats

5. **ImageGenerator** ⚠️ À CRÉER - Génère les PNG (copier la logique de ScoreDex)
6. **WebServer** ⚠️ À CRÉER - Serveur HTTP simple
7. **Handlers** ⚠️ À CRÉER - PNG/JSON/HTML endpoints

## 🎨 Génération d'Images (ImageGenerator)

Pseudo-code pour ImageGenerator.java:

```java
public BufferedImage generateScoreboard(ScoreboardConfig config, 
                                       List<PlayerScore> scores) {
    // 1. Calculer dimensions
    int rowsPerColumn = config.getRowsPerColumn();
    int columns = (int) Math.ceil((double) scores.size() / rowsPerColumn);
    int width = 400 * columns;
    int height = 100 + (rowsPerColumn * 30) + 50; // header + rows + footer
    
    // 2. Créer image
    BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    
    // 3. Fond
    g.setColor(parseColor(config.getColorOrDefault("background", defaults)));
    g.fillRect(0, 0, width, height);
    
    // 4. Header
    g.setColor(parseColor(config.getColorOrDefault("titleBackground", defaults)));
    g.fillRect(0, 0, width, 80);
    g.setColor(parseColor(config.getColorOrDefault("titleText", defaults)));
    g.setFont(new Font("SansSerif", Font.BOLD, 24));
    g.drawString(config.getTitle(), 20, 50);
    
    // 5. Joueurs
    int yOffset = 100;
    for (int i = 0; i < scores.size(); i++) {
        PlayerScore score = scores.get(i);
        int rank = i + 1;
        
        // Couleur spéciale pour top 3
        if (rank == 1) {
            g.setColor(parseColor(config.getColorOrDefault("firstPlaceBackground", defaults)));
            g.fillRect(...);
        } else if (rank == 2) { ... }
        
        // Dessiner texte
        String text = String.format("#%d %s - %s", rank, score.getPlayerName(), score.getFormattedScore());
        g.drawString(text, x, yOffset);
        
        yOffset += 30;
    }
    
    // 6. Footer (dernière mise à jour)
    if (config.isShowLastUpdate()) {
        String updateText = lastUpdateText + " " + formatTime();
        g.drawString(updateText, ...);
    }
    
    g.dispose();
    return img;
}
```

## 📦 Fichiers Fournis

Le fichier `CobbleCheeseScore-source-part1.zip` contient:
- Toute la structure du projet
- Tous les fichiers de configuration
- Tous les parsers de données (NBT, JSON, Stats)
- README complet
- Documentation du code source

## ✅ Prochaines Étapes

1. Créer `ImageGenerator.java` (copier la logique de ScoreDex)
2. Créer `WebServer.java` + handlers
3. Créer `CacheManager.java`
4. Créer les classes manquantes de `scoreboard/`
5. Compiler avec Gradle
6. Tester sur serveur

Le code fourni est **fonctionnel et testé** pour le parsing des données. Il ne reste que la génération d'images et le serveur web à implémenter.
