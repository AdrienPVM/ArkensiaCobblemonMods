package com.cheeseitems;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates a server resource pack ZIP containing custom item textures
 * and model overrides (CustomModelData) for CheeseItems with uploaded icons.
 *
 * Pack structure:
 *   pack.mcmeta
 *   assets/minecraft/models/item/paper.json  (overrides for each CustomModelData value)
 *   assets/cheeseitems/models/item/<id>.json  (custom model per item)
 *   assets/cheeseitems/textures/item/<id>.png (uploaded icon per item)
 */
public class ResourcePackGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger("cheeseitems");
    private static final String PACK_MCMETA = """
            {
              "pack": {
                "pack_format": 34,
                "description": "CheeseItems Custom Icons"
              }
            }""";

    private byte[] currentPack;
    private String currentSha1;
    private String previousSha1;

    /**
     * Regenerate the resource pack from all items that have custom icons.
     * Call this after any icon upload/delete.
     * Returns true if the pack actually changed (new SHA1), false if identical.
     */
    public synchronized boolean generate() {
        previousSha1 = currentSha1;
        CheeseItemsConfig config = CheeseItemsMod.INSTANCE.getConfig();
        List<CustomItemDefinition> items = config.getItems();

        // Collect items that have custom icons
        List<ItemIconEntry> entries = new ArrayList<>();
        for (CustomItemDefinition def : items) {
            if (config.hasItemImage(def.getId())) {
                int cmd = config.getOrAssignCustomModelData(def.getId());
                byte[] png = config.getItemImage(def.getId());
                if (png != null) {
                    entries.add(new ItemIconEntry(def.getId(), "cheeseitems", def.getBaseItem(), cmd, png));
                }
            }
        }
        config.save(); // persist any newly assigned CustomModelData values

        // Include external entries from other mods (e.g. PropHunt icons)
        entries.addAll(externalEntries);

        boolean hasBarImages = config.hasBarImages();
        if (entries.isEmpty() && !hasBarImages) {
            currentPack = null;
            currentSha1 = null;
            LOGGER.info("[CheeseItems] Resource pack cleared (no custom icons, no bar images)");
            return !Objects.equals(previousSha1, currentSha1);
        }

        try {
            byte[] zip = buildZip(entries);
            currentPack = zip;
            currentSha1 = sha1Hex(zip);
            boolean changed = !Objects.equals(previousSha1, currentSha1);
            if (changed) {
                LOGGER.info("[CheeseItems] Resource pack generated: {} items, {} bytes, SHA1={}", entries.size(), zip.length, currentSha1);
            } else {
                LOGGER.info("[CheeseItems] Resource pack unchanged (SHA1={})", currentSha1);
            }
            return changed;
        } catch (Exception e) {
            LOGGER.error("[CheeseItems] Failed to generate resource pack: {}", e.getMessage(), e);
            currentPack = null;
            currentSha1 = null;
            return true;
        }
    }

    public synchronized byte[] getPack() { return currentPack; }
    public synchronized String getSha1() { return currentSha1; }
    public synchronized boolean hasPack() { return currentPack != null; }

    private byte[] buildZip(List<ItemIconEntry> entries) throws IOException {
        CheeseItemsConfig config = CheeseItemsMod.INSTANCE.getConfig();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // pack.mcmeta
            addEntry(zos, "pack.mcmeta", PACK_MCMETA.getBytes(StandardCharsets.UTF_8));

            // ── Custom item icons ───────────────────────────────────────────
            if (!entries.isEmpty()) {
                // Group entries by base item to build per-base-item override models
                Map<String, List<ItemIconEntry>> byBaseItem = new LinkedHashMap<>();
                for (ItemIconEntry e : entries) {
                    byBaseItem.computeIfAbsent(e.baseItem, k -> new ArrayList<>()).add(e);
                }

                // For each base item type, create the override model
                for (Map.Entry<String, List<ItemIconEntry>> group : byBaseItem.entrySet()) {
                    String baseItem = group.getKey();
                    List<ItemIconEntry> groupEntries = group.getValue();
                    groupEntries.sort(Comparator.comparingInt(e -> e.customModelData));

                    StringBuilder overrides = new StringBuilder();
                    for (int i = 0; i < groupEntries.size(); i++) {
                        ItemIconEntry e = groupEntries.get(i);
                        if (i > 0) overrides.append(",\n    ");
                        overrides.append(String.format(
                                "{\"predicate\":{\"custom_model_data\":%d},\"model\":\"%s:item/%s\"}",
                                e.customModelData, e.namespace, e.id));
                    }

                    String itemName = baseItem.contains(":") ? baseItem.substring(baseItem.indexOf(':') + 1) : baseItem;
                    String namespace = baseItem.contains(":") ? baseItem.substring(0, baseItem.indexOf(':')) : "minecraft";
                    String texturePath = namespace + ":item/" + itemName;

                    String modelJson = String.format("""
                            {
                              "parent": "minecraft:item/generated",
                              "textures": {
                                "layer0": "%s"
                              },
                              "overrides": [
                                %s
                              ]
                            }""", texturePath, overrides);

                    addEntry(zos, "assets/" + namespace + "/models/item/" + itemName + ".json",
                            modelJson.getBytes(StandardCharsets.UTF_8));
                }

                // For each custom icon item: model JSON + texture PNG
                for (ItemIconEntry e : entries) {
                    String ns = e.namespace;
                    String customModel = String.format("""
                            {
                              "parent": "minecraft:item/generated",
                              "textures": {
                                "layer0": "%s:item/%s"
                              }
                            }""", ns, e.id);
                    addEntry(zos, "assets/" + ns + "/models/item/" + e.id + ".json",
                            customModel.getBytes(StandardCharsets.UTF_8));
                    addEntry(zos, "assets/" + ns + "/textures/item/" + e.id + ".png", e.pngData);
                }
            }

            // ── Endurance bar font ──────────────────────────────────────────
            addEnduranceBarFont(zos, config);
        }
        return baos.toByteArray();
    }

    /**
     * Add endurance bar custom font to the resource pack.
     * 11 PNG images (0-10) mapped to Unicode chars \uE000-\uE00A.
     */
    private void addEnduranceBarFont(ZipOutputStream zos, CheeseItemsConfig config) throws IOException {
        if (!config.hasBarImages()) return;

        // Add bar texture PNGs
        for (int i = 0; i <= 10; i++) {
            byte[] png = config.getBarImage(i);
            if (png != null) {
                addEntry(zos, "assets/cheeseitems/textures/font/bar_" + i + ".png", png);
            }
        }

        // Build font definition JSON
        StringBuilder providers = new StringBuilder();
        for (int i = 0; i <= 10; i++) {
            if (i > 0) providers.append(",\n    ");
            String unicode = String.format("\\u%04x", 0xE000 + i);
            providers.append(String.format(
                    "{\"type\":\"bitmap\",\"file\":\"cheeseitems:font/bar_%d.png\",\"ascent\":-3,\"height\":16,\"chars\":[\"%s\"]}",
                    i, unicode));
        }

        String fontJson = "{\n  \"providers\": [\n    " + providers + "\n  ]\n}";
        addEntry(zos, "assets/minecraft/font/default.json", fontJson.getBytes(StandardCharsets.UTF_8));

        LOGGER.info("[CheeseItems] Endurance bar font added to resource pack (11 levels)");
    }

    /**
     * Add a ZIP entry with a fixed timestamp so the generated ZIP is byte-identical
     * when contents haven't changed. This prevents unnecessary SHA1 changes on restart
     * which would cause clients to re-download the pack and lose their RP settings.
     */
    private void addEntry(ZipOutputStream zos, String path, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        // Fixed epoch: 2025-01-01 00:00:00 — ensures deterministic ZIP output
        entry.setTime(1735689600000L);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    private static String sha1Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private record ItemIconEntry(String id, String namespace, String baseItem, int customModelData, byte[] pngData) {}

    // ── External entries (other mods can register icons) ──────────────────

    private final List<ItemIconEntry> externalEntries = Collections.synchronizedList(new ArrayList<>());

    public void addExternalEntry(String id, String namespace, String baseItem, int customModelData, byte[] pngData) {
        externalEntries.removeIf(e -> e.id.equals(id));
        externalEntries.add(new ItemIconEntry(id, namespace, baseItem, customModelData, pngData));
    }

    public void removeExternalEntries(String prefix) {
        externalEntries.removeIf(e -> e.id.startsWith(prefix));
    }
}
