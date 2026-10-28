package com.cheesestore;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class CheeseStoreConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseStore");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path configFile;
    private final Path assetsDir;

    // Stripe configuration
    private String stripePublishableKey = "";
    private String stripeSecretKey = "";
    private String stripeWebhookSecret = "";

    // Google OAuth configuration
    private String googleClientId = "";
    private String googleClientSecret = "";
    private String googleRedirectUri = "";
    private String oauthJwtSecret = "";

    // Store configuration
    private String storeName = "Arkensia";
    private String storeSubtitle = "Boutique Premium";
    private String storeBaseUrl = "";
    private boolean storeEnabled = true;
    private String currencyName = "cr\u00e9dit";

    // Products
    private final List<StoreProduct> products = new CopyOnWriteArrayList<>();

    // Gallery (informational reward catalogue, no checkout)
    private final List<GalleryItem> gallery = new CopyOnWriteArrayList<>();

    public CheeseStoreConfig(Path configDir) {
        this.configFile = configDir.resolve("cheesestore.json");
        this.assetsDir = configDir.resolve("cheesestore_assets");
    }

    // ── Load / Save ──────────────────────────────────────────────────────────

    public void load() {
        if (!Files.exists(configFile)) {
            save(); // Create default config
            return;
        }
        try {
            String json = Files.readString(configFile);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            if (root.has("stripePublishableKey")) stripePublishableKey = root.get("stripePublishableKey").getAsString();
            if (root.has("stripeSecretKey")) stripeSecretKey = root.get("stripeSecretKey").getAsString();
            if (root.has("stripeWebhookSecret")) stripeWebhookSecret = root.get("stripeWebhookSecret").getAsString();
            if (root.has("storeName")) storeName = root.get("storeName").getAsString();
            if (root.has("storeSubtitle")) storeSubtitle = root.get("storeSubtitle").getAsString();
            if (root.has("storeBaseUrl")) storeBaseUrl = root.get("storeBaseUrl").getAsString();
            if (root.has("storeEnabled")) storeEnabled = root.get("storeEnabled").getAsBoolean();
            if (root.has("currencyName")) currencyName = root.get("currencyName").getAsString();
            if (root.has("googleClientId")) googleClientId = root.get("googleClientId").getAsString();
            if (root.has("googleClientSecret")) googleClientSecret = root.get("googleClientSecret").getAsString();
            if (root.has("googleRedirectUri")) googleRedirectUri = root.get("googleRedirectUri").getAsString();
            if (root.has("oauthJwtSecret")) oauthJwtSecret = root.get("oauthJwtSecret").getAsString();

            if (root.has("products")) {
                products.clear();
                for (JsonElement el : root.getAsJsonArray("products")) {
                    products.add(StoreProduct.fromJson(el.getAsJsonObject()));
                }
            }

            if (root.has("gallery")) {
                gallery.clear();
                for (JsonElement el : root.getAsJsonArray("gallery")) {
                    gallery.add(GalleryItem.fromJson(el.getAsJsonObject()));
                }
            }

            LOGGER.info("[CheeseStore] Config loaded ({} products, {} gallery items)", products.size(), gallery.size());
        } catch (Exception e) {
            LOGGER.error("[CheeseStore] Failed to load config: {}", e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("stripePublishableKey", stripePublishableKey);
            root.addProperty("stripeSecretKey", stripeSecretKey);
            root.addProperty("stripeWebhookSecret", stripeWebhookSecret);
            root.addProperty("storeName", storeName);
            root.addProperty("storeSubtitle", storeSubtitle);
            root.addProperty("storeBaseUrl", storeBaseUrl);
            root.addProperty("storeEnabled", storeEnabled);
            root.addProperty("currencyName", currencyName);
            root.addProperty("googleClientId", googleClientId);
            root.addProperty("googleClientSecret", googleClientSecret);
            root.addProperty("googleRedirectUri", googleRedirectUri);
            root.addProperty("oauthJwtSecret", oauthJwtSecret);

            JsonArray arr = new JsonArray();
            for (StoreProduct p : products) arr.add(p.toJson());
            root.add("products", arr);

            JsonArray galleryArr = new JsonArray();
            for (GalleryItem g : gallery) galleryArr.add(g.toJson());
            root.add("gallery", galleryArr);

            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, GSON.toJson(root));
        } catch (IOException e) {
            LOGGER.error("[CheeseStore] Failed to save config: {}", e.getMessage());
        }
    }

    // ── Product CRUD ─────────────────────────────────────────────────────────

    public List<StoreProduct> getProducts() {
        return Collections.unmodifiableList(products);
    }

    public List<StoreProduct> getEnabledProducts() {
        List<StoreProduct> result = new ArrayList<>();
        for (StoreProduct p : products) {
            if (p.isEnabled()) result.add(p);
        }
        result.sort((a, b) -> Integer.compare(a.getSortOrder(), b.getSortOrder()));
        return result;
    }

    public StoreProduct getProduct(String id) {
        return products.stream().filter(p -> p.getId().equals(id)).findFirst().orElse(null);
    }

    public void addProduct(StoreProduct product) {
        products.add(product);
        save();
    }

    public void updateProduct(StoreProduct product) {
        save(); // product is mutated in place via mergeFrom
    }

    public boolean removeProduct(String id) {
        boolean removed = products.removeIf(p -> p.getId().equals(id));
        if (removed) {
            save();
            // Delete image if exists
            try {
                Path img = assetsDir.resolve(id + ".png");
                Files.deleteIfExists(img);
            } catch (IOException e) { /* ignore */ }
        }
        return removed;
    }

    // ── Gallery CRUD ─────────────────────────────────────────────────────────

    public List<GalleryItem> getGalleryItems() {
        return Collections.unmodifiableList(gallery);
    }

    /**
     * Returns the gallery sorted as it should appear publicly:
     * isNew=true items first (most recently created on top), then the rest by sortOrder.
     */
    public List<GalleryItem> getGallerySorted() {
        List<GalleryItem> result = new ArrayList<>(gallery);
        result.sort((a, b) -> {
            if (a.isNew() != b.isNew()) return a.isNew() ? -1 : 1;
            if (a.isNew()) return Long.compare(b.getCreatedAt(), a.getCreatedAt()); // newest first
            return Integer.compare(a.getSortOrder(), b.getSortOrder());
        });
        return result;
    }

    public GalleryItem getGalleryItem(String id) {
        return gallery.stream().filter(g -> g.getId().equals(id)).findFirst().orElse(null);
    }

    public void addGalleryItem(GalleryItem item) {
        gallery.add(item);
        save();
    }

    public void updateGalleryItem(GalleryItem item) {
        save();
    }

    public boolean removeGalleryItem(String id) {
        boolean removed = gallery.removeIf(g -> g.getId().equals(id));
        if (removed) {
            save();
            try {
                Path img = assetsDir.resolve("gallery").resolve(id + ".png");
                Files.deleteIfExists(img);
            } catch (IOException e) { /* ignore */ }
        }
        return removed;
    }

    public void reorderGallery(List<String> orderedIds) {
        for (int i = 0; i < orderedIds.size(); i++) {
            GalleryItem g = getGalleryItem(orderedIds.get(i));
            if (g != null) g.setSortOrder(i);
        }
        save();
    }

    public void saveGalleryImage(String itemId, byte[] imageData) throws IOException {
        Path galleryDir = assetsDir.resolve("gallery");
        Files.createDirectories(galleryDir);
        Files.write(galleryDir.resolve(itemId + ".png"), imageData);
        GalleryItem g = getGalleryItem(itemId);
        if (g != null) {
            g.setHasImage(true);
            save();
        }
    }

    public byte[] getGalleryImage(String itemId) {
        Path img = assetsDir.resolve("gallery").resolve(itemId + ".png");
        if (!Files.exists(img)) return null;
        try {
            return Files.readAllBytes(img);
        } catch (IOException e) {
            return null;
        }
    }

    // ── Image Management ─────────────────────────────────────────────────────

    public void saveProductImage(String productId, byte[] imageData) throws IOException {
        Files.createDirectories(assetsDir);
        Files.write(assetsDir.resolve(productId + ".png"), imageData);
        StoreProduct p = getProduct(productId);
        if (p != null) {
            p.setHasImage(true);
            save();
        }
    }

    public byte[] getProductImage(String productId) {
        Path img = assetsDir.resolve(productId + ".png");
        if (!Files.exists(img)) return null;
        try {
            return Files.readAllBytes(img);
        } catch (IOException e) {
            return null;
        }
    }

    // ── Getters / Setters ────────────────────────────────────────────────────

    public String getStripePublishableKey() { return stripePublishableKey; }
    public String getStripeSecretKey() { return stripeSecretKey; }
    public String getStripeWebhookSecret() { return stripeWebhookSecret; }
    public String getStoreName() { return storeName; }
    public String getStoreSubtitle() { return storeSubtitle; }
    public String getStoreBaseUrl() { return storeBaseUrl; }
    public boolean isStoreEnabled() { return storeEnabled; }
    public Path getAssetsDir() { return assetsDir; }

    public void setStripePublishableKey(String k) { this.stripePublishableKey = k; }
    public void setStripeSecretKey(String k) { this.stripeSecretKey = k; }
    public void setStripeWebhookSecret(String k) { this.stripeWebhookSecret = k; }
    public void setStoreName(String n) { this.storeName = n; }
    public void setStoreSubtitle(String s) { this.storeSubtitle = s; }
    public void setStoreBaseUrl(String u) { this.storeBaseUrl = u; }
    public void setStoreEnabled(boolean e) { this.storeEnabled = e; }

    public String getCurrencyName() { return currencyName != null ? currencyName : "cr\u00e9dit"; }
    public void setCurrencyName(String currencyName) { this.currencyName = currencyName; }

    public String getGoogleClientId()       { return googleClientId; }
    public String getGoogleClientSecret()   { return googleClientSecret; }
    public String getGoogleRedirectUri()    { return googleRedirectUri; }
    public String getOauthJwtSecret() {
        // Auto-generate a strong random secret on first use, persist immediately.
        if (oauthJwtSecret == null || oauthJwtSecret.length() < 32) {
            byte[] bytes = new byte[48];
            new java.security.SecureRandom().nextBytes(bytes);
            oauthJwtSecret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            save();
        }
        return oauthJwtSecret;
    }

    public void setGoogleClientId(String v)     { this.googleClientId = v == null ? "" : v; }
    public void setGoogleClientSecret(String v) { this.googleClientSecret = v == null ? "" : v; }
    public void setGoogleRedirectUri(String v)  { this.googleRedirectUri = v == null ? "" : v; }
}
