package com.cheeseraids.hooks;

import com.cheeseraids.CheeseRaidsMod;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Method;

/**
 * Reflection bridge to CobbleSafari's "Hoopa Ring" (dungeon) portals. Primary path is the private
 * {@code PortalSpawnManager.spawnPortal(ServerWorld, BlockPos, ServerPlayerEntity)} (placement at an
 * arbitrary location), with the public {@code spawnPortalNearPlayer(player, force)} as a fallback
 * (overworld-only).
 */
public final class CobbleSafariHook {
    private CobbleSafariHook() {}

    private static volatile boolean init = false;
    private static Method spawnAtM;    // spawnPortal(ServerWorld, BlockPos, ServerPlayerEntity)
    private static Method spawnNearM;  // spawnPortalNearPlayer(ServerPlayerEntity, boolean)

    private static synchronized void initApi() {
        if (init) return;
        try {
            Class<?> mgr = Class.forName("maxigregrze.cobblesafari.dungeon.PortalSpawnManager");
            for (Method m : mgr.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("spawnPortal") && p.length == 3
                        && ServerWorld.class.isAssignableFrom(p[0]) && BlockPos.class.isAssignableFrom(p[1])
                        && p[2].isAssignableFrom(ServerPlayerEntity.class)) {
                    m.setAccessible(true); spawnAtM = m;
                } else if (m.getName().equals("spawnPortalNearPlayer") && p.length == 2) {
                    m.setAccessible(true); spawnNearM = m;
                }
            }
            if (spawnAtM == null && spawnNearM == null)
                CheeseRaidsMod.LOGGER.warn("[CheeseRaids] CobbleSafari portal methods not found");
        } catch (Throwable t) {
            CheeseRaidsMod.LOGGER.warn("[CheeseRaids] CobbleSafari hook init failed: {}", t.getMessage());
        }
        init = true;
    }

    public static boolean available() {
        initApi();
        return spawnAtM != null || spawnNearM != null;
    }

    /**
     * Spawn a Hoopa Ring portal at a specific world+pos (owner gives origin/announcement context).
     * Falls back to near-player (overworld-only) if the placement method is unavailable. Returns true
     * on apparent success. Must run on the server thread.
     */
    public static boolean spawnPortal(ServerWorld world, BlockPos pos, ServerPlayerEntity owner) {
        initApi();
        if (spawnAtM != null) {
            try {
                Object r = spawnAtM.invoke(null, world, pos, owner);
                if (!(r instanceof Boolean) || (Boolean) r) return true;
                CheeseRaidsMod.LOGGER.warn("[CheeseRaids] spawnPortal(world,pos) returned false at {}", pos.toShortString());
            } catch (Throwable t) {
                CheeseRaidsMod.LOGGER.warn("[CheeseRaids] spawnPortal(world,pos) failed: {}", t.getMessage());
            }
        }
        if (spawnNearM != null && owner != null) {
            try {
                Object r = spawnNearM.invoke(null, owner, true);
                return !(r instanceof Boolean) || (Boolean) r;
            } catch (Throwable t) {
                CheeseRaidsMod.LOGGER.warn("[CheeseRaids] spawnPortalNearPlayer failed: {}", t.getMessage());
            }
        }
        return false;
    }
}
