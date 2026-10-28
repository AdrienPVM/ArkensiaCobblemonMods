package com.cheeseguard.data;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Stores and persists player chunk suggestions.
 * File: config/cheeseguard/chunk_suggestions.json
 */
public class ChunkSuggestionStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseGuard");
    private static final Gson   GSON   = new GsonBuilder().setPrettyPrinting().create();
    private static final Path   CONFIG_DIR    = Paths.get("config", "cheeseguard");
    private static final Path   STORAGE_FILE  = CONFIG_DIR.resolve("chunk_suggestions.json");

    private final List<ChunkSuggestion> suggestions = new ArrayList<>();

    // ── CRUD ─────────────────────────────────────────────────────────────────

    public void add(ChunkSuggestion s) {
        suggestions.add(s);
        save();
    }

    public boolean remove(String id) {
        boolean removed = suggestions.removeIf(s -> s.getId().equals(id));
        if (removed) save();
        return removed;
    }

    public List<ChunkSuggestion> getAll() {
        // Return sorted newest first
        List<ChunkSuggestion> sorted = new ArrayList<>(suggestions);
        sorted.sort((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()));
        return sorted;
    }

    public int size() { return suggestions.size(); }

    // ── Persistence ───────────────────────────────────────────────────────────

    public void save() {
        try {
            Files.createDirectories(CONFIG_DIR);
            JsonArray arr = new JsonArray();
            for (ChunkSuggestion s : suggestions) arr.add(s.toJson());
            JsonObject root = new JsonObject();
            root.add("suggestions", arr);
            Files.writeString(STORAGE_FILE, GSON.toJson(root));
        } catch (IOException e) {
            LOGGER.error("[CG] Failed to save chunk suggestions", e);
        }
    }

    public void load() {
        suggestions.clear();
        if (!Files.exists(STORAGE_FILE)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(STORAGE_FILE)).getAsJsonObject();
            if (root.has("suggestions")) {
                for (JsonElement el : root.getAsJsonArray("suggestions")) {
                    try { suggestions.add(ChunkSuggestion.fromJson(el.getAsJsonObject())); }
                    catch (Exception e) { LOGGER.warn("[CG] Skipping malformed suggestion: {}", e.getMessage()); }
                }
            }
            LOGGER.info("[CG] Loaded {} chunk suggestions", suggestions.size());
        } catch (Exception e) {
            LOGGER.error("[CG] Failed to load chunk suggestions", e);
        }
    }
}
