package com.cheesevanity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side persistent storage of hidden slot states per player.
 * Map: playerUUID -> set of hidden slot keys (e.g., "chest/back", "hand/ring")
 */
public class VanityData {

    private static final Path DATA_PATH = Path.of("config", "cheesevanity_data.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<UUID, Set<String>> hiddenSlots = new ConcurrentHashMap<>();

    public static void load() {
        hiddenSlots.clear();
        try {
            if (Files.exists(DATA_PATH)) {
                String json = Files.readString(DATA_PATH);
                JsonObject root = GSON.fromJson(json, JsonObject.class);
                if (root != null && root.has("players")) {
                    JsonObject players = root.getAsJsonObject("players");
                    for (var entry : players.entrySet()) {
                        UUID uuid = UUID.fromString(entry.getKey());
                        Set<String> slots = ConcurrentHashMap.newKeySet();
                        JsonArray arr = entry.getValue().getAsJsonArray();
                        for (int i = 0; i < arr.size(); i++) {
                            slots.add(arr.get(i).getAsString());
                        }
                        hiddenSlots.put(uuid, slots);
                    }
                }
            }
        } catch (Exception e) {
            CheeseVanityMod.LOGGER.error("[CheeseVanity] Failed to load data", e);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(DATA_PATH.getParent());
            JsonObject root = new JsonObject();
            JsonObject players = new JsonObject();
            for (var entry : hiddenSlots.entrySet()) {
                if (entry.getValue().isEmpty()) continue;
                JsonArray arr = new JsonArray();
                for (String slot : entry.getValue()) {
                    arr.add(slot);
                }
                players.add(entry.getKey().toString(), arr);
            }
            root.add("players", players);
            Files.writeString(DATA_PATH, GSON.toJson(root));
        } catch (Exception e) {
            CheeseVanityMod.LOGGER.error("[CheeseVanity] Failed to save data", e);
        }
    }

    /** Toggle a slot for a player. Returns true if now hidden, false if now visible. */
    public static boolean toggle(UUID playerUuid, String slotKey) {
        Set<String> slots = hiddenSlots.computeIfAbsent(playerUuid, k -> ConcurrentHashMap.newKeySet());
        if (slots.contains(slotKey)) {
            slots.remove(slotKey);
            return false;
        } else {
            slots.add(slotKey);
            return true;
        }
    }

    /** Get hidden slots for a player (may be null if none). */
    public static Set<String> getHidden(UUID playerUuid) {
        return hiddenSlots.get(playerUuid);
    }

    /** Get all players with hidden slots. */
    public static Map<UUID, Set<String>> getAll() {
        return Collections.unmodifiableMap(hiddenSlots);
    }
}
