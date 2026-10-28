package com.cheeseguard.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Persistent configuration for a race track.
 * Stores scanned marker regions, block mappings, and race settings.
 * Saved to config/cheeseguard_racetracks/<eventId>.json
 */
public class RaceTrackConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseGuard");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path STORAGE_DIR = Path.of("config", "cheeseguard_racetracks");

    // ── Identity ──────────────────────────────────────────────────────────────
    private String eventId;
    private String zoneId;           // global race zone

    // ── Block → marker type mappings ──────────────────────────────────────────
    // key: block ID (e.g., "minecraft:blue_wool"), value: marker type
    // Marker types: "checkpoint", "start", or a powerup key (e.g., "speed", "jump")
    private Map<String, String> blockMappings = new LinkedHashMap<>();

    // ── Scanned regions ───────────────────────────────────────────────────────
    private List<MarkerRegion> checkpoints = new ArrayList<>();
    private List<MarkerRegion> powerups = new ArrayList<>();
    private MarkerRegion startLine;

    // ── Race settings ─────────────────────────────────────────────────────────
    private boolean orderedCheckpoints = true;
    private int laps = 1;
    private int countdownSeconds = 5;
    private boolean freezeDuringCountdown = true;
    private int endTimerSeconds = 30;
    private double checkpointMargin = 1.5;
    private String movementMode = "any";         // "any", "foot", "shift", "kart"

    // ── Checkpoint visuals ────────────────────────────────────────────────────
    private String nextCheckpointParticle = "HAPPY_VILLAGER";
    private String validatedCheckpointParticle = "FLAME";
    private boolean hideValidatedCheckpoints = false;

    // ── Transform config ──────────────────────────────────────────────────────
    private List<String> transformCommands = new ArrayList<>();
    private String transformMode = "command";    // "command" or "species"
    private boolean revertTransformsOnEnd = true;

    // ── Scoring ───────────────────────────────────────────────────────────────
    private int pointsPerCheckpoint = 10;
    private int finishBonusPoints = 100;
    private int positionBonusPoints = 50;

    // ── Block replacement at race start ─────────────────────────────────────
    private String checkpointReplacementBlock = "minecraft:air";
    private String startLineReplacementBlock = "minecraft:air";

    // ── Out of bounds ─────────────────────────────────────────────────────────
    private boolean teleportOnOutOfBounds = true;

    // ── Powerup type configs ──────────────────────────────────────────────────
    // key: marker type key (e.g., "speed", "jump"), must match blockMappings values
    private Map<String, PowerUpTypeConfig> powerupTypes = new LinkedHashMap<>();

    // ── Scan metadata ─────────────────────────────────────────────────────────
    private long lastScanTimestamp;

    // ── Zone bounds (cached from scan for 2D map rendering) ───────────────────
    private int[] zoneBoundsMin;      // [minX, minY, minZ]
    private int[] zoneBoundsMax;      // [maxX, maxY, maxZ]
    private String zoneWorld;

    // ═══════════════════════════════════════════════════════════════════════════
    // Inner classes
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * A group of adjacent blocks detected by the scanner.
     * Used for checkpoints, powerups, and start line.
     */
    public static class MarkerRegion {
        private String markerType;       // "checkpoint", "start", or powerup key
        private int order = -1;          // checkpoint order (-1 = unordered)
        private List<int[]> blocks;      // block positions [x,y,z]
        private int[] min;               // bounding box min [x,y,z]
        private int[] max;               // bounding box max [x,y,z]
        private double[] center;         // center point [x,y,z]

        public MarkerRegion() {}

        public MarkerRegion(String markerType, List<int[]> blocks, int[] min, int[] max, double[] center) {
            this.markerType = markerType;
            this.blocks = blocks;
            this.min = min;
            this.max = max;
            this.center = center;
        }

        public String getMarkerType() { return markerType; }
        public void setMarkerType(String markerType) { this.markerType = markerType; }
        public int getOrder() { return order; }
        public void setOrder(int order) { this.order = order; }
        public List<int[]> getBlocks() { return blocks; }
        public void setBlocks(List<int[]> blocks) { this.blocks = blocks; }
        public int[] getMin() { return min; }
        public void setMin(int[] min) { this.min = min; }
        public int[] getMax() { return max; }
        public void setMax(int[] max) { this.max = max; }
        public double[] getCenter() { return center; }
        public void setCenter(double[] center) { this.center = center; }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("markerType", markerType);
            obj.addProperty("order", order);
            obj.add("min", intArrayToJson(min));
            obj.add("max", intArrayToJson(max));
            obj.add("center", doubleArrayToJson(center));
            obj.addProperty("blockCount", blocks != null ? blocks.size() : 0);
            // Don't include individual block positions in API responses (too verbose)
            return obj;
        }

        public JsonObject toJsonFull() {
            JsonObject obj = toJson();
            if (blocks != null) {
                JsonArray arr = new JsonArray();
                for (int[] b : blocks) arr.add(intArrayToJson(b));
                obj.add("blocks", arr);
            }
            return obj;
        }
    }

    /**
     * Configuration for a powerup type (shared by all powerup regions of same type).
     */
    public static class PowerUpTypeConfig {
        private String effectId = "minecraft:speed";    // MC effect identifier
        private int amplifier = 0;
        private int durationTicks = 100;
        private boolean targetSelf = true;              // true = self, false = others in radius
        private int targetRadius = 5;                   // radius for targeting others
        private int cooldownSeconds = 10;
        private boolean singleUse = false;
        private String replacementBlock = "minecraft:air";
        private String sound = "block.beacon.activate";

        public PowerUpTypeConfig() {}

        public String getEffectId() { return effectId; }
        public void setEffectId(String effectId) { this.effectId = effectId; }
        public int getAmplifier() { return amplifier; }
        public void setAmplifier(int amplifier) { this.amplifier = amplifier; }
        public int getDurationTicks() { return durationTicks; }
        public void setDurationTicks(int durationTicks) { this.durationTicks = durationTicks; }
        public boolean isTargetSelf() { return targetSelf; }
        public void setTargetSelf(boolean targetSelf) { this.targetSelf = targetSelf; }
        public int getTargetRadius() { return targetRadius; }
        public void setTargetRadius(int targetRadius) { this.targetRadius = targetRadius; }
        public int getCooldownSeconds() { return cooldownSeconds; }
        public void setCooldownSeconds(int cooldownSeconds) { this.cooldownSeconds = cooldownSeconds; }
        public boolean isSingleUse() { return singleUse; }
        public void setSingleUse(boolean singleUse) { this.singleUse = singleUse; }
        public String getReplacementBlock() { return replacementBlock; }
        public void setReplacementBlock(String replacementBlock) { this.replacementBlock = replacementBlock; }
        public String getSound() { return sound; }
        public void setSound(String sound) { this.sound = sound; }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("effectId", effectId);
            obj.addProperty("amplifier", amplifier);
            obj.addProperty("durationTicks", durationTicks);
            obj.addProperty("targetSelf", targetSelf);
            obj.addProperty("targetRadius", targetRadius);
            obj.addProperty("cooldownSeconds", cooldownSeconds);
            obj.addProperty("singleUse", singleUse);
            obj.addProperty("replacementBlock", replacementBlock);
            obj.addProperty("sound", sound);
            return obj;
        }

        public static PowerUpTypeConfig fromJson(JsonObject obj) {
            PowerUpTypeConfig c = new PowerUpTypeConfig();
            if (obj.has("effectId")) c.effectId = obj.get("effectId").getAsString();
            if (obj.has("amplifier")) c.amplifier = obj.get("amplifier").getAsInt();
            if (obj.has("durationTicks")) c.durationTicks = obj.get("durationTicks").getAsInt();
            if (obj.has("targetSelf")) c.targetSelf = obj.get("targetSelf").getAsBoolean();
            if (obj.has("targetRadius")) c.targetRadius = obj.get("targetRadius").getAsInt();
            if (obj.has("cooldownSeconds")) c.cooldownSeconds = obj.get("cooldownSeconds").getAsInt();
            if (obj.has("singleUse")) c.singleUse = obj.get("singleUse").getAsBoolean();
            if (obj.has("replacementBlock")) c.replacementBlock = obj.get("replacementBlock").getAsString();
            if (obj.has("sound")) c.sound = obj.get("sound").getAsString();
            return c;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructors
    // ═══════════════════════════════════════════════════════════════════════════

    public RaceTrackConfig() {}

    public RaceTrackConfig(String eventId) {
        this.eventId = eventId;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Persistence
    // ═══════════════════════════════════════════════════════════════════════════

    public static RaceTrackConfig load(String eventId) {
        Path file = STORAGE_DIR.resolve(eventId + ".json");
        if (!Files.exists(file)) return null;
        try (Reader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            RaceTrackConfig cfg = GSON.fromJson(reader, RaceTrackConfig.class);
            if (cfg != null) cfg.eventId = eventId;
            return cfg;
        } catch (Exception e) {
            LOGGER.error("[CG] Failed to load race track config '{}': {}", eventId, e.getMessage());
            return null;
        }
    }

    public void save() {
        try {
            Files.createDirectories(STORAGE_DIR);
            Path file = STORAGE_DIR.resolve(eventId + ".json");
            try (Writer writer = new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save race track config: " + eventId, e);
        }
    }

    public static void delete(String eventId) {
        try {
            Files.deleteIfExists(STORAGE_DIR.resolve(eventId + ".json"));
        } catch (IOException ignored) {}
    }

    public static List<String> listAll() {
        List<String> ids = new ArrayList<>();
        if (!Files.exists(STORAGE_DIR)) return ids;
        try (var stream = Files.list(STORAGE_DIR)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                .forEach(p -> {
                    String name = p.getFileName().toString();
                    ids.add(name.substring(0, name.length() - 5));
                });
        } catch (IOException ignored) {}
        return ids;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // JSON serialization for API
    // ═══════════════════════════════════════════════════════════════════════════

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("eventId", eventId);
        obj.addProperty("zoneId", zoneId);

        // Block mappings
        JsonObject mappings = new JsonObject();
        blockMappings.forEach(mappings::addProperty);
        obj.add("blockMappings", mappings);

        // Checkpoints
        JsonArray cpArr = new JsonArray();
        for (MarkerRegion cp : checkpoints) cpArr.add(cp.toJson());
        obj.add("checkpoints", cpArr);

        // Powerups
        JsonArray puArr = new JsonArray();
        for (MarkerRegion pu : powerups) puArr.add(pu.toJson());
        obj.add("powerups", puArr);

        // Start line
        if (startLine != null) obj.add("startLine", startLine.toJson());

        // Settings
        obj.addProperty("orderedCheckpoints", orderedCheckpoints);
        obj.addProperty("laps", laps);
        obj.addProperty("countdownSeconds", countdownSeconds);
        obj.addProperty("freezeDuringCountdown", freezeDuringCountdown);
        obj.addProperty("endTimerSeconds", endTimerSeconds);
        obj.addProperty("checkpointMargin", checkpointMargin);
        obj.addProperty("movementMode", movementMode);

        // Checkpoint visuals
        obj.addProperty("nextCheckpointParticle", nextCheckpointParticle);
        obj.addProperty("validatedCheckpointParticle", validatedCheckpointParticle);
        obj.addProperty("hideValidatedCheckpoints", hideValidatedCheckpoints);

        // Transforms
        JsonArray cmdArr = new JsonArray();
        transformCommands.forEach(cmdArr::add);
        obj.add("transformCommands", cmdArr);
        obj.addProperty("transformMode", transformMode);
        obj.addProperty("revertTransformsOnEnd", revertTransformsOnEnd);

        // Scoring
        obj.addProperty("pointsPerCheckpoint", pointsPerCheckpoint);
        obj.addProperty("finishBonusPoints", finishBonusPoints);
        obj.addProperty("positionBonusPoints", positionBonusPoints);

        // Block replacement
        obj.addProperty("checkpointReplacementBlock", checkpointReplacementBlock);
        obj.addProperty("startLineReplacementBlock", startLineReplacementBlock);

        // Out of bounds
        obj.addProperty("teleportOnOutOfBounds", teleportOnOutOfBounds);

        // Powerup type configs
        JsonObject puTypes = new JsonObject();
        powerupTypes.forEach((k, v) -> puTypes.add(k, v.toJson()));
        obj.add("powerupTypes", puTypes);

        // Zone bounds (for 2D map)
        if (zoneBoundsMin != null && zoneBoundsMax != null) {
            JsonObject bounds = new JsonObject();
            bounds.add("min", intArrayToJson(zoneBoundsMin));
            bounds.add("max", intArrayToJson(zoneBoundsMax));
            if (zoneWorld != null) bounds.addProperty("world", zoneWorld);
            obj.add("zoneBounds", bounds);
        }

        // Metadata
        obj.addProperty("lastScanTimestamp", lastScanTimestamp);
        obj.addProperty("checkpointCount", checkpoints.size());
        obj.addProperty("powerupCount", powerups.size());
        obj.addProperty("hasStartLine", startLine != null);

        return obj;
    }

    /**
     * Merge partial update from dashboard into this config.
     * Only updates fields present in the JSON (does not touch scan data).
     */
    public void mergeSettings(JsonObject data) {
        if (data.has("zoneId")) zoneId = data.get("zoneId").getAsString();
        if (data.has("orderedCheckpoints")) orderedCheckpoints = data.get("orderedCheckpoints").getAsBoolean();
        if (data.has("laps")) laps = data.get("laps").getAsInt();
        if (data.has("countdownSeconds")) countdownSeconds = data.get("countdownSeconds").getAsInt();
        if (data.has("freezeDuringCountdown")) freezeDuringCountdown = data.get("freezeDuringCountdown").getAsBoolean();
        if (data.has("endTimerSeconds")) endTimerSeconds = data.get("endTimerSeconds").getAsInt();
        if (data.has("checkpointMargin")) checkpointMargin = data.get("checkpointMargin").getAsDouble();
        if (data.has("movementMode")) movementMode = data.get("movementMode").getAsString();
        if (data.has("nextCheckpointParticle")) nextCheckpointParticle = data.get("nextCheckpointParticle").getAsString();
        if (data.has("validatedCheckpointParticle")) validatedCheckpointParticle = data.get("validatedCheckpointParticle").getAsString();
        if (data.has("hideValidatedCheckpoints")) hideValidatedCheckpoints = data.get("hideValidatedCheckpoints").getAsBoolean();
        if (data.has("revertTransformsOnEnd")) revertTransformsOnEnd = data.get("revertTransformsOnEnd").getAsBoolean();
        if (data.has("pointsPerCheckpoint")) pointsPerCheckpoint = data.get("pointsPerCheckpoint").getAsInt();
        if (data.has("finishBonusPoints")) finishBonusPoints = data.get("finishBonusPoints").getAsInt();
        if (data.has("positionBonusPoints")) positionBonusPoints = data.get("positionBonusPoints").getAsInt();
        if (data.has("teleportOnOutOfBounds")) teleportOnOutOfBounds = data.get("teleportOnOutOfBounds").getAsBoolean();
        if (data.has("checkpointReplacementBlock")) checkpointReplacementBlock = data.get("checkpointReplacementBlock").getAsString();
        if (data.has("startLineReplacementBlock")) startLineReplacementBlock = data.get("startLineReplacementBlock").getAsString();
        if (data.has("transformMode")) transformMode = data.get("transformMode").getAsString();

        if (data.has("transformCommands")) {
            transformCommands.clear();
            data.getAsJsonArray("transformCommands").forEach(e -> transformCommands.add(e.getAsString()));
        }

        if (data.has("blockMappings")) {
            blockMappings.clear();
            data.getAsJsonObject("blockMappings").entrySet()
                .forEach(e -> blockMappings.put(e.getKey(), e.getValue().getAsString()));
        }

        if (data.has("powerupTypes")) {
            powerupTypes.clear();
            data.getAsJsonObject("powerupTypes").entrySet()
                .forEach(e -> powerupTypes.put(e.getKey(), PowerUpTypeConfig.fromJson(e.getValue().getAsJsonObject())));
        }

        // Allow updating checkpoint order from dashboard
        if (data.has("checkpointOrders")) {
            JsonArray orders = data.getAsJsonArray("checkpointOrders");
            for (int i = 0; i < orders.size() && i < checkpoints.size(); i++) {
                checkpoints.get(i).setOrder(orders.get(i).getAsInt());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Getters / Setters
    // ═══════════════════════════════════════════════════════════════════════════

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getZoneId() { return zoneId; }
    public void setZoneId(String zoneId) { this.zoneId = zoneId; }

    public Map<String, String> getBlockMappings() { return blockMappings; }
    public void setBlockMappings(Map<String, String> blockMappings) { this.blockMappings = blockMappings; }

    public List<MarkerRegion> getCheckpoints() { return checkpoints; }
    public void setCheckpoints(List<MarkerRegion> checkpoints) { this.checkpoints = checkpoints; }

    public List<MarkerRegion> getPowerups() { return powerups; }
    public void setPowerups(List<MarkerRegion> powerups) { this.powerups = powerups; }

    public MarkerRegion getStartLine() { return startLine; }
    public void setStartLine(MarkerRegion startLine) { this.startLine = startLine; }

    public boolean isOrderedCheckpoints() { return orderedCheckpoints; }
    public void setOrderedCheckpoints(boolean v) { this.orderedCheckpoints = v; }

    public int getLaps() { return laps; }
    public void setLaps(int laps) { this.laps = laps; }

    public int getCountdownSeconds() { return countdownSeconds; }
    public void setCountdownSeconds(int v) { this.countdownSeconds = v; }

    public boolean isFreezeDuringCountdown() { return freezeDuringCountdown; }
    public void setFreezeDuringCountdown(boolean v) { this.freezeDuringCountdown = v; }

    public int getEndTimerSeconds() { return endTimerSeconds; }
    public void setEndTimerSeconds(int v) { this.endTimerSeconds = v; }

    public double getCheckpointMargin() { return checkpointMargin; }
    public void setCheckpointMargin(double v) { this.checkpointMargin = v; }

    public String getMovementMode() { return movementMode; }
    public void setMovementMode(String v) { this.movementMode = v; }

    public String getNextCheckpointParticle() { return nextCheckpointParticle; }
    public String getValidatedCheckpointParticle() { return validatedCheckpointParticle; }
    public boolean isHideValidatedCheckpoints() { return hideValidatedCheckpoints; }

    public List<String> getTransformCommands() { return transformCommands; }
    public String getTransformMode() { return transformMode; }
    public boolean isRevertTransformsOnEnd() { return revertTransformsOnEnd; }

    public int getPointsPerCheckpoint() { return pointsPerCheckpoint; }
    public int getFinishBonusPoints() { return finishBonusPoints; }
    public int getPositionBonusPoints() { return positionBonusPoints; }

    public boolean isTeleportOnOutOfBounds() { return teleportOnOutOfBounds; }

    public String getCheckpointReplacementBlock() { return checkpointReplacementBlock; }
    public String getStartLineReplacementBlock() { return startLineReplacementBlock; }

    public Map<String, PowerUpTypeConfig> getPowerupTypes() { return powerupTypes; }
    public void setPowerupTypes(Map<String, PowerUpTypeConfig> v) { this.powerupTypes = v; }

    public long getLastScanTimestamp() { return lastScanTimestamp; }
    public void setLastScanTimestamp(long v) { this.lastScanTimestamp = v; }

    public int[] getZoneBoundsMin() { return zoneBoundsMin; }
    public void setZoneBoundsMin(int[] v) { this.zoneBoundsMin = v; }
    public int[] getZoneBoundsMax() { return zoneBoundsMax; }
    public void setZoneBoundsMax(int[] v) { this.zoneBoundsMax = v; }
    public String getZoneWorld() { return zoneWorld; }
    public void setZoneWorld(String v) { this.zoneWorld = v; }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private static JsonArray intArrayToJson(int[] arr) {
        JsonArray ja = new JsonArray();
        if (arr != null) for (int v : arr) ja.add(v);
        return ja;
    }

    private static JsonArray doubleArrayToJson(double[] arr) {
        JsonArray ja = new JsonArray();
        if (arr != null) for (double v : arr) ja.add(v);
        return ja;
    }
}
