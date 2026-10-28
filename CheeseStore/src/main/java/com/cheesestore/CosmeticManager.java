package com.cheesestore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stores credit-purchasable {@link CosmeticItem}s in their own file
 * {@code config/cheesestore_cosmetics.json}, kept separate from the main
 * {@code cheesestore.json} so the existing Stripe/product config is never touched.
 *
 * Images live alongside product/gallery images in
 * {@code config/cheesestore_assets/cosmetics/<id>.png}, reusing the same asset dir.
 *
 * Thread-safe via {@link CopyOnWriteArrayList} + synchronized save, mirroring
 * {@link DeliveryManager} and the gallery/product CRUD in {@link CheeseStoreConfig}.
 */
public class CosmeticManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseStore");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static CosmeticManager INSTANCE;

    private final Path cosmeticsFile;
    private final Path assetsDir; // shared cheesestore_assets dir; cosmetics live under /cosmetics
    private final List<CosmeticItem> cosmetics = new CopyOnWriteArrayList<>();

    private CosmeticManager(Path configDir) {
        this.cosmeticsFile = configDir.resolve("cheesestore_cosmetics.json");
        this.assetsDir = configDir.resolve("cheesestore_assets");
    }

    // ── Singleton ─────────────────────────────────────────────────────────────

    public static void init(Path configDir) {
        INSTANCE = new CosmeticManager(configDir);
        INSTANCE.load();
    }

    public static CosmeticManager getInstance() {
        return INSTANCE;
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    public void load() {
        if (!Files.exists(cosmeticsFile)) {
            LOGGER.info("[CheeseStore] No cosmetics file found, starting fresh");
            return;
        }
        try {
            String json = Files.readString(cosmeticsFile);
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            cosmetics.clear();
            for (JsonElement el : arr) {
                try {
                    cosmetics.add(CosmeticItem.fromJson(el.getAsJsonObject()));
                } catch (Exception ex) {
                    LOGGER.warn("[CheeseStore] Skipping invalid cosmetic entry: {}", ex.getMessage());
                }
            }
            LOGGER.info("[CheeseStore] Loaded {} cosmetic(s)", cosmetics.size());
        } catch (Exception e) {
            LOGGER.error("[CheeseStore] Failed to load cosmetics: {}", e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            JsonArray arr = new JsonArray();
            for (CosmeticItem c : cosmetics) arr.add(c.toJson());
            Files.createDirectories(cosmeticsFile.getParent());
            Files.writeString(cosmeticsFile, GSON.toJson(arr));
        } catch (IOException e) {
            LOGGER.error("[CheeseStore] Failed to save cosmetics: {}", e.getMessage());
        }
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    public List<CosmeticItem> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(cosmetics));
    }

    public List<CosmeticItem> getEnabled() {
        List<CosmeticItem> result = new ArrayList<>();
        for (CosmeticItem c : cosmetics) {
            if (c.isEnabled()) result.add(c);
        }
        result.sort((a, b) -> Integer.compare(a.getSortOrder(), b.getSortOrder()));
        return result;
    }

    public CosmeticItem get(String id) {
        return cosmetics.stream().filter(c -> c.getId().equals(id)).findFirst().orElse(null);
    }

    public void add(CosmeticItem cosmetic) {
        cosmetics.add(cosmetic);
        save();
    }

    /** The item is mutated in place via {@link CosmeticItem#mergeFrom}; this just persists. */
    public void update(CosmeticItem cosmetic) {
        save();
    }

    /**
     * Upsert by id: merges into the existing entry if present, otherwise adds it.
     * Returns the resulting (live) entry.
     */
    public synchronized CosmeticItem upsert(CosmeticItem incoming) {
        CosmeticItem existing = get(incoming.getId());
        if (existing != null) {
            existing.mergeFrom(incoming.toJson());
            save();
            return existing;
        }
        cosmetics.add(incoming);
        save();
        return incoming;
    }

    public boolean remove(String id) {
        boolean removed = cosmetics.removeIf(c -> c.getId().equals(id));
        if (removed) {
            save();
            try {
                Files.deleteIfExists(imagePath(id));
            } catch (IOException e) { /* ignore */ }
        }
        return removed;
    }

    // ── Images (reuses the shared cheesestore_assets dir, /cosmetics subfolder) ─

    public Path imagePath(String id) {
        return assetsDir.resolve("cosmetics").resolve(id + ".png");
    }

    public void saveImage(String id, byte[] data) throws IOException {
        Path dir = assetsDir.resolve("cosmetics");
        Files.createDirectories(dir);
        Files.write(dir.resolve(id + ".png"), data);
        CosmeticItem c = get(id);
        if (c != null) {
            c.setHasImage(true);
            save();
        }
    }
}
