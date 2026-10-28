package com.cobbleshift;

import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * Data class for an active player transformation.
 * Pure state — no entity reference (model swap is client-side only).
 */
public class TransformData {

    private final UUID playerUuid;
    private final String playerName;
    private final String speciesName;
    private final String type1;
    private final String type2; // nullable
    private final int bst; // base stat total
    private final boolean shiny;
    private final String form; // nullable (e.g., "alolan", "galarian", "hisuian", "mega")
    private final float pokemonHeight; // Cobblemon species height (for camera/projectile calc)
    private double originalSpeed;
    private double originalMaxHealth;
    private final long transformedAt;
    private boolean locked; // if true, player cannot self-revert
    private boolean attacksEnabled = true; // if false, no projectile attacks
    private int attackCooldown; // ticks remaining before next attack
    private float customScale = 1.0f; // visual scale multiplier (1.0 = default)
    private float customSpeed = 0f; // speed override (0 = use species default, >0 = custom multiplier)

    public TransformData(UUID playerUuid, String playerName, String speciesName,
                         String type1, String type2, int bst) {
        this(playerUuid, playerName, speciesName, type1, type2, bst, false, null, 1.0f);
    }

    public TransformData(UUID playerUuid, String playerName, String speciesName,
                         String type1, String type2, int bst, boolean shiny, String form) {
        this(playerUuid, playerName, speciesName, type1, type2, bst, shiny, form, 1.0f);
    }

    public TransformData(UUID playerUuid, String playerName, String speciesName,
                         String type1, String type2, int bst, boolean shiny, String form, float pokemonHeight) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.speciesName = speciesName;
        this.type1 = type1 != null ? type1.toLowerCase() : "normal";
        this.type2 = type2 != null ? type2.toLowerCase() : null;
        this.bst = bst;
        this.shiny = shiny;
        this.form = form;
        this.pokemonHeight = pokemonHeight;
        this.transformedAt = System.currentTimeMillis();
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public UUID getPlayerUuid() { return playerUuid; }
    public String getPlayerName() { return playerName; }
    public String getSpeciesName() { return speciesName; }
    public String getType1() { return type1; }
    public String getType2() { return type2; }
    public int getBst() { return bst; }
    public boolean isShiny() { return shiny; }
    public String getForm() { return form; }
    public float getPokemonHeight() { return pokemonHeight; }

    /**
     * Encode species + shiny + form into a single string for network transmission.
     * Format: "species" or "species|shiny" or "species|form" or "species|shiny|form"
     */
    public String getEncodedSpecies() {
        StringBuilder sb = new StringBuilder(speciesName);
        if (shiny) sb.append("|shiny");
        if (form != null && !form.isEmpty()) sb.append("|").append(form);
        return sb.toString();
    }
    public double getOriginalSpeed() { return originalSpeed; }
    public double getOriginalMaxHealth() { return originalMaxHealth; }
    public long getTransformedAt() { return transformedAt; }
    public boolean isLocked() { return locked; }
    public boolean isAttacksEnabled() { return attacksEnabled; }
    public int getAttackCooldown() { return attackCooldown; }
    public float getCustomScale() { return customScale; }
    public float getCustomSpeed() { return customSpeed; }

    // ── Setters ──────────────────────────────────────────────────────────────

    public void setOriginalSpeed(double v) { this.originalSpeed = v; }
    public void setOriginalMaxHealth(double v) { this.originalMaxHealth = v; }
    public void setLocked(boolean locked) { this.locked = locked; }
    public void setAttacksEnabled(boolean enabled) { this.attacksEnabled = enabled; }
    public void setAttackCooldown(int ticks) { this.attackCooldown = ticks; }
    public void setCustomScale(float s) { this.customScale = Math.max(0.1f, Math.min(10.0f, s)); }
    public void setCustomSpeed(float s) { this.customSpeed = Math.max(0f, Math.min(10.0f, s)); }
    public void tickCooldown() { if (attackCooldown > 0) attackCooldown--; }

    // ── JSON ─────────────────────────────────────────────────────────────────

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("playerUuid", playerUuid.toString());
        obj.addProperty("playerName", playerName);
        obj.addProperty("species", speciesName);
        obj.addProperty("type1", type1);
        if (type2 != null) obj.addProperty("type2", type2);
        obj.addProperty("bst", bst);
        obj.addProperty("shiny", shiny);
        if (form != null) obj.addProperty("form", form);
        obj.addProperty("transformedAt", transformedAt);
        obj.addProperty("locked", locked);
        if (customScale != 1.0f) obj.addProperty("customScale", customScale);
        return obj;
    }
}
