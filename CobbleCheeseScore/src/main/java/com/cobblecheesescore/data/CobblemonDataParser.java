package com.cobblecheesescore.data;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Parses Cobblemon JSON files from /world/cobblemonplayerdata/[uuid].json
 * and Minecraft stats from /world/stats/[uuid].json
 */
public class CobblemonDataParser {
    private static final Logger LOGGER = LoggerFactory.getLogger("CobbleCheeseScore");
    private static final Gson GSON = new Gson();
    
    @SuppressWarnings("unchecked")
    public static void parseCobblemonData(Path jsonFile, PlayerData playerData) {
        try {
            String json = Files.readString(jsonFile);
            Map<String, Object> data = GSON.fromJson(json, Map.class);
            playerData.cobblemonData = data;
            
            LOGGER.debug("Parsed Cobblemon data for {}", playerData.getUuid());
        } catch (IOException | JsonSyntaxException e) {
            LOGGER.error("Failed to parse Cobblemon JSON: {}", jsonFile, e);
        }
    }
    
    @SuppressWarnings("unchecked")
    public static void parseMinecraftStats(Path statsFile, PlayerData playerData) {
        try {
            String json = Files.readString(statsFile);
            Map<String, Object> root = GSON.fromJson(json, Map.class);
            
            // Navigate to stats object
            Object statsObj = root.get("stats");
            if (!(statsObj instanceof Map)) {
                LOGGER.warn("No stats object found in {}", statsFile);
                return;
            }
            
            Map<String, Object> stats = (Map<String, Object>) statsObj;
            
            // Merge all stat categories into both flat + categorized maps
            for (Map.Entry<String, Object> category : stats.entrySet()) {
                if (category.getValue() instanceof Map) {
                    String categoryKey = category.getKey();
                    Map<String, Object> categoryStats = (Map<String, Object>) category.getValue();
                    Map<String, Integer> catMap = playerData.categorizedStats
                        .computeIfAbsent(categoryKey, k -> new java.util.HashMap<>());
                    for (Map.Entry<String, Object> stat : categoryStats.entrySet()) {
                        if (stat.getValue() instanceof Number) {
                            int val = ((Number) stat.getValue()).intValue();
                            // Flat map for backward compat (works for minecraft:custom)
                            playerData.minecraftStats.put(stat.getKey(), val);
                            // Categorized map for category-specific lookups
                            catMap.put(stat.getKey(), val);
                        }
                    }
                }
            }
            
            LOGGER.debug("Parsed Minecraft stats for {}: {} entries", 
                        playerData.getUuid(), playerData.minecraftStats.size());
            
        } catch (IOException | JsonSyntaxException e) {
            LOGGER.error("Failed to parse Minecraft stats: {}", statsFile, e);
        }
    }
}
