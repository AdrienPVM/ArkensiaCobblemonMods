package com.cheeseitems.morpho;

import com.google.gson.JsonObject;

/**
 * One entry in a player's Morpho Codex — references a CheeseItems definition
 * by id and caches the resolved species/form/shiny flag for client display.
 */
public final class MorphoCodexEntry {

    private final String itemId;
    private final String species;
    private final String form;
    private final boolean shiny;
    private final long acquiredAt;

    public MorphoCodexEntry(String itemId, String species, String form, boolean shiny, long acquiredAt) {
        this.itemId = itemId;
        this.species = species == null ? "" : species;
        this.form = form == null ? "" : form;
        this.shiny = shiny;
        this.acquiredAt = acquiredAt;
    }

    public String getItemId() { return itemId; }
    public String getSpecies() { return species; }
    public String getForm() { return form; }
    public boolean isShiny() { return shiny; }
    public long getAcquiredAt() { return acquiredAt; }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("itemId", itemId);
        o.addProperty("species", species);
        o.addProperty("form", form);
        o.addProperty("shiny", shiny);
        o.addProperty("acquiredAt", acquiredAt);
        return o;
    }

    public static MorphoCodexEntry fromJson(JsonObject o) {
        return new MorphoCodexEntry(
            o.has("itemId") ? o.get("itemId").getAsString() : "",
            o.has("species") ? o.get("species").getAsString() : "",
            o.has("form") ? o.get("form").getAsString() : "",
            o.has("shiny") && o.get("shiny").getAsBoolean(),
            o.has("acquiredAt") ? o.get("acquiredAt").getAsLong() : System.currentTimeMillis()
        );
    }
}
