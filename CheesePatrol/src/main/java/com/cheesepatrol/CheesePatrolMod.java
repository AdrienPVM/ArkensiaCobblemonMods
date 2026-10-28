package com.cheesepatrol;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CheesePatrolMod implements ModInitializer {

    public static final String MOD_ID = "cheesepatrol";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[CheesePatrol] Initializing...");

        // Load patrol data EARLY (before entities tick) so the mixin can protect NPCs
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            PatrolManager.load();
            LOGGER.info("[CheesePatrol] Early-loaded {} patrol(s) for mixin protection", PatrolManager.getPatrolCount());
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            PatrolManager.forceloadActivePatrolChunks(server);
            LOGGER.info("[CheesePatrol] Forceloaded chunks for {} patrol(s) ({} active, {} inactive)",
                PatrolManager.getPatrolCount(), PatrolManager.getActivePatrolCount(),
                PatrolManager.getPatrolCount() - PatrolManager.getActivePatrolCount());
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            // Backup all patrol entities before shutdown (for respawn if they vanish)
            PatrolManager.backupAllEntities(server);
            // Do NOT release forced chunks — they persist in world data so entities
            // stay loaded immediately on next restart (prevents despawn race condition)
            PatrolManager.save();
            PatrolMarker.removeAllMarkers(server);
            LOGGER.info("[CheesePatrol] Shutdown: entities backed up, patrol data saved");
        });

        ServerTickEvents.START_SERVER_TICK.register(server -> {
            PatrolManager.tick(server);
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            PatrolCommand.register(dispatcher);
        });

        PatrolInteractionHandler.register();

        LOGGER.info("[CheesePatrol] Ready.");
    }
}
