package com.cheesevanity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of hidden slot states for all players.
 * Updated via S2C packets from the server.
 */
public class VanityClientState {

    private static final Map<UUID, Set<String>> hiddenSlots = new ConcurrentHashMap<>();

    /** Update hidden slots for a player (called from S2C packet handler). */
    public static void updatePlayer(UUID playerUuid, Set<String> slots) {
        if (slots == null || slots.isEmpty()) {
            hiddenSlots.remove(playerUuid);
        } else {
            hiddenSlots.put(playerUuid, new HashSet<>(slots));
        }
    }

    /** Check if a specific slot is hidden for a player. */
    public static boolean isHidden(UUID playerUuid, String slotKey) {
        Set<String> slots = hiddenSlots.get(playerUuid);
        return slots != null && slots.contains(slotKey);
    }

    /** Get all hidden slots for a player (may return null). */
    public static Set<String> getHiddenSlots(UUID playerUuid) {
        return hiddenSlots.get(playerUuid);
    }

    /** Clear all cached data (e.g., on disconnect). */
    public static void clear() {
        hiddenSlots.clear();
    }
}
