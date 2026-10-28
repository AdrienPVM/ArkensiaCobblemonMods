package com.cheesekart;

import com.cheesekart.network.KartSyncPayload;
import com.cheesekart.network.OpenBoatUtilsProxy;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CheeseKartMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("CheeseKart");
    public static CheeseKartMod INSTANCE;

    private CheeseKartConfig config;
    private KartManager kartManager;

    @Override
    public void onInitialize() {
        INSTANCE = this;
        LOGGER.info("[CheeseKart] Initializing...");

        // Load config
        config = new CheeseKartConfig();
        config.load();

        // Create manager
        kartManager = new KartManager(config);

        // Register payloads
        KartSyncPayload.register();
        OpenBoatUtilsProxy.register();

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                CheeseKartCommand.register(dispatcher));

        // Server lifecycle
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            kartManager.setServer(server);
            CobbleShiftProxy.tryInit();
            LOGGER.info("[CheeseKart] Server started — {} presets loaded", config.getPresets().size());
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            kartManager.stopAll();
            kartManager.setServer(null);
        });

        // Tick
        ServerTickEvents.END_SERVER_TICK.register(server -> kartManager.tick());

        // Player disconnect
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                kartManager.onPlayerDisconnect(handler.getPlayer().getUuid()));

        // Player join — sync existing karts to new player
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var player = handler.getPlayer();
            kartManager.getSessions().forEach((uuid, session) -> {
                if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, KartSyncPayload.ID)) {
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                            new KartSyncPayload(uuid, true, session.boatEntityId()));
                }
            });
        });

        LOGGER.info("[CheeseKart] Initialized");
    }

    public CheeseKartConfig getConfig() { return config; }
    public KartManager getKartManager() { return kartManager; }
}
