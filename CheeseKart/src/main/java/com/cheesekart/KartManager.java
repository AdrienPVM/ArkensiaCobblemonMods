package com.cheesekart;

import com.cheesekart.network.KartSyncPayload;
import com.cheesekart.network.OpenBoatUtilsProxy;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class KartManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseKart");

    private final CheeseKartConfig config;
    private final Map<UUID, KartSession> sessions = new ConcurrentHashMap<>();
    private MinecraftServer server;

    public KartManager(CheeseKartConfig config) {
        this.config = config;
    }

    public void setServer(MinecraftServer server) { this.server = server; }
    public MinecraftServer getServer() { return server; }

    // ── Public API ────────────────────────────────────────────────────────────

    public boolean startKart(ServerPlayerEntity player, KartPreset preset, String speciesOverride) {
        UUID uuid = player.getUuid();

        // Stop existing kart first
        if (sessions.containsKey(uuid)) {
            stopKart(player);
        }

        // Spawn invisible boat at player position
        BoatEntity boat = new BoatEntity(player.getWorld(), player.getX(), player.getY(), player.getZ());
        boat.setInvisible(true);
        boat.setInvulnerable(true);
        boat.setSilent(true);
        player.getWorld().spawnEntity(boat);

        // Force mount
        if (!player.startRiding(boat, true)) {
            LOGGER.warn("[CheeseKart] Failed to mount {} on boat", player.getName().getString());
            boat.discard();
            return false;
        }

        // Apply OBU physics
        OpenBoatUtilsProxy.applyPreset(player, preset);

        // Determine species: override > preset > none
        String species = (speciesOverride != null && !speciesOverride.isBlank()) ? speciesOverride
                : (preset.getSpecies() != null && !preset.getSpecies().isBlank()) ? preset.getSpecies()
                : "";

        // CobbleShift transform (optional)
        if (!species.isEmpty() && CobbleShiftProxy.isAvailable()) {
            CobbleShiftProxy.transform(player, species);
        }

        // Track session
        KartSession session = new KartSession(
                uuid, player.getName().getString(),
                preset.getId(), species,
                boat.getId(), boat.getUuid(),
                System.currentTimeMillis(),
                preset.isLocked()
        );
        sessions.put(uuid, session);

        // Broadcast to all clients
        broadcastSync(uuid, true, boat.getId());

        LOGGER.info("[CheeseKart] Started kart for {} (preset={}, species={})",
                player.getName().getString(), preset.getId(), species.isEmpty() ? "none" : species);
        return true;
    }

    public void stopKart(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        KartSession session = sessions.remove(uuid);
        if (session == null) return;

        // 1. Broadcast sync FIRST — clears kart state on clients before dismount
        //    so DismountMixin won't block the client-side dismount processing
        broadcastSync(uuid, false, 0);

        // 2. Dismount player from boat
        player.stopRiding();

        // 3. Reset OBU physics (after dismount, resets client-side boat settings)
        OpenBoatUtilsProxy.sendReset(player);

        // 4. Kill the boat entity
        cleanupBoat(session);

        // 5. Revert CobbleShift transform
        if (!session.species().isEmpty() && CobbleShiftProxy.isAvailable()) {
            CobbleShiftProxy.revert(player);
        }

        // 6. Force position & velocity sync — prevents player stuck after dismounting
        //    invisible boat with custom OBU physics
        player.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
        player.velocityModified = true;
        player.requestTeleport(player.getX(), player.getY(), player.getZ());

        LOGGER.info("[CheeseKart] Stopped kart for {}", session.playerName());
    }

    public void stopAll() {
        if (server == null) return;
        new ArrayList<>(sessions.keySet()).forEach(uuid -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) stopKart(player);
            else sessions.remove(uuid); // cleanup stale
        });
    }

    // ── Tick — run each server tick ───────────────────────────────────────────

    public void tick() {
        if (server == null) return;
        var toStop = new ArrayList<ServerPlayerEntity>();
        var toCleanup = new ArrayList<UUID>();
        sessions.forEach((uuid, session) -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player == null) {
                toCleanup.add(uuid);
                return;
            }

            if (!player.hasVehicle()) {
                if (session.locked()) {
                    // Locked mode: try to remount, or cleanup if boat is gone
                    var boat = findBoat(player, session);
                    if (boat != null && boat.isAlive()) {
                        player.startRiding(boat, true);
                    } else {
                        toStop.add(player);
                    }
                } else {
                    // Unlocked mode: player dismounted → stop kart
                    toStop.add(player);
                }
            } else {
                // Player is riding — verify it's still our boat
                var boat = findBoat(player, session);
                if (boat == null || !boat.isAlive()) {
                    toStop.add(player);
                }
            }
        });
        toStop.forEach(this::stopKart);
        toCleanup.forEach(uuid -> {
            KartSession session = sessions.remove(uuid);
            if (session != null) {
                cleanupBoat(session);
                broadcastSync(uuid, false, 0);
            }
        });
    }

    // ── Player disconnect ─────────────────────────────────────────────────────

    public void onPlayerDisconnect(UUID uuid) {
        KartSession session = sessions.remove(uuid);
        if (session != null) {
            // Schedule boat cleanup on MC thread (disconnect fires on Netty IO thread)
            if (server != null) {
                server.execute(() -> cleanupBoat(session));
            }
            broadcastSync(uuid, false, 0);
        }
    }

    // ── Live-reload preset to active sessions ─────────────────────────────────

    /**
     * Re-apply OBU physics to all active sessions using the given preset.
     * Called after a preset is updated via dashboard for real-time tuning.
     */
    public void reapplyPreset(String presetId) {
        if (server == null) return;
        KartPreset preset = config.getPreset(presetId);
        if (preset == null) return;
        int count = 0;
        for (var entry : sessions.entrySet()) {
            KartSession session = entry.getValue();
            if (!session.presetId().equals(presetId)) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null) continue;
            OpenBoatUtilsProxy.applyPreset(player, preset);
            count++;
        }
        if (count > 0) {
            LOGGER.info("[CheeseKart] Re-applied preset '{}' to {} active session(s)", presetId, count);
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public Map<UUID, KartSession> getSessions() { return Collections.unmodifiableMap(sessions); }
    public KartSession getSession(UUID uuid) { return sessions.get(uuid); }
    public boolean isInKart(UUID uuid) { return sessions.containsKey(uuid); }

    // ── Internal ──────────────────────────────────────────────────────────────

    /** Find the kart boat entity, trying entity ID first (reliable), then UUID fallback. */
    private BoatEntity findBoat(ServerPlayerEntity player, KartSession session) {
        if (player.getWorld() instanceof net.minecraft.server.world.ServerWorld sw) {
            // Try by entity ID first (most reliable)
            var entity = sw.getEntityById(session.boatEntityId());
            if (entity instanceof BoatEntity boat) return boat;
            // Fallback: try by UUID
            entity = sw.getEntity(session.boatUuid());
            if (entity instanceof BoatEntity boat) return boat;
        }
        return null;
    }

    /** Kill the boat entity across all worlds. */
    private void cleanupBoat(KartSession session) {
        if (server == null) return;
        for (var world : server.getWorlds()) {
            // Try entity ID first
            var entity = world.getEntityById(session.boatEntityId());
            if (entity != null) { entity.discard(); return; }
            // Fallback UUID
            entity = world.getEntity(session.boatUuid());
            if (entity != null) { entity.discard(); return; }
        }
    }

    private void broadcastSync(UUID playerUuid, boolean active, int boatEntityId) {
        if (server == null) return;
        KartSyncPayload payload = new KartSyncPayload(playerUuid, active, boatEntityId);
        server.getPlayerManager().getPlayerList().forEach(p -> {
            if (ServerPlayNetworking.canSend(p, KartSyncPayload.ID)) {
                ServerPlayNetworking.send(p, payload);
            }
        });
    }
}
