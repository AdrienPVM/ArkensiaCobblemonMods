package com.cheesestore;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A cosmetic purchasable with in-game CREDITS (the {@link WalletManager} "fromage"
 * currency that Stripe packs already top up via /premium give).
 *
 * Persisted in {@code config/cheesestore_cosmetics.json} via {@link CosmeticManager}.
 * Image (if {@link #hasImage}) lives in {@code config/cheesestore_assets/cosmetics/<id>.png}
 * — reusing the same asset pipeline as products/gallery.
 *
 * Mirrors the conventions of {@link StoreProduct} (8-char id, toJson/toPublicJson/
 * fromJson/mergeFrom) and the credit-purchase model of MorphoEntry.
 */
public class CosmeticItem {

    /** Cosmetic categories, surfaced as filter chips on the website. */
    public enum Type { HAT, TRAIL, PARTICLE, ITEM, EFFECT }

    private String id;
    private String name;
    private String description;
    private Type type = Type.ITEM;
    private int priceCredits;       // cost in in-game credits
    private boolean enabled = true;
    private boolean featured = false;
    private int sortOrder = 0;
    private boolean hasImage = false;
    private String itemId = "";     // Minecraft item id for the preview, e.g. "minecraft:diamond_helmet"
    private String modelRef = "";   // optional resourcepack / model reference
    private List<String> applyCommands = new ArrayList<>();  // console commands, {player} placeholder
    private List<String> revokeCommands = new ArrayList<>(); // optional console commands, {player} placeholder

    public CosmeticItem() {
        this.id = UUID.randomUUID().toString().substring(0, 8);
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public String getId() { return id; }
    public String getName() { return name == null ? "" : name; }
    public String getDescription() { return description == null ? "" : description; }
    public Type getType() { return type == null ? Type.ITEM : type; }
    public int getPriceCredits() { return priceCredits; }
    public boolean isEnabled() { return enabled; }
    public boolean isFeatured() { return featured; }
    public int getSortOrder() { return sortOrder; }
    public boolean hasImage() { return hasImage; }
    public String getItemId() { return itemId == null ? "" : itemId; }
    public String getModelRef() { return modelRef == null ? "" : modelRef; }
    public List<String> getApplyCommands() { return applyCommands == null ? new ArrayList<>() : applyCommands; }
    public List<String> getRevokeCommands() { return revokeCommands == null ? new ArrayList<>() : revokeCommands; }

    public void setHasImage(boolean hasImage) { this.hasImage = hasImage; }

    // ── JSON ─────────────────────────────────────────────────────────────────

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.addProperty("name", getName());
        obj.addProperty("description", getDescription());
        obj.addProperty("type", getType().name());
        obj.addProperty("priceCredits", priceCredits);
        obj.addProperty("enabled", enabled);
        obj.addProperty("featured", featured);
        obj.addProperty("sortOrder", sortOrder);
        obj.addProperty("hasImage", hasImage);
        obj.addProperty("itemId", getItemId());
        obj.addProperty("modelRef", getModelRef());
        JsonArray apply = new JsonArray();
        for (String cmd : getApplyCommands()) apply.add(cmd);
        obj.add("applyCommands", apply);
        JsonArray revoke = new JsonArray();
        for (String cmd : getRevokeCommands()) revoke.add(cmd);
        obj.add("revokeCommands", revoke);
        return obj;
    }

    /** Public view for the website — never leaks the apply/revoke commands. */
    public JsonObject toPublicJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.addProperty("name", getName());
        obj.addProperty("description", getDescription());
        obj.addProperty("type", getType().name());
        obj.addProperty("priceCredits", priceCredits);
        obj.addProperty("featured", featured);
        obj.addProperty("hasImage", hasImage);
        obj.addProperty("itemId", getItemId());
        obj.addProperty("modelRef", getModelRef());
        return obj;
    }

    public static CosmeticItem fromJson(JsonObject obj) {
        CosmeticItem c = new CosmeticItem();
        if (obj.has("id")) c.id = obj.get("id").getAsString();
        if (obj.has("name")) c.name = obj.get("name").getAsString();
        if (obj.has("description")) c.description = obj.get("description").getAsString();
        if (obj.has("type")) c.type = parseType(obj.get("type").getAsString());
        if (obj.has("priceCredits")) c.priceCredits = Math.max(0, obj.get("priceCredits").getAsInt());
        if (obj.has("enabled")) c.enabled = obj.get("enabled").getAsBoolean();
        if (obj.has("featured")) c.featured = obj.get("featured").getAsBoolean();
        if (obj.has("sortOrder")) c.sortOrder = obj.get("sortOrder").getAsInt();
        if (obj.has("hasImage")) c.hasImage = obj.get("hasImage").getAsBoolean();
        if (obj.has("itemId")) c.itemId = obj.get("itemId").getAsString();
        if (obj.has("modelRef")) c.modelRef = obj.get("modelRef").getAsString();
        if (obj.has("applyCommands")) {
            c.applyCommands = new ArrayList<>();
            for (JsonElement el : obj.getAsJsonArray("applyCommands")) c.applyCommands.add(el.getAsString());
        }
        if (obj.has("revokeCommands")) {
            c.revokeCommands = new ArrayList<>();
            for (JsonElement el : obj.getAsJsonArray("revokeCommands")) c.revokeCommands.add(el.getAsString());
        }
        return c;
    }

    /** Apply non-null fields from a partial-update payload (PUT/upsert semantics). */
    public void mergeFrom(JsonObject obj) {
        if (obj.has("name")) this.name = obj.get("name").getAsString();
        if (obj.has("description")) this.description = obj.get("description").getAsString();
        if (obj.has("type")) this.type = parseType(obj.get("type").getAsString());
        if (obj.has("priceCredits")) this.priceCredits = Math.max(0, obj.get("priceCredits").getAsInt());
        if (obj.has("enabled")) this.enabled = obj.get("enabled").getAsBoolean();
        if (obj.has("featured")) this.featured = obj.get("featured").getAsBoolean();
        if (obj.has("sortOrder")) this.sortOrder = obj.get("sortOrder").getAsInt();
        if (obj.has("itemId")) this.itemId = obj.get("itemId").getAsString();
        if (obj.has("modelRef")) this.modelRef = obj.get("modelRef").getAsString();
        if (obj.has("applyCommands")) {
            this.applyCommands = new ArrayList<>();
            for (JsonElement el : obj.getAsJsonArray("applyCommands")) this.applyCommands.add(el.getAsString());
        }
        if (obj.has("revokeCommands")) {
            this.revokeCommands = new ArrayList<>();
            for (JsonElement el : obj.getAsJsonArray("revokeCommands")) this.revokeCommands.add(el.getAsString());
        }
    }

    private static Type parseType(String raw) {
        if (raw == null) return Type.ITEM;
        try {
            return Type.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Type.ITEM;
        }
    }
}
