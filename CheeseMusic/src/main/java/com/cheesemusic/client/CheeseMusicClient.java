package com.cheesemusic.client;

import com.cheesemusic.network.MusicPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CheeseMusicClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("cheesemusic");

    @Override
    public void onInitializeClient() {
        LOGGER.info("[CheeseMusic] Client initializing...");

        // Init ReactiveMusic hook (cooperative volume control)
        ReactiveMusicHook.tryInit();

        ClientPlayNetworking.registerGlobalReceiver(MusicPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                ZoneMusicPlayer player = ZoneMusicPlayer.getInstance();
                switch (payload.action()) {
                    case "play" -> player.play(payload.tracksJson(), payload.volume(), payload.fadeTicks());
                    case "stop" -> player.stop(payload.fadeTicks());
                    case "fade_to" -> player.fadeTo(payload.volume(), payload.fadeTicks());
                }
            });
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            client.execute(() -> ZoneMusicPlayer.getInstance().reset());
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ZoneMusicPlayer.getInstance().tick();
        });

        LOGGER.info("[CheeseMusic] Client initialized");
    }
}
