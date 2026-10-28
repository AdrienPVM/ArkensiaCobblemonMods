package com.sebastian.cobblemondungeons.config;

import java.util.List;
import java.util.ArrayList;

public class DungeonConfig {
    public GeneralConfig general = new GeneralConfig();
    public NetworkingConfig networking = new NetworkingConfig();
    public LocationsConfig locations = new LocationsConfig();
    public List<LocationsConfig> additionalLocations = new ArrayList<>();
    public RequirementsConfig requirements = new RequirementsConfig();
    public ScalingConfig scaling = new ScalingConfig();
    public SpawningConfig spawning = new SpawningConfig();
    public BossConfig boss = new BossConfig();
    public int rewardCap = 0; // 0 means no cap
    public List<LootPoolConfig> lootPools = new ArrayList<>();
    public List<CompletionRewardConfig> completionRewards = new ArrayList<>();

    public List<LocationsConfig> getAllLocations() {
        List<LocationsConfig> all = new ArrayList<>();
        if (locations != null) all.add(locations);
        if (additionalLocations != null) all.addAll(additionalLocations);
        return all;
    }

    public static class GeneralConfig {
        public String name = "Generic Dungeon";
        public String id = "generic_dungeon";
        public String guiIcon = "minecraft:paper";
    }

    public static class NetworkingConfig {
        public String targetServer = "dungeonsraids";
        public String mainServer = "main";
        public String teleportMode = "CROSS_SERVER";
        public boolean syncPlayerData = true;
    }

    public static class LocationsConfig {
        public WorldPos spawnPos = new WorldPos(0, 100, 0, "minecraft:overworld");
        public WorldPos hubPos = new WorldPos(0, 100, 0, "minecraft:overworld");
        public WorldPos bossRoomPos = new WorldPos(0, 100, 0, "minecraft:overworld");
        public WorldPos returnPos = new WorldPos(0, 100, 0, "minecraft:overworld");
        public WorldPos bossSpawnPos = null; // Overrides boss.bossSpawnPos if set
    }

    public static class RequirementsConfig {
        public int minLevel = 1;
        public int dungeonsPartySizeLimit = 5;
        public int timeLimitSeconds = 1800; // 30 minutes default
        public List<String> requiredKeyItems = new ArrayList<>();
        public int requiredAlphas = 0;
        public int requiredGems = 0;
        public int requiredGuardians = 0;
        public int requiredStakes = 0;
        public int soloLives = 3;
        public int smallPartyLives = 6;
        public int largePartyLives = 10;
        public int cooldownSeconds = 0; // 0 = no cooldown
    }

    public static class ScalingConfig {
        public int tier = 1;
        public List<TierLevelConfig> tierLevels = new ArrayList<>();
    }

    public static class SpawningConfig {
        public List<AlphaPokemonConfig> alphaPool = new ArrayList<>();
        public int alphaSpawnRadius = 150;
        public double alphaTriggerRadius = 5.0;
        public int guardianSpawnRadius = 150;
        public int stakeSpawnRadius = 150;
        public int portalSpawnRadius = 150;
        public int chestSpawnRadius = 150;
        public List<Integer> alphaSpawnerIds = new ArrayList<>();
        public List<Integer> guardianSpawnerIds = new ArrayList<>();
        public List<Integer> stakeSpawnerIds = new ArrayList<>();
        public List<Integer> portalIds = new ArrayList<>();
        public List<Integer> chestIds = new ArrayList<>();
        public int spawnerScanVerticalRange = 64;
        public List<GuardianPoolConfig> guardianPool = new ArrayList<>();
        public List<String> guardianTrainerIds = new ArrayList<>();
        public double spawnProtectionRadius = 100.0;
    }

    public static class BossPokemonConfig {
        public String species = "pikachu";
        public int tier = 0;
        public int level = 50;
        public String customName = "Dungeon Boss";
        public boolean isShiny = false;
        public int weight = 10;
        public double rewardChance = 100.0;
        public double scale = 4.0;
        public List<CompletionRewardConfig> customRewards = new ArrayList<>();

        public BossPokemonConfig() {}
        public BossPokemonConfig(String species, int tier, int level, String customName, boolean isShiny, int weight) {
            this(species, tier, level, customName, isShiny, weight, 100.0, 4.0);
        }

        public BossPokemonConfig(String species, int tier, int level, String customName, boolean isShiny, int weight, double rewardChance) {
            this(species, tier, level, customName, isShiny, weight, rewardChance, 4.0);
        }

        public BossPokemonConfig(String species, int tier, int level, String customName, boolean isShiny, int weight, double rewardChance, double scale) {
            this.species = species;
            this.tier = tier;
            this.level = level;
            this.customName = customName;
            this.isShiny = isShiny;
            this.weight = weight;
            this.rewardChance = rewardChance;
            this.scale = scale;
        }
    }

    public static class LootPoolConfig {
        public int id = 1;
        public int tier = 0;
        public int rolls = 1;
        public List<LootItemConfig> items = new ArrayList<>();

        public LootPoolConfig() {}
        public LootPoolConfig(int id, int rolls) {
            this.id = id;
            this.rolls = rolls;
        }

        public LootPoolConfig(int id, int tier, int rolls) {
            this.id = id;
            this.tier = tier;
            this.rolls = rolls;
        }
    }

    public static class LootItemConfig {
        public String item = "minecraft:diamond";
        public String customName = "";
        public int min = 1;
        public int max = 1;
        public int weight = 10;
        public String nbt = "";

        public LootItemConfig() {}
        public LootItemConfig(String item, int min, int max, int weight) {
            this.item = item;
            this.min = min;
            this.max = max;
            this.weight = weight;
        }

        public LootItemConfig(String item, String customName, int min, int max, int weight) {
            this.item = item;
            this.customName = customName;
            this.min = min;
            this.max = max;
            this.weight = weight;
        }
    }

    public static class CompletionRewardConfig {
        public String item = "minecraft:diamond";
        public String customName = "";
        public int min = 1;
        public int max = 1;
        public double chance = 100.0;
        public int tier = 0;
        public String nbt = "";

        public CompletionRewardConfig() {}
        public CompletionRewardConfig(String item, int min, int max, double chance, int tier) {
            this.item = item;
            this.min = min;
            this.max = max;
            this.chance = chance;
            this.tier = tier;
        }
    }

    public static class GuardianPoolConfig {
        public String trainerId = "generic_trainer";
        public int tier = 0;
        public int weight = 10;
        public int level = 0; // Overrides tier level if > 0

        public GuardianPoolConfig() {}
        public GuardianPoolConfig(String trainerId, int weight) {
            this.trainerId = trainerId;
            this.weight = weight;
        }

        public GuardianPoolConfig(String trainerId, int weight, int tier) {
            this.trainerId = trainerId;
            this.weight = weight;
            this.tier = tier;
        }
    }

    public static class AlphaPokemonConfig {
        public String species = "ghastly";
        public int tier = 0;
        public int minLevel = 10;
        public int maxLevel = 20;
        public int weight = 10;

        public AlphaPokemonConfig() {}
        public AlphaPokemonConfig(String species, int tier, int minLevel, int maxLevel, int weight) {
            this.species = species;
            this.tier = tier;
            this.minLevel = minLevel;
            this.maxLevel = maxLevel;
            this.weight = weight;
        }
    }

    public static class TierLevelConfig {
        public int tier = 1;
        public int level = 15;

        public TierLevelConfig() {}
        public TierLevelConfig(int tier, int level) {
            this.tier = tier;
            this.level = level;
        }
    }

    public static class WorldPos {
        public double x, y, z;
        public String dimension;

        public WorldPos() {}
        public WorldPos(double x, double y, double z, String dimension) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
        }
    }

    public static class BossConfig {
        public List<BossPokemonConfig> pool = new ArrayList<>();
        public WorldPos bossSpawnPos = new WorldPos(10, 100, 10, "minecraft:overworld");

        // Legacy fields for backward compatibility
        public String species = "pikachu";
        public int level = 50;
        public String customName = "Dungeon Boss";
        public boolean isShiny = false;
    }
}
