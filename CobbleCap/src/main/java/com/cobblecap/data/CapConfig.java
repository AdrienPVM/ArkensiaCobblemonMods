package com.cobblecap.data;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CapConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("cobblecap/config.json");

    private int startingCap = 20;
    private boolean enabled = true;
    private String discordWebhookUrl = "";
    private List<String> bannedSpecies = new ArrayList<>();

    // ── Fireworks celebration (new ranked champion crowned) ─────────────────
    private boolean fireworksEnabled = false;
    private String  fireworksZoneId = "";      // CheeseGuard zone id (any world)
    private int     fireworksCount = 12;       // total rockets to launch
    private int     fireworksIntervalMs = 800; // delay between rockets
    private int     fireworksDurationSec = 12; // hard cap on the show length

    public void load() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());

            if (Files.exists(CONFIG_PATH)) {
                try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                    JsonObject obj = GSON.fromJson(reader, JsonObject.class);
                    if (obj != null) {
                        if (obj.has("startingCap")) startingCap = obj.get("startingCap").getAsInt();
                        if (obj.has("enabled")) enabled = obj.get("enabled").getAsBoolean();
                        if (obj.has("discordWebhookUrl")) discordWebhookUrl = obj.get("discordWebhookUrl").getAsString();
                        if (obj.has("bannedSpecies") && obj.get("bannedSpecies").isJsonArray()) {
                            bannedSpecies.clear();
                            for (var el : obj.getAsJsonArray("bannedSpecies")) {
                                bannedSpecies.add(el.getAsString().toLowerCase(Locale.ROOT));
                            }
                        }
                        if (obj.has("fireworksEnabled"))    fireworksEnabled    = obj.get("fireworksEnabled").getAsBoolean();
                        if (obj.has("fireworksZoneId"))     fireworksZoneId     = obj.get("fireworksZoneId").getAsString();
                        if (obj.has("fireworksCount"))      fireworksCount      = obj.get("fireworksCount").getAsInt();
                        if (obj.has("fireworksIntervalMs")) fireworksIntervalMs = obj.get("fireworksIntervalMs").getAsInt();
                        if (obj.has("fireworksDurationSec"))fireworksDurationSec= obj.get("fireworksDurationSec").getAsInt();
                    }
                }
            } else {
                // File doesn't exist — create with defaults
                save();
            }

            LOGGER.info("[CobbleCap] Config loaded (startingCap={}, enabled={})", startingCap, enabled);
        } catch (IOException e) {
            LOGGER.error("[CobbleCap] Failed to load config: {}", e.getMessage(), e);
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("startingCap", startingCap);
            obj.addProperty("enabled", enabled);
            obj.addProperty("discordWebhookUrl", discordWebhookUrl);
            com.google.gson.JsonArray bannedArr = new com.google.gson.JsonArray();
            for (String s : bannedSpecies) bannedArr.add(s);
            obj.add("bannedSpecies", bannedArr);
            obj.addProperty("fireworksEnabled", fireworksEnabled);
            obj.addProperty("fireworksZoneId", fireworksZoneId);
            obj.addProperty("fireworksCount", fireworksCount);
            obj.addProperty("fireworksIntervalMs", fireworksIntervalMs);
            obj.addProperty("fireworksDurationSec", fireworksDurationSec);
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(obj, writer);
            }
        } catch (IOException e) {
            LOGGER.error("[CobbleCap] Failed to save config: {}", e.getMessage(), e);
        }
    }

    public int getStartingCap() { return startingCap; }
    public boolean isEnabled() { return enabled; }
    public String getDiscordWebhookUrl() { return discordWebhookUrl; }
    public List<String> getBannedSpecies() { return bannedSpecies; }

    public boolean isSpeciesBanned(String species) {
        if (species == null || bannedSpecies.isEmpty()) return false;
        return bannedSpecies.contains(species.toLowerCase(Locale.ROOT));
    }

    public void setBannedSpecies(List<String> species) {
        bannedSpecies.clear();
        for (String s : species) {
            bannedSpecies.add(s.toLowerCase(Locale.ROOT));
        }
        save();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        save();
    }

    public void setDiscordWebhookUrl(String url) {
        this.discordWebhookUrl = url != null ? url : "";
        save();
    }

    // ── Fireworks getters/setters ──
    public boolean isFireworksEnabled()    { return fireworksEnabled; }
    public String  getFireworksZoneId()    { return fireworksZoneId; }
    public int     getFireworksCount()     { return fireworksCount; }
    public int     getFireworksIntervalMs(){ return fireworksIntervalMs; }
    public int     getFireworksDurationSec(){ return fireworksDurationSec; }

    public void setFireworksEnabled(boolean v)    { this.fireworksEnabled = v; save(); }
    public void setFireworksZoneId(String v)      { this.fireworksZoneId = v != null ? v : ""; save(); }
    public void setFireworksCount(int v)          { this.fireworksCount = Math.max(1, v); save(); }
    public void setFireworksIntervalMs(int v)     { this.fireworksIntervalMs = Math.max(50, v); save(); }
    public void setFireworksDurationSec(int v)    { this.fireworksDurationSec = Math.max(1, v); save(); }
}
