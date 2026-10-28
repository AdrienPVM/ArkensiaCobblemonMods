package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;

/**
 * Manages PropHunt species icons.
 * Stores PNGs in config/cheeseguard_assets/prophunt/ and registers them
 * as external entries in CheeseItems' ResourcePackGenerator via reflection.
 */
public class PropHuntIconStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseGuard");
    private static final int CMD_BASE = 900; // CustomModelData starting value (avoid CheeseItems range)

    private final Path assetsDir;
    private final Path configFile;
    private final Map<String, Integer> customModelDataMap = new LinkedHashMap<>();
    private int nextCmd = CMD_BASE;

    public PropHuntIconStorage() {
        this.assetsDir = FabricLoader.getInstance().getConfigDir().resolve("cheeseguard_assets").resolve("prophunt");
        this.configFile = FabricLoader.getInstance().getConfigDir().resolve("cheeseguard_prophunt_icons.json");
    }

    public void load() {
        customModelDataMap.clear();
        try {
            Files.createDirectories(assetsDir);
            if (Files.exists(configFile)) {
                String json = Files.readString(configFile);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                if (root.has("nextCmd")) nextCmd = root.get("nextCmd").getAsInt();
                if (root.has("mapping")) {
                    for (var entry : root.getAsJsonObject("mapping").entrySet()) {
                        customModelDataMap.put(entry.getKey(), entry.getValue().getAsInt());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[CG] Failed to load PropHunt icon config: {}", e.getMessage());
        }
    }

    public void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("nextCmd", nextCmd);
            JsonObject mapping = new JsonObject();
            customModelDataMap.forEach(mapping::addProperty);
            root.add("mapping", mapping);
            Files.writeString(configFile, new Gson().toJson(root));
        } catch (Exception e) {
            LOGGER.error("[CG] Failed to save PropHunt icon config: {}", e.getMessage());
        }
    }

    public void saveIcon(String key, byte[] data) throws IOException {
        Files.createDirectories(assetsDir);
        Files.write(assetsDir.resolve(key + ".png"), data);
    }

    public byte[] getIcon(String key) {
        Path file = assetsDir.resolve(key + ".png");
        if (Files.exists(file)) {
            try { return Files.readAllBytes(file); }
            catch (IOException e) { return null; }
        }
        return null;
    }

    public boolean hasIcon(String key) {
        return Files.exists(assetsDir.resolve(key + ".png"));
    }

    public void deleteIcon(String key) {
        try { Files.deleteIfExists(assetsDir.resolve(key + ".png")); }
        catch (IOException ignored) {}
        customModelDataMap.remove(key);
    }

    public int getOrAssignCustomModelData(String key) {
        return customModelDataMap.computeIfAbsent(key, k -> nextCmd++);
    }

    public int getCustomModelData(String key) {
        Integer cmd = customModelDataMap.get(key);
        return cmd != null ? cmd : -1;
    }

    /**
     * Register all icons as external entries in CheeseItems' resource pack.
     * Uses reflection since CheeseItems is not a compile-time dependency.
     */
    public void registerWithCheeseItems() {
        try {
            Class<?> modClass = Class.forName("com.cheeseitems.CheeseItemsMod");
            Object modInstance = modClass.getField("INSTANCE").get(null);
            if (modInstance == null) {
                LOGGER.debug("[CG] CheeseItems not initialized yet, skipping icon registration");
                return;
            }

            Method getRpg = modClass.getMethod("getResourcePackGenerator");
            Object rpg = getRpg.invoke(modInstance);
            if (rpg == null) {
                LOGGER.debug("[CG] ResourcePackGenerator not available");
                return;
            }

            // rpg.removeExternalEntries("ph_")
            Method removeMethod = rpg.getClass().getMethod("removeExternalEntries", String.class);
            removeMethod.invoke(rpg, "ph_");

            // rpg.addExternalEntry(id, namespace, baseItem, cmd, png)
            Method addMethod = rpg.getClass().getMethod("addExternalEntry",
                    String.class, String.class, String.class, int.class, byte[].class);

            for (var entry : customModelDataMap.entrySet()) {
                byte[] png = getIcon(entry.getKey());
                if (png != null) {
                    addMethod.invoke(rpg, "ph_" + entry.getKey(), "cheeseguard",
                            "minecraft:paper", entry.getValue(), png);
                }
            }

            // CheeseItemsMod.INSTANCE.regenerateAndPushPack()
            Method regenMethod = modClass.getMethod("regenerateAndPushPack");
            regenMethod.invoke(modInstance);

            LOGGER.info("[CG] Registered {} PropHunt icons with CheeseItems resource pack", customModelDataMap.size());
        } catch (ClassNotFoundException e) {
            LOGGER.debug("[CG] CheeseItems not installed, PropHunt icons won't have custom textures");
        } catch (Exception e) {
            LOGGER.warn("[CG] Failed to register PropHunt icons with CheeseItems: {}", e.getMessage());
        }
    }

    /** List all icon keys. */
    public List<String> listIcons() {
        List<String> keys = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(assetsDir, "*.png")) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                keys.add(name.substring(0, name.length() - 4));
            }
        } catch (Exception ignored) {}
        return keys;
    }
}
