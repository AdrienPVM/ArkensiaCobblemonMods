package com.cobbleshift;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persistent JSON config for CobbleShift.
 * Stored at config/cobbleshift.json
 */
public class CobbleShiftConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobbleshift");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private boolean autoRevertOnDeath = true;
    private boolean abilityAutoMode = true;
    private boolean abilityGlobalEnabled = false;
    private int abilityTickInterval = 100;
    private int abilityEffectDuration = 160;
    private boolean hotPotatoEnabled = true;
    private double hotPotatoPassRange = 3.0;
    private boolean transformScaleEnabled = true;
    private boolean transformSpeedEnabled = true;
    private boolean transformHealthEnabled = true;
    private float attackDamageMultiplier = 1.0f;
    private float attackCooldownMultiplier = 1.0f;
    private boolean transformMaxDurationEnabled = false;
    private int transformMaxDurationSeconds = 300;
    private boolean transformCooldownEnabled = false;
    private int transformCooldownSeconds = 60;

    public static CobbleShiftConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("cobbleshift.json");
        CobbleShiftConfig cfg = new CobbleShiftConfig();
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                if (obj.has("autoRevertOnDeath")) cfg.autoRevertOnDeath = obj.get("autoRevertOnDeath").getAsBoolean();
                if (obj.has("abilityAutoMode")) cfg.abilityAutoMode = obj.get("abilityAutoMode").getAsBoolean();
                if (obj.has("abilityGlobalEnabled")) cfg.abilityGlobalEnabled = obj.get("abilityGlobalEnabled").getAsBoolean();
                if (obj.has("abilityTickInterval")) cfg.abilityTickInterval = obj.get("abilityTickInterval").getAsInt();
                if (obj.has("abilityEffectDuration")) cfg.abilityEffectDuration = obj.get("abilityEffectDuration").getAsInt();
                if (obj.has("hotPotatoEnabled")) cfg.hotPotatoEnabled = obj.get("hotPotatoEnabled").getAsBoolean();
                if (obj.has("hotPotatoPassRange")) cfg.hotPotatoPassRange = obj.get("hotPotatoPassRange").getAsDouble();
                if (obj.has("transformScaleEnabled")) cfg.transformScaleEnabled = obj.get("transformScaleEnabled").getAsBoolean();
                if (obj.has("transformSpeedEnabled")) cfg.transformSpeedEnabled = obj.get("transformSpeedEnabled").getAsBoolean();
                if (obj.has("transformHealthEnabled")) cfg.transformHealthEnabled = obj.get("transformHealthEnabled").getAsBoolean();
                if (obj.has("attackDamageMultiplier")) cfg.attackDamageMultiplier = obj.get("attackDamageMultiplier").getAsFloat();
                if (obj.has("attackCooldownMultiplier")) cfg.attackCooldownMultiplier = obj.get("attackCooldownMultiplier").getAsFloat();
                if (obj.has("transformMaxDurationEnabled")) cfg.transformMaxDurationEnabled = obj.get("transformMaxDurationEnabled").getAsBoolean();
                if (obj.has("transformMaxDurationSeconds")) cfg.transformMaxDurationSeconds = obj.get("transformMaxDurationSeconds").getAsInt();
                if (obj.has("transformCooldownEnabled")) cfg.transformCooldownEnabled = obj.get("transformCooldownEnabled").getAsBoolean();
                if (obj.has("transformCooldownSeconds")) cfg.transformCooldownSeconds = obj.get("transformCooldownSeconds").getAsInt();
                LOGGER.info("[CobbleShift] Config loaded");
            } catch (Exception e) {
                LOGGER.warn("[CobbleShift] Failed to load config, using defaults: {}", e.getMessage());
            }
        } else {
            cfg.save();
            LOGGER.info("[CobbleShift] Default config created");
        }
        return cfg;
    }

    public void save() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("cobbleshift.json");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(toJson()));
        } catch (IOException e) {
            LOGGER.error("[CobbleShift] Failed to save config: {}", e.getMessage());
        }
    }

    // ── Getters & Setters ────────────────────────────────────────────────────

    public boolean isAutoRevertOnDeath() { return autoRevertOnDeath; }
    public void setAutoRevertOnDeath(boolean v) { autoRevertOnDeath = v; }

    public boolean isAbilityAutoMode() { return abilityAutoMode; }
    public void setAbilityAutoMode(boolean v) { abilityAutoMode = v; }

    public boolean isAbilityGlobalEnabled() { return abilityGlobalEnabled; }
    public void setAbilityGlobalEnabled(boolean v) { abilityGlobalEnabled = v; }

    public int getAbilityTickInterval() { return abilityTickInterval; }
    public void setAbilityTickInterval(int v) { abilityTickInterval = Math.max(20, v); }

    public int getAbilityEffectDuration() { return abilityEffectDuration; }
    public void setAbilityEffectDuration(int v) { abilityEffectDuration = Math.max(40, v); }

    public boolean isHotPotatoEnabled() { return hotPotatoEnabled; }
    public void setHotPotatoEnabled(boolean v) { hotPotatoEnabled = v; }

    public double getHotPotatoPassRange() { return hotPotatoPassRange; }
    public void setHotPotatoPassRange(double v) { hotPotatoPassRange = Math.max(1.0, v); }

    public boolean isTransformScaleEnabled() { return transformScaleEnabled; }
    public void setTransformScaleEnabled(boolean v) { transformScaleEnabled = v; }

    public boolean isTransformSpeedEnabled() { return transformSpeedEnabled; }
    public void setTransformSpeedEnabled(boolean v) { transformSpeedEnabled = v; }

    public boolean isTransformHealthEnabled() { return transformHealthEnabled; }
    public void setTransformHealthEnabled(boolean v) { transformHealthEnabled = v; }

    public float getAttackDamageMultiplier() { return attackDamageMultiplier; }
    public void setAttackDamageMultiplier(float v) { attackDamageMultiplier = Math.max(0.1f, Math.min(5.0f, v)); }

    public float getAttackCooldownMultiplier() { return attackCooldownMultiplier; }
    public void setAttackCooldownMultiplier(float v) { attackCooldownMultiplier = Math.max(0.1f, Math.min(5.0f, v)); }

    public boolean isTransformMaxDurationEnabled() { return transformMaxDurationEnabled; }
    public void setTransformMaxDurationEnabled(boolean v) { transformMaxDurationEnabled = v; }

    public int getTransformMaxDurationSeconds() { return transformMaxDurationSeconds; }
    public void setTransformMaxDurationSeconds(int v) { transformMaxDurationSeconds = Math.max(10, v); }

    public boolean isTransformCooldownEnabled() { return transformCooldownEnabled; }
    public void setTransformCooldownEnabled(boolean v) { transformCooldownEnabled = v; }

    public int getTransformCooldownSeconds() { return transformCooldownSeconds; }
    public void setTransformCooldownSeconds(int v) { transformCooldownSeconds = Math.max(5, v); }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("autoRevertOnDeath", autoRevertOnDeath);
        obj.addProperty("abilityAutoMode", abilityAutoMode);
        obj.addProperty("abilityGlobalEnabled", abilityGlobalEnabled);
        obj.addProperty("abilityTickInterval", abilityTickInterval);
        obj.addProperty("abilityEffectDuration", abilityEffectDuration);
        obj.addProperty("hotPotatoEnabled", hotPotatoEnabled);
        obj.addProperty("hotPotatoPassRange", hotPotatoPassRange);
        obj.addProperty("transformScaleEnabled", transformScaleEnabled);
        obj.addProperty("transformSpeedEnabled", transformSpeedEnabled);
        obj.addProperty("transformHealthEnabled", transformHealthEnabled);
        obj.addProperty("attackDamageMultiplier", attackDamageMultiplier);
        obj.addProperty("attackCooldownMultiplier", attackCooldownMultiplier);
        obj.addProperty("transformMaxDurationEnabled", transformMaxDurationEnabled);
        obj.addProperty("transformMaxDurationSeconds", transformMaxDurationSeconds);
        obj.addProperty("transformCooldownEnabled", transformCooldownEnabled);
        obj.addProperty("transformCooldownSeconds", transformCooldownSeconds);
        return obj;
    }
}
