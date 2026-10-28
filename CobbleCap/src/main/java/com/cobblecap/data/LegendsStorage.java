package com.cobblecap.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Persistent chronological record of every player who has earned the
 * "Maître de la Ligue Pokémon" title (beaten every CobbleCap challenge).
 * Stored in config/cobblecap_legends.json — never pruned, never overwritten.
 */
public class LegendsStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Path STORAGE_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("cobblecap_legends.json");

    private final List<LegendEntry> legends = new CopyOnWriteArrayList<>();

    public void load() {
        try {
            if (!Files.exists(STORAGE_PATH)) {
                LOGGER.info("[CobbleCap] No legends file — starting fresh hall of fame");
                return;
            }
            try (Reader r = Files.newBufferedReader(STORAGE_PATH)) {
                JsonObject root = GSON.fromJson(r, JsonObject.class);
                if (root == null || !root.has("legends")) return;
                JsonArray arr = root.getAsJsonArray("legends");
                for (var el : arr) {
                    try {
                        legends.add(LegendEntry.fromJson(el.getAsJsonObject()));
                    } catch (Exception ex) {
                        LOGGER.warn("[CobbleCap] Skipping malformed legend entry: {}", ex.getMessage());
                    }
                }
            }
            LOGGER.info("[CobbleCap] Loaded {} hall-of-fame legend(s)", legends.size());
        } catch (IOException e) {
            LOGGER.error("[CobbleCap] Failed to load legends: {}", e.getMessage(), e);
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(STORAGE_PATH.getParent());
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (LegendEntry e : legends) arr.add(e.toJson());
            root.add("legends", arr);
            Path tmp = STORAGE_PATH.resolveSibling("cobblecap_legends.json.tmp");
            try (Writer w = Files.newBufferedWriter(tmp)) {
                GSON.toJson(root, w);
            }
            try {
                Files.move(tmp, STORAGE_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomic) {
                Files.move(tmp, STORAGE_PATH, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("[CobbleCap] Failed to save legends: {}", e.getMessage(), e);
        }
    }

    /** Counts only NON-test legends (test entries do not block re-promotion). */
    public boolean hasLegend(UUID uuid) {
        for (LegendEntry e : legends)
            if (!e.isTest() && e.uuid().equals(uuid)) return true;
        return false;
    }

    /** True if no NON-test legend has ever been recorded — drives the "first ever" mention. */
    public boolean isEmpty() {
        for (LegendEntry e : legends) if (!e.isTest()) return false;
        return true;
    }

    /** Public hall of fame — test entries hidden. */
    public List<LegendEntry> getAll() {
        List<LegendEntry> out = new ArrayList<>();
        for (LegendEntry e : legends) if (!e.isTest()) out.add(e);
        return Collections.unmodifiableList(out);
    }

    /** Internal listing including test entries — used by the statue subsystem. */
    public List<LegendEntry> getAllIncludingTests() {
        return Collections.unmodifiableList(new ArrayList<>(legends));
    }

    /** Most recent entry (test or not) for the given player. Used by /statue test ops. */
    public LegendEntry findLatestForPlayerName(String playerName) {
        return findLatestForPlayerName(playerName, true);
    }

    /**
     * Most recent entry for the player. When includeTests=false, ignore isTest=true
     * entries (used by admin /statue place to never target a test snapshot).
     */
    public LegendEntry findLatestForPlayerName(String playerName, boolean includeTests) {
        LegendEntry best = null;
        for (LegendEntry e : legends) {
            if (!e.playerName().equalsIgnoreCase(playerName)) continue;
            if (!includeTests && e.isTest()) continue;
            if (best == null || e.completedAt() > best.completedAt()) best = e;
        }
        return best;
    }

    /** Append a legend and persist atomically. Returns the stored entry. */
    public LegendEntry add(LegendEntry entry) {
        legends.add(entry);
        save();
        return entry;
    }

    /** Replace the entry at the given (including-tests) index. Persists synchronously. */
    public void replaceEntry(int index, LegendEntry replacement) {
        if (index < 0 || index >= legends.size()) return;
        legends.set(index, replacement);
        save();
    }

    /** Remove every test entry belonging to the given player UUID. Returns count removed. */
    public int removeTestEntriesFor(UUID uuid) {
        int before = legends.size();
        legends.removeIf(e -> e.isTest() && e.uuid().equals(uuid));
        int removed = before - legends.size();
        if (removed > 0) save();
        return removed;
    }

    /**
     * Remove EVERY entry (test or real) belonging to the given player UUID.
     * Used by /cobblecap chrono wipe for test cleanup. Returns count removed.
     */
    public int removeAllEntriesFor(UUID uuid) {
        int before = legends.size();
        legends.removeIf(e -> e.uuid().equals(uuid));
        int removed = before - legends.size();
        if (removed > 0) save();
        return removed;
    }
}
