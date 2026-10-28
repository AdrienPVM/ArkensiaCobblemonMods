package com.cheeseguard.data;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class ChunkSetStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseGuard");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = Paths.get("config", "cheeseguard");
    private static final Path STORAGE_FILE = CONFIG_DIR.resolve("chunks.json");

    private final Map<String, ChunkSet> chunkSets = new HashMap<>();

    public ChunkSetStorage() {
        try { Files.createDirectories(CONFIG_DIR); } catch (IOException e) { LOGGER.error("Failed to create config dir", e); }
        load();
    }

    public ChunkSet getOrCreateSet(String name) { return chunkSets.computeIfAbsent(name, ChunkSet::new); }
    public ChunkSet getSet(String name) { return chunkSets.get(name); }
    public Map<String, ChunkSet> getAllSets() { return chunkSets; }
    public boolean hasSet(String name) { return chunkSets.containsKey(name); }

    public boolean deleteSet(String name) {
        boolean existed = chunkSets.remove(name) != null;
        if (existed) save();
        return existed;
    }

    public void save() {
        try {
            JsonObject root = new JsonObject();
            JsonObject setsObj = new JsonObject();
            for (Map.Entry<String, ChunkSet> entry : chunkSets.entrySet()) {
                ChunkSet cs = entry.getValue();
                JsonObject setObj = new JsonObject();

                // Chunks array
                JsonArray arr = new JsonArray();
                for (ChunkEntry chunk : cs.getChunks()) {
                    JsonObject co = new JsonObject();
                    co.addProperty("chunkX", chunk.getChunkX());
                    co.addProperty("chunkZ", chunk.getChunkZ());
                    co.addProperty("radius", chunk.getRadius());
                    if (!chunk.getLabel().isEmpty()) co.addProperty("label", chunk.getLabel());
                    co.addProperty("addedAt", chunk.getAddedAt());
                    arr.add(co);
                }
                setObj.add("chunks", arr);

                // Dimension
                setObj.addProperty("dimension", cs.getDimension());

                // Scheduling fields
                setObj.addProperty("resetTrigger", cs.getResetTrigger());
                setObj.addProperty("resetHour", cs.getResetHour());
                setObj.addProperty("resetMinute", cs.getResetMinute());
                if (!cs.getResetDays().isEmpty()) {
                    JsonArray days = new JsonArray();
                    cs.getResetDays().forEach(days::add);
                    setObj.add("resetDays", days);
                }
                if (cs.getLastAutoReset() > 0) {
                    setObj.addProperty("lastAutoReset", cs.getLastAutoReset());
                }

                setsObj.add(entry.getKey(), setObj);
            }
            root.add("sets", setsObj);
            Files.writeString(STORAGE_FILE, GSON.toJson(root));
        } catch (IOException e) { LOGGER.error("Failed to save chunk data", e); }
    }

    public void load() {
        if (!Files.exists(STORAGE_FILE)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(STORAGE_FILE)).getAsJsonObject();
            if (root.has("sets")) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("sets").entrySet()) {
                    ChunkSet set = new ChunkSet(entry.getKey());
                    JsonElement val = entry.getValue();

                    // Support both old format (direct array) and new format (object with chunks + schedule)
                    JsonArray chunksArr;
                    if (val.isJsonArray()) {
                        chunksArr = val.getAsJsonArray();
                    } else {
                        JsonObject setObj = val.getAsJsonObject();
                        chunksArr = setObj.has("chunks") ? setObj.getAsJsonArray("chunks") : new JsonArray();
                        if (setObj.has("dimension")) set.setDimension(setObj.get("dimension").getAsString());
                        if (setObj.has("resetTrigger")) set.setResetTrigger(setObj.get("resetTrigger").getAsString());
                        if (setObj.has("resetHour")) set.setResetHour(setObj.get("resetHour").getAsInt());
                        if (setObj.has("resetMinute")) set.setResetMinute(setObj.get("resetMinute").getAsInt());
                        if (setObj.has("resetDays")) {
                            java.util.List<String> days = new java.util.ArrayList<>();
                            setObj.getAsJsonArray("resetDays").forEach(e -> days.add(e.getAsString()));
                            set.setResetDays(days);
                        }
                        if (setObj.has("lastAutoReset")) set.setLastAutoReset(setObj.get("lastAutoReset").getAsLong());
                    }

                    for (JsonElement el : chunksArr) {
                        JsonObject co = el.getAsJsonObject();
                        String label = co.has("label") ? co.get("label").getAsString() : "";
                        ChunkEntry ce = new ChunkEntry(co.get("chunkX").getAsInt(), co.get("chunkZ").getAsInt(), co.get("radius").getAsInt(), label);
                        if (co.has("addedAt")) ce.setAddedAt(co.get("addedAt").getAsLong());
                        set.addChunk(ce);
                    }
                    chunkSets.put(entry.getKey(), set);
                }
            }
            LOGGER.info("Loaded {} chunk sets", chunkSets.size());
        } catch (Exception e) { LOGGER.error("Failed to load chunk data", e); }
    }
}
