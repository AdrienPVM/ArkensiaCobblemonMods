package com.cheeseguard.data;

import com.google.gson.JsonObject;
import java.util.UUID;

public class EventEffect {

    public enum Type {
        // ── Existing ─────────────────────────────────────────────────────
        POTION                  ("Potion",              "\u00a7a\uD83E\uDDEA"),
        COMMAND                 ("Commande console",    "\u00a7e\u26A1"),
        COMMAND_PLAYER          ("Commande joueur",     "\u00a7e\u26A1"),
        BROADCAST               ("Broadcast chat",      "\u00a7b\uD83D\uDCE2"),
        MULTIPLIER              ("Multiplicateur",      "\u00a7d\u2716"),
        SOUND                   ("Son",                 "\u00a7a\uD83C\uDFB5"),
        TITLE                   ("Titre/Sous-titre",    "\u00a7f\uD83D\uDCFA"),
        PARTICLE                ("Particules",          "\u00a7c\uD83C\uDF86"),
        ITEM_DROP               ("Donner Item",         "\u00a76\uD83C\uDF81"),
        WEATHER                 ("Météo",               "\u00a79\uD83C\uDF27"),
        TIME                    ("Temps jour/nuit",     "\u00a7e\uD83D\uDD50"),
        FLAG_OVERRIDE           ("Flag temporaire",     "\u00a7c\uD83D\uDEA9"),
        ENTITY_SPAWN            ("Spawn Entité",        "\u00a7d\uD83D\uDC7E"),
        ZONE_SHRINK             ("Zone Rétrécissante",  "\u00a7c\uD83D\uDFE2"),
        // ── Messages & HUD ───────────────────────────────────────────────
        ACTIONBAR_PERSISTENT    ("Actionbar persist.",  "\u00a7b\uD83D\uDCDD"),
        OBJECTIVE_HUD_UPDATE    ("Objectif HUD",        "\u00a7b\uD83C\uDFAF"),
        BROADCAST_SCORE_BOARD   ("Tableau de score",    "\u00a7e\uD83D\uDCCA"),
        STATS_BROADCAST         ("Stats live",          "\u00a7e\uD83D\uDCCA"),
        // ── Zones ────────────────────────────────────────────────────────
        ZONE_HEAL               ("Zone de Soin",        "\u00a7a\uD83D\uDFE2"),
        ZONE_DAMAGE             ("Zone de Dégâts",      "\u00a7c\uD83D\uDFE5"),
        ZONE_LOOT               ("Zone de Loot",        "\u00a76\uD83D\uDFE7"),
        ZONE_MOVE               ("Zone Mobile",         "\u00a7b\uD83D\uDFE6"),
        // ── Spawns ───────────────────────────────────────────────────────
        POKEMON_SPAWN           ("Spawn Pokémon",       "\u00a7d\uD83D\uDC3E"),
        COBBLEMON_FORCE_ENCOUNTER("Combat Pokémon",     "\u00a7d\u2694"),
        SPAWN_ITEM_AT_POS       ("Spawn Item Pos",      "\u00a76\uD83D\uDCE6"),
        RESPAWN_ENTITY_AT_POS   ("Respawn Entité",      "\u00a7d\uD83D\uDD04"),
        SPAWN_STRUCTURE         ("Spawn Structure",     "\u00a77\uD83C\uDFDB"),
        RANDOM_LOOT_AT_POSITIONS("Loot Aléatoire",      "\u00a76\uD83C\uDF81"),
        // ── Players ──────────────────────────────────────────────────────
        FREEZE_PLAYERS          ("Freeze Joueurs",      "\u00a7b\u2744"),
        UNFREEZE_PLAYERS        ("Unfreeze Joueurs",    "\u00a7a\u2744"),
        GIVE_ITEM_TO_PLAYER     ("Donner item joueur",  "\u00a76\uD83D\uDCE4"),
        TAKE_ITEM_FROM_PLAYER   ("Retirer item joueur", "\u00a7c\uD83D\uDCE5"),
        SET_PLAYER_HEALTH       ("Set PV joueur",       "\u00a7c\u2764"),
        SET_PLAYER_HUNGER       ("Set Faim joueur",     "\u00a76\uD83C\uDF57"),
        APPLY_KNOCKBACK         ("Knockback Zone",      "\u00a7c\uD83D\uDCA5"),
        LOCK_HOTBAR             ("Bloquer Hotbar",      "\u00a77\uD83D\uDD12"),
        UNLOCK_HOTBAR           ("Débloquer Hotbar",    "\u00a7a\uD83D\uDD13"),
        TELEPORT_PLAYER_TO_PLAYER("TP joueur→joueur",   "\u00a7d\uD83D\uDEF8"),
        DIMENSION_TELEPORT_ALL  ("TP groupe dimension", "\u00a7d\uD83C\uDF0D"),
        INVENTORY_DISTRIBUTE    ("Distribuer Inventaire","\u00a76\uD83C\uDF92"),
        SET_PLAYER_VISIBILITY   ("Visibilité joueur",   "\u00a77\uD83D\uDC41"),
        // ── Roles & Votes ─────────────────────────────────────────────────
        ASSIGN_ROLE_PRIVATE     ("Assigner Rôle Secret","\u00a7d\uD83C\uDFAD"),
        TRANSFER_ROLE           ("Transférer Rôle",     "\u00a7d\uD83D\uDD04"),
        CHANGE_TEAM_COLOR       ("Couleur Équipe",      "\u00a7e\uD83C\uDFA8"),
        START_VOTE              ("Démarrer Vote",       "\u00a7e\uD83D\uDDF3"),
        // ── State flags ───────────────────────────────────────────────────
        PLAYER_FLAG_SET         ("Set Flag Joueur",     "\u00a77\uD83D\uDD11"),
        // ── Redstone & Blocks ─────────────────────────────────────────────
        SET_BLOCK_AT_POS        ("Set Bloc Position",   "\u00a7c\uD83E\uDDF1"),
        REDSTONE_ACTIVATE       ("Activer Redstone",    "\u00a7c\uD83D\uDD34"),
        // ── Death ─────────────────────────────────────────────────────────
        DEATH_BEHAVIOR_OVERRIDE ("Override Mort",       "\u00a7c\uD83D\uDC80"),
        // ── Random ────────────────────────────────────────────────────────
        WEIGHTED_RANDOM_COMMAND ("Cmd Pondérée",        "\u00a7e\uD83C\uDFB2"),
        // ── Logic ─────────────────────────────────────────────────────────
        CONDITIONAL_BRANCH      ("Branche SI/SINON",    "\u00a7e\uD83D\uDD00");

        public final String label, icon;
        Type(String l, String i) { label = l; icon = i; }
    }

    private String id;
    private Type type;
    private String scopeZoneId = "";
    private String dayNightCondition = ""; // "", "day", "night"

    // POTION
    private String potionId = "minecraft:speed";
    private int amplifier = 0;
    private int potionDurationTicks = 140; // 7s default; use short value (e.g. 40) for zone-scoped effects
    // COMMAND
    private String command = "";
    private int radiusBlocks = 32;
    private int intervalSeconds = 60;
    private int chancePercent = 100;
    // BROADCAST
    private String message = "";
    private String messageColor = "gold";
    // MULTIPLIER
    private String multiplierType = "xp";
    private double multiplierValue = 2.0;
    // SOUND
    private String soundId = "minecraft:entity.experience_orb.pickup";
    private float soundVolume = 1.0f, soundPitch = 1.0f;
    // TITLE
    private String titleText = "", subtitleText = "", titleColor = "gold";
    private int fadeIn = 10, stay = 60, fadeOut = 10;
    // PARTICLE
    private String particleId = "minecraft:heart";
    private int particleCount = 5;
    private double particleSpread = 2.0;
    // ITEM_DROP
    private String itemId = "minecraft:diamond";
    private int itemCount = 1;
    // WEATHER
    private String weatherType = "clear";
    // TIME
    private int timeValue = -1;
    private boolean freezeTime = false;
    // FLAG_OVERRIDE
    private String flagId = "", targetZoneId = "";
    private boolean flagValue = true;
    // ENTITY_SPAWN
    private String entityCommand = "";
    private double spawnX = 0, spawnY = 0, spawnZ = 0;
    private boolean useRelativePos = false;
    // ZONE_SHRINK
    private double zoneCenterX = 0, zoneCenterZ = 0;
    private double zoneRadius = 50; // safe zone radius in blocks
    private double zoneDamage = 2.0; // hearts per tick outside
    private boolean zoneShowBorder = true; // particle border
    // Orbital movement
    private boolean zoneOrbitEnabled = false;
    private double zoneOrbitRadius = 10; // distance from center the eye orbits
    private double zoneOrbitSpeed = 15; // degrees per second
    private double zoneOrbitAngleOffset = 0; // starting angle offset in degrees

    public EventEffect(Type type) { this.id = UUID.randomUUID().toString().substring(0, 8); this.type = type; }
    public EventEffect(String id, Type type) { this.id = id; this.type = type; }

    public String getId() { return id; }
    public Type getType() { return type; }
    public void setType(Type t) { type = t; }
    public String getScopeZoneId() { return scopeZoneId; }
    public void setScopeZoneId(String z) { scopeZoneId = z; }
    public String getDayNightCondition() { return dayNightCondition; }
    public void setDayNightCondition(String c) { dayNightCondition = c; }

    public String getPotionId() { return potionId; }
    public void setPotionId(String p) { potionId = p; }
    public int getAmplifier() { return amplifier; }
    public void setAmplifier(int a) { amplifier = a; }
    public int getPotionDurationTicks() { return potionDurationTicks; }
    public void setPotionDurationTicks(int t) { potionDurationTicks = Math.max(1, t); }
    public String getCommand() { return command; }
    public void setCommand(String c) { command = c; }
    public int getRadiusBlocks() { return radiusBlocks; }
    public void setRadiusBlocks(int r) { radiusBlocks = r; }
    public int getIntervalSeconds() { return intervalSeconds; }
    public void setIntervalSeconds(int i) { intervalSeconds = i; }
    public int getChancePercent() { return chancePercent; }
    public void setChancePercent(int c) { chancePercent = Math.max(0, Math.min(100, c)); }
    public String getMessage() { return message; }
    public void setMessage(String m) { message = m; }
    public String getMessageColor() { return messageColor; }
    public void setMessageColor(String c) { messageColor = c; }
    public String getMultiplierType() { return multiplierType; }
    public void setMultiplierType(String t) { multiplierType = t; }
    public double getMultiplierValue() { return multiplierValue; }
    public void setMultiplierValue(double v) { multiplierValue = v; }
    public String getSoundId() { return soundId; }
    public void setSoundId(String s) { soundId = s; }
    public float getSoundVolume() { return soundVolume; }
    public void setSoundVolume(float v) { soundVolume = v; }
    public float getSoundPitch() { return soundPitch; }
    public void setSoundPitch(float p) { soundPitch = p; }
    public String getTitleText() { return titleText; }
    public void setTitleText(String t) { titleText = t; }
    public String getSubtitleText() { return subtitleText; }
    public void setSubtitleText(String s) { subtitleText = s; }
    public String getTitleColor() { return titleColor; }
    public void setTitleColor(String c) { titleColor = c; }
    public int getFadeIn() { return fadeIn; }
    public void setFadeIn(int f) { fadeIn = f; }
    public int getStay() { return stay; }
    public void setStay(int s) { stay = s; }
    public int getFadeOut() { return fadeOut; }
    public void setFadeOut(int f) { fadeOut = f; }
    public String getParticleId() { return particleId; }
    public void setParticleId(String p) { particleId = p; }
    public int getParticleCount() { return particleCount; }
    public void setParticleCount(int c) { particleCount = c; }
    public double getParticleSpread() { return particleSpread; }
    public void setParticleSpread(double s) { particleSpread = s; }
    public String getItemId() { return itemId; }
    public void setItemId(String i) { itemId = i; }
    public int getItemCount() { return itemCount; }
    public void setItemCount(int c) { itemCount = c; }
    public String getWeatherType() { return weatherType; }
    public void setWeatherType(String w) { weatherType = w; }
    public int getTimeValue() { return timeValue; }
    public void setTimeValue(int t) { timeValue = t; }
    public boolean isFreezeTime() { return freezeTime; }
    public void setFreezeTime(boolean f) { freezeTime = f; }
    public String getFlagId() { return flagId; }
    public void setFlagId(String f) { flagId = f; }
    public String getTargetZoneId() { return targetZoneId; }
    public void setTargetZoneId(String z) { targetZoneId = z; }
    public boolean getFlagValue() { return flagValue; }
    public void setFlagValue(boolean v) { flagValue = v; }
    public String getEntityCommand() { return entityCommand; }
    public void setEntityCommand(String c) { entityCommand = c; }
    public double getSpawnX() { return spawnX; }
    public void setSpawnX(double x) { spawnX = x; }
    public double getSpawnY() { return spawnY; }
    public void setSpawnY(double y) { spawnY = y; }
    public double getSpawnZ() { return spawnZ; }
    public void setSpawnZ(double z) { spawnZ = z; }
    public boolean isUseRelativePos() { return useRelativePos; }
    public void setUseRelativePos(boolean r) { useRelativePos = r; }
    public double getZoneCenterX() { return zoneCenterX; }
    public void setZoneCenterX(double x) { zoneCenterX = x; }
    public double getZoneCenterZ() { return zoneCenterZ; }
    public void setZoneCenterZ(double z) { zoneCenterZ = z; }
    public double getZoneRadius() { return zoneRadius; }
    public void setZoneRadius(double r) { zoneRadius = Math.max(1, r); }
    public double getZoneDamage() { return zoneDamage; }
    public void setZoneDamage(double d) { zoneDamage = d; }
    public boolean isZoneShowBorder() { return zoneShowBorder; }
    public void setZoneShowBorder(boolean b) { zoneShowBorder = b; }
    public boolean isZoneOrbitEnabled() { return zoneOrbitEnabled; }
    public void setZoneOrbitEnabled(boolean b) { zoneOrbitEnabled = b; }
    public double getZoneOrbitRadius() { return zoneOrbitRadius; }
    public void setZoneOrbitRadius(double r) { zoneOrbitRadius = Math.max(0, r); }
    public double getZoneOrbitSpeed() { return zoneOrbitSpeed; }
    public void setZoneOrbitSpeed(double s) { zoneOrbitSpeed = s; }
    public double getZoneOrbitAngleOffset() { return zoneOrbitAngleOffset; }
    public void setZoneOrbitAngleOffset(double a) { zoneOrbitAngleOffset = a; }

    public String getSummary() {
        String s = switch (type) {
            case POTION -> sId(potionId) + " Nv" + (amplifier + 1);
            case COMMAND -> tr(command, 25);
            case BROADCAST -> tr(message, 25);
            case MULTIPLIER -> multiplierType + " x" + multiplierValue;
            case SOUND -> sId(soundId);
            case TITLE -> tr(titleText, 25);
            case PARTICLE -> sId(particleId) + " x" + particleCount;
            case ITEM_DROP -> sId(itemId) + " x" + itemCount;
            case WEATHER -> weatherType;
            case TIME -> (freezeTime ? "Freeze " : "Set ") + timeValue;
            case FLAG_OVERRIDE -> flagId + "=" + flagValue;
            case ENTITY_SPAWN -> tr(entityCommand, 25);
            case ZONE_SHRINK -> "r=" + (int)zoneRadius + (zoneOrbitEnabled ? " orb=" + (int)zoneOrbitRadius : "") + " dmg=" + zoneDamage;
            default -> type.label;
        };
        return s + (scopeZoneId.isBlank() ? "" : " [zone]") + (dayNightCondition.isBlank() ? "" : " [" + dayNightCondition + "]");
    }

    private static String sId(String id) { return id.contains(":") ? id.substring(id.indexOf(':') + 1).replace("_", " ") : id; }
    private static String tr(String s, int m) { return s == null || s.isEmpty() ? "" : s.length() > m ? s.substring(0, m) + ".." : s; }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("type", type.name());
        if (!scopeZoneId.isBlank()) o.addProperty("scopeZone", scopeZoneId);
        if (!dayNightCondition.isBlank()) o.addProperty("dayNight", dayNightCondition);
        switch (type) {
            case POTION -> { o.addProperty("potionId", potionId); o.addProperty("amplifier", amplifier); o.addProperty("potionDuration", potionDurationTicks); o.addProperty("interval", intervalSeconds); }
            case COMMAND -> { o.addProperty("command", command); o.addProperty("radius", radiusBlocks); o.addProperty("interval", intervalSeconds); o.addProperty("chance", chancePercent); }
            case BROADCAST -> { o.addProperty("message", message); o.addProperty("color", messageColor); o.addProperty("interval", intervalSeconds); }
            case MULTIPLIER -> { o.addProperty("multType", multiplierType); o.addProperty("multValue", multiplierValue); }
            case SOUND -> { o.addProperty("soundId", soundId); o.addProperty("volume", soundVolume); o.addProperty("pitch", soundPitch); o.addProperty("interval", intervalSeconds); }
            case TITLE -> { o.addProperty("titleText", titleText); o.addProperty("subtitleText", subtitleText); o.addProperty("titleColor", titleColor); o.addProperty("fadeIn", fadeIn); o.addProperty("stay", stay); o.addProperty("fadeOut", fadeOut); o.addProperty("interval", intervalSeconds); }
            case PARTICLE -> { o.addProperty("particleId", particleId); o.addProperty("count", particleCount); o.addProperty("spread", particleSpread); o.addProperty("interval", intervalSeconds); }
            case ITEM_DROP -> { o.addProperty("itemId", itemId); o.addProperty("itemCount", itemCount); o.addProperty("interval", intervalSeconds); o.addProperty("chance", chancePercent); }
            case WEATHER -> o.addProperty("weatherType", weatherType);
            case TIME -> { o.addProperty("timeValue", timeValue); o.addProperty("freeze", freezeTime); }
            case FLAG_OVERRIDE -> { o.addProperty("flagId", flagId); o.addProperty("targetZone", targetZoneId); o.addProperty("flagValue", flagValue); }
            case ENTITY_SPAWN -> { o.addProperty("entityCommand", entityCommand); o.addProperty("spawnX", spawnX); o.addProperty("spawnY", spawnY); o.addProperty("spawnZ", spawnZ); o.addProperty("relativePos", useRelativePos); o.addProperty("interval", intervalSeconds); }
            case ZONE_SHRINK -> { o.addProperty("zoneCenterX", zoneCenterX); o.addProperty("zoneCenterZ", zoneCenterZ); o.addProperty("zoneRadius", zoneRadius); o.addProperty("zoneDamage", zoneDamage); o.addProperty("showBorder", zoneShowBorder); o.addProperty("orbitEnabled", zoneOrbitEnabled); o.addProperty("orbitRadius", zoneOrbitRadius); o.addProperty("orbitSpeed", zoneOrbitSpeed); o.addProperty("orbitAngle", zoneOrbitAngleOffset); }
        }
        return o;
    }

    public static EventEffect fromJson(JsonObject o) {
        Type type = Type.valueOf(o.get("type").getAsString());
        EventEffect e = new EventEffect(o.has("id") ? o.get("id").getAsString() : UUID.randomUUID().toString().substring(0, 8), type);
        if (o.has("scopeZone")) e.scopeZoneId = o.get("scopeZone").getAsString();
        if (o.has("dayNight")) e.dayNightCondition = o.get("dayNight").getAsString();
        switch (type) {
            case POTION -> { if (o.has("potionId")) e.potionId = o.get("potionId").getAsString(); if (o.has("amplifier")) e.amplifier = o.get("amplifier").getAsInt(); if (o.has("potionDuration")) e.potionDurationTicks = o.get("potionDuration").getAsInt(); if (o.has("interval")) e.intervalSeconds = o.get("interval").getAsInt(); }
            case COMMAND -> { if (o.has("command")) e.command = o.get("command").getAsString(); if (o.has("radius")) e.radiusBlocks = o.get("radius").getAsInt(); if (o.has("interval")) e.intervalSeconds = o.get("interval").getAsInt(); if (o.has("chance")) e.chancePercent = o.get("chance").getAsInt(); }
            case BROADCAST -> { if (o.has("message")) e.message = o.get("message").getAsString(); if (o.has("color")) e.messageColor = o.get("color").getAsString(); if (o.has("interval")) e.intervalSeconds = o.get("interval").getAsInt(); }
            case MULTIPLIER -> { if (o.has("multType")) e.multiplierType = o.get("multType").getAsString(); if (o.has("multValue")) e.multiplierValue = o.get("multValue").getAsDouble(); }
            case SOUND -> { if (o.has("soundId")) e.soundId = o.get("soundId").getAsString(); if (o.has("volume")) e.soundVolume = o.get("volume").getAsFloat(); if (o.has("pitch")) e.soundPitch = o.get("pitch").getAsFloat(); if (o.has("interval")) e.intervalSeconds = o.get("interval").getAsInt(); }
            case TITLE -> { if (o.has("titleText")) e.titleText = o.get("titleText").getAsString(); if (o.has("subtitleText")) e.subtitleText = o.get("subtitleText").getAsString(); if (o.has("titleColor")) e.titleColor = o.get("titleColor").getAsString(); if (o.has("fadeIn")) e.fadeIn = o.get("fadeIn").getAsInt(); if (o.has("stay")) e.stay = o.get("stay").getAsInt(); if (o.has("fadeOut")) e.fadeOut = o.get("fadeOut").getAsInt(); if (o.has("interval")) e.intervalSeconds = o.get("interval").getAsInt(); }
            case PARTICLE -> { if (o.has("particleId")) e.particleId = o.get("particleId").getAsString(); if (o.has("count")) e.particleCount = o.get("count").getAsInt(); if (o.has("spread")) e.particleSpread = o.get("spread").getAsDouble(); if (o.has("interval")) e.intervalSeconds = o.get("interval").getAsInt(); }
            case ITEM_DROP -> { if (o.has("itemId")) e.itemId = o.get("itemId").getAsString(); if (o.has("itemCount")) e.itemCount = o.get("itemCount").getAsInt(); if (o.has("interval")) e.intervalSeconds = o.get("interval").getAsInt(); if (o.has("chance")) e.chancePercent = o.get("chance").getAsInt(); }
            case WEATHER -> { if (o.has("weatherType")) e.weatherType = o.get("weatherType").getAsString(); }
            case TIME -> { if (o.has("timeValue")) e.timeValue = o.get("timeValue").getAsInt(); if (o.has("freeze")) e.freezeTime = o.get("freeze").getAsBoolean(); }
            case FLAG_OVERRIDE -> { if (o.has("flagId")) e.flagId = o.get("flagId").getAsString(); if (o.has("targetZone")) e.targetZoneId = o.get("targetZone").getAsString(); if (o.has("flagValue")) e.flagValue = o.get("flagValue").getAsBoolean(); }
            case ENTITY_SPAWN -> { if (o.has("entityCommand")) e.entityCommand = o.get("entityCommand").getAsString(); if (o.has("spawnX")) e.spawnX = o.get("spawnX").getAsDouble(); if (o.has("spawnY")) e.spawnY = o.get("spawnY").getAsDouble(); if (o.has("spawnZ")) e.spawnZ = o.get("spawnZ").getAsDouble(); if (o.has("relativePos")) e.useRelativePos = o.get("relativePos").getAsBoolean(); if (o.has("interval")) e.intervalSeconds = o.get("interval").getAsInt(); }
            case ZONE_SHRINK -> { if (o.has("zoneCenterX")) e.zoneCenterX = o.get("zoneCenterX").getAsDouble(); if (o.has("zoneCenterZ")) e.zoneCenterZ = o.get("zoneCenterZ").getAsDouble(); if (o.has("zoneRadius")) e.zoneRadius = o.get("zoneRadius").getAsDouble(); if (o.has("zoneDamage")) e.zoneDamage = o.get("zoneDamage").getAsDouble(); if (o.has("showBorder")) e.zoneShowBorder = o.get("showBorder").getAsBoolean(); if (o.has("orbitEnabled")) e.zoneOrbitEnabled = o.get("orbitEnabled").getAsBoolean(); if (o.has("orbitRadius")) e.zoneOrbitRadius = o.get("orbitRadius").getAsDouble(); if (o.has("orbitSpeed")) e.zoneOrbitSpeed = o.get("orbitSpeed").getAsDouble(); if (o.has("orbitAngle")) e.zoneOrbitAngleOffset = o.get("orbitAngle").getAsDouble(); }
        }
        return e;
    }
}
