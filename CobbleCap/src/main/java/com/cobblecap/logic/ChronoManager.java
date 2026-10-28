package com.cobblecap.logic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks "League Master challenge" timers. Started by an external trigger
 * (typically a CheeseGuard dimension entry-command for conseil4) and stopped by
 * another external trigger (typically a FTB Quests reward command).
 *
 * Persisted to config/cobblecap_chronos.json so an active run survives a crash
 * or restart — the player's chrono keeps ticking against the original start.
 */
public class ChronoManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Path PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("cobblecap_chronos.json");

    /** UUID → epoch-ms when the chrono was started. */
    private final ConcurrentHashMap<UUID, Long> active = new ConcurrentHashMap<>();

    public void load() {
        try {
            if (!Files.exists(PATH)) return;
            try (Reader r = Files.newBufferedReader(PATH)) {
                JsonObject root = GSON.fromJson(r, JsonObject.class);
                if (root == null) return;
                for (var entry : root.entrySet()) {
                    try {
                        active.put(UUID.fromString(entry.getKey()), entry.getValue().getAsLong());
                    } catch (Exception ignored) {}
                }
            }
            LOGGER.info("[CobbleCap] Loaded {} active chrono(s)", active.size());
        } catch (IOException e) {
            LOGGER.error("[CobbleCap] Failed to load chronos: {}", e.getMessage(), e);
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(PATH.getParent());
            JsonObject root = new JsonObject();
            for (var entry : active.entrySet()) {
                root.addProperty(entry.getKey().toString(), entry.getValue());
            }
            Path tmp = PATH.resolveSibling("cobblecap_chronos.json.tmp");
            try (Writer w = Files.newBufferedWriter(tmp)) { GSON.toJson(root, w); }
            try { Files.move(tmp, PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException atomic) { Files.move(tmp, PATH, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) {
            LOGGER.error("[CobbleCap] Failed to save chronos: {}", e.getMessage(), e);
        }
    }

    /** Start a new chrono for the player. If one is already running, leave it alone. */
    public boolean start(UUID uuid) {
        if (active.containsKey(uuid)) {
            LOGGER.info("[CobbleCap] Chrono start IGNORED (already running) uuid={}, since={}",
                    uuid, active.get(uuid));
            return false;
        }
        active.put(uuid, System.currentTimeMillis());
        save();
        LOGGER.info("[CobbleCap] Chrono STARTED uuid={}, activeCount={}", uuid, active.size());
        return true;
    }

    /** Force-restart the chrono regardless of any existing run. Returns previous start if any. */
    public Long forceRestart(UUID uuid) {
        Long prev = active.put(uuid, System.currentTimeMillis());
        save();
        return prev;
    }

    /**
     * Stop the chrono for the player. Returns elapsed milliseconds, or -1 if no
     * chrono was active. Removes the entry on success.
     */
    public long stop(UUID uuid) {
        Long start = active.remove(uuid);
        if (start == null) {
            LOGGER.info("[CobbleCap] Chrono stop FAILED — no active chrono for uuid={}, activeUUIDs={}",
                    uuid, active.keySet());
            return -1L;
        }
        long elapsed = System.currentTimeMillis() - start;
        save();
        LOGGER.info("[CobbleCap] Chrono STOPPED uuid={}, elapsed={}ms, remaining active={}",
                uuid, elapsed, active.size());
        return elapsed;
    }

    public boolean isActive(UUID uuid) { return active.containsKey(uuid); }

    public long getStartTime(UUID uuid) {
        Long v = active.get(uuid);
        return v == null ? -1L : v;
    }

    public long elapsedFor(UUID uuid) {
        Long v = active.get(uuid);
        return v == null ? -1L : (System.currentTimeMillis() - v);
    }

    public int activeCount() { return active.size(); }

    /** Cancel without recording elapsed (e.g. test cleanup). */
    public boolean cancel(UUID uuid) {
        boolean removed = active.remove(uuid) != null;
        if (removed) {
            save();
            LOGGER.info("[CobbleCap] Chrono CANCELLED uuid={}, remaining active={}", uuid, active.size());
        }
        return removed;
    }

    /** Format ms as HH:MM:SS or MM:SS for friendly display. */
    public static String formatDuration(long ms) {
        if (ms < 0) return "—";
        long totalSec = ms / 1000L;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format("%dh%02dm%02ds", h, m, s);
        return String.format("%dm%02ds", m, s);
    }
}
