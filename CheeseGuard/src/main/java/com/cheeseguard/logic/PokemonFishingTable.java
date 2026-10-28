package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.google.gson.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Configurable Pokemon fishing loot table with rarity tiers.
 * Used by both REALISTIC (rarity → points) and ARCADE (random roll + points) modes.
 * Config file: config/cheeseguard_fish_pokemon.json
 */
public class PokemonFishingTable {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static class FishEntry {
        public final String species;  // e.g. "magikarp"
        public final String display;  // e.g. "Magicarpe"
        public final int points;
        public final int weight;      // Relative weight for random rolls (higher = more common)
        public final String tier;     // Tier name for display

        public FishEntry(String species, String display, int points, int weight, String tier) {
            this.species = species; this.display = display; this.points = points;
            this.weight = weight; this.tier = tier;
        }
    }

    private final List<FishEntry> entries = new ArrayList<>();
    private int totalWeight = 0;

    // ── Singleton ────────────────────────────────────────────────────────────

    private static PokemonFishingTable instance;

    public static PokemonFishingTable get() {
        if (instance == null) {
            instance = new PokemonFishingTable();
            instance.loadDefaults();
        }
        return instance;
    }

    public static void reload(net.minecraft.server.MinecraftServer server) {
        instance = new PokemonFishingTable();
        instance.load(server);
    }

    // ── Random roll (ARCADE mode) ────────────────────────────────────────────

    /**
     * Roll a random pokemon from the table based on weights.
     * @return the rolled entry, or null if table is empty
     */
    public FishEntry rollRandom() {
        if (entries.isEmpty() || totalWeight <= 0) return null;
        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
        int cumulative = 0;
        for (FishEntry entry : entries) {
            cumulative += entry.weight;
            if (roll < cumulative) return entry;
        }
        return entries.getLast();
    }

    // ── Lookup (REALISTIC mode) ──────────────────────────────────────────────

    /**
     * Find the points for a given species.
     * @param species Cobblemon species string (e.g. "cobblemon:magikarp" or "magikarp")
     * @return points for that species, or 1 if not found
     */
    public int getPoints(String species) {
        String clean = species.toLowerCase();
        if (clean.startsWith("cobblemon:")) clean = clean.substring(10);
        for (FishEntry entry : entries) {
            if (entry.species.equalsIgnoreCase(clean)) return entry.points;
        }
        return 1; // Default 1 point for unknown species
    }

    /**
     * Find entry by species name.
     */
    public FishEntry findBySpecies(String species) {
        String clean = species.toLowerCase();
        if (clean.startsWith("cobblemon:")) clean = clean.substring(10);
        for (FishEntry entry : entries) {
            if (entry.species.equalsIgnoreCase(clean)) return entry;
        }
        return null;
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    public void load(net.minecraft.server.MinecraftServer server) {
        try {
            Path file = server.getRunDirectory().resolve("config").resolve("cheeseguard_fish_pokemon.json");
            if (Files.exists(file)) {
                JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                JsonArray tiers = root.getAsJsonArray("tiers");
                entries.clear();
                totalWeight = 0;
                for (var tierEl : tiers) {
                    JsonObject tier = tierEl.getAsJsonObject();
                    String tierName = tier.get("name").getAsString();
                    int pts = tier.get("points").getAsInt();
                    int weight = tier.get("weight").getAsInt();
                    JsonArray pokemon = tier.getAsJsonArray("pokemon");
                    for (var pEl : pokemon) {
                        JsonObject p = pEl.getAsJsonObject();
                        String species = p.get("id").getAsString();
                        String display = p.has("name") ? p.get("name").getAsString() : species;
                        entries.add(new FishEntry(species, display, pts, weight, tierName));
                        totalWeight += weight;
                    }
                }
                CheeseGuardMod.LOGGER.info("[CG] Fish Pokemon table loaded: {} entries", entries.size());
            } else {
                loadDefaults();
                save(server);
            }
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to load fish pokemon table: {}", e.getMessage());
            loadDefaults();
        }
    }

    public void save(net.minecraft.server.MinecraftServer server) {
        try {
            // Group entries by tier
            Map<String, List<FishEntry>> byTier = new LinkedHashMap<>();
            for (FishEntry e : entries) {
                byTier.computeIfAbsent(e.tier, k -> new ArrayList<>()).add(e);
            }
            JsonObject root = new JsonObject();
            JsonArray tiers = new JsonArray();
            for (var entry : byTier.entrySet()) {
                List<FishEntry> list = entry.getValue();
                JsonObject tier = new JsonObject();
                tier.addProperty("name", entry.getKey());
                tier.addProperty("points", list.getFirst().points);
                tier.addProperty("weight", list.getFirst().weight);
                JsonArray pokemon = new JsonArray();
                for (FishEntry fe : list) {
                    JsonObject p = new JsonObject();
                    p.addProperty("id", fe.species);
                    p.addProperty("name", fe.display);
                    pokemon.add(p);
                }
                tier.add("pokemon", pokemon);
                tiers.add(tier);
            }
            root.add("tiers", tiers);
            Path dir = server.getRunDirectory().resolve("config");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("cheeseguard_fish_pokemon.json"), GSON.toJson(root));
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to save fish pokemon table: {}", e.getMessage());
        }
    }

    // ── Defaults ─────────────────────────────────────────────────────────────

    private void loadDefaults() {
        entries.clear();
        totalWeight = 0;

        // Tier 1: Commun (1 pt, weight 50)
        addDefault("Commun", 1, 50,
            "magikarp", "Magicarpe",
            "tentacool", "Tentacool",
            "goldeen", "Poissirene",
            "poliwag", "Ptitard",
            "psyduck", "Psykokwak",
            "wooper", "Axoloto",
            "lotad", "Nenupiot",
            "wishiwashi", "Froussardine",
            "finneon", "Ecrapince",
            "remoraid", "Remoraid"
        );

        // Tier 2: Peu commun (3 pts, weight 30)
        addDefault("Peu commun", 3, 30,
            "staryu", "Stari",
            "shellder", "Kokiyas",
            "krabby", "Krabby",
            "horsea", "Hypotrempe",
            "corphish", "Ecrapince",
            "barboach", "Barloche",
            "carvanha", "Carvanha",
            "chinchou", "Loupio",
            "qwilfish", "Qwilfish",
            "basculin", "Bargantua"
        );

        // Tier 3: Rare (5 pts, weight 12)
        addDefault("Rare", 5, 12,
            "dratini", "Minidraco",
            "feebas", "Barpau",
            "relicanth", "Relicanth",
            "clauncher", "Flingouste",
            "skrelp", "Venalgue",
            "bruxish", "Denticrisse",
            "dhelmise", "Sinistrail",
            "arrokuda", "Embrochet"
        );

        // Tier 4: Tres rare (10 pts, weight 5)
        addDefault("Tres rare", 10, 5,
            "lapras", "Lokhlass",
            "milotic", "Milobellus",
            "dragonair", "Draco",
            "wailord", "Wailord",
            "gyarados", "Leviator",
            "kingdra", "Hyporoi"
        );

        // Tier 5: Legendaire (25 pts, weight 2)
        addDefault("Legendaire", 25, 2,
            "dragonite", "Dracolosse",
            "lugia", "Lugia",
            "kyogre", "Kyogre"
        );

        // Tier 6: Mythique (50 pts, weight 1)
        addDefault("Mythique", 50, 1,
            "manaphy", "Manaphy",
            "phione", "Phione"
        );
    }

    private void addDefault(String tier, int points, int weight, String... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            entries.add(new FishEntry(pairs[i], pairs[i + 1], points, weight, tier));
            totalWeight += weight;
        }
    }

    // ── Query ────────────────────────────────────────────────────────────────

    public List<FishEntry> getEntries() { return Collections.unmodifiableList(entries); }
    public int size() { return entries.size(); }

    /**
     * Get a formatted tier color for display.
     */
    public static String tierColor(String tier) {
        return switch (tier) {
            case "Commun" -> "§7";
            case "Peu commun" -> "§a";
            case "Rare" -> "§9";
            case "Tres rare" -> "§d";
            case "Legendaire" -> "§6";
            case "Mythique" -> "§c§l";
            default -> "§f";
        };
    }

    public static String tierIcon(String tier) {
        return switch (tier) {
            case "Commun" -> "🐟";
            case "Peu commun" -> "🐠";
            case "Rare" -> "🎏";
            case "Tres rare" -> "🐡";
            case "Legendaire" -> "🐉";
            case "Mythique" -> "✨";
            default -> "🎣";
        };
    }
}
