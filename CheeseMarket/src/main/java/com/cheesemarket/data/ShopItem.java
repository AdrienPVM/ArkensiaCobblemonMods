package com.cheesemarket.data;

import com.google.gson.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A single purchasable item in a ShopSet.
 *
 * - displayItem: the item shown in the shop GUI (its icon)
 * - displayName: optional custom name override
 * - outputs: what the player receives (one or more ItemEntry)
 * - costs: what the player pays (one or more ItemEntry)
 * - stock: stock/limit configuration
 * - enabled: whether this item appears in the shop
 * - sortOrder: manual sort position in the set
 */
public class ShopItem {

    private String          id;
    private String          displayItem;   // e.g. "minecraft:diamond_sword"
    private String          displayName;   // Custom name, empty = use item's default name
    private List<ItemEntry>    outputs;
    private List<ItemEntry>    costs;
    private List<CommandEntry> commands;
    private StockConfig        stock;
    private boolean            enabled;
    private int                sortOrder;

    public ShopItem(String id) {
        this.id          = id;
        this.displayItem = "minecraft:paper";
        this.displayName = "";
        this.outputs     = new ArrayList<>();
        this.costs       = new ArrayList<>();
        this.commands    = new ArrayList<>();
        this.stock       = new StockConfig();
        this.enabled     = true;
        this.sortOrder   = 0;
    }

    // ── Serialization ──────────────────────────────────────────────────────────

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("displayItem", displayItem);
        o.addProperty("displayName", displayName);
        JsonArray outArr = new JsonArray();
        for (ItemEntry e : outputs) outArr.add(e.toJson());
        o.add("outputs", outArr);
        JsonArray costArr = new JsonArray();
        for (ItemEntry e : costs) costArr.add(e.toJson());
        o.add("costs", costArr);
        JsonArray cmdArr = new JsonArray();
        for (CommandEntry c : commands) cmdArr.add(c.toJson());
        o.add("commands", cmdArr);
        o.add("stock", stock.toJson());
        o.addProperty("enabled", enabled);
        o.addProperty("sortOrder", sortOrder);
        return o;
    }

    public static ShopItem fromJson(JsonObject o) {
        String id = o.has("id") ? o.get("id").getAsString() : UUID.randomUUID().toString().substring(0, 8);
        ShopItem item = new ShopItem(id);
        if (o.has("displayItem")) item.displayItem = o.get("displayItem").getAsString();
        if (o.has("displayName")) item.displayName = o.get("displayName").getAsString();
        if (o.has("outputs")) {
            for (JsonElement e : o.getAsJsonArray("outputs"))
                item.outputs.add(ItemEntry.fromJson(e.getAsJsonObject()));
        }
        if (o.has("costs")) {
            for (JsonElement e : o.getAsJsonArray("costs"))
                item.costs.add(ItemEntry.fromJson(e.getAsJsonObject()));
        }
        if (o.has("commands")) {
            for (JsonElement e : o.getAsJsonArray("commands"))
                item.commands.add(CommandEntry.fromJson(e.getAsJsonObject()));
        }
        if (o.has("stock")) item.stock = StockConfig.fromJson(o.getAsJsonObject("stock"));
        if (o.has("enabled")) item.enabled = o.get("enabled").getAsBoolean();
        if (o.has("sortOrder")) item.sortOrder = o.get("sortOrder").getAsInt();
        return item;
    }

    // ── Display helpers ────────────────────────────────────────────────────────

    /**
     * The item ID used as icon.
     * If displayItem is blank or not set, falls back to the first output's itemId.
     */
    public String getEffectiveDisplayItem() {
        if (displayItem != null && !displayItem.isBlank() && !displayItem.equals("minecraft:paper"))
            return displayItem;
        if (!outputs.isEmpty())
            return outputs.get(0).getItemId();
        return "minecraft:paper";
    }

    /** The name shown in the GUI (custom or derived from item id). */
    public String getEffectiveName() {
        if (displayName != null && !displayName.isBlank()) return displayName;
        if (displayItem != null && displayItem.contains(":"))
            return displayItem.substring(displayItem.indexOf(':') + 1).replace('_', ' ');
        return displayItem;
    }

    /** Short summary of outputs for list display. */
    public String outputsSummary() {
        if (outputs.isEmpty()) return "—";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < outputs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(outputs.get(i).display());
        }
        return sb.toString();
    }

    /** Short summary of commands for list display. */
    public String commandsSummary() {
        if (commands.isEmpty()) return "\u2014";
        return commands.size() + " commande(s)";
    }

    /** Short summary of costs for list display. */
    public String costsSummary() {
        if (costs.isEmpty()) return "Gratuit";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < costs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(costs.get(i).display());
        }
        return sb.toString();
    }

    // ── Getters / Setters ──────────────────────────────────────────────────────

    public String          getId()                       { return id; }
    public String          getDisplayItem()              { return displayItem; }
    public void            setDisplayItem(String v)      { this.displayItem = v; }
    public String          getDisplayName()              { return displayName; }
    public void            setDisplayName(String v)      { this.displayName = v; }
    public List<ItemEntry>    getOutputs()                { return outputs; }
    public List<ItemEntry>    getCosts()                  { return costs; }
    public List<CommandEntry> getCommands()               { return commands; }
    public StockConfig     getStock()                    { return stock; }
    public boolean         isEnabled()                   { return enabled; }
    public void            setEnabled(boolean v)         { this.enabled = v; }
    public int             getSortOrder()                { return sortOrder; }
    public void            setSortOrder(int v)           { this.sortOrder = v; }
}
