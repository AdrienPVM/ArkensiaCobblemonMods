package com.cheesemarket.data;

import com.google.gson.JsonObject;

/**
 * Defines how stock/purchase limits work for a ShopItem.
 *
 * Modes:
 *  UNLIMITED       — No limit. Anyone can buy as many times as they want.
 *  GLOBAL          — Shared stock across all players. Total server-wide limit.
 *  PER_PLAYER      — Each player has their own quota. Never resets.
 *  PER_PLAYER_RESET — Each player has a quota that resets on a schedule.
 *
 * Reset types (for PER_PLAYER_RESET):
 *  DAILY   — resets at midnight server time
 *  WEEKLY  — resets every Monday midnight
 *  MONTHLY — resets on the 1st of each month
 *  CUSTOM  — resets every N seconds (configured via resetIntervalSeconds)
 */
public class StockConfig {

    public enum Mode {
        UNLIMITED, GLOBAL, PER_PLAYER, PER_PLAYER_RESET
    }

    public enum ResetType {
        DAILY, WEEKLY, MONTHLY, CUSTOM
    }

    private Mode      mode;
    private int       globalStock;       // for GLOBAL mode: total available
    private int       perPlayerLimit;    // for PER_PLAYER / PER_PLAYER_RESET: max per player
    private ResetType resetType;         // for PER_PLAYER_RESET
    private long      resetIntervalSeconds; // for CUSTOM reset

    // Runtime (not persisted here — tracked by ShopManager):
    // - globalRemaining: int
    // - perPlayerPurchases: Map<UUID, Integer>
    // - lastResetTimestamp: long

    public StockConfig() {
        this.mode = Mode.UNLIMITED;
        this.globalStock = 100;
        this.perPlayerLimit = 1;
        this.resetType = ResetType.DAILY;
        this.resetIntervalSeconds = 86400;
    }

    // ── Serialization ──────────────────────────────────────────────────────────

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("mode", mode.name());
        o.addProperty("globalStock", globalStock);
        o.addProperty("perPlayerLimit", perPlayerLimit);
        o.addProperty("resetType", resetType.name());
        o.addProperty("resetIntervalSeconds", resetIntervalSeconds);
        return o;
    }

    public static StockConfig fromJson(JsonObject o) {
        StockConfig s = new StockConfig();
        if (o == null) return s;
        if (o.has("mode"))          s.mode = safeEnum(Mode.class, o.get("mode").getAsString(), Mode.UNLIMITED);
        if (o.has("globalStock"))   s.globalStock = o.get("globalStock").getAsInt();
        if (o.has("perPlayerLimit"))s.perPlayerLimit = o.get("perPlayerLimit").getAsInt();
        if (o.has("resetType"))     s.resetType = safeEnum(ResetType.class, o.get("resetType").getAsString(), ResetType.DAILY);
        if (o.has("resetIntervalSeconds")) s.resetIntervalSeconds = o.get("resetIntervalSeconds").getAsLong();
        return s;
    }

    private static <E extends Enum<E>> E safeEnum(Class<E> cls, String val, E def) {
        try { return Enum.valueOf(cls, val); } catch (Exception e) { return def; }
    }

    // ── Display helpers ────────────────────────────────────────────────────────

    public String modeLabel() {
        return switch (mode) {
            case UNLIMITED       -> "Illimité";
            case GLOBAL          -> "Global serveur";
            case PER_PLAYER      -> "Par joueur";
            case PER_PLAYER_RESET -> "Par joueur (reset)";
        };
    }

    public String resetLabel() {
        return switch (resetType) {
            case DAILY   -> "Quotidien";
            case WEEKLY  -> "Hebdomadaire";
            case MONTHLY -> "Mensuel";
            case CUSTOM  -> "Custom (" + resetIntervalSeconds + "s)";
        };
    }

    // ── Getters / Setters ──────────────────────────────────────────────────────

    public Mode      getMode()                          { return mode; }
    public void      setMode(Mode mode)                 { this.mode = mode; }
    public int       getGlobalStock()                   { return globalStock; }
    public void      setGlobalStock(int v)              { this.globalStock = Math.max(1, v); }
    public int       getPerPlayerLimit()                { return perPlayerLimit; }
    public void      setPerPlayerLimit(int v)           { this.perPlayerLimit = Math.max(1, v); }
    public ResetType getResetType()                     { return resetType; }
    public void      setResetType(ResetType t)          { this.resetType = t; }
    public long      getResetIntervalSeconds()          { return resetIntervalSeconds; }
    public void      setResetIntervalSeconds(long v)    { this.resetIntervalSeconds = Math.max(60, v); }
}
