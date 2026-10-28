package com.cobblecap.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Loads and persists the ordered champion list from config/cobblecap/champions.json.
 * Champions are evaluated in order — the first unbeaten champion is the player's current gate.
 */
public class ChampionConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("cobblecap/champions.json");

    private List<ChampionEntry> champions = new ArrayList<>();

    /**
     * Load champions from disk. Creates the file with an empty array if it does not exist.
     */
    public void load() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());

            if (!Files.exists(CONFIG_PATH)) {
                // First run — create empty champions file
                JsonObject empty = new JsonObject();
                empty.add("champions", new JsonArray());
                try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                    GSON.toJson(empty, writer);
                }
                LOGGER.info("[CobbleCap] Created empty champions.json");
                champions = new ArrayList<>();
                return;
            }

            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                JsonObject obj = GSON.fromJson(reader, JsonObject.class);
                champions = new ArrayList<>();
                if (obj != null && obj.has("champions")) {
                    for (var elem : obj.getAsJsonArray("champions")) {
                        champions.add(ChampionEntry.fromJson(elem.getAsJsonObject()));
                    }
                }
            }

            LOGGER.info("[CobbleCap] Loaded {} champion(s) from champions.json", champions.size());

        } catch (IOException e) {
            LOGGER.error("[CobbleCap] Failed to load champions.json: {}", e.getMessage(), e);
        }
    }

    /**
     * Save the current champion list to disk (used by future dashboard API).
     */
    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonArray arr = new JsonArray();
            for (ChampionEntry entry : champions) {
                arr.add(entry.toJson());
            }
            JsonObject obj = new JsonObject();
            obj.add("champions", arr);
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(obj, writer);
            }
        } catch (IOException e) {
            LOGGER.error("[CobbleCap] Failed to save champions.json: {}", e.getMessage(), e);
        }
    }

    /**
     * Reload from disk and return the number of champions loaded.
     */
    public int reload() {
        load();
        return champions.size();
    }

    /**
     * Find a champion by its trainer ID.
     */
    public Optional<ChampionEntry> findById(String id) {
        return champions.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    /**
     * Find a champion by its challenge variant trainer ID.
     */
    public Optional<ChampionEntry> findByChallengeId(String challengeId) {
        if (challengeId == null || challengeId.isEmpty()) return Optional.empty();
        return champions.stream()
                .filter(c -> c.challengeId() != null && c.challengeId().equals(challengeId))
                .findFirst();
    }

    /**
     * Return the first champion in the ordered list whose ID is not in the player's beaten list.
     * This is the player's current progression gate.
     */
    public Optional<ChampionEntry> nextUnbeaten(List<String> beatenIds) {
        return champions.stream().filter(c -> !beatenIds.contains(c.id())).findFirst();
    }

    /**
     * Replace the entire champion list and persist to disk (used by dashboard API).
     */
    public void setChampions(List<ChampionEntry> newList) {
        this.champions = new ArrayList<>(newList);
        save();
    }

    /**
     * Return an unmodifiable view of the champion list.
     */
    public List<ChampionEntry> getChampions() {
        return Collections.unmodifiableList(champions);
    }
}
