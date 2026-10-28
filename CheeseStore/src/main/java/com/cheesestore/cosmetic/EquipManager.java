package com.cheesestore.cosmetic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the ONE cosmetic equipped per category per player, persisted to
 * {@code config/cheesestore_equipped.json} as
 * {@code { "<uuid>": { "TRAIL": "trail_flame", "NAME_COLOR": "name_red", ... } }}.
 *
 * <p>Equipping a cosmetic in a category replaces whatever was previously equipped there.
 * Thread-safe via nested ConcurrentHashMaps + async persistence.
 */
public class EquipManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseStore");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static EquipManager INSTANCE;

    private final Path file;
    // uuid -> (category -> cosmeticId)
    private final Map<UUID, Map<String, String>> equipped = new ConcurrentHashMap<>();

    private EquipManager(Path configDir) {
        this.file = configDir.resolve("cheesestore_equipped.json");
    }

    // ── Singleton ─────────────────────────────────────────────────────────────

    public static void init() {
        init(FabricLoader.getInstance().getConfigDir());
    }

    public static void init(Path configDir) {
        if (INSTANCE != null) return; // idempotent
        INSTANCE = new EquipManager(configDir);
        INSTANCE.load();
    }

    public static EquipManager getInstance() {
        return INSTANCE;
    }

    // ── API ─────────────────────────────────────────────────────────────────────

    private static String norm(String category) {
        return category == null ? "" : category.trim().toUpperCase(Locale.ROOT);
    }

    /** The cosmetic id equipped by {@code uuid} in {@code category}, or {@code null}. */
    public String getEquipped(UUID uuid, String category) {
        if (uuid == null) return null;
        Map<String, String> m = equipped.get(uuid);
        return m == null ? null : m.get(norm(category));
    }

    /**
     * Equip {@code cosmeticId} for the player. The category is resolved from the catalogue
     * entry, so equipping one cosmetic in a category replaces any previous one there.
     * Returns the category that was affected, or {@code null} if the id is unknown.
     */
    public String equip(UUID uuid, String cosmeticId) {
        if (uuid == null || cosmeticId == null) return null;
        CosmeticManager mgr = CosmeticManager.getInstance();
        CosmeticEntry entry = mgr != null ? mgr.get(cosmeticId) : null;
        if (entry == null) return null;
        String category = norm(entry.getCategory());
        if (category.isEmpty()) return null;
        equipped.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(category, cosmeticId);
        saveAsync();
        return category;
    }

    /** Unequip whatever is equipped in {@code category} (no-op if nothing). */
    public void unequip(UUID uuid, String category) {
        if (uuid == null) return;
        Map<String, String> m = equipped.get(uuid);
        if (m != null && m.remove(norm(category)) != null) saveAsync();
    }

    /** Unmodifiable snapshot of all equipped cosmetics for the player (category -> id). */
    public Map<String, String> getAllEquipped(UUID uuid) {
        Map<String, String> m = equipped.get(uuid);
        if (m == null) return Collections.emptyMap();
        return Collections.unmodifiableMap(new ConcurrentHashMap<>(m));
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    public void load() {
        if (!Files.exists(file)) {
            LOGGER.info("[CheeseStore] No equipped-cosmetics file found, starting fresh");
            return;
        }
        try {
            String json = Files.readString(file);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            equipped.clear();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                try {
                    UUID uuid = UUID.fromString(e.getKey());
                    Map<String, String> m = new ConcurrentHashMap<>();
                    for (Map.Entry<String, JsonElement> ce : e.getValue().getAsJsonObject().entrySet()) {
                        m.put(norm(ce.getKey()), ce.getValue().getAsString());
                    }
                    equipped.put(uuid, m);
                } catch (Exception ex) {
                    LOGGER.warn("[CheeseStore] Skipping invalid equipped entry {}: {}", e.getKey(), ex.getMessage());
                }
            }
            LOGGER.info("[CheeseStore] Loaded equipped cosmetics for {} player(s)", equipped.size());
        } catch (Exception e) {
            LOGGER.error("[CheeseStore] Failed to load equipped cosmetics: {}", e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<UUID, Map<String, String>> e : equipped.entrySet()) {
                JsonObject byCat = new JsonObject();
                for (Map.Entry<String, String> ce : e.getValue().entrySet()) {
                    byCat.addProperty(ce.getKey(), ce.getValue());
                }
                root.add(e.getKey().toString(), byCat);
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root));
        } catch (IOException e) {
            LOGGER.error("[CheeseStore] Failed to save equipped cosmetics: {}", e.getMessage());
        }
    }

    private void saveAsync() {
        CompletableFuture.runAsync(this::save).exceptionally(ex -> {
            LOGGER.error("[CheeseStore] Async equipped-cosmetics save failed: {}", ex.getMessage());
            return null;
        });
    }
}
