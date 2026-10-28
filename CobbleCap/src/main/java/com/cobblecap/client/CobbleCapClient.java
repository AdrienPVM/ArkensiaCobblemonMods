package com.cobblecap.client;

import com.cobblecap.data.StatueStyle;
import com.cobblecap.logic.SyntheticUuidRegistry;
import com.cobblecap.network.CapSyncPayload;
import com.cobblecap.network.PastureSyncPayload;
import com.cobblecap.network.StatueStyleSyncPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entry point for CobbleCap.
 * Registers the CapSyncPayload receiver and the HUD overlay renderer.
 */
public class CobbleCapClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");

    @Override
    public void onInitializeClient() {
        LOGGER.info("[CobbleCap] Client initializing...");

        // Note: the S2C pasture-sync codec is registered by CobbleCapMod's
        // common init — which runs on both server AND client — so registering
        // it again here would throw "already registered". We just attach the
        // receiver below.

        ClientPlayNetworking.registerGlobalReceiver(CapSyncPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                CapHudRenderer.update(
                        payload.capLevel(), payload.nextChampion(),
                        payload.hudVisible(), payload.hudPosition(),
                        payload.systemEnabled()
                );
            });
        });

        // Receive pasture-sync from server. The mode byte tells us snapshot vs delta.
        ClientPlayNetworking.registerGlobalReceiver(PastureSyncPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                switch (payload.mode()) {
                    case PastureSyncPayload.MODE_SNAPSHOT ->
                            PastureHideClient.applySnapshot(payload.uuids());
                    case PastureSyncPayload.MODE_ADD -> {
                        for (var u : payload.uuids()) PastureHideClient.addPastured(u);
                    }
                    case PastureSyncPayload.MODE_REMOVE -> {
                        for (var u : payload.uuids()) PastureHideClient.removePastured(u);
                    }
                }
            });
        });

        // Receive statue-style mappings for doppelganger retexturing.
        // CRITICAL: this payload also serves as the doppel-UUID-discovery channel.
        // SyntheticUuidRegistry is server-only by construction (server adds in
        // ChampionDoppelganger.showToWorld); the client mixins (freeze + retexture)
        // need to know which UUIDs are doppels to fire — so we mirror the registry
        // here. Every payload mentioning a UUID is by definition a doppel.
        ClientPlayNetworking.registerGlobalReceiver(StatueStyleSyncPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                StatueStyle[] all = StatueStyle.values();
                switch (payload.mode()) {
                    case StatueStyleSyncPayload.MODE_SNAPSHOT -> {
                        StatueStyleClient.clear();
                        // Don't clear SyntheticUuidRegistry here — snapshots only
                        // arrive on rare events; old UUIDs are still synthetic if
                        // their server-side doppel exists. The REMOVE path is the
                        // authoritative deregister.
                        for (var e : payload.entries()) {
                            int ord = e.styleOrdinal() & 0xFF;
                            if (ord < all.length) StatueStyleClient.put(e.uuid(), all[ord]);
                            SyntheticUuidRegistry.add(e.uuid());
                        }
                    }
                    case StatueStyleSyncPayload.MODE_UPSERT -> {
                        for (var e : payload.entries()) {
                            int ord = e.styleOrdinal() & 0xFF;
                            if (ord < all.length) StatueStyleClient.put(e.uuid(), all[ord]);
                            SyntheticUuidRegistry.add(e.uuid());
                        }
                    }
                    case StatueStyleSyncPayload.MODE_REMOVE -> {
                        for (var e : payload.entries()) {
                            StatueStyleClient.remove(e.uuid());
                            SyntheticUuidRegistry.remove(e.uuid());
                        }
                    }
                }
            });
        });

        CapHudRenderer.register();

        // Pasture-hide client toggle: load saved preference + register /pasturehide
        PastureHideClient.load();
        PastureHideCommand.register();

        LOGGER.info("[CobbleCap] Client initialized — HUD registered, /pasturehide enabled (hidden={})",
                PastureHideClient.isHidden());
    }
}
