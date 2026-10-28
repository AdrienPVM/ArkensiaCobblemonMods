package com.roicheese.eggandcheese.integration;

import com.roicheese.eggandcheese.EggAndCheese;
import com.roicheese.eggandcheese.data.CollectibleEntry;
import com.roicheese.eggandcheese.dna.CollectibleType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Method;

/**
 * Optional integration with JourneyMap.
 * Creates waypoints for admin-placed collectibles.
 * Uses reflection so JourneyMap is not a hard dependency.
 */
public class JourneyMapIntegration {
    private static Boolean available = null;

    public static boolean isAvailable() {
        if (available != null) return available;
        try {
            Class.forName("journeymap.client.api.IClientAPI");
            available = true;
        } catch (ClassNotFoundException e) {
            available = false;
        }
        return available;
    }

    /**
     * Adds a waypoint for the given collectible entry.
     * Called server-side when an admin places a new egg.
     * Note: JourneyMap waypoints are client-side - we'd need a C2S sync.
     * This method exists as a hook; actual waypoint creation happens on the client
     * via the ClientEvents when the block is placed.
     */
    public static void addWaypoint(CollectibleEntry entry, ServerPlayerEntity admin) {
        if (!isAvailable()) return;
        // Client-side waypoint creation is handled via packet to admin
        // See EggAndCheeseClient for actual JourneyMap API calls
        EggAndCheese.LOGGER.info("[EggAndCheese] Would add JourneyMap waypoint for: " + entry.getId());
    }

    /**
     * Attempts to add a JourneyMap waypoint client-side.
     * Must be called on the client thread.
     */
    @SuppressWarnings("unchecked")
    public static void addClientWaypoint(String id, int x, int y, int z, String dimension, boolean isEgg) {
        if (!isAvailable()) return;
        try {
            Class<?> apiClass = Class.forName("journeymap.client.api.IClientAPI");
            Class<?> waypointClass = Class.forName("journeymap.client.api.model.MapWaypoint");
            Object api = Class.forName("journeymap.client.api.ClientPlugin")
                .getMethod("getInstance").invoke(null);
            if (api == null) return;

            // Build waypoint using JourneyMap's API
            // journeymap.client.api.model.MapWaypoint(name, dim, x, y, z)
            Object waypoint = waypointClass.getConstructor(String.class, String.class, int.class, int.class, int.class)
                .newInstance("[EAC] " + id, dimension, x, y, z);

            // Set color: yellow for eggs
            int color = isEgg ? 0xFFD700 : 0xFF8C00;
            waypointClass.getMethod("setColor", int.class).invoke(waypoint, color);
            waypointClass.getMethod("setIconColor", int.class).invoke(waypoint, color);

            // Add waypoint to API
            Method addMethod = apiClass.getMethod("addWaypoint", waypointClass);
            addMethod.invoke(api, waypoint);

            EggAndCheese.LOGGER.info("[EggAndCheese] JourneyMap waypoint added for: " + id);
        } catch (Exception e) {
            EggAndCheese.LOGGER.warn("[EggAndCheese] Failed to add JourneyMap waypoint: " + e.getMessage());
        }
    }
}
