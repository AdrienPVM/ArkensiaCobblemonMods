package com.cheeseguard.data;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Manages server-wide tag configurations.
 * Stored in config/cheeseguard/tag_configs.json.
 */
public class TagConfigStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseGuard");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = Paths.get("config", "cheeseguard");
    private static final Path FILE = CONFIG_DIR.resolve("tag_configs.json");

    private final Map<String, TagConfig> configs = new LinkedHashMap<>();

    public TagConfigStorage() {
        try { Files.createDirectories(CONFIG_DIR); } catch (IOException ignored) {}
        load();
    }

    // ── CRUD ────────────────────────────────────────────────────────────────

    public Collection<TagConfig> getAll() { return configs.values(); }
    public TagConfig get(String tagId) { return configs.get(tagId); }
    public boolean has(String tagId) { return configs.containsKey(tagId); }

    public void put(TagConfig config) {
        configs.put(config.getId(), config);
        save();
    }

    public boolean remove(String tagId) {
        if (configs.remove(tagId) != null) {
            save();
            return true;
        }
        return false;
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    public void save() {
        try {
            JsonObject root = new JsonObject();
            for (TagConfig tc : configs.values()) {
                JsonObject o = new JsonObject();
                if (tc.getLabel() != null && !tc.getLabel().equals(tc.getId())) {
                    o.addProperty("label", tc.getLabel());
                }
                if (tc.getColor() != null && !tc.getColor().isEmpty()) {
                    o.addProperty("color", tc.getColor());
                }
                o.addProperty("ownershipLimit", tc.getOwnershipLimit());
                root.add(tc.getId(), o);
            }
            Files.writeString(FILE, GSON.toJson(root));
        } catch (IOException e) {
            LOGGER.error("[CheeseGuard] Failed to save tag configs", e);
        }
    }

    private void load() {
        if (!Files.exists(FILE)) return;
        try {
            String json = Files.readString(FILE);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            for (var entry : root.entrySet()) {
                String tagId = entry.getKey();
                JsonObject o = entry.getValue().getAsJsonObject();
                String label = o.has("label") ? o.get("label").getAsString() : null;
                String color = o.has("color") ? o.get("color").getAsString() : null;
                int limit = o.has("ownershipLimit") ? o.get("ownershipLimit").getAsInt() : 0;
                configs.put(tagId, new TagConfig(tagId, label, color, limit));
            }
            LOGGER.info("[CheeseGuard] Loaded {} tag configs", configs.size());
        } catch (Exception e) {
            LOGGER.error("[CheeseGuard] Failed to load tag configs", e);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Validates a tag ID: lowercase, alphanumeric + hyphens only, 1-64 chars.
     */
    public static boolean isValidTagId(String id) {
        return id != null && !id.isEmpty() && id.length() <= 64
            && id.matches("^[a-z0-9-]+$");
    }

    /**
     * Counts how many zones with the given tag are owned by the specified player.
     *
     * @param tag       the tag ID to check
     * @param playerUuid the UUID of the player to count zones for
     * @param storage   the RegionStorage to scan
     * @return the number of zones with this tag where the player is the OWNER
     */
    public static int countOwnedZonesWithTag(String tag, java.util.UUID playerUuid, RegionStorage storage) {
        if (tag == null || playerUuid == null || storage == null) return 0;
        int count = 0;
        for (Region r : storage.getRegions()) {
            if (r.getOwner() != null
                && r.getOwner().getUuid().equals(playerUuid)
                && r.getTags().contains(tag)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Checks whether a player would exceed any tag ownership limit if they became
     * the owner of the specified zone.
     *
     * @param zone       the zone the player wants to acquire
     * @param playerUuid the UUID of the player
     * @param storage    the RegionStorage to scan for existing ownership
     * @return null if all limits are satisfied, or the tag ID that would be exceeded
     */
    public String checkOwnershipLimits(Region zone, java.util.UUID playerUuid, RegionStorage storage) {
        if (zone == null || playerUuid == null || storage == null) return null;
        for (String tag : zone.getTags()) {
            TagConfig tc = get(tag);
            if (tc == null) continue;               // No config = no limit
            int limit = tc.getOwnershipLimit();
            if (limit <= 0) continue;                // 0 = unlimited
            int owned = countOwnedZonesWithTag(tag, playerUuid, storage);
            if (owned >= limit) return tag;          // Would exceed limit
        }
        return null;                                  // All limits satisfied
    }
}
