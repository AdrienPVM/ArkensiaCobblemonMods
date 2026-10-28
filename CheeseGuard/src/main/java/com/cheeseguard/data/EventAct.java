package com.cheeseguard.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An "Act" is a chapter within an event.
 * Each act has its own timeline of blocks and a transition condition to the next act.
 *
 * Acts replace the old EventPhase system. Backward compatibility:
 *   - Old events with phases are auto-migrated to a single Act containing
 *     timeline blocks mapped from the phases' effects.
 */
public class EventAct {

    public enum TransitionType {
        DURATION,    // Act ends after X seconds
        CONDITION,   // Act ends when a condition is met
        MANUAL       // Act only ends when admin triggers it
    }

    public enum TransitionCondition {
        PLAYERS_ALIVE_BELOW,   // fewer than N players alive
        SCORE_REACHED,         // any player reaches score X
        ALL_TASKS_COMPLETE,    // all player tasks complete (Among Us style)
        VOTE_COMPLETE,         // a vote has concluded
        CUSTOM_FLAG,           // a player_flag meets condition
        TIME_OF_DAY            // in-game time reaches value
    }

    // ── Identity ──────────────────────────────────────────────────────────────
    private String id;
    private String name = "Acte";
    private String description = "";
    private int order = 0; // display order (0-indexed)

    // ── Duration ──────────────────────────────────────────────────────────────
    private TransitionType transitionType = TransitionType.DURATION;
    private int durationSeconds = 300;          // used when transitionType=DURATION

    // ── Transition condition ──────────────────────────────────────────────────
    private TransitionCondition transitionCondition = TransitionCondition.PLAYERS_ALIVE_BELOW;
    private String transitionConditionParam = "";
    private double transitionConditionValue = 1.0;

    // ── Transition entry effect (played when this act STARTS) ─────────────────
    private String entryTitleText = "";
    private String entrySubtitleText = "";
    private String entryTitleColor = "gold";
    private String entrySound = "";
    private int entryFadeIn = 10, entryStay = 60, entryFadeOut = 20;

    // ── Death behavior override (overrides event default for this act) ─────────
    // "" = use event default
    private String deathBehaviorOverride = "";

    // ── Timeline blocks ───────────────────────────────────────────────────────
    private final List<TimelineBlock> blocks = new ArrayList<>();

    // ── Constructors ──────────────────────────────────────────────────────────
    public EventAct() {
        this.id = UUID.randomUUID().toString().substring(0, 8);
    }
    public EventAct(String id) { this.id = id; }
    public EventAct(String id, String name, int durationSeconds) {
        this.id = id;
        this.name = name;
        this.durationSeconds = durationSeconds;
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────
    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String n) { name = n; }
    public String getDescription() { return description; }
    public void setDescription(String d) { description = d; }
    public int getOrder() { return order; }
    public void setOrder(int o) { order = o; }

    public TransitionType getTransitionType() { return transitionType; }
    public void setTransitionType(TransitionType t) { transitionType = t; }
    public int getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(int d) { durationSeconds = Math.max(1, d); }

    public TransitionCondition getTransitionCondition() { return transitionCondition; }
    public void setTransitionCondition(TransitionCondition c) { transitionCondition = c; }
    public String getTransitionConditionParam() { return transitionConditionParam; }
    public void setTransitionConditionParam(String p) { transitionConditionParam = p; }
    public double getTransitionConditionValue() { return transitionConditionValue; }
    public void setTransitionConditionValue(double v) { transitionConditionValue = v; }

    public String getEntryTitleText() { return entryTitleText; }
    public void setEntryTitleText(String t) { entryTitleText = t; }
    public String getEntrySubtitleText() { return entrySubtitleText; }
    public void setEntrySubtitleText(String t) { entrySubtitleText = t; }
    public String getEntryTitleColor() { return entryTitleColor; }
    public void setEntryTitleColor(String c) { entryTitleColor = c; }
    public String getEntrySound() { return entrySound; }
    public void setEntrySound(String s) { entrySound = s; }
    public int getEntryFadeIn() { return entryFadeIn; }
    public void setEntryFadeIn(int f) { entryFadeIn = f; }
    public int getEntryStay() { return entryStay; }
    public void setEntryStay(int s) { entryStay = s; }
    public int getEntryFadeOut() { return entryFadeOut; }
    public void setEntryFadeOut(int f) { entryFadeOut = f; }

    public String getDeathBehaviorOverride() { return deathBehaviorOverride; }
    public void setDeathBehaviorOverride(String d) { deathBehaviorOverride = d; }
    public boolean hasDeathBehaviorOverride() { return !deathBehaviorOverride.isBlank(); }

    // ── Blocks ────────────────────────────────────────────────────────────────
    public List<TimelineBlock> getBlocks() { return blocks; }

    public TimelineBlock getBlock(String blockId) {
        for (TimelineBlock b : blocks) if (b.getId().equals(blockId)) return b;
        return null;
    }

    public void addBlock(TimelineBlock b) { blocks.add(b); }

    public void removeBlock(String blockId) { blocks.removeIf(b -> b.getId().equals(blockId)); }

    /** Get all blocks on a specific track, sorted by start time */
    public List<TimelineBlock> getBlocksOnTrack(String track) {
        return blocks.stream()
            .filter(b -> track.equals(b.getTrack()))
            .sorted(java.util.Comparator.comparingInt(TimelineBlock::getStartSeconds))
            .toList();
    }

    /** Total duration: if DURATION type, use configured value; otherwise use last block end time */
    public int getEffectiveDurationSeconds() {
        if (transitionType == TransitionType.DURATION) return durationSeconds;
        int max = durationSeconds; // minimum
        for (TimelineBlock b : blocks) max = Math.max(max, b.getEndSeconds());
        return max;
    }

    // ── JSON ──────────────────────────────────────────────────────────────────
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("description", description);
        o.addProperty("order", order);
        o.addProperty("transitionType", transitionType.name());
        o.addProperty("duration", durationSeconds);
        o.addProperty("transitionCondition", transitionCondition.name());
        o.addProperty("transitionCondParam", transitionConditionParam);
        o.addProperty("transitionCondValue", transitionConditionValue);
        o.addProperty("entryTitle", entryTitleText);
        o.addProperty("entrySub", entrySubtitleText);
        o.addProperty("entryColor", entryTitleColor);
        o.addProperty("entrySound", entrySound);
        o.addProperty("entryFadeIn", entryFadeIn);
        o.addProperty("entryStay", entryStay);
        o.addProperty("entryFadeOut", entryFadeOut);
        if (!deathBehaviorOverride.isBlank()) o.addProperty("deathOverride", deathBehaviorOverride);

        JsonArray blocksArr = new JsonArray();
        for (TimelineBlock b : blocks) blocksArr.add(b.toJson());
        o.add("blocks", blocksArr);
        return o;
    }

    public static EventAct fromJson(JsonObject o) {
        EventAct a = new EventAct(o.has("id") ? o.get("id").getAsString() : UUID.randomUUID().toString().substring(0, 8));
        if (o.has("name")) a.name = o.get("name").getAsString();
        if (o.has("description")) a.description = o.get("description").getAsString();
        if (o.has("order")) a.order = o.get("order").getAsInt();
        if (o.has("transitionType")) { try { a.transitionType = TransitionType.valueOf(o.get("transitionType").getAsString()); } catch (Exception ignored) {} }
        if (o.has("duration")) a.durationSeconds = o.get("duration").getAsInt();
        if (o.has("transitionCondition")) { try { a.transitionCondition = TransitionCondition.valueOf(o.get("transitionCondition").getAsString()); } catch (Exception ignored) {} }
        if (o.has("transitionCondParam")) a.transitionConditionParam = o.get("transitionCondParam").getAsString();
        if (o.has("transitionCondValue")) a.transitionConditionValue = o.get("transitionCondValue").getAsDouble();
        if (o.has("entryTitle")) a.entryTitleText = o.get("entryTitle").getAsString();
        if (o.has("entrySub")) a.entrySubtitleText = o.get("entrySub").getAsString();
        if (o.has("entryColor")) a.entryTitleColor = o.get("entryColor").getAsString();
        if (o.has("entrySound")) a.entrySound = o.get("entrySound").getAsString();
        if (o.has("entryFadeIn")) a.entryFadeIn = o.get("entryFadeIn").getAsInt();
        if (o.has("entryStay")) a.entryStay = o.get("entryStay").getAsInt();
        if (o.has("entryFadeOut")) a.entryFadeOut = o.get("entryFadeOut").getAsInt();
        if (o.has("deathOverride")) a.deathBehaviorOverride = o.get("deathOverride").getAsString();
        if (o.has("blocks")) {
            for (JsonElement el : o.getAsJsonArray("blocks")) {
                try { a.blocks.add(TimelineBlock.fromJson(el.getAsJsonObject())); } catch (Exception ignored) {}
            }
        }
        return a;
    }

    /** Migrate an old EventPhase to an EventAct (backward compatibility) */
    public static EventAct fromLegacyPhase(EventPhase phase, int order) {
        EventAct act = new EventAct(phase.getId(), phase.getName(), phase.getDurationSeconds());
        act.order = order;
        act.entryTitleText = phase.getTitleMessage();
        act.entrySubtitleText = phase.getSubtitleMessage();
        act.entryTitleColor = phase.getTitleColor();
        act.entrySound = phase.getStartSound();
        // Migrate effects to timeline blocks distributed evenly
        List<EventEffect> effects = phase.getEffects();
        for (int i = 0; i < effects.size(); i++) {
            EventEffect eff = effects.get(i);
            TimelineBlock block = new TimelineBlock();
            block.setTrack(effectToTrack(eff.getType()));
            block.setEffectType(eff.getType());
            block.setStartSeconds(0); // all start at act begin
            block.setDurationSeconds(phase.getDurationSeconds());
            // Copy common properties
            JsonObject props = new JsonObject();
            props.addProperty("interval", eff.getIntervalSeconds());
            switch (eff.getType()) {
                case POTION -> { props.addProperty("potionId", eff.getPotionId()); props.addProperty("amplifier", eff.getAmplifier()); props.addProperty("potionDuration", eff.getPotionDurationTicks()); }
                case COMMAND -> { props.addProperty("command", eff.getCommand()); props.addProperty("radius", eff.getRadiusBlocks()); props.addProperty("chance", eff.getChancePercent()); }
                case BROADCAST -> { props.addProperty("message", eff.getMessage()); props.addProperty("color", eff.getMessageColor()); }
                case MULTIPLIER -> { props.addProperty("multType", eff.getMultiplierType()); props.addProperty("multValue", eff.getMultiplierValue()); }
                case SOUND -> { props.addProperty("soundId", eff.getSoundId()); props.addProperty("volume", eff.getSoundVolume()); props.addProperty("pitch", eff.getSoundPitch()); }
                case TITLE -> { props.addProperty("titleText", eff.getTitleText()); props.addProperty("subtitleText", eff.getSubtitleText()); props.addProperty("titleColor", eff.getTitleColor()); props.addProperty("fadeIn", eff.getFadeIn()); props.addProperty("stay", eff.getStay()); props.addProperty("fadeOut", eff.getFadeOut()); }
                case ITEM_DROP -> { props.addProperty("itemId", eff.getItemId()); props.addProperty("itemCount", eff.getItemCount()); }
                case WEATHER -> props.addProperty("weatherType", eff.getWeatherType());
                case TIME -> { props.addProperty("timeValue", eff.getTimeValue()); props.addProperty("freeze", eff.isFreezeTime()); }
                case FLAG_OVERRIDE -> { props.addProperty("flagId", eff.getFlagId()); props.addProperty("targetZone", eff.getTargetZoneId()); props.addProperty("flagValue", eff.getFlagValue()); }
                case ENTITY_SPAWN -> { props.addProperty("entityCommand", eff.getEntityCommand()); props.addProperty("spawnX", eff.getSpawnX()); props.addProperty("spawnY", eff.getSpawnY()); props.addProperty("spawnZ", eff.getSpawnZ()); props.addProperty("relativePos", eff.isUseRelativePos()); }
                case ZONE_SHRINK -> { props.addProperty("zoneCenterX", eff.getZoneCenterX()); props.addProperty("zoneCenterZ", eff.getZoneCenterZ()); props.addProperty("radiusInitial", eff.getZoneRadius()); props.addProperty("radiusFinal", eff.getZoneRadius()); props.addProperty("zoneDamage", eff.getZoneDamage()); props.addProperty("showBorder", eff.isZoneShowBorder()); }
                default -> {}
            }
            block.setProperties(props);
            act.blocks.add(block);
        }
        return act;
    }

    private static String effectToTrack(EventEffect.Type type) {
        return switch (type) {
            case POTION -> TimelineBlock.TRACK_POTIONS;
            case COMMAND, COMMAND_PLAYER -> TimelineBlock.TRACK_COMMANDS;
            case BROADCAST, ACTIONBAR_PERSISTENT, TITLE -> TimelineBlock.TRACK_MESSAGES;
            case MULTIPLIER -> TimelineBlock.TRACK_SCORE;
            case SOUND -> TimelineBlock.TRACK_SOUNDS;
            case PARTICLE -> TimelineBlock.TRACK_SPAWNS;
            case ITEM_DROP, RANDOM_LOOT_AT_POSITIONS, WEIGHTED_RANDOM_COMMAND -> TimelineBlock.TRACK_RANDOM;
            case WEATHER -> TimelineBlock.TRACK_WEATHER;
            case TIME -> TimelineBlock.TRACK_WEATHER;
            case FLAG_OVERRIDE -> TimelineBlock.TRACK_FLAGS;
            case ENTITY_SPAWN, POKEMON_SPAWN, COBBLEMON_FORCE_ENCOUNTER -> TimelineBlock.TRACK_SPAWNS;
            case ZONE_SHRINK, ZONE_HEAL, ZONE_DAMAGE, ZONE_MOVE, ZONE_LOOT -> TimelineBlock.TRACK_ZONES;
            case FREEZE_PLAYERS, UNFREEZE_PLAYERS, GIVE_ITEM_TO_PLAYER, TAKE_ITEM_FROM_PLAYER, SET_PLAYER_HEALTH, SET_PLAYER_HUNGER, APPLY_KNOCKBACK, LOCK_HOTBAR, UNLOCK_HOTBAR -> TimelineBlock.TRACK_PLAYERS;
            case ASSIGN_ROLE_PRIVATE, TRANSFER_ROLE, CHANGE_TEAM_COLOR -> TimelineBlock.TRACK_ROLES;
            case START_VOTE -> TimelineBlock.TRACK_VOTES;
            case PLAYER_FLAG_SET -> TimelineBlock.TRACK_STATE_FLAGS;
            case SET_BLOCK_AT_POS, REDSTONE_ACTIVATE -> TimelineBlock.TRACK_REDSTONE;
            case SPAWN_STRUCTURE -> TimelineBlock.TRACK_DIMENSION;
            case TELEPORT_PLAYER_TO_PLAYER, DIMENSION_TELEPORT_ALL -> TimelineBlock.TRACK_DIMENSION;
            case BROADCAST_SCORE_BOARD, STATS_BROADCAST -> TimelineBlock.TRACK_STATS;
            case OBJECTIVE_HUD_UPDATE -> TimelineBlock.TRACK_OBJECTIVES;
            case DEATH_BEHAVIOR_OVERRIDE -> TimelineBlock.TRACK_DEATH;
            case INVENTORY_DISTRIBUTE -> TimelineBlock.TRACK_INVENTORY;
            case SET_PLAYER_VISIBILITY -> TimelineBlock.TRACK_VISIBILITY;
            case CONDITIONAL_BRANCH -> TimelineBlock.TRACK_LOGIC;
            case SPAWN_ITEM_AT_POS, RESPAWN_ENTITY_AT_POS -> TimelineBlock.TRACK_SPAWNS;
            default -> TimelineBlock.TRACK_COMMANDS;
        };
    }
}
