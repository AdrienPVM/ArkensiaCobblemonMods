package com.cheesestore;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class StoreProduct {

    private String id;
    private String name;
    private String description;
    private int priceCents; // price in euro cents (e.g., 499 = 4.99 EUR)
    private int credits;    // amount of in-game credits delivered (badge on the card)
    private String currency = "eur";
    private String category; // "pack" or "gems"
    private List<String> commands = new ArrayList<>(); // commands with {player} placeholder
    private boolean enabled = true;
    private int sortOrder = 0;
    private boolean hasImage = false;
    private boolean featured = false; // shows "Populaire" badge + animated gradient border

    public StoreProduct() {
        this.id = UUID.randomUUID().toString().substring(0, 8);
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getPriceCents() { return priceCents; }
    public int getCredits() { return credits; }
    public String getCurrency() { return currency; }
    public String getCategory() { return category; }
    public List<String> getCommands() { return commands; }
    public boolean isEnabled() { return enabled; }
    public int getSortOrder() { return sortOrder; }
    public boolean hasImage() { return hasImage; }
    public boolean isFeatured() { return featured; }

    public void setHasImage(boolean hasImage) { this.hasImage = hasImage; }

    // ── JSON ─────────────────────────────────────────────────────────────────

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.addProperty("name", name);
        obj.addProperty("description", description);
        obj.addProperty("priceCents", priceCents);
        obj.addProperty("credits", credits);
        obj.addProperty("currency", currency);
        obj.addProperty("category", category);
        obj.addProperty("enabled", enabled);
        obj.addProperty("featured", featured);
        obj.addProperty("sortOrder", sortOrder);
        obj.addProperty("hasImage", hasImage);
        JsonArray cmds = new JsonArray();
        for (String cmd : commands) cmds.add(cmd);
        obj.add("commands", cmds);
        return obj;
    }

    public JsonObject toPublicJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.addProperty("name", name);
        obj.addProperty("description", description);
        obj.addProperty("priceCents", priceCents);
        obj.addProperty("credits", credits);
        obj.addProperty("currency", currency);
        obj.addProperty("category", category);
        obj.addProperty("featured", featured);
        obj.addProperty("hasImage", hasImage);
        return obj;
    }

    public static StoreProduct fromJson(JsonObject obj) {
        StoreProduct p = new StoreProduct();
        if (obj.has("id")) p.id = obj.get("id").getAsString();
        if (obj.has("name")) p.name = obj.get("name").getAsString();
        if (obj.has("description")) p.description = obj.get("description").getAsString();
        if (obj.has("priceCents")) p.priceCents = obj.get("priceCents").getAsInt();
        if (obj.has("credits")) p.credits = obj.get("credits").getAsInt();
        if (obj.has("currency")) p.currency = obj.get("currency").getAsString();
        if (obj.has("category")) p.category = obj.get("category").getAsString();
        if (obj.has("enabled")) p.enabled = obj.get("enabled").getAsBoolean();
        if (obj.has("featured")) p.featured = obj.get("featured").getAsBoolean();
        if (obj.has("sortOrder")) p.sortOrder = obj.get("sortOrder").getAsInt();
        if (obj.has("hasImage")) p.hasImage = obj.get("hasImage").getAsBoolean();
        if (obj.has("commands")) {
            p.commands.clear();
            for (JsonElement el : obj.getAsJsonArray("commands")) {
                p.commands.add(el.getAsString());
            }
        }
        return p;
    }

    public void mergeFrom(JsonObject obj) {
        if (obj.has("name")) this.name = obj.get("name").getAsString();
        if (obj.has("description")) this.description = obj.get("description").getAsString();
        if (obj.has("priceCents")) this.priceCents = obj.get("priceCents").getAsInt();
        if (obj.has("credits")) this.credits = obj.get("credits").getAsInt();
        if (obj.has("currency")) this.currency = obj.get("currency").getAsString();
        if (obj.has("category")) this.category = obj.get("category").getAsString();
        if (obj.has("enabled")) this.enabled = obj.get("enabled").getAsBoolean();
        if (obj.has("featured")) this.featured = obj.get("featured").getAsBoolean();
        if (obj.has("sortOrder")) this.sortOrder = obj.get("sortOrder").getAsInt();
        if (obj.has("commands")) {
            this.commands.clear();
            for (JsonElement el : obj.getAsJsonArray("commands")) {
                this.commands.add(el.getAsString());
            }
        }
    }
}
