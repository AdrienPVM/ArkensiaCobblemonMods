package com.cobblecheesescore.data;

import java.util.*;

/**
 * Holds all data for a single player from multiple sources
 */
public class PlayerData {
    private final String uuid;
    private String playerName;
    
    // From pokedex/[uuid].nbt
    public Map<String, SpeciesRecord> pokedexData = new HashMap<>();
    
    // From cobblemonplayerdata/[uuid].json
    public Map<String, Object> cobblemonData = new HashMap<>();
    
    // From stats/[uuid].json (flat — custom stats only, e.g. "minecraft:play_time")
    public Map<String, Integer> minecraftStats = new HashMap<>();

    // From stats/[uuid].json (categorized — category → stat → value)
    public Map<String, Map<String, Integer>> categorizedStats = new HashMap<>();
    
    public PlayerData(String uuid) {
        this.uuid = uuid;
    }
    
    public String getUuid() {
        return uuid;
    }
    
    public String getPlayerName() {
        return playerName;
    }
    
    public void setPlayerName(String name) {
        this.playerName = name;
    }
    
    // Helper methods for common stats
    public int getPokedexCaught() {
        int count = 0;
        for (SpeciesRecord species : pokedexData.values()) {
            if (species.hasAnyCaught()) {
                count++;
            }
        }
        return count;
    }
    
    public int getPokedexSeen() {
        return pokedexData.size();
    }
    
    public int getShinyCaptureCount() {
        return getIntFromCobblemon("totalShinyCaptureCount");
    }
    
    public int getTotalCaptureCount() {
        return getIntFromCobblemon("totalCaptureCount");
    }
    
    public int getTotalEvolvedCount() {
        return getIntFromCobblemon("totalEvolvedCount");
    }
    
    public int getTotalBattleVictoryCount() {
        return getIntFromCobblemon("totalBattleVictoryCount");
    }
    
    public int getPvPBattleVictoryCount() {
        return getIntFromCobblemon("totalPvPBattleVictoryCount");
    }
    
    public int getPvWBattleVictoryCount() {
        return getIntFromCobblemon("totalPvWBattleVictoryCount");
    }
    
    public int getTotalTradedCount() {
        return getIntFromCobblemon("totalTradedCount");
    }
    
    public int getTypeCaptures(String type) {
        Map<String, Integer> typeCounts = getTypeCaptureCountsFromCobblemon();
        return typeCounts.getOrDefault(type.toLowerCase(), 0);
    }
    
    public double getPlayTimeHours() {
        int ticks = minecraftStats.getOrDefault("minecraft:play_time", 0);
        return ticks / 20.0 / 3600.0;
    }
    
    public double getDistanceKm() {
        int walk = minecraftStats.getOrDefault("minecraft:walk_one_cm", 0);
        int sprint = minecraftStats.getOrDefault("minecraft:sprint_one_cm", 0);
        int fly = minecraftStats.getOrDefault("minecraft:fly_one_cm", 0);
        int swim = minecraftStats.getOrDefault("minecraft:swim_one_cm", 0);
        return (walk + sprint + fly + swim) / 100000.0;
    }
    
    public int getDeathCount() {
        return minecraftStats.getOrDefault("minecraft:deaths", 0);
    }
    
    public int getPokeballsCrafted() {
        // Sum only items from minecraft:crafted category containing "ball" and "cobblemon"
        Map<String, Integer> crafted = categorizedStats.get("minecraft:crafted");
        if (crafted == null) return 0;
        int total = 0;
        for (Map.Entry<String, Integer> entry : crafted.entrySet()) {
            String key = entry.getKey();
            if (key.contains("ball") && key.contains("cobblemon")) {
                total += entry.getValue();
            }
        }
        return total;
    }
    
    /**
     * Count legendary/mythical Pokémon caught by the player.
     * Checks pokedex species names against a known list.
     */
    public int getLegendaryCaptureCount() {
        int count = 0;
        for (Map.Entry<String, SpeciesRecord> entry : pokedexData.entrySet()) {
            if (entry.getValue().hasAnyCaught() && LEGENDARY_SPECIES.contains(entry.getKey().toLowerCase())) {
                count++;
            }
        }
        return count;
    }

    // Comprehensive list of legendary + mythical Pokémon (Cobblemon species IDs, lowercase)
    private static final Set<String> LEGENDARY_SPECIES = Set.of(
        // Gen 1
        "articuno", "zapdos", "moltres", "mewtwo", "mew",
        // Gen 2
        "raikou", "entei", "suicune", "lugia", "ho-oh", "celebi",
        // Gen 3
        "regirock", "regice", "registeel", "latias", "latios",
        "kyogre", "groudon", "rayquaza", "jirachi", "deoxys",
        // Gen 4
        "uxie", "mesprit", "azelf", "dialga", "palkia", "giratina",
        "heatran", "regigigas", "cresselia", "phione", "manaphy",
        "darkrai", "shaymin", "arceus",
        // Gen 5
        "victini", "cobalion", "terrakion", "virizion",
        "tornadus", "thundurus", "reshiram", "zekrom", "landorus",
        "kyurem", "keldeo", "meloetta", "genesect",
        // Gen 6
        "xerneas", "yveltal", "zygarde", "diancie", "hoopa", "volcanion",
        // Gen 7
        "type_null", "silvally", "tapu_koko", "tapu_lele", "tapu_bulu", "tapu_fini",
        "cosmog", "cosmoem", "solgaleo", "lunala",
        "nihilego", "buzzwole", "pheromosa", "xurkitree", "celesteela", "kartana", "guzzlord",
        "necrozma", "magearna", "marshadow",
        "poipole", "naganadel", "stakataka", "blacephalon", "zeraora",
        "meltan", "melmetal",
        // Gen 8
        "zacian", "zamazenta", "eternatus", "kubfu", "urshifu",
        "zarude", "regieleki", "regidrago", "glastrier", "spectrier", "calyrex",
        // Gen 9
        "wo-chien", "chien-pao", "ting-lu", "chi-yu",
        "koraidon", "miraidon", "walking_wake", "iron_leaves",
        "okidogi", "munkidori", "fezandipiti", "ogerpon",
        "terapagos", "pecharunt"
    );

    @SuppressWarnings("unchecked")
    private int getIntFromCobblemon(String key) {
        try {
            Map<String, Object> advData = (Map<String, Object>) cobblemonData.get("advancementData");
            if (advData == null) {
                // Try direct access if no advancementData wrapper
                Object value = cobblemonData.get(key);
                if (value instanceof Number) {
                    return ((Number) value).intValue();
                }
                return 0;
            }
            
            Object value = advData.get(key);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            
            // Handle string numbers
            if (value instanceof String) {
                try {
                    return Integer.parseInt((String) value);
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        } catch (Exception e) {
            // Silently fail and return 0
        }
        return 0;
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, Integer> getTypeCaptureCountsFromCobblemon() {
        Map<String, Integer> result = new HashMap<>();
        try {
            Map<String, Object> advData = (Map<String, Object>) cobblemonData.get("advancementData");
            if (advData == null) return result;
            
            Object typeCounts = advData.get("totalTypeCaptureCounts");
            if (typeCounts instanceof Map) {
                ((Map<String, Object>) typeCounts).forEach((k, v) -> {
                    if (v instanceof Number) {
                        result.put(k.toLowerCase(), ((Number) v).intValue());
                    } else if (v instanceof String) {
                        try {
                            result.put(k.toLowerCase(), Integer.parseInt((String) v));
                        } catch (NumberFormatException e) {
                            // Skip invalid values
                        }
                    }
                });
            }
        } catch (Exception e) {
            // Return empty map on any error
        }
        return result;
    }
    
    /**
     * Represents a Pokémon species in the Pokédex
     */
    public static class SpeciesRecord {
        public Map<String, FormRecord> formRecords = new HashMap<>();
        
        public boolean hasAnyCaught() {
            for (FormRecord form : formRecords.values()) {
                if ("CAUGHT".equals(form.knowledge)) {
                    return true;
                }
            }
            return false;
        }
    }
    
    /**
     * Represents a form of a Pokémon species (normal, alolan, hisuian, etc.)
     */
    public static class FormRecord {
        public String knowledge; // "CAUGHT" or "ENCOUNTERED"
        public List<String> genders = new ArrayList<>();
        public List<String> seenShinyStates = new ArrayList<>();
    }
}
