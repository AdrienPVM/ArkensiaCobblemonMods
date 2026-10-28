package com.cheesevanity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class VanityConfig {

    private static final Path CONFIG_PATH = Path.of("config", "cheesevanity.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // If true, our mod also handles vanilla armor slot visibility (disabled by default — Academy handles it)
    public boolean enableVanillaArmor = false;
    // If true, our mod handles trinket slot visibility
    public boolean enableTrinkets = true;
    // Specific slot keys to exclude from toggling (e.g., "chest/back" to prevent hiding backpacks)
    public Set<String> disabledSlots = new HashSet<>();

    public static VanityConfig load() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                String json = Files.readString(CONFIG_PATH);
                VanityConfig config = GSON.fromJson(json, VanityConfig.class);
                if (config != null) return config;
            }
        } catch (Exception e) {
            CheeseVanityMod.LOGGER.error("[CheeseVanity] Failed to load config", e);
        }
        VanityConfig config = new VanityConfig();
        config.save();
        return config;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(this));
        } catch (Exception e) {
            CheeseVanityMod.LOGGER.error("[CheeseVanity] Failed to save config", e);
        }
    }

    /** Check if a slot key is toggleable by this mod. */
    public boolean isSlotToggleable(String slotKey) {
        if (disabledSlots.contains(slotKey)) return false;
        if (isVanillaArmorSlot(slotKey)) return enableVanillaArmor;
        return enableTrinkets;
    }

    public static boolean isVanillaArmorSlot(String slotKey) {
        return slotKey.equals("helmet") || slotKey.equals("chestplate")
                || slotKey.equals("leggings") || slotKey.equals("boots");
    }
}
