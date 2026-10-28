package com.cheeseguard.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Global configuration for player-launched events.
 * Controls rate limiting (per-player + global) and time windows.
 * Stored in config/cheeseguard_event_launch.json.
 */
public class EventLaunchConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("cheeseguard");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ── Per-player rate limit ────────────────────────────────────────────────
    private int playerLimitCount = 0;         // 0 = unlimited
    private String playerLimitPeriod = "DAY"; // HOUR, DAY, WEEK

    // ── Global (server-wide) rate limit ──────────────────────────────────────
    private int globalLimitCount = 0;
    private String globalLimitPeriod = "DAY";

    // ── Time window ──────────────────────────────────────────────────────────
    private boolean timeWindowEnabled = false;
    private int timeWindowStartHour = 8;  // 0-23
    private int timeWindowEndHour = 22;   // 0-23

    // ── Getters / Setters ────────────────────────────────────────────────────

    public int getPlayerLimitCount() { return playerLimitCount; }
    public void setPlayerLimitCount(int c) { playerLimitCount = Math.max(0, c); }
    public String getPlayerLimitPeriod() { return playerLimitPeriod; }
    public void setPlayerLimitPeriod(String p) { playerLimitPeriod = validPeriod(p); }

    public int getGlobalLimitCount() { return globalLimitCount; }
    public void setGlobalLimitCount(int c) { globalLimitCount = Math.max(0, c); }
    public String getGlobalLimitPeriod() { return globalLimitPeriod; }
    public void setGlobalLimitPeriod(String p) { globalLimitPeriod = validPeriod(p); }

    public boolean isTimeWindowEnabled() { return timeWindowEnabled; }
    public void setTimeWindowEnabled(boolean b) { timeWindowEnabled = b; }
    public int getTimeWindowStartHour() { return timeWindowStartHour; }
    public void setTimeWindowStartHour(int h) { timeWindowStartHour = Math.max(0, Math.min(23, h)); }
    public int getTimeWindowEndHour() { return timeWindowEndHour; }
    public void setTimeWindowEndHour(int h) { timeWindowEndHour = Math.max(0, Math.min(23, h)); }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String validPeriod(String p) {
        if ("HOUR".equals(p) || "DAY".equals(p) || "WEEK".equals(p)) return p;
        return "DAY";
    }

    public static long periodToMs(String period) {
        return switch (period) {
            case "HOUR" -> 3_600_000L;
            case "WEEK" -> 604_800_000L;
            default     -> 86_400_000L; // DAY
        };
    }

    // ── JSON serialization ───────────────────────────────────────────────────

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("playerLimitCount", playerLimitCount);
        o.addProperty("playerLimitPeriod", playerLimitPeriod);
        o.addProperty("globalLimitCount", globalLimitCount);
        o.addProperty("globalLimitPeriod", globalLimitPeriod);
        o.addProperty("timeWindowEnabled", timeWindowEnabled);
        o.addProperty("timeWindowStartHour", timeWindowStartHour);
        o.addProperty("timeWindowEndHour", timeWindowEndHour);
        return o;
    }

    public void applyJson(JsonObject o) {
        if (o.has("playerLimitCount")) setPlayerLimitCount(o.get("playerLimitCount").getAsInt());
        if (o.has("playerLimitPeriod")) setPlayerLimitPeriod(o.get("playerLimitPeriod").getAsString());
        if (o.has("globalLimitCount")) setGlobalLimitCount(o.get("globalLimitCount").getAsInt());
        if (o.has("globalLimitPeriod")) setGlobalLimitPeriod(o.get("globalLimitPeriod").getAsString());
        if (o.has("timeWindowEnabled")) setTimeWindowEnabled(o.get("timeWindowEnabled").getAsBoolean());
        if (o.has("timeWindowStartHour")) setTimeWindowStartHour(o.get("timeWindowStartHour").getAsInt());
        if (o.has("timeWindowEndHour")) setTimeWindowEndHour(o.get("timeWindowEndHour").getAsInt());
    }

    // ── File persistence ─────────────────────────────────────────────────────

    public void load(Path configDir) {
        Path file = configDir.resolve("cheeseguard_event_launch.json");
        if (!Files.exists(file)) return;
        try {
            String content = Files.readString(file);
            applyJson(JsonParser.parseString(content).getAsJsonObject());
            LOGGER.info("[CG] Event launch config loaded");
        } catch (Exception e) {
            LOGGER.warn("[CG] Failed to load event launch config: {}", e.getMessage());
        }
    }

    public void save(Path configDir) {
        Path file = configDir.resolve("cheeseguard_event_launch.json");
        try {
            Files.createDirectories(configDir);
            Files.writeString(file, GSON.toJson(toJson()));
        } catch (Exception e) {
            LOGGER.warn("[CG] Failed to save event launch config: {}", e.getMessage());
        }
    }
}
