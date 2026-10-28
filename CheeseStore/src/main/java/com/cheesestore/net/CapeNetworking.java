package com.cheesestore.net;

import com.cheesestore.cosmetic.CosmeticEntry;
import com.cheesestore.cosmetic.CosmeticManager;
import com.cheesestore.cosmetic.EquipManager;
import com.cheesestore.cosmetic.OwnershipManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server-side broadcaster for the dedicated {@code cheesestore:capes} payload.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Register the S2C payload type once (so the server can encode {@link CapePayload}).</li>
 *   <li>On player JOIN, send the FULL current cape-map to every online client (the new joiner needs
 *       everyone's cape, and everyone needs the joiner's — easiest correct thing is a full rebroadcast).</li>
 *   <li>Expose {@link #broadcastAll(MinecraftServer)} for the equip layer to call whenever any player
 *       equips/unequips a cape.</li>
 * </ul>
 *
 * <p>The map sent is {@code online-player-uuid -> equipped CAPE cosmetic id}, omitting players with no
 * cape equipped (and defensively omitting capes the player no longer owns). The capeId broadcast is the
 * cosmetic's {@code texture} param when present (which equals the entry id), else the entry id.
 *
 * <p><b>Never throws into a Fabric hook</b> — every public entry point and the JOIN listener wrap their
 * body in try/catch and degrade to a no-op (mirrors the never-throw discipline of the cosmetic managers).
 */
public final class CapeNetworking {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseStore");
    private static final String CAPE_CATEGORY = "CAPE";

    private static boolean initialised = false;

    private CapeNetworking() {}

    /** Idempotent. Registers the payload type + the JOIN broadcast. Call from the cosmetics module init. */
    public static synchronized void init() {
        if (initialised) return;
        initialised = true;
        try {
            PayloadTypeRegistry.playS2C().register(CapePayload.ID, CapePayload.CODEC);
        } catch (Throwable t) {
            // Already registered (e.g. dev hot-reload) or API missing — keep going so equip still works.
            LOGGER.warn("[CheeseStore] CapePayload S2C registration failed (continuing): {}", t.toString());
        }

        // On JOIN, push the full cape-map to everyone so the new client learns existing capes and the
        // others learn the joiner's cape. Runs on the server thread (JOIN fires there).
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            try {
                if (server != null) broadcastAll(server);
            } catch (Throwable t) {
                LOGGER.debug("[CheeseStore] cape JOIN broadcast failed: {}", t.toString());
            }
        });

        LOGGER.info("[CheeseStore] Cape networking initialised (cheesestore:capes)");
    }

    /**
     * Broadcast the full cape-map (every online player with a cape equipped) to ALL online clients.
     * Safe to call from any equip/unequip code path; never throws.
     */
    public static void broadcastAll(MinecraftServer server) {
        if (server == null) return;
        try {
            CapePayload payload = new CapePayload(buildEntries(server));
            for (ServerPlayerEntity target : server.getPlayerManager().getPlayerList()) {
                try {
                    ServerPlayNetworking.send(target, payload);
                } catch (Throwable t) {
                    // A single bad recipient must not abort the whole broadcast.
                    LOGGER.debug("[CheeseStore] cape send to {} failed: {}",
                            safeName(target), t.toString());
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("[CheeseStore] broadcastAll failed: {}", t.toString());
        }
    }

    /**
     * Convenience: broadcast using the server reachable from {@code player}. Used by the equip layer
     * which has a player but not always the server in hand.
     */
    public static void broadcastFrom(ServerPlayerEntity player) {
        if (player == null) return;
        try {
            MinecraftServer server = player.getServer();
            if (server != null) broadcastAll(server);
        } catch (Throwable t) {
            LOGGER.debug("[CheeseStore] broadcastFrom failed: {}", t.toString());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Build the (uuid → capeId) entry list for all online players with a valid, owned cape. */
    private static List<CapePayload.Entry> buildEntries(MinecraftServer server) {
        List<CapePayload.Entry> entries = new ArrayList<>();
        EquipManager equip = EquipManager.getInstance();
        CosmeticManager catalog = CosmeticManager.getInstance();
        OwnershipManager ownership = OwnershipManager.getInstance();
        if (equip == null) return entries;

        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            try {
                UUID uuid = p.getUuid();
                String equippedId = equip.getEquipped(uuid, CAPE_CATEGORY);
                if (equippedId == null || equippedId.isEmpty()) continue;

                // Resolve to the broadcast capeId (texture param, falling back to the entry id) and
                // defensively drop disabled/unowned capes so clients never render something invalid.
                String capeId = equippedId;
                if (catalog != null) {
                    CosmeticEntry entry = catalog.get(equippedId);
                    if (entry == null || !entry.isEnabled()
                            || !CAPE_CATEGORY.equalsIgnoreCase(entry.getCategory())) {
                        continue;
                    }
                    capeId = entry.param("texture", equippedId);
                    if (capeId == null || capeId.isEmpty()) capeId = equippedId;
                }
                if (ownership != null && !ownership.owns(uuid, equippedId)) continue;

                entries.add(new CapePayload.Entry(uuid, capeId));
            } catch (Throwable t) {
                LOGGER.debug("[CheeseStore] cape entry build failed for {}: {}", safeName(p), t.toString());
            }
        }
        return entries;
    }

    private static String safeName(ServerPlayerEntity p) {
        try { return p == null ? "?" : p.getName().getString(); }
        catch (Throwable t) { return "?"; }
    }
}
