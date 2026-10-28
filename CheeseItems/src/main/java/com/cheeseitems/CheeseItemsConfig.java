package com.cheeseitems;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Persistence layer for custom item definitions.
 * Stored at config/cheeseitems.json.
 */
public class CheeseItemsConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("cheeseitems");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final List<CustomItemDefinition> items = new ArrayList<>();
    private final Map<String, Integer> customModelDataMap = new LinkedHashMap<>();
    private int nextCustomModelData = 1;
    private String resourcePackHost = "";

    public void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("cheeseitems.json");
        items.clear();
        customModelDataMap.clear();
        nextCustomModelData = 1;
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                if (root.has("items")) {
                    for (JsonElement e : root.getAsJsonArray("items")) {
                        items.add(CustomItemDefinition.fromJson(e.getAsJsonObject()));
                    }
                }
                if (root.has("customModelData")) {
                    for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("customModelData").entrySet()) {
                        int val = e.getValue().getAsInt();
                        customModelDataMap.put(e.getKey(), val);
                        if (val >= nextCustomModelData) nextCustomModelData = val + 1;
                    }
                }
                if (root.has("resourcePackHost")) {
                    resourcePackHost = root.get("resourcePackHost").getAsString();
                }
                LOGGER.info("[CheeseItems] Loaded {} item definitions, {} custom model data mappings", items.size(), customModelDataMap.size());
            } catch (Exception e) {
                LOGGER.error("[CheeseItems] Failed to load config: {}", e.getMessage());
            }
        } else {
            save();
            LOGGER.info("[CheeseItems] Default config created");
        }
    }

    public void save() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("cheeseitems.json");
        try {
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (CustomItemDefinition def : items) arr.add(def.toJson());
            root.add("items", arr);
            JsonObject cmdObj = new JsonObject();
            for (Map.Entry<String, Integer> e : customModelDataMap.entrySet()) {
                cmdObj.addProperty(e.getKey(), e.getValue());
            }
            root.add("customModelData", cmdObj);
            root.addProperty("resourcePackHost", resourcePackHost);
            Files.writeString(path, GSON.toJson(root));
        } catch (IOException e) {
            LOGGER.error("[CheeseItems] Failed to save config: {}", e.getMessage());
        }
    }

    // ── CustomModelData management ────────────────────────────────────────────

    public int getOrAssignCustomModelData(String itemId) {
        return customModelDataMap.computeIfAbsent(itemId, k -> nextCustomModelData++);
    }

    public Integer getCustomModelData(String itemId) {
        return customModelDataMap.get(itemId);
    }

    public void removeCustomModelData(String itemId) {
        customModelDataMap.remove(itemId);
    }

    public String getResourcePackHost() { return resourcePackHost; }

    public List<CustomItemDefinition> getItems() {
        return Collections.unmodifiableList(items);
    }

    public List<CustomItemDefinition> getItemsMutable() {
        return items;
    }

    // ── Item icon images ─────────────────────────────────────────────────────

    public void saveItemImage(String id, byte[] data) throws IOException {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("cheeseitems_assets");
        Files.createDirectories(dir);
        Files.write(dir.resolve(id + ".png"), data);
    }

    public byte[] getItemImage(String id) {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("cheeseitems_assets").resolve(id + ".png");
        if (Files.exists(file)) {
            try { return Files.readAllBytes(file); }
            catch (IOException e) { return null; }
        }
        return null;
    }

    public boolean hasItemImage(String id) {
        return Files.exists(FabricLoader.getInstance().getConfigDir().resolve("cheeseitems_assets").resolve(id + ".png"));
    }

    public void deleteItemImage(String id) {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("cheeseitems_assets").resolve(id + ".png");
        try { Files.deleteIfExists(file); } catch (IOException ignored) {}
    }

    // ── Endurance bar images ──────────────────────────────────────────────────

    public boolean hasBarImages() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("cheeseitems_assets").resolve("bar");
        if (!Files.isDirectory(dir)) return false;
        for (int i = 0; i <= 10; i++) {
            if (!Files.exists(dir.resolve(i + ".png"))) return false;
        }
        return true;
    }

    public byte[] getBarImage(int level) {
        Path file = FabricLoader.getInstance().getConfigDir()
                .resolve("cheeseitems_assets").resolve("bar").resolve(level + ".png");
        if (Files.exists(file)) {
            try { return Files.readAllBytes(file); }
            catch (IOException e) { return null; }
        }
        return null;
    }
}
