package com.cheesemusic;

import com.cheesemusic.logic.CheeseGuardHook;
import com.cheesemusic.logic.CobblemonBattleHook;
import com.cheesemusic.logic.SoundScanner;
import com.cheesemusic.logic.ZoneMusicTracker;
import com.cheesemusic.network.MusicPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CheeseMusicMod implements ModInitializer {

    public static final String MOD_ID = "cheesemusic";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static CheeseMusicMod INSTANCE;

    private CheeseMusicConfig config;
    private ZoneMusicTracker tracker;
    private SoundScanner soundScanner;
    private MinecraftServer server;

    @Override
    public void onInitialize() {
        INSTANCE = this;
        LOGGER.info("[CheeseMusic] Initializing...");

        MusicPayload.register();

        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(this::onTick);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            if (tracker != null) tracker.onPlayerJoin(handler.getPlayer());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            if (tracker != null) tracker.onPlayerDisconnect(handler.getPlayer().getUuid());
        });

        LOGGER.info("[CheeseMusic] Initialized successfully!");
    }

    private void onServerStarted(MinecraftServer srv) {
        this.server = srv;
        config = CheeseMusicConfig.load(srv.getRunDirectory().resolve("config"));
        soundScanner = new SoundScanner(srv.getRunDirectory());
        soundScanner.scan();
        tracker = new ZoneMusicTracker(config);

        CheeseGuardHook.tryInit();
        CobblemonBattleHook.tryInit();

        LOGGER.info("[CheeseMusic] Server started — {} assignments loaded", config.getAllAssignments().size());
    }

    private void onServerStopping(MinecraftServer srv) {
        LOGGER.info("[CheeseMusic] Server stopping...");
        if (tracker != null) tracker.clear();
        this.server = null;
    }

    private void onTick(MinecraftServer srv) {
        if (tracker != null && config != null && config.isEnabled()) {
            tracker.tick(srv);
        }
    }

    public CheeseMusicConfig getConfig() { return config; }
    public ZoneMusicTracker getTracker() { return tracker; }
    public SoundScanner getSoundScanner() { return soundScanner; }
    public MinecraftServer getServer() { return server; }
    public static CheeseMusicMod getInstance() { return INSTANCE; }
}
