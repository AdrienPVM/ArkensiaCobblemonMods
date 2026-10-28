package com.cheesemarket.data;

import com.google.gson.JsonObject;

/**
 * Represents a single item + quantity in a shop item's output or cost list.
 * Example: { itemId: "minecraft:diamond", count: 3 }
 */
public class ItemEntry {

    private String itemId;
    private int count;
    private String components; // Optional: MC 1.21.1 component bracket string, e.g. "[custom_data={...}]"

    public ItemEntry(String itemId, int count) {
        this.itemId = itemId;
        this.count = Math.max(1, count);
    }

    public ItemEntry(String itemId, int count, String components) {
        this.itemId = itemId;
        this.count = Math.max(1, count);
        this.components = components;
    }

    // ── Serialization ──────────────────────────────────────────────────────────

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("itemId", itemId);
        o.addProperty("count", count);
        if (components != null && !components.isEmpty()) {
            o.addProperty("components", components);
        }
        return o;
    }

    public static ItemEntry fromJson(JsonObject o) {
        String id = o.has("itemId") ? o.get("itemId").getAsString() : "minecraft:air";
        int c = o.has("count") ? o.get("count").getAsInt() : 1;
        String comp = o.has("components") ? o.get("components").getAsString() : null;
        return new ItemEntry(id, c, comp);
    }

    // ── Getters / Setters ──────────────────────────────────────────────────────

    public String getItemId()              { return itemId; }
    public void   setItemId(String itemId) { this.itemId = itemId; }
    public int    getCount()               { return count; }
    public void   setCount(int count)      { this.count = Math.max(1, count); }
    public String getComponents()          { return components; }
    public void   setComponents(String c)  { this.components = c; }
    public boolean hasComponents()         { return components != null && !components.isEmpty(); }

    /** Display string for GUI. e.g. "minecraft:diamond x3" */
    public String display() {
        if ("cheesestore:fromage".equals(itemId)) {
            return "\uD83E\uDDC0 " + count + " Cr\u00e9dit";
        }
        String short_id = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        return short_id + (count > 1 ? " x" + count : "");
    }

    @Override
    public String toString() { return itemId + " x" + count; }
}
