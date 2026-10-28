package com.cheeseguard.logic;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which custom dimension a player entered the Nether from,
 * so the return portal can redirect them back there instead of Overworld.
 */
public class PortalOriginTracker {

    public record PortalOrigin(RegistryKey<World> dimension, Vec3d pos, float yaw, float pitch) {}

    /** Origin where the player entered a NETHER portal (= where they should land on return). */
    private static final Map<UUID, PortalOrigin> netherOrigins = new ConcurrentHashMap<>();
    /** Origin where the player entered an END portal (= where they should land on return). */
    private static final Map<UUID, PortalOrigin> endOrigins = new ConcurrentHashMap<>();

    // ── Nether ─────────────────────────────────────────────────────────────

    public static void store(UUID playerId, PortalOrigin origin) {
        netherOrigins.put(playerId, origin);
    }
    public static PortalOrigin get(UUID playerId)    { return netherOrigins.get(playerId); }
    public static PortalOrigin remove(UUID playerId) { return netherOrigins.remove(playerId); }

    // ── End ────────────────────────────────────────────────────────────────

    public static void storeEnd(UUID playerId, PortalOrigin origin) { endOrigins.put(playerId, origin); }
    public static PortalOrigin getEnd(UUID playerId)    { return endOrigins.get(playerId); }
    public static PortalOrigin removeEnd(UUID playerId) { return endOrigins.remove(playerId); }
}
