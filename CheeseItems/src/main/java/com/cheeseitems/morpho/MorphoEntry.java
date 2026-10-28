package com.cheeseitems.morpho;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One entry in the central Morpho Catalog. The catalog is the unique source of truth for
 * morphos across three surfaces: the in-game Codex GUI, the public web store and the
 * Métamorph NPC shop. Each surface filters by {@link #availableOnWebsite} or
 * {@link #availableInNpcShop}.
 *
 * Persisted in {@code config/cheeseitems_morphos.json} via {@link MorphoCatalog}.
 * Image (if {@link #hasImage}) lives in {@code config/cheeseitems_assets/morphos/<id>.png}.
 */
public final class MorphoEntry {

    private String id;
    private String title;
    private String description;
    private String species;
    private String form;
    private boolean shiny;
    private boolean hasImage;
    private int priceCredits;
    private int priceGold;
    private boolean availableOnWebsite;
    private boolean availableInNpcShop;
    private boolean isNew;
    private int sortOrder;
    private long createdAt;

    /** Habitat flags surfaced as icon badges on the website cards. */
    private boolean terrestrial;
    private boolean aquatic;
    private boolean aerial;

    /** Marks the morpho as legendary / ultra-rare — surfaced as a star badge. */
    private boolean legendary;

    /** Extra commands to run on the player when they transform (the cshift transform itself
     *  is always executed automatically and is not listed here). */
    private List<String> commandsOnTransform = new ArrayList<>();
    /** Extra commands to run on the player when they revert. */
    private List<String> commandsOnRevert    = new ArrayList<>();

    /** When true, the four custom* fields override the global endurance config. */
    private boolean useCustomEndurance;
    private int   customCapacity       = 100;
    private float customDrainPerSecond = 2.0f;
    private float customRegenPerSecond = 1.0f;

    public MorphoEntry() {}

    public MorphoEntry(String id) {
        this.id = id;
        this.title = "";
        this.description = "";
        this.species = "";
        this.form = "";
        this.shiny = false;
        this.hasImage = false;
        this.priceCredits = 0;
        this.priceGold = 0;
        this.availableOnWebsite = false;
        this.availableInNpcShop = false;
        this.isNew = false;
        this.sortOrder = 0;
        this.createdAt = System.currentTimeMillis();
    }

    public String getId()                     { return id; }
    public String getTitle()                  { return title == null ? "" : title; }
    public String getDescription()            { return description == null ? "" : description; }
    public String getSpecies()                { return species == null ? "" : species; }
    public String getForm()                   { return form == null ? "" : form; }
    public boolean isShiny()                  { return shiny; }
    public boolean hasImage()                 { return hasImage; }
    public int getPriceCredits()              { return priceCredits; }
    public int getPriceGold()                 { return priceGold; }
    public boolean isAvailableOnWebsite()     { return availableOnWebsite; }
    public boolean isAvailableInNpcShop()     { return availableInNpcShop; }
    public boolean isNew()                    { return isNew; }
    public int getSortOrder()                 { return sortOrder; }
    public long getCreatedAt()                { return createdAt; }
    public boolean isTerrestrial()            { return terrestrial; }
    public boolean isAquatic()                { return aquatic; }
    public boolean isAerial()                 { return aerial; }
    public boolean isLegendary()              { return legendary; }
    public List<String> getCommandsOnTransform() { return commandsOnTransform == null ? Collections.emptyList() : commandsOnTransform; }
    public List<String> getCommandsOnRevert()    { return commandsOnRevert    == null ? Collections.emptyList() : commandsOnRevert; }
    public boolean isUseCustomEndurance()        { return useCustomEndurance; }
    public int   getCustomCapacity()             { return customCapacity; }
    public float getCustomDrainPerSecond()       { return customDrainPerSecond; }
    public float getCustomRegenPerSecond()       { return customRegenPerSecond; }

    public void setId(String id)                              { this.id = id; }
    public void setTitle(String title)                        { this.title = title; }
    public void setDescription(String d)                      { this.description = d; }
    public void setSpecies(String s)                          { this.species = s; }
    public void setForm(String f)                             { this.form = f; }
    public void setShiny(boolean s)                           { this.shiny = s; }
    public void setHasImage(boolean h)                        { this.hasImage = h; }
    public void setPriceCredits(int v)                        { this.priceCredits = Math.max(0, v); }
    public void setPriceGold(int v)                           { this.priceGold = Math.max(0, v); }
    public void setAvailableOnWebsite(boolean b)              { this.availableOnWebsite = b; }
    public void setAvailableInNpcShop(boolean b)              { this.availableInNpcShop = b; }
    public void setNew(boolean b)                             { this.isNew = b; }
    public void setSortOrder(int v)                           { this.sortOrder = v; }
    public void setCreatedAt(long v)                          { this.createdAt = v; }
    public void setTerrestrial(boolean v)                     { this.terrestrial = v; }
    public void setAquatic(boolean v)                         { this.aquatic = v; }
    public void setAerial(boolean v)                          { this.aerial = v; }
    public void setLegendary(boolean v)                       { this.legendary = v; }
    public void setCommandsOnTransform(List<String> v)        { this.commandsOnTransform = v == null ? new ArrayList<>() : v; }
    public void setCommandsOnRevert(List<String> v)           { this.commandsOnRevert    = v == null ? new ArrayList<>() : v; }
    public void setUseCustomEndurance(boolean v)              { this.useCustomEndurance = v; }
    public void setCustomCapacity(int v)                      { this.customCapacity = Math.max(1, v); }
    public void setCustomDrainPerSecond(float v)              { this.customDrainPerSecond = Math.max(0f, v); }
    public void setCustomRegenPerSecond(float v)              { this.customRegenPerSecond = Math.max(0f, v); }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("title", getTitle());
        o.addProperty("description", getDescription());
        o.addProperty("species", getSpecies());
        o.addProperty("form", getForm());
        o.addProperty("shiny", shiny);
        o.addProperty("hasImage", hasImage);
        o.addProperty("priceCredits", priceCredits);
        o.addProperty("priceGold", priceGold);
        o.addProperty("availableOnWebsite", availableOnWebsite);
        o.addProperty("availableInNpcShop", availableInNpcShop);
        o.addProperty("isNew", isNew);
        o.addProperty("sortOrder", sortOrder);
        o.addProperty("createdAt", createdAt);
        o.addProperty("terrestrial", terrestrial);
        o.addProperty("aquatic", aquatic);
        o.addProperty("aerial", aerial);
        o.addProperty("legendary", legendary);
        JsonArray onT = new JsonArray();
        for (String c : getCommandsOnTransform()) onT.add(c);
        o.add("commandsOnTransform", onT);
        JsonArray onR = new JsonArray();
        for (String c : getCommandsOnRevert()) onR.add(c);
        o.add("commandsOnRevert", onR);
        o.addProperty("useCustomEndurance", useCustomEndurance);
        o.addProperty("customCapacity", customCapacity);
        o.addProperty("customDrainPerSecond", customDrainPerSecond);
        o.addProperty("customRegenPerSecond", customRegenPerSecond);
        return o;
    }

    public static MorphoEntry fromJson(JsonObject o) {
        MorphoEntry e = new MorphoEntry();
        e.id                 = o.has("id") ? o.get("id").getAsString() : "";
        e.title              = o.has("title") ? o.get("title").getAsString() : "";
        e.description        = o.has("description") ? o.get("description").getAsString() : "";
        e.species            = o.has("species") ? o.get("species").getAsString() : "";
        e.form               = o.has("form") ? o.get("form").getAsString() : "";
        e.shiny              = o.has("shiny") && o.get("shiny").getAsBoolean();
        e.hasImage           = o.has("hasImage") && o.get("hasImage").getAsBoolean();
        e.priceCredits       = o.has("priceCredits") ? o.get("priceCredits").getAsInt() : 0;
        e.priceGold          = o.has("priceGold") ? o.get("priceGold").getAsInt() : 0;
        e.availableOnWebsite = o.has("availableOnWebsite") && o.get("availableOnWebsite").getAsBoolean();
        e.availableInNpcShop = o.has("availableInNpcShop") && o.get("availableInNpcShop").getAsBoolean();
        e.isNew              = o.has("isNew") && o.get("isNew").getAsBoolean();
        e.sortOrder          = o.has("sortOrder") ? o.get("sortOrder").getAsInt() : 0;
        e.createdAt          = o.has("createdAt") ? o.get("createdAt").getAsLong() : System.currentTimeMillis();
        e.terrestrial        = o.has("terrestrial") && o.get("terrestrial").getAsBoolean();
        e.aquatic            = o.has("aquatic") && o.get("aquatic").getAsBoolean();
        e.aerial             = o.has("aerial") && o.get("aerial").getAsBoolean();
        e.legendary          = o.has("legendary") && o.get("legendary").getAsBoolean();
        if (o.has("commandsOnTransform")) {
            e.commandsOnTransform = new ArrayList<>();
            for (JsonElement el : o.getAsJsonArray("commandsOnTransform")) e.commandsOnTransform.add(el.getAsString());
        }
        if (o.has("commandsOnRevert")) {
            e.commandsOnRevert = new ArrayList<>();
            for (JsonElement el : o.getAsJsonArray("commandsOnRevert")) e.commandsOnRevert.add(el.getAsString());
        }
        if (o.has("useCustomEndurance")) e.useCustomEndurance = o.get("useCustomEndurance").getAsBoolean();
        if (o.has("customCapacity"))       e.customCapacity = Math.max(1, o.get("customCapacity").getAsInt());
        if (o.has("customDrainPerSecond")) e.customDrainPerSecond = Math.max(0f, o.get("customDrainPerSecond").getAsFloat());
        if (o.has("customRegenPerSecond")) e.customRegenPerSecond = Math.max(0f, o.get("customRegenPerSecond").getAsFloat());
        return e;
    }

    /** Apply non-null fields from a partial-update payload to this entry (PUT semantics). */
    public void mergeFrom(JsonObject o) {
        if (o.has("title"))              this.title = o.get("title").getAsString();
        if (o.has("description"))        this.description = o.get("description").getAsString();
        if (o.has("species"))            this.species = o.get("species").getAsString();
        if (o.has("form"))               this.form = o.get("form").getAsString();
        if (o.has("shiny"))              this.shiny = o.get("shiny").getAsBoolean();
        if (o.has("priceCredits"))       this.priceCredits = Math.max(0, o.get("priceCredits").getAsInt());
        if (o.has("priceGold"))          this.priceGold = Math.max(0, o.get("priceGold").getAsInt());
        if (o.has("availableOnWebsite")) this.availableOnWebsite = o.get("availableOnWebsite").getAsBoolean();
        if (o.has("availableInNpcShop")) this.availableInNpcShop = o.get("availableInNpcShop").getAsBoolean();
        if (o.has("isNew"))              this.isNew = o.get("isNew").getAsBoolean();
        if (o.has("sortOrder"))          this.sortOrder = o.get("sortOrder").getAsInt();
        if (o.has("terrestrial"))        this.terrestrial = o.get("terrestrial").getAsBoolean();
        if (o.has("aquatic"))            this.aquatic = o.get("aquatic").getAsBoolean();
        if (o.has("aerial"))             this.aerial = o.get("aerial").getAsBoolean();
        if (o.has("legendary"))          this.legendary = o.get("legendary").getAsBoolean();
        if (o.has("commandsOnTransform")) {
            commandsOnTransform = new ArrayList<>();
            for (JsonElement el : o.getAsJsonArray("commandsOnTransform")) commandsOnTransform.add(el.getAsString());
        }
        if (o.has("commandsOnRevert")) {
            commandsOnRevert = new ArrayList<>();
            for (JsonElement el : o.getAsJsonArray("commandsOnRevert")) commandsOnRevert.add(el.getAsString());
        }
        if (o.has("useCustomEndurance"))   useCustomEndurance = o.get("useCustomEndurance").getAsBoolean();
        if (o.has("customCapacity"))       customCapacity = Math.max(1, o.get("customCapacity").getAsInt());
        if (o.has("customDrainPerSecond")) customDrainPerSecond = Math.max(0f, o.get("customDrainPerSecond").getAsFloat());
        if (o.has("customRegenPerSecond")) customRegenPerSecond = Math.max(0f, o.get("customRegenPerSecond").getAsFloat());
    }
}
