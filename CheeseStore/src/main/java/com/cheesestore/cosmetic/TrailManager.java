package com.cheesestore.cosmetic;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders the equipped TRAIL cosmetic for every online player on the server tick.
 *
 * <p>For each player with a TRAIL equipped (via {@link EquipManager#getEquipped}), the
 * catalogue entry's params drive the look:
 * <ul>
 *   <li>{@code particle} — vanilla particle id (e.g. {@code minecraft:flame})</li>
 *   <li>{@code shape}    — {@code trail} (under the feet), {@code halo} (ring around the waist),
 *                          {@code spiral} (rising helix), {@code orbit} (points circling the body)</li>
 *   <li>{@code density}  — base particle count multiplier (clamped 1..6)</li>
 * </ul>
 *
 * <p>Particles are spawned server-side via
 * {@link ServerWorld#spawnParticles(ParticleEffect, double, double, double, int, double, double, double, double)},
 * so they are visible to ALL nearby players (not just the wearer).
 *
 * <p>Performance: the trail only renders every {@link #TICK_INTERVAL} ticks, particle counts are
 * clamped, and {@code trail}/{@code halo} are skipped when the player is standing still to keep the
 * effect cheap and visually clean. Animated shapes ({@code spiral}/{@code orbit}) use a monotonic
 * tick counter so they rotate over time.
 *
 * <p>Contract (do not change): {@code public static void init()} — idempotent, registers its own
 * Fabric tick event.
 */
public final class TrailManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseStore");

    /** Render cadence in server ticks (20 ticks = 1 s). Every 2 ticks ≈ 10 renders/s — smooth + cheap. */
    private static final int TICK_INTERVAL = 2;

    /** Hard cap on density to bound packet cost regardless of catalogue/admin values. */
    private static final int MAX_DENSITY = 6;

    /** Speed (squared, per render window) at which the moving trail reaches full strength. */
    private static final double FULL_SPEED_SQ = 0.09; // ~0.3 blocks/render → sprinting

    /** Monotonic render counter, used to animate spiral/orbit and to gate the interval. */
    private static long tickCounter = 0L;

    /**
     * Last known horizontal position per player, captured on the previous render so we can compute
     * real movement. {@code ServerPlayerEntity.prevX}/{@code prevZ} are NOT reliably updated on the
     * server tick (they often equal the current position when END_SERVER_TICK fires), which made the
     * old velocity check almost always read "standing still" — that was the root cause of the
     * {@code shape=trail} cosmetics spawning nothing at all.
     */
    private static final Map<UUID, double[]> LAST_POS = new ConcurrentHashMap<>();

    /** Resolved particle cache keyed by particle id string (avoids per-tick registry lookups). */
    private static final Map<String, ParticleEffect> PARTICLE_CACHE = new ConcurrentHashMap<>();

    /** Sentinel cached when an id fails to resolve, so we don't re-resolve a bad id every tick. */
    private static final ParticleEffect UNRESOLVED = ParticleTypes.FLAME;

    private static volatile boolean registered = false;

    private TrailManager() {}

    // ── Lifecycle ────────────────────────────────────────────────────────────────

    public static void init() {
        if (registered) return; // idempotent
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(TrailManager::onEndTick);
        LOGGER.info("[CheeseStore] TrailManager initialised (render every {} ticks)", TICK_INTERVAL);
    }

    // ── Tick ─────────────────────────────────────────────────────────────────────

    private static void onEndTick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter % TICK_INTERVAL != 0) return;

        EquipManager equip = EquipManager.getInstance();
        CosmeticManager catalog = CosmeticManager.getInstance();
        if (equip == null || catalog == null) return; // not yet initialised

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player == null || player.isRemoved() || player.isSpectator()) continue;

            UUID uuid;
            try {
                uuid = player.getUuid();
            } catch (Exception ex) {
                continue;
            }
            if (uuid == null) continue;

            String trailId = equip.getEquipped(uuid, "TRAIL");
            if (trailId == null) continue;

            CosmeticEntry entry = catalog.get(trailId);
            if (entry == null || !entry.isEnabled()) continue;

            try {
                render(player, entry);
            } catch (Exception ex) {
                // Never let one player's trail break the tick for everyone else.
                LOGGER.debug("[CheeseStore] Trail render failed for {}: {}",
                        player.getName().getString(), ex.toString());
            }
        }

        // Occasionally drop movement snapshots for players no longer online (bounded memory).
        if (tickCounter % (TICK_INTERVAL * 100L) == 0 && !LAST_POS.isEmpty()) {
            LAST_POS.keySet().removeIf(id -> server.getPlayerManager().getPlayer(id) == null);
        }
    }

    // ── Rendering ────────────────────────────────────────────────────────────────

    private static void render(ServerPlayerEntity player, CosmeticEntry entry) {
        ServerWorld world = player.getServerWorld();
        if (world == null) return;

        ParticleEffect particle = resolveParticle(entry.param("particle", "minecraft:flame"));
        if (particle == null) return;

        int density = Math.max(1, Math.min(MAX_DENSITY, entry.paramInt("density", 2)));
        String shape = entry.param("shape", "trail").trim().toLowerCase(Locale.ROOT);

        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        double height = Math.max(1.0, player.getHeight()); // ~1.8 standing, less when sneaking

        switch (shape) {
            case "halo"   -> renderHalo(world, particle, x, y + height * 0.55, z, density);
            case "spiral" -> renderSpiral(world, particle, x, y, z, height, density);
            case "orbit"  -> renderOrbit(world, particle, x, y + height * 0.55, z, density);
            default       -> renderTrail(world, player, particle, x, y, z, density);
        }
    }

    /**
     * TRAIL: a soft puff at the player's feet that is ALWAYS present — faint when standing still,
     * fuller and more spread out the faster the player moves.
     *
     * <p>Movement is measured from a self-tracked previous position ({@link #LAST_POS}) rather than
     * {@code ServerPlayerEntity.prevX}/{@code prevZ}, which are unreliable on the server tick.
     */
    private static void renderTrail(ServerWorld world, ServerPlayerEntity player, ParticleEffect particle,
                                    double x, double y, double z, int density) {
        UUID uuid = player.getUuid();
        double[] last = LAST_POS.get(uuid);
        // Update our snapshot for next render before doing anything else.
        LAST_POS.put(uuid, new double[]{x, z});

        // Horizontal displacement since the previous render (0 on the very first render).
        double moveSq = 0.0;
        if (last != null) {
            double dx = x - last[0];
            double dz = z - last[1];
            moveSq = dx * dx + dz * dz;
        }

        // 0.0 (still) .. 1.0 (sprinting). Always spawn something, even at rest.
        double movingFactor = Math.min(1.0, moveSq / FULL_SPEED_SQ);

        // Count scales from a faint idle puff up to the full density while moving.
        // idle: max(1, density/3) ; moving: up to `density` extra puffs on top.
        int idleCount = Math.max(1, density / 3);
        int count = idleCount + (int) Math.round(density * movingFactor);
        count = Math.max(1, Math.min(MAX_DENSITY, count)); // bound packet cost

        // Tighter cloud at rest, wider spread (and a touch of upward drift) while moving.
        double spread = 0.08 + 0.14 * movingFactor;
        double drift = 0.02 + 0.04 * movingFactor;
        spawn(world, particle, x, y + 0.1, z, count, spread, drift, spread, 0.0);
    }

    /** HALO: a static ring of particles around the player's waist. */
    private static void renderHalo(ServerWorld world, ParticleEffect particle,
                                   double cx, double cy, double cz, int density) {
        int points = 6 + density * 2; // 8..18 points around the ring
        double radius = 0.7;
        for (int i = 0; i < points; i++) {
            double angle = (Math.PI * 2.0) * i / points;
            double px = cx + Math.cos(angle) * radius;
            double pz = cz + Math.sin(angle) * radius;
            spawn(world, particle, px, cy, pz, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /** SPIRAL: a rising helix wrapping the player; the phase advances with the tick counter. */
    private static void renderSpiral(ServerWorld world, ParticleEffect particle,
                                     double cx, double cy, double cz, double height, int density) {
        int points = 3 + density;          // 4..9 points per render
        double radius = 0.55;
        double turns = 1.5;                 // how many wraps over the player's height
        double phase = (tickCounter % 40) / 40.0 * Math.PI * 2.0; // animate the helix rotation
        for (int i = 0; i < points; i++) {
            double t = (double) i / points; // 0..1 up the body
            double angle = phase + t * Math.PI * 2.0 * turns;
            double px = cx + Math.cos(angle) * radius;
            double pz = cz + Math.sin(angle) * radius;
            double py = cy + t * height;
            spawn(world, particle, px, py, pz, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /** ORBIT: a few particles circling the player's waist, rotating over time. */
    private static void renderOrbit(ServerWorld world, ParticleEffect particle,
                                    double cx, double cy, double cz, int density) {
        int points = 1 + density / 2;      // 1..4 orbiting points
        double radius = 0.9;
        double phase = (tickCounter % 30) / 30.0 * Math.PI * 2.0; // one revolution ~3 s
        for (int i = 0; i < points; i++) {
            double angle = phase + (Math.PI * 2.0) * i / points;
            double px = cx + Math.cos(angle) * radius;
            double pz = cz + Math.sin(angle) * radius;
            double py = cy + Math.sin(phase * 2.0) * 0.15; // gentle vertical bob
            spawn(world, particle, px, py, pz, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /**
     * Spawn a particle visible to all nearby players (server broadcast).
     * Uses the {@code force=false} overload so distant clients are not spammed.
     */
    private static void spawn(ServerWorld world, ParticleEffect particle,
                              double x, double y, double z, int count,
                              double dx, double dy, double dz, double speed) {
        world.spawnParticles(particle, x, y, z, count, dx, dy, dz, speed);
    }

    /**
     * Resolve a particle id string to a usable {@link ParticleEffect}, cached.
     *
     * <p>Only simple, parameterless particle types (which implement {@code ParticleEffect}
     * themselves) are supported here — that covers every TRAIL particle in the default
     * catalogue. Parameterised types (dust, block, item…) cannot be built from an id alone,
     * so we fall back to a sane default for them.
     */
    private static ParticleEffect resolveParticle(String id) {
        if (id == null || id.isBlank()) return ParticleTypes.FLAME;
        ParticleEffect cached = PARTICLE_CACHE.get(id);
        if (cached != null) {
            return cached == UNRESOLVED ? null : cached;
        }

        ParticleEffect resolved = lookup(id);
        PARTICLE_CACHE.put(id, resolved == null ? UNRESOLVED : resolved);
        return resolved;
    }

    private static ParticleEffect lookup(String id) {
        try {
            Identifier ident = Identifier.tryParse(id);
            if (ident == null) {
                LOGGER.warn("[CheeseStore] Trail particle id not parseable: '{}' — falling back to flame", id);
                return ParticleTypes.FLAME;
            }
            // Use getOrEmpty: Registries.get(id) returns the registry DEFAULT (non-null) for an
            // unknown id, which would silently mask typos. getOrEmpty is empty for unknown ids.
            Object type = Registries.PARTICLE_TYPE.getOrEmpty(ident).orElse(null);
            if (type instanceof ParticleEffect effect) {
                // SimpleParticleType implements ParticleEffect — usable directly.
                // (Parameterised types like dust/block/item do NOT, so they fall through.)
                return effect;
            }
            // Unknown id, or a parameterised type that can't be built from an id alone.
            LOGGER.warn("[CheeseStore] Trail particle '{}' is unknown or parameterised — falling back to flame", id);
            return ParticleTypes.FLAME;
        } catch (Exception ex) {
            LOGGER.warn("[CheeseStore] Failed to resolve trail particle '{}': {}", id, ex.toString());
            return ParticleTypes.FLAME;
        }
    }
}
