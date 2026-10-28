package com.cheesemarket.data;

import com.google.gson.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A named collection of ShopItems with a rotation/schedule config.
 *
 * Multiple sets can be active simultaneously in the same shop.
 * The shop's displayMode determines whether active sets each get
 * their own tab or are merged into a flat paginated list.
 */
public class ShopSet {

    private String         id;
    private String         name;
    private RotationConfig rotation;
    private List<ShopItem> items;
    private int            sortOrder;

    public ShopSet(String id) {
        this.id        = id;
        this.name      = "Nouveau set";
        this.rotation  = new RotationConfig();
        this.items     = new ArrayList<>();
        this.sortOrder = 0;
    }

    // ── Serialization ──────────────────────────────────────────────────────────

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.add("rotation", rotation.toJson());
        JsonArray arr = new JsonArray();
        for (ShopItem item : items) arr.add(item.toJson());
        o.add("items", arr);
        o.addProperty("sortOrder", sortOrder);
        return o;
    }

    public static ShopSet fromJson(JsonObject o) {
        String id = o.has("id") ? o.get("id").getAsString() : UUID.randomUUID().toString().substring(0, 8);
        ShopSet set = new ShopSet(id);
        if (o.has("name"))      set.name = o.get("name").getAsString();
        if (o.has("rotation"))  set.rotation = RotationConfig.fromJson(o.getAsJsonObject("rotation"));
        if (o.has("items")) {
            for (JsonElement e : o.getAsJsonArray("items"))
                set.items.add(ShopItem.fromJson(e.getAsJsonObject()));
        }
        if (o.has("sortOrder")) set.sortOrder = o.get("sortOrder").getAsInt();
        return set;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    public boolean isCurrentlyActive() { return rotation.isCurrentlyActive(); }

    public ShopItem findItem(String itemId) {
        return items.stream().filter(i -> i.getId().equals(itemId)).findFirst().orElse(null);
    }

    public ShopItem addItem() {
        String newId = UUID.randomUUID().toString().substring(0, 8);
        ShopItem item = new ShopItem(newId);
        item.setSortOrder(items.size());
        items.add(item);
        return item;
    }

    public boolean removeItem(String itemId) {
        return items.removeIf(i -> i.getId().equals(itemId));
    }

    /** Returns only enabled items, sorted by sortOrder. */
    public List<ShopItem> getEnabledItems() {
        return items.stream()
                .filter(ShopItem::isEnabled)
                .sorted((a, b) -> Integer.compare(a.getSortOrder(), b.getSortOrder()))
                .toList();
    }

    // ── Getters / Setters ──────────────────────────────────────────────────────

    public String         getId()                  { return id; }
    public String         getName()                { return name; }
    public void           setName(String v)        { this.name = v; }
    public RotationConfig getRotation()            { return rotation; }
    public List<ShopItem> getItems()               { return items; }
    public int            getSortOrder()           { return sortOrder; }
    public void           setSortOrder(int v)      { this.sortOrder = v; }
}
