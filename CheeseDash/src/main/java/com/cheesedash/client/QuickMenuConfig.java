package com.cheesedash.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads/writes client-side Quick Menu shortcuts from config/cheesedash_shortcuts.json.
 */
public class QuickMenuConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("cheesedash_shortcuts.json");

    private final List<Shortcut> shortcuts = new ArrayList<>();

    public record Shortcut(String label, String command) {}

    public List<Shortcut> getShortcuts() { return shortcuts; }

    public void load() {
        shortcuts.clear();
        if (!Files.exists(CONFIG_PATH)) return;
        try {
            String json = Files.readString(CONFIG_PATH);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.has("shortcuts") && obj.get("shortcuts").isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray("shortcuts");
                arr.forEach(el -> {
                    JsonObject s = el.getAsJsonObject();
                    String label = s.has("label") ? s.get("label").getAsString() : "";
                    String command = s.has("command") ? s.get("command").getAsString() : "";
                    if (!label.isEmpty() && !command.isEmpty()) {
                        shortcuts.add(new Shortcut(label, command));
                    }
                });
            }
        } catch (Exception e) {
            // Silently ignore corrupt config — will be overwritten on save
        }
    }

    public void save() {
        try {
            JsonObject obj = new JsonObject();
            JsonArray arr = new JsonArray();
            for (Shortcut s : shortcuts) {
                JsonObject so = new JsonObject();
                so.addProperty("label", s.label());
                so.addProperty("command", s.command());
                arr.add(so);
            }
            obj.add("shortcuts", arr);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj));
        } catch (Exception e) {
            // Silently ignore write errors
        }
    }

    public void addShortcut(String label, String command) {
        shortcuts.add(new Shortcut(label, command));
        save();
    }

    public void removeShortcut(int index) {
        if (index >= 0 && index < shortcuts.size()) {
            shortcuts.remove(index);
            save();
        }
    }
}
