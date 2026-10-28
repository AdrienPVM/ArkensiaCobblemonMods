package com.cheesecompat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourcePackProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Client-side initializer for CheeseCompatModsFix.
 * Ensures all required Arkensia resource packs are enabled on first install.
 * CurseForge does not apply options.txt from modpack overrides, so packs
 * would be disabled for new players. This runs once when packs aren't set up.
 *
 * Reads the pack list from config/resourcepackoverrides.json (synced by AutoModpack)
 * so the server remains the single source of truth. Falls back to a hardcoded list
 * if the config isn't available yet.
 */
public class CheeseCompatClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("cheesecompatmodsfix");

    // Fallback pack list if resourcepackoverrides.json is not available.
    private static final List<String> FALLBACK_PACKS = Arrays.asList(
        "vanilla",
        "fabric",
        "cobbreeding:pasturefix",
        "moonlight:mods_dynamic_assets",
        "$polymer-resources",
        "tomsstorage_knowlogy:toms_storage",
        "cobblemon:regionbiasforms",
        "cobblemon:uniqueshinyforms",
        "cobblemon:gyaradosjump",
        "cobblemon_knowlogy:cobblemon",
        "cobblemon_knowlogy:cobblemonpokenav",
        "scholar:colored_books",
        "cobblemon_knowlogy:pokepedia",
        "exposure_knowlogy:exposure_polaroid",
        "mega_showdown:gyaradosjumpingmega",
        "knowlogy:1.0.0_minecraft",
        "exposure_knowlogy:exposure",
        "cobblemon_knowlogy:cobbleride",
        "file/Disable SeasonHUD Slot 1.1.1.zip",
        "file/FTB-VanillaLike.zip",
        "file/CobblemonClassicGrass.zip",
        "file/CobblemonTextFix.zip",
        "file/MoreFishingBaits1.2.zip",
        "file/Cobbletowns 1.6 - No excessive marts and centers2.zip",
        "file/CobblemonRaidDenDesigns-0.5_RELEASE.zip",
        "file/BCA-Datapack-3.8_CE_norm_M1.21.1_C1.6.1.zip",
        "file/MysticMons",
        "file/Cobblemon improvedmon v1.1.zip",
        "file/AlphabetSoup-v2.2.zip",
        "file/CloudianMons_1.3.zip",
        "file/Seavls Bizarremons 1.1.0v.zip",
        "file/Fossil Hybrids! 1.03.zip",
        "file/extra paradox mons.zip",
        "file/Glow-in-the-Dark Pokemon.zip",
        "file/[V.1.4.1] Cobblemon Stellar Blue.zip",
        "file/The New Hisui-WithMegas.zip",
        "file/Cobblemotion_v1.3a beta hotfix.zip",
        "file/Z-A Mega\u0027s Early",
        "file/AllTheMons x Mega Showdown [2.9.0LM].zip",
        "file/Baby Legends.zip",
        "file/RCTTrainers.zip",
        "file/CCC_MAL",
        "file/dCIM",
        "file/Traduction_FR_V3",
        "file/CobbleSounds",
        "file/Academy",
        "file/CobblemonInterface.zip",
        "file/Cheese-BoxWallpapers.zip",
        "file/CheeseMusic",
        "file/CacademyMons",
        "scholar:chiseled_bookshelf_colored_books"
    );

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            if (client.options.resourcePacks.contains("file/CacademyMons")) return;

            LOGGER.info("[CheeseCompat] Arkensia resource packs not configured — activating...");

            List<String> targetPacks = readPackListFromConfig();
            if (targetPacks == null) {
                LOGGER.info("[CheeseCompat] No resourcepackoverrides.json found, using fallback list");
                targetPacks = FALLBACK_PACKS;
            } else {
                LOGGER.info("[CheeseCompat] Loaded {} packs from resourcepackoverrides.json", targetPacks.size());
            }

            // Re-scan the resourcepacks folder to discover all available packs
            ResourcePackManager rpm = client.getResourcePackManager();
            rpm.scanPacks();

            // Log available packs for debugging
            Collection<ResourcePackProfile> available = rpm.getProfiles();
            LOGGER.info("[CheeseCompat] Available packs after scan: {}", available.size());

            // Log which target packs are missing
            List<String> missing = new ArrayList<>();
            for (String pack : targetPacks) {
                if (rpm.getProfile(pack) == null) {
                    missing.add(pack);
                }
            }
            if (!missing.isEmpty()) {
                LOGGER.warn("[CheeseCompat] {} packs not found: {}", missing.size(), missing);
            }

            // Enable packs through the ResourcePackManager (updates internal state)
            rpm.setEnabledProfiles(targetPacks);

            // Sync the manager's state back to options
            Collection<String> enabledIds = rpm.getEnabledIds();
            client.options.resourcePacks.clear();
            client.options.resourcePacks.addAll(enabledIds);
            client.options.incompatibleResourcePacks.clear();
            client.options.write();

            // Reload resources with the properly configured pack manager
            client.reloadResources();

            LOGGER.info("[CheeseCompat] {} resource packs activated (of {} requested).",
                    enabledIds.size(), targetPacks.size());
        });
    }

    /**
     * Reads the ordered resource pack list from config/resourcepackoverrides.json.
     * This file is synced from the server by AutoModpack, making it the single source of truth.
     */
    private static List<String> readPackListFromConfig() {
        try {
            Path configPath = FabricLoader.getInstance().getConfigDir()
                    .resolve("resourcepackoverrides.json");
            if (!Files.exists(configPath)) return null;

            String json = Files.readString(configPath);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            JsonArray packs = obj.getAsJsonArray("default_packs");
            if (packs == null || packs.isEmpty()) return null;

            List<String> result = new ArrayList<>();
            for (JsonElement e : packs) {
                result.add(e.getAsString());
            }
            return result;
        } catch (Exception e) {
            LOGGER.warn("[CheeseCompat] Failed to read resourcepackoverrides.json: {}", e.getMessage());
            return null;
        }
    }
}
