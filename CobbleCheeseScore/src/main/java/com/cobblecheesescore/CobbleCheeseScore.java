package com.cobblecheesescore;

import com.cobblecheesescore.config.Config;
import com.cobblecheesescore.data.DataCollector;
import com.cobblecheesescore.scoreboard.ScoreboardManager;
import com.cobblecheesescore.scoreboard.MiniGameManager;
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
    private static ScoreboardManager scoreboardManager;
    private static DataCollector dataCollector;
    private static CacheManager cacheManager;
    private static MiniGameManager miniGameManager;
    private static ScheduledExecutorService scheduler;

    @Override
    public void onInitializeServer() {
        LOGGER.info("Initializing CobbleCheeseScore...");

        // Load config
        config = Config.load();

        // Initialize components
        cacheManager = new CacheManager();
        miniGameManager = new MiniGameManager();

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

        // Initial update
        try {
            LOGGER.info("Running initial scoreboard update...");
            scoreboardManager.updateAll();
            LOGGER.info("Initial update complete");
        } catch (Exception e) {
            LOGGER.error("Failed initial update", e);
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
            config.getUpdateIntervalMinutes(),
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

        server = null;
        LOGGER.info("CobbleCheeseScore shutdown complete");
    }

    public static Config getConfig() {
        return config;
    }

    public static MinecraftServer getServer() {
        return server;
    }

    public static ScoreboardManager getScoreboardManager() {
        return scoreboardManager;
    }

    public static DataCollector getDataCollector() {
        return dataCollector;
    }

    public static CacheManager getCacheManager() {
        return cacheManager;
    }

    public static MiniGameManager getMiniGameManager() {
        return miniGameManager;
    }
}
