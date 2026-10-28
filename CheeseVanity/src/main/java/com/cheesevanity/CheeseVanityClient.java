package com.cheesevanity;

import com.cheesevanity.network.SyncHiddenSlotsS2CPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class CheeseVanityClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Handle S2C sync packets — update local cache
        ClientPlayNetworking.registerGlobalReceiver(SyncHiddenSlotsS2CPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                VanityClientState.updatePlayer(payload.playerUuid(), payload.hiddenSlots());
            });
        });

        CheeseVanityMod.LOGGER.info("[CheeseVanity] Client initialized");
    }
}
