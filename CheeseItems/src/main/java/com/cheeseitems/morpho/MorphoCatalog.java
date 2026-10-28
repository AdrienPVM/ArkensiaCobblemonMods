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
 * Central catalog of morphos. Single source of truth shared by the in-game Codex,
 * the web store and the Métamorph NPC shop.
 *
 * Persisted in config/cheeseitems_morphos.json. Image files live separately in
 * config/cheeseitems_assets/morphos/<id>.png (handled by MorphoCatalogApi).
 *
 * Sorting rules:
 * - Default for both website and NPC shop: isNew=true entries first, sorted by createdAt desc
 *   (most recent "new" on top), then non-new entries by sortOrder asc (admin drag-drop).
 */
public class MorphoCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger("cheeseitems");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long SAVE_DEBOUNCE_MS = 5_000;

    private static MorphoCatalog INSTANCE;

    private final Path file;
    private final Path imageDir;
    private final Map<String, MorphoEntry> entries = new ConcurrentHashMap<>();

    /** Default endurance applied to morphos that don't override it. */
    public static final class GlobalEndurance {
        public int capacity = 100;
        public float drainPerSecond = 2.0f;
        public float regenPerSecond = 1.0f;

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("capacity", capacity);
            o.addProperty("drainPerSecond", drainPerSecond);
            o.addProperty("regenPerSecond", regenPerSecond);
            return o;
        }
        public void readJson(JsonObject o) {
            if (o.has("capacity"))       capacity       = Math.max(1, o.get("capacity").getAsInt());
            if (o.has("drainPerSecond")) drainPerSecond = Math.max(0f, o.get("drainPerSecond").getAsFloat());
            if (o.has("regenPerSecond")) regenPerSecond = Math.max(0f, o.get("regenPerSecond").getAsFloat());
        }
    }
    private final GlobalEndurance globalEndurance = new GlobalEndurance();

    private volatile boolean dirty = false;
    private volatile long lastSaveAt = 0;

    public MorphoCatalog() {
        Path cfg = FabricLoader.getInstance().getConfigDir();
        this.file = cfg.resolve("cheeseitems_morphos.json");
        this.imageDir = cfg.resolve("cheeseitems_assets").resolve("morphos");
    }

    public static MorphoCatalog getInstance() {
        if (INSTANCE == null) {
            synchronized (MorphoCatalog.class) {
                if (INSTANCE == null) {
                    INSTANCE = new MorphoCatalog();
                    INSTANCE.load();
                }
            }
        }
        return INSTANCE;
    }

    public synchronized void load() {
        entries.clear();
        if (!Files.exists(file)) {
            save();
            return;
        }
        try {
            String json = Files.readString(file);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("entries")) {
                for (JsonElement el : root.getAsJsonArray("entries")) {
                    MorphoEntry e = MorphoEntry.fromJson(el.getAsJsonObject());
                    if (e.getId() != null && !e.getId().isEmpty()) {
                        entries.put(e.getId(), e);
                    }
                }
            }
            if (root.has("globalEndurance")) {
                globalEndurance.readJson(root.getAsJsonObject("globalEndurance"));
            }
            LOGGER.info("[CheeseItems] MorphoCatalog loaded ({} entries)", entries.size());
        } catch (Exception e) {
            LOGGER.error("[CheeseItems] Failed to load morpho catalog: {}", e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            // Stable order for readability
            List<MorphoEntry> all = new ArrayList<>(entries.values());
            all.sort(Comparator.comparingInt(MorphoEntry::getSortOrder)
                    .thenComparingLong(MorphoEntry::getCreatedAt));
            for (MorphoEntry e : all) arr.add(e.toJson());
            root.add("entries", arr);
            root.add("globalEndurance", globalEndurance.toJson());
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root));
            dirty = false;
            lastSaveAt = System.currentTimeMillis();
        } catch (IOException e) {
            LOGGER.error("[CheeseItems] Failed to save morpho catalog: {}", e.getMessage());
        }
    }

    public void markDirty() {
        dirty = true;
        long now = System.currentTimeMillis();
        if (now - lastSaveAt > SAVE_DEBOUNCE_MS) save();
    }

    public void flush() {
        if (dirty) save();
    }

    // ── Global endurance ─────────────────────────────────────────────────────

    public GlobalEndurance getGlobalEndurance() { return globalEndurance; }

    public synchronized void updateGlobalEndurance(JsonObject patch) {
        globalEndurance.readJson(patch);
        markDirty();
    }

    /** Resolved endurance config for a given entry — falls back to global defaults. */
    public int    capacityFor(MorphoEntry e)  { return e.isUseCustomEndurance() ? e.getCustomCapacity() : globalEndurance.capacity; }
    public float  drainFor(MorphoEntry e)     { return e.isUseCustomEndurance() ? e.getCustomDrainPerSecond() : globalEndurance.drainPerSecond; }
    public float  regenFor(MorphoEntry e)     { return e.isUseCustomEndurance() ? e.getCustomRegenPerSecond() : globalEndurance.regenPerSecond; }

    // ── Reads ────────────────────────────────────────────────────────────────

    public Collection<MorphoEntry> getAll() {
        return Collections.unmodifiableCollection(entries.values());
    }

    public MorphoEntry get(String id) {
        return id == null ? null : entries.get(id);
    }

    public boolean exists(String id) {
        return id != null && entries.containsKey(id);
    }

    public List<MorphoEntry> getSorted() {
        List<MorphoEntry> all = new ArrayList<>(entries.values());
        all.sort(defaultComparator());
        return all;
    }

    public List<MorphoEntry> getSortedForWebsite() {
        List<MorphoEntry> all = new ArrayList<>();
        for (MorphoEntry e : entries.values()) if (e.isAvailableOnWebsite()) all.add(e);
        all.sort(defaultComparator());
        return all;
    }

    public List<MorphoEntry> getSortedForNpcShop() {
        List<MorphoEntry> all = new ArrayList<>();
        for (MorphoEntry e : entries.values()) if (e.isAvailableInNpcShop()) all.add(e);
        all.sort(defaultComparator());
        return all;
    }

    private static Comparator<MorphoEntry> defaultComparator() {
        // isNew=true first, by createdAt desc within
        // Then isNew=false by sortOrder asc, createdAt asc as tiebreak
        return (a, b) -> {
            if (a.isNew() != b.isNew()) return a.isNew() ? -1 : 1;
            if (a.isNew()) return Long.compare(b.getCreatedAt(), a.getCreatedAt());
            int c = Integer.compare(a.getSortOrder(), b.getSortOrder());
            if (c != 0) return c;
            return Long.compare(a.getCreatedAt(), b.getCreatedAt());
        };
    }

    // ── Mutations ────────────────────────────────────────────────────────────

    public synchronized MorphoEntry add(MorphoEntry entry) {
        if (entry.getId() == null || entry.getId().isEmpty()) {
            entry.setId(genId());
        }
        if (entry.getCreatedAt() == 0) entry.setCreatedAt(System.currentTimeMillis());
        if (entry.getSortOrder() == 0) {
            // append at end
            int max = 0;
            for (MorphoEntry e : entries.values()) max = Math.max(max, e.getSortOrder());
            entry.setSortOrder(max + 1);
        }
        entries.put(entry.getId(), entry);
        markDirty();
        return entry;
    }

    public synchronized boolean update(String id, JsonObject patch) {
        MorphoEntry e = entries.get(id);
        if (e == null) return false;
        e.mergeFrom(patch);
        markDirty();
        return true;
    }

    public synchronized boolean remove(String id) {
        MorphoEntry e = entries.remove(id);
        if (e != null) {
            deleteImage(id);
            markDirty();
            return true;
        }
        return false;
    }

    public synchronized void reorder(List<String> orderedIds) {
        int i = 1;
        for (String id : orderedIds) {
            MorphoEntry e = entries.get(id);
            if (e != null) {
                e.setSortOrder(i);
                i++;
            }
        }
        markDirty();
    }

    // ── Images ───────────────────────────────────────────────────────────────

    public void saveImage(String id, byte[] data) throws IOException {
        Files.createDirectories(imageDir);
        Path original = imageDir.resolve(id + ".png");
        Files.write(original, data);
        // Drop the cached thumbnail so the next /thumb hit regenerates against the new source.
        MorphoThumbnail.invalidate(original);
        MorphoEntry e = entries.get(id);
        if (e != null) {
            e.setHasImage(true);
            markDirty();
        }
    }

    public byte[] getImage(String id) {
        Path p = imageDir.resolve(id + ".png");
        if (!Files.exists(p)) return null;
        try { return Files.readAllBytes(p); }
        catch (IOException e) { return null; }
    }

    /** Disk path of the catalog image (may not exist). Used for HTTP revalidation. */
    public Path getImagePath(String id) {
        return imageDir.resolve(id + ".png");
    }

    public boolean hasImage(String id) {
        return Files.exists(imageDir.resolve(id + ".png"));
    }

    public void deleteImage(String id) {
        Path original = imageDir.resolve(id + ".png");
        try { Files.deleteIfExists(original); }
        catch (IOException ignored) {}
        MorphoThumbnail.invalidate(original);
        MorphoEntry e = entries.get(id);
        if (e != null && e.hasImage()) {
            e.setHasImage(false);
            markDirty();
        }
    }

    // ── Utils ────────────────────────────────────────────────────────────────

    private static String genId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
