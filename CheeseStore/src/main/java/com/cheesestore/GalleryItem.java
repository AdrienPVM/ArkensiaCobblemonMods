package com.cheesestore;

import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * Read-only gallery entry shown on the public storefront below the purchasable packs.
 * Represents a reward that can be obtained via in-game credits — purely informational,
 * no checkout flow attached.
 */
public class GalleryItem {

    private String id;
    private String title;
    private String description = "";
    private int priceCredits;
    private boolean hasImage = false;
    private boolean isNew = false;
    private int sortOrder = 0;
    private long createdAt;

    public GalleryItem() {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.createdAt = System.currentTimeMillis();
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public int getPriceCredits() { return priceCredits; }
    public boolean hasImage() { return hasImage; }
    public boolean isNew() { return isNew; }
    public int getSortOrder() { return sortOrder; }
    public long getCreatedAt() { return createdAt; }

    public void setHasImage(boolean v) { this.hasImage = v; }
    public void setSortOrder(int v) { this.sortOrder = v; }
    public void setNew(boolean v) { this.isNew = v; }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.addProperty("title", title);
        obj.addProperty("description", description);
        obj.addProperty("priceCredits", priceCredits);
        obj.addProperty("hasImage", hasImage);
        obj.addProperty("isNew", isNew);
        obj.addProperty("sortOrder", sortOrder);
        obj.addProperty("createdAt", createdAt);
        return obj;
    }

    public JsonObject toPublicJson() {
        // Same shape — no secrets here, gallery is purely informative.
        return toJson();
    }

    public static GalleryItem fromJson(JsonObject obj) {
        GalleryItem g = new GalleryItem();
        if (obj.has("id")) g.id = obj.get("id").getAsString();
        if (obj.has("title")) g.title = obj.get("title").getAsString();
        if (obj.has("description")) g.description = obj.get("description").getAsString();
        if (obj.has("priceCredits")) g.priceCredits = obj.get("priceCredits").getAsInt();
        if (obj.has("hasImage")) g.hasImage = obj.get("hasImage").getAsBoolean();
        if (obj.has("isNew")) g.isNew = obj.get("isNew").getAsBoolean();
        if (obj.has("sortOrder")) g.sortOrder = obj.get("sortOrder").getAsInt();
        if (obj.has("createdAt")) g.createdAt = obj.get("createdAt").getAsLong();
        return g;
    }

    public void mergeFrom(JsonObject obj) {
        if (obj.has("title")) this.title = obj.get("title").getAsString();
        if (obj.has("description")) this.description = obj.get("description").getAsString();
        if (obj.has("priceCredits")) this.priceCredits = obj.get("priceCredits").getAsInt();
        if (obj.has("isNew")) this.isNew = obj.get("isNew").getAsBoolean();
        if (obj.has("sortOrder")) this.sortOrder = obj.get("sortOrder").getAsInt();
    }
}
