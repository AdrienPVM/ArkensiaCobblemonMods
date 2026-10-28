package com.cobblecheesescore.data;

import com.cobblecheesescore.config.Config;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * Collects all player data from various sources
 */
public class DataCollector {
    private static final Logger LOGGER = LoggerFactory.getLogger("CobbleCheeseScore");
    
    private final MinecraftServer server;
    private final Config config;
    private Path worldPath;
    
    public DataCollector(MinecraftServer server, Config config) {
        this.server = server;
        this.config = config;
        this.worldPath = detectWorldPath();
    }
    
    private Path detectWorldPath() {
        if (!config.isAutoDetectDataFolder() && !config.getManualDataFolderPath().isEmpty()) {
            Path manual = Paths.get(config.getManualDataFolderPath());
            if (Files.exists(manual)) {
                LOGGER.info("Using manual world path: {}", manual);
                return manual;
            } else {
                LOGGER.warn("Manual path does not exist: {}", manual);
            }
        }
        
        // Auto-detect from server: world root = run directory + level name (Yarn 1.21.1)
        try {
            String levelName = server.getSaveProperties().getLevelName();
            Path worldDir = server.getPath(levelName);
            LOGGER.info("Auto-detected world path: {}", worldDir);
            return worldDir;
        } catch (Exception e) {
            LOGGER.error("Failed to auto-detect world path", e);
            return Paths.get("world");
        }
    }
    
    /**
     * Collect data for all players
     */
    public Map<String, PlayerData> collectAllPlayerData() {
        Map<String, PlayerData> allData = new HashMap<>();
        
        // Find all player UUIDs from pokedex folder
        Path pokedexPath = worldPath.resolve("pokedex");
        if (!Files.exists(pokedexPath)) {
            LOGGER.warn("Pokedex folder not found: {}", pokedexPath);
            return allData;
        }
        
        try (Stream<Path> paths = Files.walk(pokedexPath, 2)) {
            paths.filter(p -> p.toString().endsWith(".nbt"))
                 .forEach(nbtFile -> {
                     String uuid = extractUuidFromPath(nbtFile);
                     if (uuid != null) {
                         PlayerData data = collectPlayerData(uuid);
                         if (data != null) {
                             allData.put(uuid, data);
                         }
                     }
                 });
        } catch (IOException e) {
            LOGGER.error("Error walking pokedex directory", e);
        }
        
        LOGGER.info("Collected data for {} players", allData.size());
        return allData;
    }
    
    /**
     * Collect data for a single player
     */
    public PlayerData collectPlayerData(String uuid) {
        PlayerData playerData = new PlayerData(uuid);
        
        // Get player name
        playerData.setPlayerName(getPlayerName(uuid));
        
        // Parse Pokédex NBT
        Path pokedexFile = findPokedexFile(uuid);
        if (pokedexFile != null && Files.exists(pokedexFile)) {
            PokedexNBTParser.parsePokedex(pokedexFile, playerData);
            LOGGER.debug("Parsed Pokédex for {}: {} species", uuid, playerData.pokedexData.size());
        } else {
            LOGGER.warn("Pokédex file not found for {}", uuid);
        }
        
        // Parse Cobblemon data JSON
        Path cobblemonFile = findCobblemonDataFile(uuid);
        if (cobblemonFile != null && Files.exists(cobblemonFile)) {
            CobblemonDataParser.parseCobblemonData(cobblemonFile, playerData);
            LOGGER.debug("Parsed Cobblemon data for {}: {} keys", uuid, playerData.cobblemonData.size());
        } else {
            LOGGER.warn("Cobblemon data file not found for {}", uuid);
        }
        
        // Parse Minecraft stats JSON
        Path statsFile = findMinecraftStatsFile(uuid);
        if (statsFile != null && Files.exists(statsFile)) {
            CobblemonDataParser.parseMinecraftStats(statsFile, playerData);
            LOGGER.debug("Parsed Minecraft stats for {}: {} stats", uuid, playerData.minecraftStats.size());
        } else {
            LOGGER.warn("Minecraft stats file not found for {}", uuid);
        }
        
        return playerData;
    }
    
    private Path findPokedexFile(String uuid) {
        // Try: pokedex/XX/uuid.nbt (with subdirectory - 2 first chars of UUID)
        String prefix = uuid.substring(0, 2);
        Path withSubdir = worldPath.resolve("pokedex").resolve(prefix).resolve(uuid + ".nbt");
        if (Files.exists(withSubdir)) {
            LOGGER.debug("Found Pokédex file with subdirectory: {}", withSubdir);
            return withSubdir;
        }
        
        // Try: pokedex/uuid.nbt (direct)
        Path direct = worldPath.resolve("pokedex").resolve(uuid + ".nbt");
        if (Files.exists(direct)) {
            LOGGER.debug("Found Pokédex file direct: {}", direct);
            return direct;
        }
        
        LOGGER.warn("Pokédex file not found for UUID: {} (tried both {} and {})", uuid, withSubdir, direct);
        return null;
    }
    
    private Path findCobblemonDataFile(String uuid) {
        // Try: cobblemonplayerdata/XX/uuid.json (with subdirectory - 2 first chars of UUID)
        String prefix = uuid.substring(0, 2);
        Path withSubdir = worldPath.resolve("cobblemonplayerdata").resolve(prefix).resolve(uuid + ".json");
        if (Files.exists(withSubdir)) {
            LOGGER.debug("Found Cobblemon data file with subdirectory: {}", withSubdir);
            return withSubdir;
        }
        
        // Try: cobblemonplayerdata/uuid.json (direct)
        Path direct = worldPath.resolve("cobblemonplayerdata").resolve(uuid + ".json");
        if (Files.exists(direct)) {
            LOGGER.debug("Found Cobblemon data file direct: {}", direct);
            return direct;
        }
        
        LOGGER.warn("Cobblemon data file not found for UUID: {} (tried both {} and {})", uuid, withSubdir, direct);
        return null;
    }
    
    private Path findMinecraftStatsFile(String uuid) {
        // Stats are usually directly in stats/ folder (no subdirectory)
        Path direct = worldPath.resolve("stats").resolve(uuid + ".json");
        if (Files.exists(direct)) {
            LOGGER.debug("Found Minecraft stats file: {}", direct);
            return direct;
        }
        
        LOGGER.warn("Minecraft stats file not found for UUID: {}", uuid);
        return null;
    }
    
    private String extractUuidFromPath(Path path) {
        String filename = path.getFileName().toString();
        if (filename.endsWith(".nbt")) {
            return filename.substring(0, filename.length() - 4);
        }
        return null;
    }
    
    private String getPlayerName(String uuid) {
        // Try to get from online players first
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.getUuidAsString().equals(uuid)) {
                return player.getName().getString();
            }
        }
        
        // Try to get from UserCache
        try {
            var profile = server.getUserCache().getByUuid(UUID.fromString(uuid));
            if (profile.isPresent()) {
                return profile.get().getName();
            }
        } catch (Exception e) {
            LOGGER.debug("Could not get player name from cache for {}", uuid);
        }
        
        // Fallback to UUID
        return uuid.substring(0, 8);
    }
}
