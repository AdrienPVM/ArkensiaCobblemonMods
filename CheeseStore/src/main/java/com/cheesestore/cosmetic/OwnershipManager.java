package com.cheesestore.cosmetic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Tracks which cosmetic ids each player OWNS, persisted to
 * {@code config/cheesestore_ownership.json} as {@code { "<uuid>": ["id1","id2",...] }}.
 *
 * <p>Thread-safe (ConcurrentHashMap + per-player synchronized sets), async persistence,
 * mirroring {@link com.cheesestore.WalletManager}.
 */
public class OwnershipManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseStore");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static OwnershipManager INSTANCE;

    private final Path file;
    private final Map<UUID, Set<String>> owned = new ConcurrentHashMap<>();

    private OwnershipManager(Path configDir) {
        this.file = configDir.resolve("cheesestore_ownership.json");
    }

    // ── Singleton ─────────────────────────────────────────────────────────────

    public static void init() {
        init(FabricLoader.getInstance().getConfigDir());
    }

    public static void init(Path configDir) {
        if (INSTANCE != null) return; // idempotent
        INSTANCE = new OwnershipManager(configDir);
        INSTANCE.load();
    }

    public static OwnershipManager getInstance() {
        return INSTANCE;
    }

    // ── API ─────────────────────────────────────────────────────────────────────

    public boolean owns(UUID uuid, String cosmeticId) {
        if (uuid == null || cosmeticId == null) return false;
        Set<String> set = owned.get(uuid);
        return set != null && set.contains(cosmeticId);
    }

    public void grant(UUID uuid, String cosmeticId) {
        if (uuid == null || cosmeticId == null) return;
        Set<String> set = owned.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
        if (set.add(cosmeticId)) saveAsync();
    }

    public void revoke(UUID uuid, String cosmeticId) {
        if (uuid == null || cosmeticId == null) return;
        Set<String> set = owned.get(uuid);
        if (set != null && set.remove(cosmeticId)) saveAsync();
    }

    /** Unmodifiable snapshot of the player's owned cosmetic ids. */
    public Set<String> getOwned(UUID uuid) {
        Set<String> set = owned.get(uuid);
        if (set == null) return Collections.emptySet();
        Set<String> copy = ConcurrentHashMap.newKeySet();
        copy.addAll(set);
        return Collections.unmodifiableSet(copy);
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    public void load() {
        if (!Files.exists(file)) {
            LOGGER.info("[CheeseStore] No cosmetic ownership file found, starting fresh");
            return;
        }
        try {
            String json = Files.readString(file);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            owned.clear();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                try {
                    UUID uuid = UUID.fromString(e.getKey());
                    Set<String> set = ConcurrentHashMap.newKeySet();
                    for (JsonElement el : e.getValue().getAsJsonArray()) set.add(el.getAsString());
                    owned.put(uuid, set);
                } catch (Exception ex) {
                    LOGGER.warn("[CheeseStore] Skipping invalid ownership entry {}: {}", e.getKey(), ex.getMessage());
                }
            }
            LOGGER.info("[CheeseStore] Loaded cosmetic ownership for {} player(s)", owned.size());
        } catch (Exception e) {
            LOGGER.error("[CheeseStore] Failed to load cosmetic ownership: {}", e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<UUID, Set<String>> e : owned.entrySet()) {
                JsonArray arr = new JsonArray();
                for (String id : e.getValue()) arr.add(id);
                root.add(e.getKey().toString(), arr);
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root));
        } catch (IOException e) {
            LOGGER.error("[CheeseStore] Failed to save cosmetic ownership: {}", e.getMessage());
        }
    }

    private void saveAsync() {
        CompletableFuture.runAsync(this::save).exceptionally(ex -> {
            LOGGER.error("[CheeseStore] Async ownership save failed: {}", ex.getMessage());
            return null;
        });
    }
}
