package com.cheesekart.client;

import com.cheesekart.network.KartSyncPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CheeseKartClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseKart");

    // Track which players are in karts and their boat entity IDs
    private static final Set<UUID> kartPlayers = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Integer> kartBoatIds = new ConcurrentHashMap<>();

    @Override
    public void onInitializeClient() {
        // Handle S2C kart sync
        ClientPlayNetworking.registerGlobalReceiver(KartSyncPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                if (payload.active()) {
                    kartPlayers.add(payload.playerUuid());
                    kartBoatIds.put(payload.playerUuid(), payload.boatEntityId());
                } else {
                    kartPlayers.remove(payload.playerUuid());
                    kartBoatIds.remove(payload.playerUuid());
                }
            });
        });

        LOGGER.info("[CheeseKart] Client initialized");
    }

    public static boolean isInKart(UUID uuid) {
        return kartPlayers.contains(uuid);
    }

    public static boolean isKartBoat(int entityId) {
        return kartBoatIds.containsValue(entityId);
    }

    public static void clear() {
        kartPlayers.clear();
        kartBoatIds.clear();
    }
}
