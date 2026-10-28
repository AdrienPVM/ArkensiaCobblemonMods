package com.cheesedash;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persistent audit logger for CheeseDash.
 * Records all admin actions with who/when/what/IP.
 * Persists to config/cheesedash_audit.json and broadcasts via WebSocket.
 *
 * Thread-safe. Max 10,000 entries kept in memory and on disk (oldest trimmed).
 */
public class AuditLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseDash");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_ENTRIES = 10_000;

    private final Path auditFile;
    private final List<JsonObject> entries;

    public AuditLogger(Path configDir) {
        this.auditFile = configDir.resolve("cheesedash_audit.json");
        this.entries = Collections.synchronizedList(new ArrayList<>());
        loadFromDisk();
    }

    // ── Logging ──────────────────────────────────────────────────────────

    /**
     * Log an admin action.
     *
     * @param username  The admin who performed the action
     * @param role      Their role at time of action
     * @param action    Action category (e.g. "zone.update", "server.command", "auth.login")
     * @param detail    Human-readable detail string
     * @param ip        Client IP address
     */
    public void log(String username, String role, String action, String detail, String ip) {
        JsonObject entry = new JsonObject();
        entry.addProperty("timestamp", Instant.now().toString());
        entry.addProperty("username", username);
        entry.addProperty("role", role);
        entry.addProperty("action", action);
        entry.addProperty("detail", detail);
        entry.addProperty("ip", ip);

        synchronized (entries) {
            entries.add(entry);
            // Trim oldest if over max
            while (entries.size() > MAX_ENTRIES) {
                entries.remove(0);
            }
        }

        // Broadcast to WS clients on audit channel
        JsonObject wsMsg = new JsonObject();
        wsMsg.addProperty("type", "audit");
        wsMsg.addProperty("timestamp", entry.get("timestamp").getAsString());
        wsMsg.addProperty("username", username);
        wsMsg.addProperty("role", role);
        wsMsg.addProperty("action", action);
        wsMsg.addProperty("detail", detail);
        DashboardRegistry.broadcast("audit", wsMsg.toString());

        // Persist to disk (async-ish — we're already on the HTTP thread)
        saveToDiskAsync();
    }

    /**
     * Helper: log with session context.
     */
    public void log(SessionManager.Session session, String action, String detail) {
        log(
            session.getUsername(),
            session.getRole().name(),
            action,
            detail,
            session.getIp()
        );
    }

    // ── Querying ─────────────────────────────────────────────────────────

    /**
     * Get the most recent N entries, most-recent first.
     */
    public JsonArray getRecent(int n) {
        JsonArray arr = new JsonArray();
        synchronized (entries) {
            int start = Math.max(0, entries.size() - n);
            // Return in reverse chronological order
            for (int i = entries.size() - 1; i >= start; i--) {
                arr.add(entries.get(i));
            }
        }
        return arr;
    }

    /**
     * Get entries with pagination (most-recent first).
     *
     * @param offset  Number of most-recent entries to skip
     * @param limit   Max entries to return
     */
    public JsonArray getEntries(int offset, int limit) {
        JsonArray arr = new JsonArray();
        synchronized (entries) {
            int total = entries.size();
            // offset=0 means most recent
            int startIdx = total - 1 - offset;
            int endIdx = Math.max(-1, startIdx - limit);
            for (int i = startIdx; i > endIdx && i >= 0; i--) {
                arr.add(entries.get(i));
            }
        }
        return arr;
    }

    /**
     * Total number of audit entries stored.
     */
    public int getTotal() {
        return entries.size();
    }

    // ── Persistence ──────────────────────────────────────────────────────

    private void loadFromDisk() {
        if (!Files.exists(auditFile)) return;
        try {
            String raw = Files.readString(auditFile);
            JsonArray arr = JsonParser.parseString(raw).getAsJsonArray();
            synchronized (entries) {
                entries.clear();
                for (JsonElement el : arr) {
                    entries.add(el.getAsJsonObject());
                }
                // Trim if loaded file was too large
                while (entries.size() > MAX_ENTRIES) {
                    entries.remove(0);
                }
            }
            LOGGER.info("[CheeseDash] Loaded {} audit entries", entries.size());
        } catch (Exception e) {
            LOGGER.warn("[CheeseDash] Failed to load audit log, starting fresh: {}", e.getMessage());
        }
    }

    private void saveToDiskAsync() {
        // Run disk I/O in a separate thread to avoid blocking the HTTP thread
        Thread writer = new Thread(() -> {
            try {
                JsonArray arr;
                synchronized (entries) {
                    arr = new JsonArray();
                    for (JsonObject entry : entries) {
                        arr.add(entry);
                    }
                }
                Files.createDirectories(auditFile.getParent());
                Files.writeString(auditFile, GSON.toJson(arr));
            } catch (IOException e) {
                LOGGER.warn("[CheeseDash] Failed to save audit log: {}", e.getMessage());
            }
        }, "CheeseDash-AuditWrite");
        writer.setDaemon(true);
        writer.start();
    }
}
