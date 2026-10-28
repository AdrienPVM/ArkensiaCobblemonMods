package com.cheeseraids;

import com.cheeseraids.commands.CheeseRaidsCommand;
import com.cheeseraids.data.RaidInstance;
import com.cheeseraids.logic.RaidManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

public class CheeseRaidsMod implements ModInitializer {

    public static final String MODID = "cheeseraids";
    public static final Logger LOGGER = LoggerFactory.getLogger("CheeseRaids");
    public static CheeseRaidsMod INSTANCE;

    /** Optional hook set by the dashboard module to broadcast instance updates over WebSocket. */
    public static Consumer<RaidInstance> DASHBOARD_BROADCAST = null;

    private final RaidManager raidManager = new RaidManager();

    @Override
    public void onInitialize() {
        INSTANCE = this;

        ServerLifecycleEvents.SERVER_STARTED.register(raidManager::onServerStart);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> raidManager.onServerStop());
        ServerTickEvents.END_SERVER_TICK.register(server -> raidManager.tick());

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            CheeseRaidsCommand.register(dispatcher));

        LOGGER.info("[CheeseRaids] initialized — modular raid framework ready.");
    }

    public RaidManager getRaidManager() { return raidManager; }
}
