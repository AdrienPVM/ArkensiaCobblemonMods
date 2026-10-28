package com.cheesekart;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CheeseKartConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseKart");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, KartPreset> presets = new ConcurrentHashMap<>();
    private final Path configPath;

    public CheeseKartConfig() {
        this.configPath = FabricLoader.getInstance().getConfigDir().resolve("cheesekart.json");
    }

    public void load() {
        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                if (root.has("presets")) {
                    JsonArray arr = root.getAsJsonArray("presets");
                    presets.clear();
                    for (JsonElement el : arr) {
                        KartPreset p = KartPreset.fromJson(el.getAsJsonObject());
                        if (p.getId() != null) presets.put(p.getId(), p);
                    }
                }
                LOGGER.info("[CheeseKart] Loaded {} presets from config", presets.size());
            } catch (Exception e) {
                LOGGER.error("[CheeseKart] Failed to load config: {}", e.getMessage());
            }
        }
        // Create defaults if empty
        if (presets.isEmpty()) {
            presets.put("rally", KartPreset.rally());
            presets.put("drift", KartPreset.drift());
            presets.put("parkour", KartPreset.parkour());
            save();
            LOGGER.info("[CheeseKart] Created default presets (rally, drift, parkour)");
        }
    }

    public void save() {
        try {
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            presets.values().forEach(p -> arr.add(p.toJson()));
            root.add("presets", arr);
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, GSON.toJson(root));
        } catch (IOException e) {
            LOGGER.error("[CheeseKart] Failed to save config: {}", e.getMessage());
        }
    }

    // ── Preset management ─────────────────────────────────────────────────────
    public Map<String, KartPreset> getPresets() { return Collections.unmodifiableMap(presets); }
    public KartPreset getPreset(String id) { return presets.get(id); }

    public void putPreset(KartPreset preset) {
        presets.put(preset.getId(), preset);
        save();
    }

    public boolean removePreset(String id) {
        boolean removed = presets.remove(id) != null;
        if (removed) save();
        return removed;
    }
}
