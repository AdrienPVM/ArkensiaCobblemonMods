package com.cheesemarket.data;

import com.google.gson.*;

import java.util.*;

/**
 * Top-level shop object.
 *
 * Display modes:
 *  TABS     — each active set gets its own tab (configurable label = set name)
 *  FLAT     — all items from all active sets merged into one paginated list
 *  FEATURED — first active set shown with larger tiles, rest below in a flat list
 *
 * Tab visibility: inactive sets are always hidden. Active sets with exhausted
 * stock are still shown (items appear greyed out / unavailable).
 */
public class ShopData {

    public enum DisplayMode {
        TABS, FLAT, FEATURED
    }

    private String      id;
    private String      name;
    private String      description;
    private DisplayMode displayMode;
    private boolean     showWelcomeMessage;
    private String      welcomeMessage;
    private String      titleColor;
    private String      bgColor;
    private String      borderColor;
    private String      accentColor;
    private String      btnBuyColor;     // hex RRGGBB, buy/confirm button bg
    private String      btnBuyTextColor; // hex RRGGBB, buy/confirm button text
    private String      btnCloseColor;   // hex RRGGBB, close/cancel button bg
    private String      btnCloseTextColor; // hex RRGGBB, close/cancel button text
    private ShopLayout   layout;  // nullable — null = default hardcoded layout
    private List<ShopSet> sets;
    /**
     * When true, the shop ignores its own configured sets/items and is auto-populated
     * at view-build time from the central CheeseItems Morpho Catalog (entries with
     * availableInNpcShop=true). Used by the Métamorph NPC shop so admins manage all
     * morphos from a single dashboard instead of duplicating them per shop.
     */
    private boolean      morphoCatalog;

    public ShopData(String id) {
        this.id                 = id;
        this.name               = "Nouveau shop";
        this.description        = "";
        this.displayMode        = DisplayMode.TABS;
        this.showWelcomeMessage = false;
        this.welcomeMessage     = "Bienvenue dans le shop !";
        this.titleColor         = "gold";
        this.bgColor            = "1A1A2E";
        this.borderColor        = "2A5A8A";
        this.accentColor        = "4AC8EA";
        this.btnBuyColor        = "1A5C2A";
        this.btnBuyTextColor    = "FFFFFF";
        this.btnCloseColor      = "1E3A5F";
        this.btnCloseTextColor  = "CCCCCC";
        this.sets               = new ArrayList<>();
    }

    // ── Serialization ──────────────────────────────────────────────────────────

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("description", description);
        o.addProperty("displayMode", displayMode.name());
        o.addProperty("showWelcomeMessage", showWelcomeMessage);
        o.addProperty("welcomeMessage", welcomeMessage);
        o.addProperty("titleColor", titleColor);
        o.addProperty("bgColor", bgColor);
        o.addProperty("borderColor", borderColor);
        o.addProperty("accentColor", accentColor);
        o.addProperty("btnBuyColor", btnBuyColor);
        o.addProperty("btnBuyTextColor", btnBuyTextColor);
        o.addProperty("btnCloseColor", btnCloseColor);
        o.addProperty("btnCloseTextColor", btnCloseTextColor);
        if (layout != null) o.add("layout", layout.toJson());
        o.addProperty("morphoCatalog", morphoCatalog);
        JsonArray arr = new JsonArray();
        for (ShopSet set : sets) arr.add(set.toJson());
        o.add("sets", arr);
        return o;
    }

    public static ShopData fromJson(JsonObject o) {
        String id = o.has("id") ? o.get("id").getAsString() : UUID.randomUUID().toString().substring(0, 8);
        ShopData shop = new ShopData(id);
        if (o.has("name"))               shop.name = o.get("name").getAsString();
        if (o.has("description"))        shop.description = o.get("description").getAsString();
        if (o.has("displayMode"))        shop.displayMode = safeMode(o.get("displayMode").getAsString());
        if (o.has("showWelcomeMessage")) shop.showWelcomeMessage = o.get("showWelcomeMessage").getAsBoolean();
        if (o.has("welcomeMessage"))     shop.welcomeMessage = o.get("welcomeMessage").getAsString();
        if (o.has("titleColor"))         shop.titleColor = o.get("titleColor").getAsString();
        if (o.has("bgColor"))           shop.bgColor = o.get("bgColor").getAsString();
        if (o.has("borderColor"))       shop.borderColor = o.get("borderColor").getAsString();
        if (o.has("accentColor"))       shop.accentColor = o.get("accentColor").getAsString();
        if (o.has("btnBuyColor"))       shop.btnBuyColor = o.get("btnBuyColor").getAsString();
        if (o.has("btnBuyTextColor"))   shop.btnBuyTextColor = o.get("btnBuyTextColor").getAsString();
        if (o.has("btnCloseColor"))     shop.btnCloseColor = o.get("btnCloseColor").getAsString();
        if (o.has("btnCloseTextColor")) shop.btnCloseTextColor = o.get("btnCloseTextColor").getAsString();
        if (o.has("layout") && o.get("layout").isJsonObject()) {
            shop.layout = ShopLayout.fromJson(o.getAsJsonObject("layout"));
        }
        if (o.has("morphoCatalog")) shop.morphoCatalog = o.get("morphoCatalog").getAsBoolean();
        if (o.has("sets")) {
            for (JsonElement e : o.getAsJsonArray("sets"))
                shop.sets.add(ShopSet.fromJson(e.getAsJsonObject()));
        }
        return shop;
    }

    private static DisplayMode safeMode(String s) {
        try { return DisplayMode.valueOf(s); } catch (Exception e) { return DisplayMode.TABS; }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    public ShopSet findSet(String setId) {
        return sets.stream().filter(s -> s.getId().equals(setId)).findFirst().orElse(null);
    }

    public ShopSet addSet() {
        String newId = UUID.randomUUID().toString().substring(0, 8);
        ShopSet set = new ShopSet(newId);
        set.setSortOrder(sets.size());
        sets.add(set);
        return set;
    }

    public boolean removeSet(String setId) {
        return sets.removeIf(s -> s.getId().equals(setId));
    }

    /** Returns only currently-active sets, sorted by sortOrder. */
    public List<ShopSet> getActiveSets() {
        return sets.stream()
                .filter(ShopSet::isCurrentlyActive)
                .sorted((a, b) -> Integer.compare(a.getSortOrder(), b.getSortOrder()))
                .toList();
    }

    public String displayModeLabel() {
        return switch (displayMode) {
            case TABS     -> "Onglets";
            case FLAT     -> "Liste plate";
            case FEATURED -> "Mise en avant";
        };
    }

    // ── Getters / Setters ──────────────────────────────────────────────────────

    public String      getId()                          { return id; }
    public String      getName()                        { return name; }
    public void        setName(String v)                { this.name = v; }
    public String      getDescription()                 { return description; }
    public void        setDescription(String v)         { this.description = v; }
    public DisplayMode getDisplayMode()                 { return displayMode; }
    public void        setDisplayMode(DisplayMode v)    { this.displayMode = v; }
    public boolean     isShowWelcomeMessage()           { return showWelcomeMessage; }
    public void        setShowWelcomeMessage(boolean v) { this.showWelcomeMessage = v; }
    public String      getWelcomeMessage()              { return welcomeMessage; }
    public void        setWelcomeMessage(String v)      { this.welcomeMessage = v; }
    public String      getTitleColor()                  { return titleColor; }
    public void        setTitleColor(String v)          { this.titleColor = v; }
    public String      getBgColor()                    { return bgColor; }
    public void        setBgColor(String v)             { this.bgColor = v; }
    public String      getBorderColor()                 { return borderColor; }
    public void        setBorderColor(String v)         { this.borderColor = v; }
    public String      getAccentColor()                 { return accentColor; }
    public void        setAccentColor(String v)         { this.accentColor = v; }
    public String      getBtnBuyColor()                 { return btnBuyColor != null ? btnBuyColor : "1A5C2A"; }
    public void        setBtnBuyColor(String v)         { this.btnBuyColor = v; }
    public String      getBtnBuyTextColor()             { return btnBuyTextColor != null ? btnBuyTextColor : "FFFFFF"; }
    public void        setBtnBuyTextColor(String v)     { this.btnBuyTextColor = v; }
    public String      getBtnCloseColor()               { return btnCloseColor != null ? btnCloseColor : "1E3A5F"; }
    public void        setBtnCloseColor(String v)       { this.btnCloseColor = v; }
    public String      getBtnCloseTextColor()           { return btnCloseTextColor != null ? btnCloseTextColor : "CCCCCC"; }
    public void        setBtnCloseTextColor(String v)   { this.btnCloseTextColor = v; }
    public ShopLayout    getLayout()                     { return layout; }
    public void          setLayout(ShopLayout v)         { this.layout = v; }
    public List<ShopSet> getSets()                      { return sets; }
    public boolean       isMorphoCatalog()               { return morphoCatalog; }
    public void          setMorphoCatalog(boolean v)     { this.morphoCatalog = v; }
}
