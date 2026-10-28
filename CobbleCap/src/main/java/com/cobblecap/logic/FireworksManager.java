package com.cobblecap.logic;

import com.cobblecap.CobbleCapMod;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FireworkExplosionComponent;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spawns a celebratory fireworks show inside a CheeseGuard zone when a new
 * ranked Maître de la Ligue is crowned. Configurable via CapConfig:
 *   fireworksEnabled / fireworksZoneId / fireworksCount / fireworksIntervalMs /
 *   fireworksDurationSec
 *
 * CheeseGuard is queried via reflection so CobbleCap doesn't have to declare a
 * hard mod dependency on it (CheeseGuard is "suggests"-level for CheeseDash).
 * If CheeseGuard isn't installed or the configured zone is missing, the show
 * is silently skipped with a debug log.
 */
public final class FireworksManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");
    private static final Random RNG = new Random();
    /** Re-entry guard so simultaneous champion changes don't stack shows. */
    private static final AtomicBoolean SHOW_RUNNING = new AtomicBoolean(false);

    private FireworksManager() {}

    /** Trigger a fireworks show for the just-crowned ranked champion. No-op if disabled. */
    public static void triggerShow(MinecraftServer server) { triggerShow(server, /*force=*/false); }

    /**
     * Force-run a fireworks show using the current configuration, ignoring the
     * {@code fireworksEnabled} flag. Used by the dashboard "Test fireworks"
     * button so admins can validate the zone + visuals before turning the
     * feature on for real champion events.
     */
    public static void triggerTestShow(MinecraftServer server) { triggerShow(server, /*force=*/true); }

    private static void triggerShow(MinecraftServer server, boolean force) {
        if (server == null) return;
        var cfg = CobbleCapMod.INSTANCE.getCapConfig();
        if (!force && !cfg.isFireworksEnabled()) return;
        if (cfg.getFireworksZoneId() == null || cfg.getFireworksZoneId().isBlank()) {
            LOGGER.debug("[CobbleCap] Fireworks {}but no zone configured — skipped",
                    force ? "test " : "enabled ");
            return;
        }
        if (!SHOW_RUNNING.compareAndSet(false, true)) {
            LOGGER.info("[CobbleCap] Fireworks show already running — new request coalesced");
            return;
        }

        ZoneBounds bounds = resolveZone(server, cfg.getFireworksZoneId());
        if (bounds == null) {
            LOGGER.warn("[CobbleCap] Fireworks zone '{}' not found via CheeseGuard — show aborted",
                    cfg.getFireworksZoneId());
            SHOW_RUNNING.set(false);
            return;
        }
        ServerWorld world = bounds.world;
        int count = cfg.getFireworksCount();
        int intervalMs = cfg.getFireworksIntervalMs();
        long deadlineMs = System.currentTimeMillis() + (long) cfg.getFireworksDurationSec() * 1000L;
        LOGGER.info("[CobbleCap] Fireworks show starting in zone '{}' ({} rockets, every {}ms, max {}s)",
                cfg.getFireworksZoneId(), count, intervalMs, cfg.getFireworksDurationSec());

        new Thread(() -> {
            try {
                for (int i = 0; i < count; i++) {
                    if (System.currentTimeMillis() > deadlineMs) {
                        LOGGER.info("[CobbleCap] Fireworks show hit duration cap at rocket {}/{}", i, count);
                        break;
                    }
                    final int idx = i;
                    server.execute(() -> spawnOneRocket(world, bounds, idx));
                    try { Thread.sleep(intervalMs); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            } finally {
                SHOW_RUNNING.set(false);
                LOGGER.info("[CobbleCap] Fireworks show complete");
            }
        }, "cobblecap-fireworks").start();
    }

    private static void spawnOneRocket(ServerWorld world, ZoneBounds b, int idx) {
        try {
            int x = b.minX + RNG.nextInt(Math.max(1, b.maxX - b.minX + 1));
            int z = b.minZ + RNG.nextInt(Math.max(1, b.maxZ - b.minZ + 1));
            // Spawn at zone top — rockets fly up from there.
            int y = b.maxY;
            BlockPos pos = new BlockPos(x, y, z);

            ItemStack stack = buildRandomFireworkStack();
            FireworkRocketEntity rocket = new FireworkRocketEntity(world,
                    pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, stack);
            world.spawnEntity(rocket);
        } catch (Throwable t) {
            LOGGER.debug("[CobbleCap] fireworks rocket {} failed: {}", idx, t.getMessage());
        }
    }

    /** Build a random-coloured firework_rocket ItemStack with a 1-3 second flight. */
    private static ItemStack buildRandomFireworkStack() {
        ItemStack stack = new ItemStack(Items.FIREWORK_ROCKET);
        // 1-3 = ~1-3 second flight time
        byte flight = (byte) (1 + RNG.nextInt(3));
        // Random shape per explosion
        FireworkExplosionComponent.Type[] shapes = FireworkExplosionComponent.Type.values();
        FireworkExplosionComponent.Type shape = shapes[RNG.nextInt(shapes.length)];
        // 1-3 explosions per rocket, each with 1-3 colours
        int explosions = 1 + RNG.nextInt(3);
        java.util.List<FireworkExplosionComponent> bursts = new java.util.ArrayList<>(explosions);
        for (int i = 0; i < explosions; i++) {
            it.unimi.dsi.fastutil.ints.IntArrayList colors = new it.unimi.dsi.fastutil.ints.IntArrayList();
            int cn = 1 + RNG.nextInt(3);
            for (int c = 0; c < cn; c++) colors.add(randomFireworkColor());
            it.unimi.dsi.fastutil.ints.IntArrayList fadeColors = new it.unimi.dsi.fastutil.ints.IntArrayList();
            if (RNG.nextBoolean()) fadeColors.add(randomFireworkColor());
            bursts.add(new FireworkExplosionComponent(shape, colors, fadeColors,
                    /*trail=*/RNG.nextBoolean(), /*twinkle=*/RNG.nextBoolean()));
        }
        stack.set(DataComponentTypes.FIREWORKS, new FireworksComponent(flight, bursts));
        return stack;
    }

    private static int randomFireworkColor() {
        // Use vanilla firework dye colours so the explosions look saturated.
        int[] palette = {
                0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F,
                0xF38BAA, 0x474F52, 0x9D9D97, 0x169C9C, 0x8932B8, 0x3C44AA,
                0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21
        };
        return palette[RNG.nextInt(palette.length)];
    }

    /** Zone snapshot — copied out of the CheeseGuard Region via reflection so we
     *  don't hold a reference to its mutable state on the spawn thread. */
    private static class ZoneBounds {
        final ServerWorld world;
        final int minX, minY, minZ, maxX, maxY, maxZ;
        ZoneBounds(ServerWorld world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.world = world;
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        }
    }

    /** Resolve a CheeseGuard zone by id, returning a position snapshot, or null. */
    private static ZoneBounds resolveZone(MinecraftServer server, String zoneId) {
        try {
            Class<?> modCls = Class.forName("com.cheeseguard.CheeseGuardMod");
            Object instance = modCls.getField("INSTANCE").get(null);
            if (instance == null) return null;
            Object storage = modCls.getMethod("getStorage").invoke(instance);
            if (storage == null) return null;
            Object region = storage.getClass().getMethod("getRegion", String.class).invoke(storage, zoneId);
            if (region == null) return null;
            Class<?> rcls = region.getClass();
            String worldId = (String) findGetter(rcls, "getWorld").invoke(region);
            int minX = (int) findGetter(rcls, "getMinX").invoke(region);
            int minY = (int) findGetter(rcls, "getMinY").invoke(region);
            int minZ = (int) findGetter(rcls, "getMinZ").invoke(region);
            int maxX = (int) findGetter(rcls, "getMaxX").invoke(region);
            int maxY = (int) findGetter(rcls, "getMaxY").invoke(region);
            int maxZ = (int) findGetter(rcls, "getMaxZ").invoke(region);
            ServerWorld world = findWorld(server, worldId);
            if (world == null) {
                LOGGER.warn("[CobbleCap] Fireworks zone '{}' targets world '{}' which isn't loaded", zoneId, worldId);
                return null;
            }
            return new ZoneBounds(world, minX, minY, minZ, maxX, maxY, maxZ);
        } catch (ClassNotFoundException notInstalled) {
            LOGGER.debug("[CobbleCap] CheeseGuard not installed — fireworks zone lookup skipped");
            return null;
        } catch (Exception e) {
            LOGGER.warn("[CobbleCap] resolveZone({}) error: {}", zoneId, e.getMessage());
            return null;
        }
    }

    private static Method findGetter(Class<?> cls, String name) throws NoSuchMethodException {
        return cls.getMethod(name);
    }

    private static ServerWorld findWorld(MinecraftServer server, String worldId) {
        for (ServerWorld w : server.getWorlds()) {
            if (w.getRegistryKey().getValue().toString().equals(worldId)) return w;
        }
        return null;
    }

    /** Public lookup: list all available CheeseGuard zone ids — used by the dashboard
     *  UI to populate a dropdown. Returns empty list if CheeseGuard absent. */
    @SuppressWarnings("unchecked")
    public static List<String> listAvailableZones() {
        try {
            Class<?> modCls = Class.forName("com.cheeseguard.CheeseGuardMod");
            Object instance = modCls.getField("INSTANCE").get(null);
            if (instance == null) return List.of();
            Object storage = modCls.getMethod("getStorage").invoke(instance);
            if (storage == null) return List.of();
            Object regions = storage.getClass().getMethod("getRegions").invoke(storage);
            if (!(regions instanceof Iterable<?> iter)) return List.of();
            java.util.List<String> ids = new java.util.ArrayList<>();
            for (Object r : iter) {
                Object id = r.getClass().getMethod("getId").invoke(r);
                if (id != null) ids.add(id.toString());
            }
            java.util.Collections.sort(ids);
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }
}
