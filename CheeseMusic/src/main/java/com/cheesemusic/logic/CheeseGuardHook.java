package com.cheesemusic.logic;

import com.cheesemusic.CheeseMusicMod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Soft-dependency hook for CheeseGuard mod.
 * Uses reflection to call RegionManager.getRegionsAt() without compile-time dependency.
 */
public class CheeseGuardHook {

    private static boolean initialized = false;
    private static boolean available = false;

    private static Object regionManagerInstance;
    private static Method getRegionsAtMethod;

    // Region method caches
    private static Method getIdMethod;
    private static Method getNameMethod;
    private static Method getPriorityMethod;
    private static Method getTagsMethod;

    // Storage method caches
    private static Method getStorageMethod;
    private static Method getRegionsMethod;

    public record ZoneInfo(String id, String name, int priority, List<String> tags) {}

    public static void tryInit() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> modClass = Class.forName("com.cheeseguard.CheeseGuardMod");
            // CheeseGuardMod.INSTANCE is a field, not a method
            Object modInstance = modClass.getField("INSTANCE").get(null);
            if (modInstance == null) {
                CheeseMusicMod.LOGGER.info("[CheeseMusic] CheeseGuard detected but not yet initialized");
                return;
            }

            Method getRegionManager = modClass.getMethod("getRegionManager");
            regionManagerInstance = getRegionManager.invoke(modInstance);
            if (regionManagerInstance == null) {
                CheeseMusicMod.LOGGER.info("[CheeseMusic] CheeseGuard RegionManager not yet initialized");
                return;
            }

            Class<?> rmClass = regionManagerInstance.getClass();
            getRegionsAtMethod = rmClass.getMethod("getRegionsAt", String.class, int.class, int.class, int.class);

            // Pre-cache storage access for getAllZones()
            getStorageMethod = rmClass.getMethod("getStorage");
            Object storage = getStorageMethod.invoke(regionManagerInstance);
            getRegionsMethod = storage.getClass().getMethod("getRegions");

            available = true;
            CheeseMusicMod.LOGGER.info("[CheeseMusic] CheeseGuard detected — zone music enabled");
        } catch (ClassNotFoundException e) {
            CheeseMusicMod.LOGGER.info("[CheeseMusic] CheeseGuard not installed — zone music disabled");
        } catch (Exception e) {
            CheeseMusicMod.LOGGER.warn("[CheeseMusic] CheeseGuard hook failed: {}", e.getMessage());
        }
    }

    public static boolean isAvailable() { return available; }

    @SuppressWarnings("unchecked")
    public static List<ZoneInfo> getRegionsAt(String worldId, int x, int y, int z) {
        if (!available) return Collections.emptyList();
        try {
            Object result = getRegionsAtMethod.invoke(regionManagerInstance, worldId, x, y, z);
            Collection<?> regions = (Collection<?>) result;
            if (regions == null || regions.isEmpty()) return Collections.emptyList();
            return toZoneInfoList(regions);
        } catch (Exception e) {
            CheeseMusicMod.LOGGER.debug("[CheeseMusic] getRegionsAt failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * List all zones (for dashboard dropdown).
     */
    public static List<ZoneInfo> getAllZones() {
        if (!available) return Collections.emptyList();
        try {
            Object storage = getStorageMethod.invoke(regionManagerInstance);
            Collection<?> regions = (Collection<?>) getRegionsMethod.invoke(storage);
            if (regions == null || regions.isEmpty()) return Collections.emptyList();
            return toZoneInfoList(regions);
        } catch (Exception e) {
            CheeseMusicMod.LOGGER.debug("[CheeseMusic] getAllZones failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ZoneInfo> toZoneInfoList(Collection<?> regions) throws Exception {
        List<ZoneInfo> result = new ArrayList<>(regions.size());
        for (Object region : regions) {
            if (getIdMethod == null) {
                Class<?> regionClass = region.getClass();
                getIdMethod = regionClass.getMethod("getId");
                getNameMethod = regionClass.getMethod("getName");
                getPriorityMethod = regionClass.getMethod("getPriority");
                getTagsMethod = regionClass.getMethod("getTags");
            }
            String id = (String) getIdMethod.invoke(region);
            String name = (String) getNameMethod.invoke(region);
            int priority = (int) getPriorityMethod.invoke(region);
            List<String> tags = (List<String>) getTagsMethod.invoke(region);
            result.add(new ZoneInfo(id, name, priority, tags != null ? tags : Collections.emptyList()));
        }
        return result;
    }
}
