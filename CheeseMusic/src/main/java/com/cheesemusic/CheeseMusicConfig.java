package com.cheesemusic;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class CheeseMusicConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("cheesemusic");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private boolean enabled = true;
    private int defaultFadeInTicks = 60;
    private int defaultFadeOutTicks = 60;
    private int checkIntervalTicks = 10;
    private float defaultVolume = 0.7f;
    private final Map<String, MusicAssignment> assignments = new LinkedHashMap<>();

    private transient Path configPath;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getDefaultFadeInTicks() { return defaultFadeInTicks; }
    public void setDefaultFadeInTicks(int v) { this.defaultFadeInTicks = v; }
    public int getDefaultFadeOutTicks() { return defaultFadeOutTicks; }
    public void setDefaultFadeOutTicks(int v) { this.defaultFadeOutTicks = v; }
    public int getCheckIntervalTicks() { return checkIntervalTicks; }
    public void setCheckIntervalTicks(int v) { this.checkIntervalTicks = Math.max(1, Math.min(v, 100)); }
    public float getDefaultVolume() { return defaultVolume; }
    public void setDefaultVolume(float v) { this.defaultVolume = Math.max(0f, Math.min(v, 1f)); }

    public MusicAssignment getAssignment(String zoneId) {
        return assignments.get(zoneId);
    }

    public Map<String, MusicAssignment> getAllAssignments() {
        return Collections.unmodifiableMap(assignments);
    }

    public void putAssignment(String zoneId, MusicAssignment assignment) {
        assignments.put(zoneId, assignment);
    }

    public void removeAssignment(String zoneId) {
        assignments.remove(zoneId);
    }

    public void save() {
        if (configPath == null) return;
        try {
            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);
            root.addProperty("defaultFadeInTicks", defaultFadeInTicks);
            root.addProperty("defaultFadeOutTicks", defaultFadeOutTicks);
            root.addProperty("checkIntervalTicks", checkIntervalTicks);
            root.addProperty("defaultVolume", defaultVolume);

            JsonObject assignObj = new JsonObject();
            for (var entry : assignments.entrySet()) {
                assignObj.add(entry.getKey(), entry.getValue().toJson());
            }
            root.add("assignments", assignObj);

            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, GSON.toJson(root));
        } catch (IOException e) {
            LOGGER.error("[CheeseMusic] Failed to save config: {}", e.getMessage());
        }
    }

    public static CheeseMusicConfig load(Path configDir) {
        CheeseMusicConfig config = new CheeseMusicConfig();
        config.configPath = configDir.resolve("cheesemusic.json");

        if (!Files.exists(config.configPath)) {
            config.save();
            return config;
        }

        try {
            String json = Files.readString(config.configPath);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            if (root.has("enabled")) config.enabled = root.get("enabled").getAsBoolean();
            if (root.has("defaultFadeInTicks")) config.defaultFadeInTicks = root.get("defaultFadeInTicks").getAsInt();
            if (root.has("defaultFadeOutTicks")) config.defaultFadeOutTicks = root.get("defaultFadeOutTicks").getAsInt();
            if (root.has("checkIntervalTicks")) config.checkIntervalTicks = root.get("checkIntervalTicks").getAsInt();
            if (root.has("defaultVolume")) config.defaultVolume = root.get("defaultVolume").getAsFloat();

            if (root.has("assignments")) {
                JsonObject assignObj = root.getAsJsonObject("assignments");
                for (var entry : assignObj.entrySet()) {
                    MusicAssignment a = MusicAssignment.fromJson(entry.getValue().getAsJsonObject());
                    config.assignments.put(entry.getKey(), a);
                }
            }

            LOGGER.info("[CheeseMusic] Loaded config with {} assignments", config.assignments.size());
        } catch (Exception e) {
            LOGGER.error("[CheeseMusic] Failed to load config, using defaults: {}", e.getMessage());
        }

        return config;
    }
}
