package com.cobblecap.logic;

import com.cobblecap.network.PastureSyncPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side authority for pasture-tethered Pokémon. The data lives only
 * server-side ({@code Pokemon.tetheringId} is never DataTracker-synced), so
 * /pasturehide on the client has to ask the server who's tethered.
 *
 * Lifecycle:
 *   - Every 40 ticks (2s), scan all PokemonEntity in all worlds via reflection
 *     for a non-null tethering / tetheringId.
 *   - Compute diff vs. previous set → broadcast {@link PastureSyncPayload#add}
 *     / {@link PastureSyncPayload#remove} packets to all connected clients.
 *   - On player join, send a {@link PastureSyncPayload#snapshot} containing
 *     the current set.
 */
public class PastureTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");

    /** UUIDs of all currently-tethered Pokémon entities, server-wide. */
    private static final Set<UUID> PASTURED = ConcurrentHashMap.newKeySet();

    private static int tickCounter = 0;

    /** Called every server tick — actual scan is throttled to once per ~2s. */
    public static void tick(MinecraftServer server) {
        if (server == null) return;
        if (++tickCounter % 40 != 0) return;
        try {
            scanAndBroadcast(server);
        } catch (Exception e) {
            LOGGER.debug("[CobbleCap] PastureTracker scan failed: {}", e.getMessage());
        }
    }

    /** Send full current snapshot to a single player (on join / dim change). */
    public static void sendSnapshotTo(ServerPlayerEntity player) {
        try {
            if (!ServerPlayNetworking.canSend(player, PastureSyncPayload.ID)) return;
            ServerPlayNetworking.send(player,
                    PastureSyncPayload.snapshot(new ArrayList<>(PASTURED)));
        } catch (Exception e) {
            LOGGER.debug("[CobbleCap] PastureTracker snapshot send failed: {}", e.getMessage());
        }
    }

    private static void scanAndBroadcast(MinecraftServer server) {
        Set<UUID> nowTethered = new HashSet<>();
        for (ServerWorld w : server.getWorlds()) {
            for (Entity e : w.iterateEntities()) {
                if (!e.getClass().getName().endsWith(".PokemonEntity")) continue;
                if (isTetheredServerSide(e)) nowTethered.add(e.getUuid());
            }
        }
        // Diff
        Set<UUID> added = new HashSet<>(nowTethered);
        added.removeAll(PASTURED);
        Set<UUID> removed = new HashSet<>(PASTURED);
        removed.removeAll(nowTethered);
        if (added.isEmpty() && removed.isEmpty()) return;

        // Apply locally
        PASTURED.removeAll(removed);
        PASTURED.addAll(added);

        // Broadcast deltas
        List<ServerPlayerEntity> targets = server.getPlayerManager().getPlayerList();
        for (UUID u : added) {
            PastureSyncPayload p = PastureSyncPayload.add(u);
            for (ServerPlayerEntity sp : targets) {
                if (ServerPlayNetworking.canSend(sp, PastureSyncPayload.ID)) {
                    ServerPlayNetworking.send(sp, p);
                }
            }
        }
        for (UUID u : removed) {
            PastureSyncPayload p = PastureSyncPayload.remove(u);
            for (ServerPlayerEntity sp : targets) {
                if (ServerPlayNetworking.canSend(sp, PastureSyncPayload.ID)) {
                    ServerPlayNetworking.send(sp, p);
                }
            }
        }
        if (added.size() + removed.size() > 0) {
            LOGGER.debug("[CobbleCap] PastureTracker: +{} -{} (total tethered={})",
                    added.size(), removed.size(), PASTURED.size());
        }
    }

    /** Reflection: returns true if either getTethering() or getTetheringId()
     *  on the entity (or its Pokemon object) yields non-null. Server-side
     *  values are populated by Cobblemon's pasture block logic. */
    private static boolean isTetheredServerSide(Entity entity) {
        try {
            // 1. Direct: entity.getTetheringId()
            for (Method m : entity.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                String n = m.getName();
                if (n.equals("getTetheringId") || n.equals("getTethering")) {
                    try {
                        Object v = m.invoke(entity);
                        if (v != null) return true;
                    } catch (Exception ignored) {}
                }
            }
            // 2. Via Pokemon object
            Object pokemon = null;
            for (Method m : entity.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (!m.getName().equals("getPokemon")) continue;
                if (m.getReturnType().getSimpleName().equals("Pokemon")) {
                    try { pokemon = m.invoke(entity); break; }
                    catch (Exception ignored) {}
                }
            }
            if (pokemon == null) return false;
            for (Method m : pokemon.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                String n = m.getName();
                if (n.equals("getTetheringId") || n.equals("getTethering")) {
                    try {
                        Object v = m.invoke(pokemon);
                        if (v != null) return true;
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static int countTethered() { return PASTURED.size(); }
}
