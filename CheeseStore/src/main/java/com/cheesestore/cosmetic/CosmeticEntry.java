package com.cheesestore.cosmetic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A playable cosmetic in the {@code com.cheesestore.cosmetic} system (distinct from the
 * legacy website-only {@link com.cheesestore.CosmeticItem} which drives the product cards).
 *
 * <p>This is the model shared by ALL cosmetic sub-systems (trails, kill/death effects,
 * chat tags, name colours, pets) and is the contract every agent depends on. It is a plain
 * POJO serialised with Gson by {@link CosmeticManager} into
 * {@code config/cheesestore_cosmetics.json} (the catalogue).
 *
 * <p>Category-specific tuning lives in {@link #params}:
 * <ul>
 *   <li>TRAIL       — particle (vanilla particle id), shape (trail|halo|spiral|orbit), density (int)</li>
 *   <li>KILL_EFFECT — effect (firework|lightning|explosion|souls|totem|blood), sound (opt), particle (opt)</li>
 *   <li>DEATH_EFFECT— effect (...), particle (opt), sound (opt)</li>
 *   <li>CHAT_TAG    — tag (prefix e.g. "✦ VIP"), color (minecraft colour)</li>
 *   <li>NAME_COLOR  — color (minecraft colour)</li>
 *   <li>PET         — mob (entity id e.g. "minecraft:allay"), petName (opt), baby (true|false)</li>
 *   <li>MOUNT       — mob (rideable entity id e.g. "minecraft:horse"), saddle (true|false),
 *                     variant (opt, e.g. "white"/"black" for horses), baby (opt true|false).
 *                     A MOUNT is summoned/dismissed on demand (cosmetic-only, no stats); equipping
 *                     one only selects it, it is spawned by an explicit summon — see MountManager.</li>
 *   <li>CAPE        — texture (the {@code capeId}, e.g. "cape_phenix"). Equipping selects ONE cape
 *                     per player; the actual cape is rendered CLIENT-side by the factionwarhud
 *                     companion mod from the texture
 *                     {@code factionwarhud:textures/entity/cape/<capeId>.png} (64x32 vanilla cape
 *                     format). The server only broadcasts the uuid→capeId map over the
 *                     {@code cheesestore:capes} payload — cosmetic only, no stats.</li>
 * </ul>
 */
public class CosmeticEntry {

    /** The cosmetic categories. Stored as a String in the POJO per the contract. */
    public enum Category {
        TRAIL, KILL_EFFECT, DEATH_EFFECT, CHAT_TAG, NAME_COLOR, PET, MOUNT, CAPE;

        public static boolean isValid(String raw) {
            if (raw == null) return false;
            try { Category.valueOf(raw.trim().toUpperCase(Locale.ROOT)); return true; }
            catch (IllegalArgumentException ex) { return false; }
        }
    }

    private String id;
    private String title;
    private String description;
    /** One of {@link Category} — kept as String for the contract (POJO + Gson). */
    private String category;
    /** Vanilla item id used as the preview/icon, e.g. "minecraft:blaze_powder". */
    private String icon;
    private int price;
    private boolean isNew;
    private int sortOrder;
    private boolean enabled = true;
    private Map<String, String> params = new LinkedHashMap<>();

    public CosmeticEntry() {}

    public CosmeticEntry(String id, String title, String description, String category,
                         String icon, int price) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.category = category;
        this.icon = icon;
        this.price = price;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public String getId() { return id == null ? "" : id; }
    public String getTitle() { return title == null ? "" : title; }
    public String getDescription() { return description == null ? "" : description; }
    public String getCategory() { return category == null ? "" : category; }
    public String getIcon() { return icon == null ? "minecraft:barrier" : icon; }
    public int getPrice() { return price; }
    public boolean isNew() { return isNew; }
    public int getSortOrder() { return sortOrder; }
    public boolean isEnabled() { return enabled; }
    public Map<String, String> getParams() {
        if (params == null) params = new LinkedHashMap<>();
        return params;
    }

    /** Convenience: typed {@link Category} or {@code null} if the stored string is invalid. */
    public Category getCategoryEnum() {
        try { return Category.valueOf(getCategory().trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { return null; }
    }

    /** Null-safe param accessor with a default. */
    public String param(String key, String def) {
        String v = getParams().get(key);
        return v == null ? def : v;
    }

    public int paramInt(String key, int def) {
        try { return Integer.parseInt(getParams().get(key)); }
        catch (Exception ex) { return def; }
    }

    public boolean paramBool(String key, boolean def) {
        String v = getParams().get(key);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    // ── Setters ──────────────────────────────────────────────────────────────

    public void setId(String id) { this.id = id; }
    public void setTitle(String title) { this.title = title; }
    public void setDescription(String description) { this.description = description; }
    public void setCategory(String category) { this.category = category; }
    public void setIcon(String icon) { this.icon = icon; }
    public void setPrice(int price) { this.price = Math.max(0, price); }
    public void setNew(boolean isNew) { this.isNew = isNew; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setParams(Map<String, String> params) {
        this.params = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
    }
    public CosmeticEntry put(String key, String value) {
        getParams().put(key, value);
        return this;
    }

    // ── JSON (Gson handles this automatically, but explicit (de)serialisation
    //     keeps us robust to malformed admin payloads and mirrors StoreProduct) ──

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", getId());
        obj.addProperty("title", getTitle());
        obj.addProperty("description", getDescription());
        obj.addProperty("category", getCategory());
        obj.addProperty("icon", getIcon());
        obj.addProperty("price", price);
        obj.addProperty("isNew", isNew);
        obj.addProperty("sortOrder", sortOrder);
        obj.addProperty("enabled", enabled);
        JsonObject p = new JsonObject();
        for (Map.Entry<String, String> e : getParams().entrySet()) {
            if (e.getKey() != null && e.getValue() != null) p.addProperty(e.getKey(), e.getValue());
        }
        obj.add("params", p);
        return obj;
    }

    public static CosmeticEntry fromJson(JsonObject obj) {
        CosmeticEntry c = new CosmeticEntry();
        if (obj.has("id")) c.id = obj.get("id").getAsString();
        if (obj.has("title")) c.title = obj.get("title").getAsString();
        if (obj.has("description")) c.description = obj.get("description").getAsString();
        if (obj.has("category")) c.category = obj.get("category").getAsString();
        if (obj.has("icon")) c.icon = obj.get("icon").getAsString();
        if (obj.has("price")) c.price = Math.max(0, obj.get("price").getAsInt());
        if (obj.has("isNew")) c.isNew = obj.get("isNew").getAsBoolean();
        if (obj.has("sortOrder")) c.sortOrder = obj.get("sortOrder").getAsInt();
        if (obj.has("enabled")) c.enabled = obj.get("enabled").getAsBoolean();
        if (obj.has("params") && obj.get("params").isJsonObject()) {
            c.params = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("params").entrySet()) {
                if (e.getValue() != null && e.getValue().isJsonPrimitive()) {
                    c.params.put(e.getKey(), e.getValue().getAsString());
                }
            }
        }
        return c;
    }

    /** Apply non-null fields from a partial admin payload (PUT/upsert semantics). */
    public void mergeFrom(JsonObject obj) {
        if (obj.has("title")) this.title = obj.get("title").getAsString();
        if (obj.has("description")) this.description = obj.get("description").getAsString();
        if (obj.has("category")) this.category = obj.get("category").getAsString();
        if (obj.has("icon")) this.icon = obj.get("icon").getAsString();
        if (obj.has("price")) this.price = Math.max(0, obj.get("price").getAsInt());
        if (obj.has("isNew")) this.isNew = obj.get("isNew").getAsBoolean();
        if (obj.has("sortOrder")) this.sortOrder = obj.get("sortOrder").getAsInt();
        if (obj.has("enabled")) this.enabled = obj.get("enabled").getAsBoolean();
        if (obj.has("params") && obj.get("params").isJsonObject()) {
            this.params = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("params").entrySet()) {
                if (e.getValue() != null && e.getValue().isJsonPrimitive()) {
                    this.params.put(e.getKey(), e.getValue().getAsString());
                }
            }
        }
    }
}
