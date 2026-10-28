package com.cheeseextra;

import com.cheeseextra.arkensia.IslandTourTracker;
import com.cheeseextra.network.IslandTourPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CheeseExtraMod implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("cheeseextra");

    @Override
    public void onInitialize() {
        LOGGER.info("[CheeseExtra] Initializing CheeseExtraCommands...");

        CommandRegistrationCallback.EVENT.register((dispatcher, reg, env) -> {
            CheeseCheckCommand.register(dispatcher);
        });

        IslandTourPayload.register();
        ServerTickEvents.END_SERVER_TICK.register(IslandTourTracker::onServerTick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            IslandTourTracker.onPlayerJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            IslandTourTracker.onPlayerDisconnect(handler.getPlayer()));

        LOGGER.info("[CheeseExtra] Commands + Arkensia island-tour tracker registered.");
    }
}
