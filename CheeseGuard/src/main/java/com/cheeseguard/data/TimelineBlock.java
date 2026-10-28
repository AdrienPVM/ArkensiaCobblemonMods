package com.cheeseguard.data;

import com.google.gson.JsonObject;
import java.util.UUID;

/**
 * A single block on the event timeline.
 * Represents an effect active between [startSeconds, startSeconds+durationSeconds].
 * Duration=0 means instant/one-shot at startSeconds.
 */
public class TimelineBlock {

    // ── Track constants ───────────────────────────────────────────────────────
    public static final String TRACK_MESSAGES    = "messages";
    public static final String TRACK_POTIONS     = "potions";
    public static final String TRACK_ZONES       = "zones";
    public static final String TRACK_SPAWNS      = "spawns";
    public static final String TRACK_COMMANDS    = "commands";
    public static final String TRACK_WEATHER     = "weather";
    public static final String TRACK_SOUNDS      = "sounds";
    public static final String TRACK_SCORE       = "score";
    public static final String TRACK_FLAGS       = "flags";
    public static final String TRACK_ROLES       = "roles";
    public static final String TRACK_VOTES       = "votes";
    public static final String TRACK_PLAYERS     = "players";
    public static final String TRACK_STATE_FLAGS = "state_flags";
    public static final String TRACK_RANDOM      = "random";
    public static final String TRACK_OBJECTIVES  = "objectives";
    public static final String TRACK_DEATH       = "death";
    public static final String TRACK_DIMENSION   = "dimension";
    public static final String TRACK_INVENTORY   = "inventory";
    public static final String TRACK_VISIBILITY  = "visibility";
    public static final String TRACK_REDSTONE    = "redstone";
    public static final String TRACK_STATS       = "stats";
    public static final String TRACK_LOGIC       = "logic";

    // ── Condition types ───────────────────────────────────────────────────────
    public enum ConditionType {
        NONE,
        PLAYER_COUNT_BELOW,     // fewer than N players alive
        PLAYER_COUNT_ABOVE,     // more than N players alive
        SCORE_ABOVE,            // any player score above X
        SCORE_BELOW,
        TEAM_SCORE_ABOVE,
        TIME_OF_DAY_DAY,
        TIME_OF_DAY_NIGHT,
        PLAYER_FLAG_TRUE,       // a player state flag is set
        PLAYER_FLAG_FALSE,
        RANDOM_CHANCE           // X% chance to execute
    }

    // ── Fields ────────────────────────────────────────────────────────────────
    private String id;
    private String track;                          // which track row
    private int lane = 0;                          // sub-lane within track (0-based, for multi-lane display)
    private EventEffect.Type effectType;          // what kind of effect
    private int startSeconds;                      // when to start (from act begin)
    private int durationSeconds;                   // 0 = instant
    private int repeatIntervalSeconds = 0;         // 0 = no repeat, >0 = repeat every N sec

    // Condition (optional)
    private ConditionType conditionType = ConditionType.NONE;
    private String conditionParam = "";            // depends on condition type
    private double conditionValue = 0;

    // Effect properties stored as JsonObject (type-specific)
    private JsonObject properties = new JsonObject();

    // Visual / meta
    private String label = "";                     // custom label shown in block
    private String colorOverride = "";             // hex override for block color

    // ── Constructor ───────────────────────────────────────────────────────────
    public TimelineBlock() {
        this.id = UUID.randomUUID().toString().substring(0, 8);
    }
    public TimelineBlock(String id) { this.id = id; }
    public TimelineBlock(String track, EventEffect.Type type, int startSec, int durationSec) {
        this();
        this.track = track;
        this.effectType = type;
        this.startSeconds = startSec;
        this.durationSeconds = durationSec;
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────
    public String getId() { return id; }
    public String getTrack() { return track; }
    public void setTrack(String t) { track = t; }
    public int getLane() { return lane; }
    public void setLane(int l) { lane = Math.max(0, l); }
    public EventEffect.Type getEffectType() { return effectType; }
    public void setEffectType(EventEffect.Type t) { effectType = t; }
    public int getStartSeconds() { return startSeconds; }
    public void setStartSeconds(int s) { startSeconds = Math.max(0, s); }
    public int getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(int d) { durationSeconds = Math.max(0, d); }
    public int getEndSeconds() { return startSeconds + durationSeconds; }
    public int getRepeatIntervalSeconds() { return repeatIntervalSeconds; }
    public void setRepeatIntervalSeconds(int r) { repeatIntervalSeconds = Math.max(0, r); }
    public ConditionType getConditionType() { return conditionType; }
    public void setConditionType(ConditionType c) { conditionType = c; }
    public String getConditionParam() { return conditionParam; }
    public void setConditionParam(String p) { conditionParam = p; }
    public double getConditionValue() { return conditionValue; }
    public void setConditionValue(double v) { conditionValue = v; }
    public JsonObject getProperties() { return properties; }
    public void setProperties(JsonObject p) { properties = p != null ? p : new JsonObject(); }
    public String getLabel() { return label; }
    public void setLabel(String l) { label = l; }
    public String getColorOverride() { return colorOverride; }
    public void setColorOverride(String c) { colorOverride = c; }
    public boolean isInstant() { return durationSeconds == 0; }

    /** Helper: get a string property */
    public String getProp(String key, String def) {
        return properties.has(key) ? properties.get(key).getAsString() : def;
    }
    /** Helper: get an int property */
    public int getPropInt(String key, int def) {
        return properties.has(key) ? properties.get(key).getAsInt() : def;
    }
    /** Helper: get a double property */
    public double getPropDouble(String key, double def) {
        return properties.has(key) ? properties.get(key).getAsDouble() : def;
    }
    /** Helper: get a boolean property */
    public boolean getPropBool(String key, boolean def) {
        return properties.has(key) ? properties.get(key).getAsBoolean() : def;
    }
    /** Helper: set a string property */
    public void setProp(String key, String val) { properties.addProperty(key, val); }
    public void setProp(String key, int val)    { properties.addProperty(key, val); }
    public void setProp(String key, double val) { properties.addProperty(key, val); }
    public void setProp(String key, boolean val){ properties.addProperty(key, val); }

    /** Get display label for the block (custom label or auto from type+props) */
    public String getDisplayLabel() {
        if (!label.isBlank()) return label;
        if (effectType == null) return "?";
        return switch (effectType) {
            case BROADCAST, ACTIONBAR_PERSISTENT -> {
                String msg = getProp("message", "");
                yield msg.length() > 18 ? msg.substring(0, 18) + "…" : msg;
            }
            case POTION -> {
                String p = getProp("potionId", "speed");
                yield p.contains(":") ? p.substring(p.indexOf(':') + 1) : p;
            }
            case COMMAND, COMMAND_PLAYER -> {
                String cmd = getProp("command", "");
                yield cmd.length() > 18 ? cmd.substring(0, 18) + "…" : cmd;
            }
            case ZONE_SHRINK, ZONE_HEAL, ZONE_DAMAGE, ZONE_MOVE -> {
                double r = getPropDouble("radiusFinal", getPropDouble("radius", 0));
                yield "r=" + (int)r;
            }
            case ZONE_POWERUP -> {
                String pt = getProp("powerType", "SPEED");
                yield "PU:" + pt.substring(0, Math.min(pt.length(), 5)).toLowerCase();
            }
            case ENTITY_SPAWN, POKEMON_SPAWN -> {
                String cmd = getProp("command", getProp("species", ""));
                yield cmd.length() > 18 ? cmd.substring(0, 18) + "…" : cmd;
            }
            case ASSIGN_ROLE_PRIVATE -> getProp("roleName", "Rôle");
            case START_VOTE -> getProp("question", "Vote");
            case TITLE -> getProp("titleText", "Titre");
            case WEATHER -> getProp("weatherType", "Météo");
            case FREEZE_PLAYERS -> "Freeze";
            case UNFREEZE_PLAYERS -> "Unfreeze";
            case SET_BLOCK_AT_POS -> "SetBlock";
            case SPAWN_STRUCTURE -> getProp("structureName", "Structure");
            case WEIGHTED_RANDOM_COMMAND -> "Aléatoire";
            case CONDITIONAL_BRANCH -> "IF " + getProp("conditionLabel", "cond");
            case DUNGEON_OPEN -> getProp("dungeonId", "Donjon");
            case DUNGEON_MODIFIER -> getProp("modifierType", "Modifier");
            case DUNGEON_TICKET_GIVE -> "Ticket " + getProp("dungeonId", "");
            default -> effectType.label;
        };
    }

    /** Track color for UI rendering */
    public static int trackColor(String track) {
        return switch (track) {
            case TRACK_MESSAGES    -> 0xFF4A8FCC; // blue
            case TRACK_POTIONS     -> 0xFF9B59B6; // purple
            case TRACK_ZONES       -> 0xFF27AE60; // green
            case TRACK_SPAWNS      -> 0xFFE67E22; // orange
            case TRACK_COMMANDS    -> 0xFFF1C40F; // yellow
            case TRACK_WEATHER     -> 0xFF2980B9; // sky blue
            case TRACK_SOUNDS      -> 0xFF1ABC9C; // teal
            case TRACK_SCORE       -> 0xFFE74C3C; // red
            case TRACK_FLAGS       -> 0xFFE74C3C; // red/orange
            case TRACK_ROLES       -> 0xFFFF6B9D; // pink
            case TRACK_VOTES       -> 0xFFFF8C00; // amber
            case TRACK_PLAYERS     -> 0xFF3498DB; // light blue
            case TRACK_STATE_FLAGS -> 0xFF95A5A6; // gray
            case TRACK_RANDOM      -> 0xFFF39C12; // orange-yellow
            case TRACK_OBJECTIVES  -> 0xFF00BCD4; // cyan
            case TRACK_DEATH       -> 0xFF8B0000; // dark red
            case TRACK_DIMENSION   -> 0xFF7B68EE; // medium slate blue
            case TRACK_INVENTORY   -> 0xFF8D6E63; // brown
            case TRACK_VISIBILITY  -> 0xFF78909C; // blue-gray
            case TRACK_REDSTONE    -> 0xFFCC0000; // bright red
            case TRACK_STATS       -> 0xFF26A69A; // teal-green
            case TRACK_LOGIC       -> 0xFFAB47BC; // medium purple
            default                -> 0xFF607D8B;
        };
    }

    /** Track icon for UI */
    public static String trackIcon(String track) {
        return switch (track) {
            case TRACK_MESSAGES    -> "💬";
            case TRACK_POTIONS     -> "🧪";
            case TRACK_ZONES       -> "🗺";
            case TRACK_SPAWNS      -> "👾";
            case TRACK_COMMANDS    -> "⚡";
            case TRACK_WEATHER     -> "🌦";
            case TRACK_SOUNDS      -> "🎵";
            case TRACK_SCORE       -> "🏆";
            case TRACK_FLAGS       -> "🚩";
            case TRACK_ROLES       -> "🎭";
            case TRACK_VOTES       -> "🗳";
            case TRACK_PLAYERS     -> "❄";
            case TRACK_STATE_FLAGS -> "🔑";
            case TRACK_RANDOM      -> "🎰";
            case TRACK_OBJECTIVES  -> "🎯";
            case TRACK_DEATH       -> "💀";
            case TRACK_DIMENSION   -> "🌐";
            case TRACK_INVENTORY   -> "🎒";
            case TRACK_VISIBILITY  -> "👁";
            case TRACK_REDSTONE    -> "🔴";
            case TRACK_STATS       -> "📊";
            case TRACK_LOGIC       -> "🔗";
            default                -> "•";
        };
    }

    /** Track display name */
    public static String trackLabel(String track) {
        return switch (track) {
            case TRACK_MESSAGES    -> "Messages";
            case TRACK_POTIONS     -> "Potions";
            case TRACK_ZONES       -> "Zones";
            case TRACK_SPAWNS      -> "Spawns";
            case TRACK_COMMANDS    -> "Commandes";
            case TRACK_WEATHER     -> "Météo";
            case TRACK_SOUNDS      -> "Sons";
            case TRACK_SCORE       -> "Score";
            case TRACK_FLAGS       -> "Flags";
            case TRACK_ROLES       -> "Rôles";
            case TRACK_VOTES       -> "Votes";
            case TRACK_PLAYERS     -> "Joueurs";
            case TRACK_STATE_FLAGS -> "État";
            case TRACK_RANDOM      -> "Aléatoire";
            case TRACK_OBJECTIVES  -> "Objectifs";
            case TRACK_DEATH       -> "Mort/Respawn";
            case TRACK_DIMENSION   -> "Dimension";
            case TRACK_INVENTORY   -> "Inventaire";
            case TRACK_VISIBILITY  -> "Visibilité";
            case TRACK_REDSTONE    -> "Redstone";
            case TRACK_STATS       -> "Statistiques";
            case TRACK_LOGIC       -> "Logique";
            default                -> track;
        };
    }

    /** Ordered list of all tracks for timeline display */
    public static String[] ALL_TRACKS = {
        TRACK_MESSAGES, TRACK_POTIONS, TRACK_ZONES, TRACK_SPAWNS, TRACK_COMMANDS,
        TRACK_WEATHER, TRACK_SOUNDS, TRACK_SCORE, TRACK_FLAGS, TRACK_ROLES,
        TRACK_VOTES, TRACK_PLAYERS, TRACK_STATE_FLAGS, TRACK_RANDOM, TRACK_OBJECTIVES,
        TRACK_DEATH, TRACK_DIMENSION, TRACK_INVENTORY, TRACK_VISIBILITY,
        TRACK_REDSTONE, TRACK_STATS, TRACK_LOGIC
    };

    // ── JSON ──────────────────────────────────────────────────────────────────
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("track", track);
        if (lane > 0) o.addProperty("lane", lane);
        if (effectType != null) o.addProperty("effectType", effectType.name());
        o.addProperty("start", startSeconds);
        o.addProperty("duration", durationSeconds);
        if (repeatIntervalSeconds > 0) o.addProperty("repeat", repeatIntervalSeconds);
        if (conditionType != ConditionType.NONE) {
            o.addProperty("condType", conditionType.name());
            o.addProperty("condParam", conditionParam);
            o.addProperty("condValue", conditionValue);
        }
        o.add("props", properties);
        if (!label.isBlank()) o.addProperty("label", label);
        if (!colorOverride.isBlank()) o.addProperty("color", colorOverride);
        return o;
    }

    public static TimelineBlock fromJson(JsonObject o) {
        TimelineBlock b = new TimelineBlock(o.has("id") ? o.get("id").getAsString() : UUID.randomUUID().toString().substring(0, 8));
        if (o.has("track")) b.track = o.get("track").getAsString();
        if (o.has("lane")) b.lane = o.get("lane").getAsInt();
        if (o.has("effectType")) { try { b.effectType = EventEffect.Type.valueOf(o.get("effectType").getAsString()); } catch (Exception ignored) {} }
        if (o.has("start")) b.startSeconds = o.get("start").getAsInt();
        if (o.has("duration")) b.durationSeconds = o.get("duration").getAsInt();
        if (o.has("repeat")) b.repeatIntervalSeconds = o.get("repeat").getAsInt();
        if (o.has("condType")) { try { b.conditionType = ConditionType.valueOf(o.get("condType").getAsString()); } catch (Exception ignored) {} }
        if (o.has("condParam")) b.conditionParam = o.get("condParam").getAsString();
        if (o.has("condValue")) b.conditionValue = o.get("condValue").getAsDouble();
        if (o.has("props") && o.get("props").isJsonObject()) b.properties = o.getAsJsonObject("props");
        if (o.has("label")) b.label = o.get("label").getAsString();
        if (o.has("color")) b.colorOverride = o.get("color").getAsString();
        return b;
    }
}
