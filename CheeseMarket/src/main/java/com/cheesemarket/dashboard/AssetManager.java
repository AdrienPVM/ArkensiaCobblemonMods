package com.cheesemarket.dashboard;

import com.cheesemarket.data.ShopLayout;
import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Manages custom shop assets (uploaded images) and layout templates.
 * Storage:
 *   world/data/cheesemarket/assets/{shopId}/{name}.png
 *   world/data/cheesemarket/templates/{name}.json
 */
public class AssetManager {

    private static AssetManager INSTANCE;

    private final Path assetsDir;
    private final Path templatesDir;

    private static final int MAX_ASSET_SIZE = 2 * 1024 * 1024; // 2 MB
    private static final int MAX_ASSETS_PER_SHOP = 10;
    private static final int MAX_TEMPLATES = 50;
    private static final int MAX_NAME_LEN = 128;

    // PNG magic bytes
    private static final byte[] PNG_HEADER = { (byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };

    private AssetManager(Path dataDir) {
        this.assetsDir = dataDir.resolve("cheesemarket").resolve("assets");
        this.templatesDir = dataDir.resolve("cheesemarket").resolve("templates");
        try {
            Files.createDirectories(assetsDir);
            Files.createDirectories(templatesDir);
        } catch (IOException ignored) {}
    }

    public static AssetManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new AssetManager(
                FabricLoader.getInstance().getConfigDir().getParent().resolve("world").resolve("data")
            );
        }
        return INSTANCE;
    }

    public static void init(Path worldDataDir) {
        INSTANCE = new AssetManager(worldDataDir);
    }

    // ── Asset management ────────────────────────────────────────────────────

    /** Upload a PNG asset. Returns null on success, error message on failure. */
    public String uploadAsset(String shopId, String name, byte[] data) {
        if (!isValidAssetName(name)) return "Invalid asset name";
        if (data == null || data.length == 0) return "Empty data";
        if (data.length > MAX_ASSET_SIZE) return "File too large (max 2 MB)";
        if (!isPng(data)) return "Not a valid PNG file";

        Path shopDir = assetsDir.resolve(shopId);
        try { Files.createDirectories(shopDir); } catch (IOException e) { return "Cannot create directory"; }

        // Check asset count
        long count = countAssets(shopId);
        Path target = shopDir.resolve(name + ".png");
        if (!Files.exists(target) && count >= MAX_ASSETS_PER_SHOP) {
            return "Maximum " + MAX_ASSETS_PER_SHOP + " assets per shop";
        }

        try {
            Files.write(target, data);
            return null; // success
        } catch (IOException e) {
            return "Failed to write: " + e.getMessage();
        }
    }

    /** List asset names (without .png extension) for a shop. */
    public List<String> listAssets(String shopId) {
        List<String> result = new ArrayList<>();
        Path shopDir = assetsDir.resolve(shopId);
        if (!Files.isDirectory(shopDir)) return result;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(shopDir, "*.png")) {
            for (Path f : stream) {
                String fn = f.getFileName().toString();
                result.add(fn.substring(0, fn.length() - 4)); // strip .png
            }
        } catch (IOException ignored) {}
        return result;
    }

    /** Get the raw bytes of an asset. Returns null if not found. */
    public byte[] getAsset(String shopId, String name) {
        if (!isValidAssetName(name)) return null;
        Path file = assetsDir.resolve(shopId).resolve(name + ".png");
        if (!Files.exists(file)) return null;
        try { return Files.readAllBytes(file); }
        catch (IOException e) { return null; }
    }

    /** Delete an asset. Returns true if deleted. */
    public boolean deleteAsset(String shopId, String name) {
        if (!isValidAssetName(name)) return false;
        Path file = assetsDir.resolve(shopId).resolve(name + ".png");
        try { return Files.deleteIfExists(file); }
        catch (IOException e) { return false; }
    }

    /** Delete all assets for a shop (when shop is deleted). */
    public void deleteShopAssets(String shopId) {
        Path shopDir = assetsDir.resolve(shopId);
        if (!Files.isDirectory(shopDir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(shopDir)) {
            for (Path f : stream) Files.deleteIfExists(f);
        } catch (IOException ignored) {}
        try { Files.deleteIfExists(shopDir); } catch (IOException ignored) {}
    }

    private long countAssets(String shopId) {
        Path shopDir = assetsDir.resolve(shopId);
        if (!Files.isDirectory(shopDir)) return 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(shopDir, "*.png")) {
            long c = 0;
            for (Path ignored : stream) c++;
            return c;
        } catch (IOException e) { return 0; }
    }

    // ── Template management ─────────────────────────────────────────────────

    /** Save a layout template. Overwrites if exists. */
    public String saveTemplate(String name, ShopLayout layout) {
        if (!isValidTemplateName(name)) return "Invalid template name";

        // Check count (only if new)
        Path target = templatesDir.resolve(name + ".json");
        if (!Files.exists(target)) {
            long count = countTemplates();
            if (count >= MAX_TEMPLATES) return "Maximum " + MAX_TEMPLATES + " templates";
        }

        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(target, gson.toJson(layout.toJson()));
            return null; // success
        } catch (IOException e) {
            return "Failed to save: " + e.getMessage();
        }
    }

    /** Load a template by name. Returns null if not found. */
    public ShopLayout loadTemplate(String name) {
        if (!isValidTemplateName(name)) return null;
        Path file = templatesDir.resolve(name + ".json");
        if (!Files.exists(file)) return null;
        try {
            String json = Files.readString(file);
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            return ShopLayout.fromJson(o);
        } catch (Exception e) { return null; }
    }

    /** List all template names. */
    public List<String> listTemplates() {
        List<String> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(templatesDir, "*.json")) {
            for (Path f : stream) {
                String fn = f.getFileName().toString();
                result.add(fn.substring(0, fn.length() - 5)); // strip .json
            }
        } catch (IOException ignored) {}
        return result;
    }

    /** Delete a template. */
    public boolean deleteTemplate(String name) {
        if (!isValidTemplateName(name)) return false;
        Path file = templatesDir.resolve(name + ".json");
        try { return Files.deleteIfExists(file); }
        catch (IOException e) { return false; }
    }

    private long countTemplates() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(templatesDir, "*.json")) {
            long c = 0;
            for (Path ignored : stream) c++;
            return c;
        } catch (IOException e) { return 0; }
    }

    // ── Validation ──────────────────────────────────────────────────────────

    private static boolean isPng(byte[] data) {
        if (data.length < 8) return false;
        for (int i = 0; i < 8; i++) {
            if (data[i] != PNG_HEADER[i]) return false;
        }
        return true;
    }

    private static boolean isValidAssetName(String name) {
        return name != null && !name.isEmpty() && name.length() < MAX_NAME_LEN
                && name.matches("[a-zA-Z0-9_-]+")
                && !name.contains("..");
    }

    private static boolean isValidTemplateName(String name) {
        return name != null && !name.isEmpty() && name.length() < MAX_NAME_LEN
                && name.matches("[a-zA-Z0-9_-]+")
                && !name.contains("..");
    }
}
