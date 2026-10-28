package com.cheesecompat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;

public class CheeseCompatConfig {
    private static final Path CONFIG_PATH = Path.of("config", "cheesecompatmodsfix.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean arcPhoneHealEnabled = true;
    public boolean arcPhonePCEnabled = true;
    public boolean sanctuaryOverflowFix = true;

    public static CheeseCompatConfig load() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                String json = Files.readString(CONFIG_PATH);
                CheeseCompatConfig cfg = GSON.fromJson(json, CheeseCompatConfig.class);
                if (cfg != null) {
                    cfg.save(); // re-save to add any new fields
                    return cfg;
                }
            }
        } catch (Exception e) {
            CheeseCompatMod.LOGGER.error("[CheeseCompat] Failed to load config", e);
        }
        CheeseCompatConfig cfg = new CheeseCompatConfig();
        cfg.save();
        return cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(this));
        } catch (Exception e) {
            CheeseCompatMod.LOGGER.error("[CheeseCompat] Failed to save config", e);
        }
    }
}
