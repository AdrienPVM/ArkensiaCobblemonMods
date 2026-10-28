package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.ServerEvent;
import com.google.gson.*;
import net.minecraft.server.MinecraftServer;

import java.nio.file.*;
import java.util.*;

/**
 * Save and load event configurations as reusable templates.
 * Templates are stored in config/cheeseguard_templates/ as JSON files.
 */
public class EventTemplate {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static boolean save(MinecraftServer server, ServerEvent event, String templateName) {
        try {
            JsonObject json = event.toJson();
            json.remove("lastRun");
            json.addProperty("templateName", templateName);
            json.addProperty("templateCreated", System.currentTimeMillis());
            json.addProperty("templateSource", event.getName());

            Path dir = getTemplateDir(server);
            Files.createDirectories(dir);
            String filename = templateName.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase() + ".json";
            Files.writeString(dir.resolve(filename), GSON.toJson(json));
            CheeseGuardMod.LOGGER.info("[CG] Template saved: {}", templateName);
            return true;
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to save template '{}': {}", templateName, e.getMessage());
            return false;
        }
    }

    public static ServerEvent load(MinecraftServer server, String templateName) {
        try {
            Path dir = getTemplateDir(server);
            String filename = templateName.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase() + ".json";
            Path file = dir.resolve(filename);
            if (!Files.exists(file)) {
                file = dir.resolve(templateName + ".json");
                if (!Files.exists(file)) return null;
            }
            JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            json.addProperty("id", "evt_" + System.currentTimeMillis() % 100000);
            json.addProperty("lastRun", 0L);
            ServerEvent event = ServerEvent.fromJson(json);
            CheeseGuardMod.LOGGER.info("[CG] Template loaded: {} -> event {}", templateName, event.getId());
            return event;
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to load template '{}': {}", templateName, e.getMessage());
            return null;
        }
    }

    public static List<String> list(MinecraftServer server) {
        List<String> templates = new ArrayList<>();
        try {
            Path dir = getTemplateDir(server);
            if (!Files.exists(dir)) return templates;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
                for (Path file : stream) {
                    try {
                        JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                        String name = json.has("templateName") ? json.get("templateName").getAsString()
                            : file.getFileName().toString().replace(".json", "");
                        templates.add(name);
                    } catch (Exception ignored) {
                        templates.add(file.getFileName().toString().replace(".json", ""));
                    }
                }
            }
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] Failed to list templates: {}", e.getMessage());
        }
        Collections.sort(templates);
        return templates;
    }

    public static boolean delete(MinecraftServer server, String templateName) {
        try {
            Path dir = getTemplateDir(server);
            String filename = templateName.replaceAll("[^a-zA-Z0-9_-]", "_").toLowerCase() + ".json";
            return Files.deleteIfExists(dir.resolve(filename));
        } catch (Exception e) { return false; }
    }

    private static Path getTemplateDir(MinecraftServer server) {
        return server.getRunDirectory().resolve("config").resolve("cheeseguard_templates");
    }
}
