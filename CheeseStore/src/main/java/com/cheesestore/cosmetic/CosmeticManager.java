package com.cheesestore.cosmetic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton catalogue of playable {@link CosmeticEntry}s, persisted to
 * {@code config/cheesestore_playcosmetics.json} (kept separate from the legacy
 * product-style cosmetics in {@code config/cheesestore_cosmetics.json}).
 *
 * <p>On first launch (file absent) it writes a fully-populated DEFAULT CATALOGUE of
 * ~50 ready-to-play cosmetics (see {@link DefaultCatalog}).
 *
 * <p>Thread-safe via {@link CopyOnWriteArrayList} + synchronized save, mirroring the
 * legacy {@link com.cheesestore.CosmeticManager}.
 */
public class CosmeticManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseStore");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static CosmeticManager INSTANCE;

    private final Path catalogFile;
    private final List<CosmeticEntry> cosmetics = new CopyOnWriteArrayList<>();

    private CosmeticManager(Path configDir) {
        this.catalogFile = configDir.resolve("cheesestore_playcosmetics.json");
    }

    // ── Singleton ─────────────────────────────────────────────────────────────

    /** Convenience init using the Fabric config dir. */
    public static void init() {
        init(FabricLoader.getInstance().getConfigDir());
    }

    public static void init(Path configDir) {
        if (INSTANCE != null) return; // idempotent
        INSTANCE = new CosmeticManager(configDir);
        INSTANCE.load();
    }

    public static CosmeticManager getInstance() {
        return INSTANCE;
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    public void load() {
        if (!Files.exists(catalogFile)) {
            LOGGER.info("[CheeseStore] No cosmetic catalogue found — writing default catalogue");
            cosmetics.clear();
            cosmetics.addAll(DefaultCatalog.build());
            save();
            LOGGER.info("[CheeseStore] Default cosmetic catalogue written ({} entries)", cosmetics.size());
            return;
        }
        try {
            String json = Files.readString(catalogFile);
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            cosmetics.clear();
            for (JsonElement el : arr) {
                try {
                    cosmetics.add(CosmeticEntry.fromJson(el.getAsJsonObject()));
                } catch (Exception ex) {
                    LOGGER.warn("[CheeseStore] Skipping invalid cosmetic entry: {}", ex.getMessage());
                }
            }
            if (cosmetics.isEmpty()) {
                LOGGER.warn("[CheeseStore] Cosmetic catalogue empty — repopulating with defaults");
                cosmetics.addAll(DefaultCatalog.build());
                save();
            }
            LOGGER.info("[CheeseStore] Loaded {} cosmetic(s) from catalogue", cosmetics.size());
        } catch (Exception e) {
            LOGGER.error("[CheeseStore] Failed to load cosmetic catalogue: {}", e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            JsonArray arr = new JsonArray();
            for (CosmeticEntry c : cosmetics) arr.add(c.toJson());
            Files.createDirectories(catalogFile.getParent());
            Files.writeString(catalogFile, GSON.toJson(arr));
        } catch (IOException e) {
            LOGGER.error("[CheeseStore] Failed to save cosmetic catalogue: {}", e.getMessage());
        }
    }

    // ── Lookups ────────────────────────────────────────────────────────────────

    /** All entries (immutable copy), sorted by (sortOrder, title). */
    public List<CosmeticEntry> getAll() {
        List<CosmeticEntry> out = new ArrayList<>(cosmetics);
        out.sort((a, b) -> {
            int c = Integer.compare(a.getSortOrder(), b.getSortOrder());
            return c != 0 ? c : a.getTitle().compareToIgnoreCase(b.getTitle());
        });
        return Collections.unmodifiableList(out);
    }

    /** Enabled entries only, same sort. */
    public List<CosmeticEntry> getEnabled() {
        List<CosmeticEntry> out = new ArrayList<>();
        for (CosmeticEntry c : cosmetics) if (c.isEnabled()) out.add(c);
        out.sort((a, b) -> {
            int c = Integer.compare(a.getSortOrder(), b.getSortOrder());
            return c != 0 ? c : a.getTitle().compareToIgnoreCase(b.getTitle());
        });
        return Collections.unmodifiableList(out);
    }

    /** Enabled entries of one category, sorted. */
    public List<CosmeticEntry> getByCategory(String category) {
        List<CosmeticEntry> out = new ArrayList<>();
        if (category == null) return out;
        for (CosmeticEntry c : cosmetics) {
            if (c.isEnabled() && category.equalsIgnoreCase(c.getCategory())) out.add(c);
        }
        out.sort((a, b) -> {
            int c = Integer.compare(a.getSortOrder(), b.getSortOrder());
            return c != 0 ? c : a.getTitle().compareToIgnoreCase(b.getTitle());
        });
        return Collections.unmodifiableList(out);
    }

    public CosmeticEntry get(String id) {
        if (id == null) return null;
        return cosmetics.stream().filter(c -> id.equals(c.getId())).findFirst().orElse(null);
    }

    // ── Mutations ────────────────────────────────────────────────────────────────

    public void add(CosmeticEntry entry) {
        if (entry == null) return;
        cosmetics.removeIf(c -> c.getId().equals(entry.getId()));
        cosmetics.add(entry);
        save();
    }

    /** Persist after the caller mutated the live entry in place (e.g. via mergeFrom). */
    public void update(CosmeticEntry entry) {
        save();
    }

    public boolean remove(String id) {
        boolean removed = cosmetics.removeIf(c -> c.getId().equals(id));
        if (removed) save();
        return removed;
    }

    /**
     * Reorder by the supplied id list — entries are assigned sortOrder by their index
     * in {@code ids}; any entry not listed keeps a stable order after the listed ones.
     */
    public synchronized void reorder(List<String> ids) {
        if (ids == null) return;
        Map<String, Integer> rank = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) rank.put(ids.get(i), i);
        int tail = ids.size();
        for (CosmeticEntry c : cosmetics) {
            Integer r = rank.get(c.getId());
            c.setSortOrder(r != null ? r : tail++);
        }
        save();
    }
}
