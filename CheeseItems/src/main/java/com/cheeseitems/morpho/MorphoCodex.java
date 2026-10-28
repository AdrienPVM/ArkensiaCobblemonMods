package com.cheeseitems.morpho;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent per-player Morpho Codex — stores which morpho items each player has unlocked
 * plus the currently active morpho. Saved on mutation (debounced) and at server stop.
 */
public class MorphoCodex {

    private static final Logger LOGGER = LoggerFactory.getLogger("cheeseitems");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Map<UUID, List<MorphoCodexEntry>> byPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, String> activeByPlayer = new ConcurrentHashMap<>();

    private volatile boolean dirty = false;
    private volatile long lastSaveAt = 0;
    private static final long SAVE_DEBOUNCE_MS = 5_000;

    public MorphoCodex() {
        this.file = FabricLoader.getInstance().getConfigDir().resolve("cheeseitems_morpho.json");
    }

    public void load() {
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            byPlayer.clear();
            activeByPlayer.clear();
            if (root.has("players")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
                    UUID uuid;
                    try { uuid = UUID.fromString(e.getKey()); } catch (IllegalArgumentException ex) { continue; }
                    JsonObject pdata = e.getValue().getAsJsonObject();
                    List<MorphoCodexEntry> entries = new ArrayList<>();
                    if (pdata.has("entries")) {
                        for (JsonElement el : pdata.getAsJsonArray("entries")) {
                            entries.add(MorphoCodexEntry.fromJson(el.getAsJsonObject()));
                        }
                    }
                    byPlayer.put(uuid, entries);
                    if (pdata.has("active") && !pdata.get("active").isJsonNull()) {
                        activeByPlayer.put(uuid, pdata.get("active").getAsString());
                    }
                }
            }
            LOGGER.info("[CheeseItems] MorphoCodex loaded ({} players)", byPlayer.size());
        } catch (Exception e) {
            LOGGER.error("[CheeseItems] Failed to load morpho codex: {}", e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            JsonObject players = new JsonObject();
            for (Map.Entry<UUID, List<MorphoCodexEntry>> e : byPlayer.entrySet()) {
                JsonObject pdata = new JsonObject();
                JsonArray arr = new JsonArray();
                for (MorphoCodexEntry entry : e.getValue()) arr.add(entry.toJson());
                pdata.add("entries", arr);
                String active = activeByPlayer.get(e.getKey());
                if (active != null) pdata.addProperty("active", active);
                players.add(e.getKey().toString(), pdata);
            }
            root.add("players", players);
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root));
            dirty = false;
            lastSaveAt = System.currentTimeMillis();
        } catch (IOException e) {
            LOGGER.error("[CheeseItems] Failed to save morpho codex: {}", e.getMessage());
        }
    }

    /**
     * Mark dirty and save if last save was more than SAVE_DEBOUNCE_MS ago.
     * Always call save() at server stop to flush.
     */
    public void markDirty() {
        dirty = true;
        long now = System.currentTimeMillis();
        if (now - lastSaveAt > SAVE_DEBOUNCE_MS) {
            save();
        }
    }

    public void flush() {
        if (dirty) save();
    }

    // ── Mutations ────────────────────────────────────────────────────────────

    public boolean hasInCodex(UUID player, String itemId) {
        List<MorphoCodexEntry> list = byPlayer.get(player);
        if (list == null) return false;
        for (MorphoCodexEntry e : list) if (e.getItemId().equals(itemId)) return true;
        return false;
    }

    public void addToCodex(UUID player, MorphoCodexEntry entry) {
        byPlayer.computeIfAbsent(player, k -> new ArrayList<>()).add(entry);
        // First entry becomes the active morpho automatically.
        activeByPlayer.putIfAbsent(player, entry.getItemId());
        markDirty();
    }

    public List<MorphoCodexEntry> getEntries(UUID player) {
        return byPlayer.getOrDefault(player, Collections.emptyList());
    }

    public String getActive(UUID player) {
        return activeByPlayer.get(player);
    }

    public void setActive(UUID player, String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            activeByPlayer.remove(player);
        } else if (hasInCodex(player, itemId)) {
            activeByPlayer.put(player, itemId);
        } else {
            return; // ignore: not in codex
        }
        markDirty();
    }

    public void removeFromCodex(UUID player, String itemId) {
        List<MorphoCodexEntry> list = byPlayer.get(player);
        if (list != null) {
            list.removeIf(e -> e.getItemId().equals(itemId));
            if (list.isEmpty()) byPlayer.remove(player);
        }
        String active = activeByPlayer.get(player);
        if (active != null && active.equals(itemId)) activeByPlayer.remove(player);
        markDirty();
    }
}
