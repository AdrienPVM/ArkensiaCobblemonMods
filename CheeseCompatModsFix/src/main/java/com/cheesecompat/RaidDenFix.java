package com.cheesecompat;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v5 — Fix for Cobblemon Raid Dens crystal not closing after players leave/die/disconnect.
 *
 * Track by CRYSTAL POSITION (unique per crystal) instead of dimension ID (reused per player).
 * This prevents the map-overwrite bug where rapid sequential raids by the same player
 * caused the old crystal to be forgotten.
 *
 * When a player exits a raid, schedule an immediate check (1s) on that specific crystal.
 * Force-load the crystal's chunk if needed.
 */
public class RaidDenFix {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseCompatModsFix");
    private static final String RAID_DIM_PREFIX = "cobblemonraiddens";
    private static final String CRYSTAL_CLASS = "RaidCrystalBlockEntity";
    private static final String HOME_CLASS = "RaidHomeBlockEntity";

    /**
     * Crystals pending cleanup check. Key = "worldId@x,y,z" (unique per crystal).
     * Value = tick when the crystal was flagged for checking.
     */
    private static final Map<String, PendingCrystal> pending = new ConcurrentHashMap<>();

    // Reflection handles
    private static Method mGetHomePos, mGetHome, mSetQueueClose;
    private static Field fRaidHost, fQueueClose, fPlayerQueue, fDimensionLevel, fInactiveTicks;
    private static boolean reqReflectionDone = false;
    private static boolean reqReflectionFailed = false;
    private static boolean optReflectionTried = false;

    private record PendingCrystal(BlockPos pos, ServerWorld world, long flaggedAt) {}

    private static final int FAST_CHECK_DELAY = 20;   // 1s after player exits
    private static final int SCAN_INTERVAL = 100;      // 5s periodic scan
    private static final int GRACE_PERIOD = 100;       // 5s before first action
    private static final int MEDIUM_THRESHOLD = 200;   // 10s
    private static final int NUCLEAR_THRESHOLD = 300;  // 15s
    private static final int GIVE_UP_THRESHOLD = 1200; // 60s — stop tracking

    public static void register() {
        // === Player enters or leaves raid dimension ===
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
            String originDim = origin.getRegistryKey().getValue().toString();

            // Player LEAVING a raid → flag crystal for cleanup
            if (originDim.contains(RAID_DIM_PREFIX)) {
                boolean dimNowEmpty = origin.getPlayers().isEmpty()
                        || (origin.getPlayers().size() == 1 && origin.getPlayers().get(0) == player);
                if (dimNowEmpty) {
                    flagCrystalFromRaidDim(origin, player.getServer());
                }
            }
        });

        // === Player disconnects while in raid ===
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            String playerDim = player.getServerWorld().getRegistryKey().getValue().toString();
            if (playerDim.contains(RAID_DIM_PREFIX)) {
                ServerWorld raidWorld = player.getServerWorld();
                boolean dimNowEmpty = raidWorld.getPlayers().isEmpty()
                        || (raidWorld.getPlayers().size() == 1 && raidWorld.getPlayers().get(0) == player);
                if (dimNowEmpty) {
                    flagCrystalFromRaidDim(raidWorld, server);
                }
            }
        });

        // === Periodic scan ===
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % SCAN_INTERVAL != 0) return;
            processPending(server);
        });

        LOGGER.info("[CheeseCompat] Raid Den crystal fix registered (v5 — position-based tracking)");
    }

    /**
     * Read the crystal position from the raid dimension's home block and add it to pending cleanup.
     */
    private static void flagCrystalFromRaidDim(ServerWorld raidWorld, MinecraftServer server) {
        try {
            BlockEntity homeEntity = raidWorld.getBlockEntity(BlockPos.ORIGIN);
            if (homeEntity == null || !homeEntity.getClass().getName().contains(HOME_CLASS)) return;

            initRequiredReflection(homeEntity.getClass(), null);
            if (reqReflectionFailed) return;

            BlockPos crystalPos = (BlockPos) mGetHomePos.invoke(homeEntity);
            Object homeDimKey = mGetHome.invoke(homeEntity);
            if (crystalPos == null || homeDimKey == null) return;

            ServerWorld homeWorld = null;
            for (ServerWorld sw : server.getWorlds()) {
                if (sw.getRegistryKey().equals(homeDimKey)) {
                    homeWorld = sw;
                    break;
                }
            }
            if (homeWorld == null) return;

            String key = crystalKey(homeWorld, crystalPos);
            if (!pending.containsKey(key)) {
                pending.put(key, new PendingCrystal(crystalPos, homeWorld, server.getTicks()));
                LOGGER.info("[CheeseCompat] Flagged crystal at {} for cleanup check",
                        crystalPos.toShortString());
            }
        } catch (Exception e) {
            LOGGER.debug("[CheeseCompat] flagCrystalFromRaidDim failed: {}", e.getMessage());
        }
    }

    /**
     * Check all pending crystals and clean up stuck ones.
     */
    private static void processPending(MinecraftServer server) {
        long now = server.getTicks();

        for (var entry : new ArrayList<>(pending.entrySet())) {
            String key = entry.getKey();
            PendingCrystal pc = entry.getValue();
            long age = now - pc.flaggedAt;

            // Give up after 60 seconds
            if (age > GIVE_UP_THRESHOLD) {
                LOGGER.debug("[CheeseCompat] Giving up on crystal at {} after 60s", pc.pos.toShortString());
                pending.remove(key);
                continue;
            }

            try {
                // Force-load the crystal's chunk (async — may take 1-2 ticks)
                int cx = pc.pos.getX() >> 4;
                int cz = pc.pos.getZ() >> 4;
                long chunkLong = ChunkPos.toLong(cx, cz);
                boolean wasForced = pc.world.getForcedChunks().contains(chunkLong);
                if (!wasForced) {
                    pc.world.setChunkForced(cx, cz, true);
                }

                BlockEntity crystalEntity = pc.world.getBlockEntity(pc.pos);

                if (crystalEntity == null || !crystalEntity.getClass().getName().contains(CRYSTAL_CLASS)) {
                    // Chunk might not be loaded yet (async force-load) — retry next scan
                    // Only give up after GIVE_UP_THRESHOLD
                    if (age > GIVE_UP_THRESHOLD) {
                        LOGGER.info("[CheeseCompat] Crystal at {} not found after force-load, giving up ({}s)",
                                pc.pos.toShortString(), age / 20);
                        pending.remove(key);
                        if (!wasForced) pc.world.setChunkForced(cx, cz, false);
                    } else {
                        LOGGER.debug("[CheeseCompat] Crystal at {} not loaded yet, retrying ({}s)",
                                pc.pos.toShortString(), age / 20);
                        // Keep chunk forced until next scan
                    }
                    continue;
                }

                // Chunk is loaded and crystal found — unforce chunk now
                if (!wasForced) {
                    pc.world.setChunkForced(cx, cz, false);
                }

                initRequiredReflection(null, crystalEntity.getClass());
                if (reqReflectionFailed) continue;
                initOptionalReflection(crystalEntity.getClass());

                Object raidHost = fRaidHost.get(crystalEntity);
                if (raidHost == null) {
                    // raidHost cleaned — but check if block state is still visually ACTIVE
                    fixBlockStateIfNeeded(pc.world, pc.pos, crystalEntity);
                    LOGGER.info("[CheeseCompat] Crystal at {} already cleaned by mod ({}s)",
                            pc.pos.toShortString(), age / 20);
                    pending.remove(key);
                    continue;
                }

                // Crystal still has raidHost — escalate based on age
                if (age < GRACE_PERIOD) {
                    // Grace period — let the mod handle it
                } else if (age < MEDIUM_THRESHOLD) {
                    // Pass 1: setQueueClose
                    boolean queued = fQueueClose.getBoolean(crystalEntity);
                    if (!queued) {
                        mSetQueueClose.invoke(crystalEntity);
                        LOGGER.info("[CheeseCompat] Pass 1: setQueueClose at {} ({}s)",
                                pc.pos.toShortString(), age / 20);
                    }
                } else if (age < NUCLEAR_THRESHOLD) {
                    // Pass 2: force queueClose + null dimension ref
                    fQueueClose.setBoolean(crystalEntity, true);
                    if (fDimensionLevel != null) {
                        try { fDimensionLevel.set(crystalEntity, null); } catch (Exception ignored) {}
                    }
                    crystalEntity.markDirty();
                    LOGGER.info("[CheeseCompat] Pass 2: forced queueClose + null dim at {} ({}s)",
                            pc.pos.toShortString(), age / 20);
                } else {
                    // Pass 3: nuclear reset
                    LOGGER.warn("[CheeseCompat] Pass 3: NUCLEAR RESET at {} (stuck {}s)",
                            pc.pos.toShortString(), age / 20);
                    nuclearReset(crystalEntity);
                    pending.remove(key);
                }

            } catch (Exception e) {
                LOGGER.debug("[CheeseCompat] processPending error for {}: {}", pc.pos.toShortString(), e.getMessage());
            }
        }
    }

    private static void nuclearReset(BlockEntity crystal) {
        try {
            fRaidHost.set(crystal, null);
            fQueueClose.setBoolean(crystal, false);

            if (fDimensionLevel != null) {
                try { fDimensionLevel.set(crystal, null); } catch (Exception ignored) {}
            }
            if (fInactiveTicks != null) {
                try { fInactiveTicks.setInt(crystal, 0); } catch (Exception ignored) {}
            }
            if (fPlayerQueue != null) {
                try {
                    Object pq = fPlayerQueue.get(crystal);
                    if (pq instanceof Collection<?> col) col.clear();
                } catch (Exception ignored) {}
            }

            crystal.markDirty();

            // Also fix block state
            if (crystal.getWorld() instanceof ServerWorld sw) {
                fixBlockStateIfNeeded(sw, crystal.getPos(), crystal);
            }

            LOGGER.info("[CheeseCompat] Nuclear reset complete");
        } catch (Exception e) {
            LOGGER.error("[CheeseCompat] Nuclear reset failed: {}", e.getMessage());
        }
    }

    /**
     * If the crystal's block state still has ACTIVE=true but raidHost is null,
     * force the block state to ACTIVE=false. This fixes the visual glitch where
     * the mod cleans up raidHost but doesn't update the block appearance.
     */
    private static void fixBlockStateIfNeeded(ServerWorld world, BlockPos pos, BlockEntity crystal) {
        try {
            net.minecraft.block.BlockState state = world.getBlockState(pos);
            // Find the ACTIVE BooleanProperty on the block class via reflection
            if (fActiveProperty == null && !activePropertySearched) {
                activePropertySearched = true;
                try {
                    Class<?> blockClass = state.getBlock().getClass();
                    // Walk hierarchy to find static ACTIVE field
                    Class<?> current = blockClass;
                    while (current != null && current != Object.class) {
                        try {
                            java.lang.reflect.Field f = current.getDeclaredField("ACTIVE");
                            f.setAccessible(true);
                            Object val = f.get(null); // static field
                            if (val instanceof net.minecraft.state.property.BooleanProperty bp) {
                                fActiveProperty = bp;
                                LOGGER.info("[CheeseCompat] Found ACTIVE property on {}", current.getName());
                            }
                            break;
                        } catch (NoSuchFieldException ignored) {}
                        current = current.getSuperclass();
                    }
                } catch (Exception e) {
                    LOGGER.debug("[CheeseCompat] Could not find ACTIVE property: {}", e.getMessage());
                }
            }

            if (fActiveProperty != null && state.contains(fActiveProperty)) {
                boolean isActive = state.get(fActiveProperty);
                if (isActive) {
                    world.setBlockState(pos, state.with(fActiveProperty, false));
                    LOGGER.info("[CheeseCompat] Fixed block state: ACTIVE=false at {}", pos.toShortString());
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[CheeseCompat] fixBlockState failed: {}", e.getMessage());
        }
    }

    private static net.minecraft.state.property.BooleanProperty fActiveProperty = null;
    private static boolean activePropertySearched = false;

    private static String crystalKey(ServerWorld world, BlockPos pos) {
        return world.getRegistryKey().getValue() + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    // =====================================================================
    // Reflection — walks class hierarchy
    // =====================================================================

    private static Field findFieldInHierarchy(Class<?> cls, String name) throws NoSuchFieldException {
        Class<?> current = cls;
        while (current != null && current != Object.class) {
            try {
                Field f = current.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
            current = current.getSuperclass();
        }
        throw new NoSuchFieldException(name + " not found in " + cls.getName() + " hierarchy");
    }

    private static Method findMethodInHierarchy(Class<?> cls, String name, Class<?>... params) throws NoSuchMethodException {
        Class<?> current = cls;
        while (current != null && current != Object.class) {
            try { return current.getMethod(name, params); } catch (NoSuchMethodException ignored) {}
            try {
                Method m = current.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(name + " not found in " + cls.getName() + " hierarchy");
    }

    private static void initRequiredReflection(Class<?> homeClass, Class<?> crystalClass) {
        if (reqReflectionFailed) return;
        try {
            if (homeClass != null && mGetHomePos == null) {
                mGetHomePos = findMethodInHierarchy(homeClass, "getHomePos");
                mGetHome = findMethodInHierarchy(homeClass, "getHome");
                LOGGER.info("[CheeseCompat] Home reflection OK ({})", homeClass.getName());
            }
            if (crystalClass != null && fRaidHost == null) {
                fRaidHost = findFieldInHierarchy(crystalClass, "raidHost");
                fQueueClose = findFieldInHierarchy(crystalClass, "queueClose");
                mSetQueueClose = findMethodInHierarchy(crystalClass, "setQueueClose");
                reqReflectionDone = true;
                LOGGER.info("[CheeseCompat] Crystal reflection OK ({})", crystalClass.getName());
            }
        } catch (Exception e) {
            LOGGER.error("[CheeseCompat] REQUIRED reflection failed — fix disabled: {}", e.getMessage());
            reqReflectionFailed = true;
        }
    }

    private static void initOptionalReflection(Class<?> crystalClass) {
        if (optReflectionTried) return;
        optReflectionTried = true;
        try { fPlayerQueue = findFieldInHierarchy(crystalClass, "playerQueue"); } catch (Exception ignored) {}
        try { fDimensionLevel = findFieldInHierarchy(crystalClass, "dimensionLevel"); } catch (Exception ignored) {}
        try { fInactiveTicks = findFieldInHierarchy(crystalClass, "inactiveTicks"); } catch (Exception ignored) {}
        LOGGER.info("[CheeseCompat] Optional reflection: queue={}, dim={}, ticks={}",
                fPlayerQueue != null ? "OK" : "MISS",
                fDimensionLevel != null ? "OK" : "MISS",
                fInactiveTicks != null ? "OK" : "MISS");
    }
}
