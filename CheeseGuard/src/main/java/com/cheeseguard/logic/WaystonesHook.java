package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Soft-dependency hook for the Waystones mod (BlayTheNinth, fabric 21.1.x).
 * Uses reflection on the public WaystonesAPI to:
 *   - List all server waystones
 *   - Remove any waystone whose dimension matches a deleted CheeseGuard dim
 *
 * Calling removeWaystoneFromDatabase syncs the removal to all connected
 * clients automatically (so JourneyMap/Xaero pins disappear immediately).
 */
public class WaystonesHook {

    private static boolean initialized = false;
    private static boolean available = false;

    private static Method getAllWaystonesMethod;        // WaystonesAPI.getAllWaystones(MinecraftServer) -> Stream<Waystone>
    private static Method removeWaystoneMethod;         // WaystonesAPI.removeWaystoneFromDatabase(MinecraftServer, Waystone)
    private static Method getDimensionMethod;           // Waystone.getDimension() -> RegistryKey<World>
    private static Method getNameMethod;                // Waystone.getName() -> Text (for logging)

    public static void tryInit() {
        if (initialized) return;
        initialized = true;
        if (!FabricLoader.getInstance().isModLoaded("waystones")) {
            CheeseGuardMod.LOGGER.info("[CG] Waystones not present — waystone cleanup on dim deletion disabled");
            return;
        }
        try {
            Class<?> apiClass = Class.forName("net.blay09.mods.waystones.api.WaystonesAPI");
            Class<?> waystoneClass = Class.forName("net.blay09.mods.waystones.api.Waystone");

            getAllWaystonesMethod = apiClass.getMethod("getAllWaystones", MinecraftServer.class);
            removeWaystoneMethod = apiClass.getMethod("removeWaystoneFromDatabase", MinecraftServer.class, waystoneClass);
            getDimensionMethod = waystoneClass.getMethod("getDimension");
            getNameMethod = waystoneClass.getMethod("getName");

            available = true;
            CheeseGuardMod.LOGGER.info("[CG] Waystones hook initialized");
        } catch (Throwable t) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to init Waystones hook: {}", t.getMessage());
            available = false;
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    /**
     * Remove every waystone whose dimension matches {@code targetDimId} from the
     * server-side waystone registry. Returns the number of waystones removed.
     * Safe to call on the server thread; no-op if Waystones isn't loaded.
     */
    public static int removeWaystonesInDimension(MinecraftServer server, Identifier targetDimId) {
        if (!available || server == null || targetDimId == null) return 0;
        try {
            @SuppressWarnings("unchecked")
            Stream<Object> stream = (Stream<Object>) getAllWaystonesMethod.invoke(null, server);
            List<Object> toRemove = new ArrayList<>();
            stream.forEach(w -> {
                try {
                    Object dimKey = getDimensionMethod.invoke(w);
                    @SuppressWarnings("unchecked")
                    RegistryKey<World> rkey = (RegistryKey<World>) dimKey;
                    if (rkey != null && targetDimId.equals(rkey.getValue())) {
                        toRemove.add(w);
                    }
                } catch (Throwable ignored) {}
            });

            int count = 0;
            for (Object w : toRemove) {
                try {
                    String name = "?";
                    try { name = String.valueOf(getNameMethod.invoke(w)); } catch (Throwable ignored) {}
                    removeWaystoneMethod.invoke(null, server, w);
                    count++;
                    CheeseGuardMod.LOGGER.info("[CG] Removed waystone '{}' from deleted dim {}", name, targetDimId);
                } catch (Throwable t) {
                    CheeseGuardMod.LOGGER.warn("[CG] Failed to remove a waystone in {}: {}", targetDimId, t.getMessage());
                }
            }
            return count;
        } catch (Throwable t) {
            CheeseGuardMod.LOGGER.warn("[CG] Waystones cleanup failed for {}: {}", targetDimId, t.getMessage());
            return 0;
        }
    }
}
