package com.cobblecap.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Persistent store for ranked Conseil 4 tenures.
 * <p>
 * File: {@code config/cobblecap_tenures.json}
 * Layout:
 * <pre>{ "tenures": [ Tenure, Tenure, ... ] }</pre>
 * The "current" tenure is the unique entry with endedAt == 0. There is at most
 * one current tenure at any time. When a new ranked winner emerges, we mark the
 * old current as ended (endedAt = now) and append a new current.
 */
public class TenureStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");
    private final Path file = FabricLoader.getInstance().getConfigDir().resolve("cobblecap_tenures.json");
    private final List<Tenure> tenures = new CopyOnWriteArrayList<>();

    public synchronized void load() {
        tenures.clear();
        if (!Files.exists(file)) {
            save();
            return;
        }
        try (Reader r = Files.newBufferedReader(file)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            if (root.has("tenures")) {
                for (var el : root.getAsJsonArray("tenures")) {
                    tenures.add(Tenure.fromJson(el.getAsJsonObject()));
                }
            }
            LOGGER.info("[CobbleCap] Tenures loaded: {} ({} current)", tenures.size(), current() != null ? 1 : 0);
        } catch (Exception e) {
            LOGGER.error("[CobbleCap] Failed to load tenures: {}", e.getMessage(), e);
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (Tenure t : tenures) arr.add(t.toJson());
            root.add("tenures", arr);
            try (Writer w = Files.newBufferedWriter(file)) {
                w.write(root.toString());
            }
        } catch (IOException e) {
            LOGGER.warn("[CobbleCap] Failed to save tenures: {}", e.getMessage());
        }
    }

    /** Async-friendly save — fire and forget on a tiny pool. Caller is on server thread typically. */
    public void saveAsync() {
        new Thread(this::save, "cobblecap-tenures-save").start();
    }

    /** All tenures, ordered chronologically (oldest first as stored). */
    public List<Tenure> all() { return new ArrayList<>(tenures); }

    /** Tenures sorted by startedAt DESC — current first, then most-recent past tenures. */
    public List<Tenure> allReverseChronological() {
        List<Tenure> sorted = new ArrayList<>(tenures);
        sorted.sort((a, b) -> Long.compare(b.startedAt, a.startedAt));
        return sorted;
    }

    /** The active tenure (endedAt==0), or null if no one has claimed the title yet. */
    public Tenure current() {
        for (Tenure t : tenures) if (t.isCurrent()) return t;
        return null;
    }

    /**
     * Close the active tenure (if any) and create a new current one for the given
     * player. Returns the new Tenure. Persisted immediately.
     */
    public synchronized Tenure startNewTenure(UUID championUuid, String championName, long bestTimeMs) {
        long now = System.currentTimeMillis();
        Tenure old = current();
        if (old != null) {
            old.endedAt = now;
            LOGGER.info("[CobbleCap] Tenure ended: {} (reigned {} ms, {} failed attempts logged)",
                    old.championName, now - old.startedAt, old.failedAttempts.size());
        }
        Tenure t = new Tenure(UUID.randomUUID(), championUuid, championName, now, bestTimeMs);
        tenures.add(t);
        save();
        LOGGER.info("[CobbleCap] New tenure started: {}", championName);
        return t;
    }

    /**
     * Record a failed challenge against the current tenure. No-op if there's no
     * current tenure (i.e. nobody is champion yet). Persists.
     */
    public void recordFailure(UUID challengerUuid, String challengerName) {
        Tenure t = current();
        if (t == null) {
            LOGGER.debug("[CobbleCap] recordFailure({}): no current tenure — skipped", challengerName);
            return;
        }
        t.recordFailure(challengerUuid, challengerName);
        saveAsync();
    }
}
