package com.cheesecompat.cobbleboss;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ShriekerBossState {
    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseCompatModsFix");

    public static final long COOLDOWN_MILLIS = 10L * 60L * 1000L; // 10 min
    public static final int COOLDOWN_RADIUS = 200;
    public static final long COOLDOWN_RADIUS_SQ = (long) COOLDOWN_RADIUS * COOLDOWN_RADIUS;
    public static final int AGGRO_SCAN_RADIUS = 96;
    public static final int AGGRO_TARGET_RADIUS = 256;
    public static final int TELEPORT_DIST_THRESHOLD = 24;
    public static final int TELEPORT_MAX_DIST = 256;
    public static final long TELEPORT_COOLDOWN_MS = 5000L;

    private static final Path COOLDOWN_FILE = Path.of("config", "cheesecompatmodsfix_shrieker_cooldowns.json");
    public static final ConcurrentHashMap<Long, Long> COOLDOWN_UNTIL = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;

    private ShriekerBossState() {}

    public static long packXZ(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }
    public static int unpackX(long k) { return (int) (k >> 32); }
    public static int unpackZ(long k) { return (int) k; }

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (Files.exists(COOLDOWN_FILE)) {
                JsonObject obj = JsonParser.parseString(Files.readString(COOLDOWN_FILE)).getAsJsonObject();
                long now = System.currentTimeMillis();
                for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                    long expiry = e.getValue().getAsLong();
                    if (expiry > now) COOLDOWN_UNTIL.put(Long.parseLong(e.getKey()), expiry);
                }
                LOGGER.info("[CheeseCompat] Loaded {} active shrieker cooldown(s)", COOLDOWN_UNTIL.size());
            }
        } catch (Throwable t) {
            LOGGER.warn("[CheeseCompat] Failed to load shrieker cooldowns: {}", t.getMessage());
        }
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(COOLDOWN_FILE.getParent());
            JsonObject obj = new JsonObject();
            long now = System.currentTimeMillis();
            for (Map.Entry<Long, Long> e : COOLDOWN_UNTIL.entrySet()) {
                if (e.getValue() > now) obj.addProperty(String.valueOf(e.getKey()), e.getValue());
            }
            Files.writeString(COOLDOWN_FILE, obj.toString());
        } catch (Throwable t) {
            LOGGER.warn("[CheeseCompat] Failed to save shrieker cooldowns: {}", t.getMessage());
        }
    }
}
