package com.cheeseguard.logic;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player key-value flags for the duration of an event.
 * Examples:  role=impostor,  tasks_done=3,  has_bomb=true,  checkpoint=2
 * All flags are reset when the event ends.
 */
public class PlayerStateFlags {

    // eventId -> (playerUUID -> (flagKey -> value))
    private final Map<String, Map<UUID, Map<String, String>>> data = new HashMap<>();

    // ── Write ─────────────────────────────────────────────────────────────────

    public void set(String eventId, UUID player, String key, String value) {
        data.computeIfAbsent(eventId, k -> new HashMap<>())
            .computeIfAbsent(player, k -> new HashMap<>())
            .put(key, value);
    }

    public void set(String eventId, UUID player, String key, boolean value) {
        set(eventId, player, key, String.valueOf(value));
    }

    public void set(String eventId, UUID player, String key, int value) {
        set(eventId, player, key, String.valueOf(value));
    }

    public void increment(String eventId, UUID player, String key, int delta) {
        int cur = getInt(eventId, player, key, 0);
        set(eventId, player, key, cur + delta);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    public String get(String eventId, UUID player, String key, String def) {
        var eventMap = data.get(eventId);
        if (eventMap == null) return def;
        var playerMap = eventMap.get(player);
        if (playerMap == null) return def;
        return playerMap.getOrDefault(key, def);
    }

    public boolean getBool(String eventId, UUID player, String key, boolean def) {
        String v = get(eventId, player, key, null);
        if (v == null) return def;
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v);
    }

    public int getInt(String eventId, UUID player, String key, int def) {
        String v = get(eventId, player, key, null);
        if (v == null) return def;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
    }

    /** Check if a specific flag value matches */
    public boolean is(String eventId, UUID player, String key, String expected) {
        return expected.equals(get(eventId, player, key, null));
    }

    /** Get all flags for a player in an event */
    public Map<String, String> getAll(String eventId, UUID player) {
        var eventMap = data.get(eventId);
        if (eventMap == null) return Map.of();
        var playerMap = eventMap.get(player);
        return playerMap != null ? Map.copyOf(playerMap) : Map.of();
    }

    /** Count how many players in this event have a specific flag value */
    public int countPlayersWithFlag(String eventId, String key, String value) {
        var eventMap = data.get(eventId);
        if (eventMap == null) return 0;
        int count = 0;
        for (var playerMap : eventMap.values()) {
            if (value.equals(playerMap.get(key))) count++;
        }
        return count;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Remove all flags for an event (call on event end) */
    public void clearEvent(String eventId) {
        data.remove(eventId);
    }

    /** Remove all flags for a specific player in an event */
    public void clearPlayer(String eventId, UUID player) {
        var eventMap = data.get(eventId);
        if (eventMap != null) eventMap.remove(player);
    }
}
