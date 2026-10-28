package com.sebastian.cobblemondungeons.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sebastian.cobblemondungeons.CobblemonDungeons;
import com.sebastian.cobblemondungeons.dungeon.DungeonManager;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

public class ConfigLoader {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("cobblemon_dungeons");
    private static final Path DUNGEONS_DIR = CONFIG_DIR.resolve("dungeons");
    private static MainConfig mainConfig = new MainConfig();
    private static DungeonTicketConfig ticketConfig = new DungeonTicketConfig();

    public static void load() {
        // Clear existing dungeon configs for idempotent hot-reload
        DungeonManager.clearDungeonConfigs();

        File configFolder = CONFIG_DIR.toFile();
        if (!configFolder.exists()) {
            configFolder.mkdirs();
        }

        File mainConfigFile = CONFIG_DIR.resolve("config.json").toFile();
        if (mainConfigFile.exists()) {
            CobblemonDungeons.LOGGER.info("Loading main config from " + mainConfigFile.getAbsolutePath());
            try (FileReader reader = new FileReader(mainConfigFile)) {
                MainConfig loaded = GSON.fromJson(reader, MainConfig.class);
                if (loaded != null) {
                    mainConfig = loaded;
                }
            } catch (IOException e) {
                CobblemonDungeons.LOGGER.error("Failed to load main config file", e);
            }
        } else {
            CobblemonDungeons.LOGGER.info("Creating default main config at " + mainConfigFile.getAbsolutePath());
            saveMainConfig();
        }

        File ticketConfigFile = CONFIG_DIR.resolve("dungeon_tickets.json").toFile();
        if (ticketConfigFile.exists()) {
            CobblemonDungeons.LOGGER.info("Loading dungeon tickets from " + ticketConfigFile.getAbsolutePath());
            try (FileReader reader = new FileReader(ticketConfigFile)) {
                DungeonTicketConfig loaded = GSON.fromJson(reader, DungeonTicketConfig.class);
                if (loaded != null) {
                    ticketConfig = loaded;
                }
            } catch (IOException e) {
                CobblemonDungeons.LOGGER.error("Failed to load dungeon tickets config file", e);
            }
        } else {
            CobblemonDungeons.LOGGER.info("Creating default dungeon tickets at " + ticketConfigFile.getAbsolutePath());
            saveTicketConfig();
        }

        File dungeonsFolder = DUNGEONS_DIR.toFile();
        if (!dungeonsFolder.exists()) {
            dungeonsFolder.mkdirs();
        }

        File defaultFile = DUNGEONS_DIR.resolve("example_dungeon.json").toFile();
        if (!defaultFile.exists()) {
            createDefaultDungeonFile();
        }

        File[] files = dungeonsFolder.listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                try (FileReader reader = new FileReader(file)) {
                    DungeonConfig config = GSON.fromJson(reader, DungeonConfig.class);
                    if (config != null) {
                        DungeonManager.registerDungeon(config);
                        CobblemonDungeons.LOGGER.info("Registered dungeon from file: " + file.getName());
                    }
                } catch (IOException e) {
                    CobblemonDungeons.LOGGER.error("Failed to load dungeon file: " + file.getName(), e);
                }
            }
        }

        // Load run history for dashboard stats
        DungeonManager.loadRunHistory();
        DungeonManager.loadCooldowns();
        DungeonManager.loadLeaderboard();
    }

    public static MainConfig getMainConfig() {
        return mainConfig;
    }

    public static DungeonTicketConfig getTicketConfig() {
        return ticketConfig;
    }

    public static String getTicketConfigJson() {
        return GSON.toJson(ticketConfig);
    }

    public static void updateTicketConfig(String json) {
        try {
            DungeonTicketConfig decoded = GSON.fromJson(json, DungeonTicketConfig.class);
            if (decoded != null) {
                ticketConfig = decoded;
                CobblemonDungeons.LOGGER.info("Updated dungeon tickets config from server.");
            }
        } catch (Exception e) {
            CobblemonDungeons.LOGGER.error("Failed to update dungeon tickets config from server", e);
        }
    }

    /** Public save for ticket config — used by dashboard API. */
    public static void saveTicketConfigPublic() {
        saveTicketConfig();
    }

    /** Save a dungeon config to disk by ID. */
    public static void saveDungeonConfig(DungeonConfig config) {
        if (config == null || config.general.id == null) return;
        File file = DUNGEONS_DIR.resolve(config.general.id + ".json").toFile();
        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(config, writer);
        } catch (IOException e) {
            CobblemonDungeons.LOGGER.error("Failed to save dungeon config: " + config.general.id, e);
        }
    }

    /** Delete a dungeon config file by ID. */
    public static void deleteDungeonConfig(String id) {
        File file = DUNGEONS_DIR.resolve(id + ".json").toFile();
        if (file.exists()) file.delete();
    }

    /** Get the GSON instance for serialization. */
    public static Gson getGson() { return GSON; }

    /** Get the dungeons config directory. */
    public static Path getDungeonsDir() { return DUNGEONS_DIR; }

    /** Get the trainers directory. */
    public static Path getTrainersDir() { return CONFIG_DIR.resolve("trainers"); }

    private static void saveMainConfig() {
        File mainConfigFile = CONFIG_DIR.resolve("config.json").toFile();
        try (FileWriter writer = new FileWriter(mainConfigFile)) {
            GSON.toJson(mainConfig, writer);
        } catch (IOException e) {
            CobblemonDungeons.LOGGER.error("Failed to save main config file", e);
        }
    }

    private static void saveTicketConfig() {
        if (ticketConfig.tickets.isEmpty()) {
            DungeonTicketConfig.TicketConfig example = new DungeonTicketConfig.TicketConfig();
            example.dungeonId = "generic_dungeon";
            example.itemName = "\u00A76Copper Dungeon Ticket";
            example.tier = 1;
            ticketConfig.tickets.add(example);
        }
        File ticketConfigFile = CONFIG_DIR.resolve("dungeon_tickets.json").toFile();
        try (FileWriter writer = new FileWriter(ticketConfigFile)) {
            GSON.toJson(ticketConfig, writer);
        } catch (IOException e) {
            CobblemonDungeons.LOGGER.error("Failed to save dungeon tickets config file", e);
        }
    }

    private static void createDefaultDungeonFile() {
        DungeonConfig defaultConfig = new DungeonConfig();
        
        // Populate example with all recent fields
        defaultConfig.spawning.alphaPool.add(new DungeonConfig.AlphaPokemonConfig("ghastly", 0, 15, 25, 10));
        defaultConfig.spawning.alphaPool.add(new DungeonConfig.AlphaPokemonConfig("haunter", 0, 25, 35, 5));
        defaultConfig.spawning.alphaSpawnerIds.add(1);
        defaultConfig.spawning.guardianSpawnerIds.add(1);
        defaultConfig.spawning.stakeSpawnerIds.add(1);
        defaultConfig.spawning.portalIds.add(1);
        defaultConfig.spawning.chestIds.add(1);
        defaultConfig.spawning.spawnerScanVerticalRange = 64;

        defaultConfig.scaling.tierLevels.add(new DungeonConfig.TierLevelConfig(1, 15));
        defaultConfig.scaling.tierLevels.add(new DungeonConfig.TierLevelConfig(2, 30));
        defaultConfig.scaling.tierLevels.add(new DungeonConfig.TierLevelConfig(3, 60));
        defaultConfig.scaling.tierLevels.add(new DungeonConfig.TierLevelConfig(4, 85));
        defaultConfig.scaling.tierLevels.add(new DungeonConfig.TierLevelConfig(5, 115));
        defaultConfig.scaling.tier = 1;
        
        defaultConfig.spawning.guardianPool.add(new DungeonConfig.GuardianPoolConfig("guardian_grunt", 10, 0));
        defaultConfig.spawning.guardianPool.add(new DungeonConfig.GuardianPoolConfig("guardian_elite", 5, 2));

        defaultConfig.requirements.requiredAlphas = 2;
        defaultConfig.requirements.requiredGems = 3;
        defaultConfig.requirements.requiredGuardians = 2;
        defaultConfig.requirements.requiredStakes = 4;
        defaultConfig.requirements.timeLimitSeconds = 1800;
        defaultConfig.requirements.soloLives = 3;
        defaultConfig.requirements.smallPartyLives = 6;
        defaultConfig.requirements.largePartyLives = 10;
        defaultConfig.networking.teleportMode = "CROSS_SERVER";
        defaultConfig.general.guiIcon = "minecraft:paper";
        defaultConfig.locations.spawnPos = new DungeonConfig.WorldPos(100, 64, 100, "minecraft:overworld");
        defaultConfig.locations.hubPos = new DungeonConfig.WorldPos(0, 100, 0, "minecraft:overworld");
        defaultConfig.locations.returnPos = new DungeonConfig.WorldPos(0, 100, 0, "minecraft:overworld");

        // Example of an additional location slot for the same dungeon
        DungeonConfig.LocationsConfig secondLocation = new DungeonConfig.LocationsConfig();
        secondLocation.spawnPos = new DungeonConfig.WorldPos(500, 64, 500, "minecraft:overworld");
        secondLocation.hubPos = new DungeonConfig.WorldPos(400, 100, 400, "minecraft:overworld");
        secondLocation.returnPos = new DungeonConfig.WorldPos(0, 100, 0, "minecraft:overworld");
        defaultConfig.additionalLocations.add(secondLocation);

        defaultConfig.rewardCap = 1;

        defaultConfig.spawning.guardianTrainerIds.add("Guardian Leader");
        
        DungeonConfig.LootPoolConfig examplePool = new DungeonConfig.LootPoolConfig(1, 0, 3);
        examplePool.items.add(new DungeonConfig.LootItemConfig("minecraft:diamond", 1, 3, 10));
        examplePool.items.add(new DungeonConfig.LootItemConfig("minecraft:gold_ingot", 3, 8, 20));
        examplePool.items.add(new DungeonConfig.LootItemConfig("cobblemon:ultra_ball", 5, 10, 50));
        // Example: Adding a Dungeon Ticket by its configured name
        examplePool.items.add(new DungeonConfig.LootItemConfig("§6Copper Dungeon Ticket", 1, 1, 5));
        defaultConfig.lootPools.add(examplePool);

        DungeonConfig.LootPoolConfig tieredPool = new DungeonConfig.LootPoolConfig(1, 2, 5); // Tier 2 specifically
        tieredPool.items.add(new DungeonConfig.LootItemConfig("minecraft:netherite_ingot", 1, 1, 5));
        tieredPool.items.add(new DungeonConfig.LootItemConfig("cobblemon:master_ball", 1, 1, 1));
        defaultConfig.lootPools.add(tieredPool);

        defaultConfig.completionRewards.add(new DungeonConfig.CompletionRewardConfig("minecraft:netherite_upgrade_smithing_template", 1, 1, 5.0, 0));
        defaultConfig.completionRewards.add(new DungeonConfig.CompletionRewardConfig("minecraft:diamond_block", 1, 2, 50.0, 1));
        defaultConfig.completionRewards.add(new DungeonConfig.CompletionRewardConfig("cobblemon:master_ball", 1, 1, 1.0, 5));
        
        DungeonConfig.BossPokemonConfig charizard = new DungeonConfig.BossPokemonConfig("charizard", 0, 50, "Dungeon Boss Charizard", false, 10, 50.0, 4.0);
        charizard.customRewards.add(new DungeonConfig.CompletionRewardConfig("minecraft:fire_charge", 1, 5, 100.0, 0));
        defaultConfig.boss.pool.add(charizard);

        File defaultFile = DUNGEONS_DIR.resolve("example_dungeon.json").toFile();
        try (FileWriter writer = new FileWriter(defaultFile)) {
            GSON.toJson(defaultConfig, writer);
        } catch (IOException e) {
            CobblemonDungeons.LOGGER.error("Failed to create default dungeon file", e);
        }
    }
}
