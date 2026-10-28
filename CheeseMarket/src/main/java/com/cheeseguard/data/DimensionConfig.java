package com.cheeseguard.data;

import com.google.gson.JsonObject;

/**
 * Stores config for a custom dimension created by CheeseGuard.
 */
public class DimensionConfig {

    public enum GenType {
        VOID("Vide", "\uD83C\uDF11"),
        FLAT("Plat", "\u2B1C"),
        NORMAL("Normal", "\uD83C\uDF0D"),
        NETHER("Nether", "\uD83D\uDD25"),
        END("End", "\u2B50");

        public final String label, icon;
        GenType(String l, String i) { label = l; icon = i; }
    }

    private String id;           // e.g., "arena_pvp" → namespace = cheeseguard:arena_pvp
    private String displayName;  // e.g., "Arène PvP"
    private GenType genType = GenType.VOID;
    private long seed = 0;       // 0 = server default seed
    private String flatLayers = "bedrock,3*stone,grass_block"; // only for FLAT

    // Spawn point (where players TP to by default)
    private double spawnX = 0.5, spawnY = 65, spawnZ = 0.5;

    // Time/weather locks
    private boolean timeLocked = false;
    private int lockedTime = 6000; // noon
    private boolean weatherLocked = false;
    private String lockedWeather = "clear";

    // Rules
    private boolean pvp = true;
    private boolean mobSpawning = true;
    private boolean keepInventory = false;
    /**
     * Per-dimension difficulty override.
     * "default" = use server difficulty (no override)
     * "peaceful" = hostile mobs don't target players (simulated, MC difficulty is server-global)
     * "easy" / "normal" / "hard" = note: these are cosmetic labels only since MC difficulty
     * is global. Only "peaceful" has real effect (mob aggro cleared every tick).
     */
    private String difficulty = "default";

    // Access control
    private boolean enabled = true; // if false, players can't enter
    private String enabledMessage = "Cette dimension est fermee pour le moment.";
    private java.util.Set<String> whitelist = new java.util.HashSet<>(); // UUIDs allowed when disabled

    // Construction mode
    private boolean constructionMode = false; // save+clear inventory on enter, restore on exit
    private java.util.Set<String> constructionBuilders = new java.util.HashSet<>(); // UUIDs of creative builders
    private boolean commandBlocks = false;  // allow command blocks (default disabled)
    private boolean enderChest = true;      // allow ender chests (default enabled)
    private boolean generateStructures = true; // generate structures (default on; VOID always off)
    private boolean cobblemonBlocked = false;  // block ALL cobblemon spawning in this dimension

    // Snapshot / auto-reset system
    public enum ResetTrigger {
        MANUAL("Manuel"),
        ON_EMPTY("Vide → Reset"),
        SCHEDULED("Programmé");
        public final String label;
        ResetTrigger(String l) { this.label = l; }
    }
    private boolean snapshotEnabled = false;
    private ResetTrigger snapshotResetTrigger = ResetTrigger.MANUAL;
    private int  snapshotResetHour = 4; // 0–23, used for SCHEDULED
    private long lastResetAt       = 0; // epoch ms of last reset

    // Protection (vanilla dimensions can't be deleted)
    private boolean builtIn = false;

    public DimensionConfig(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    // ── Getters / Setters ────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String n) { this.displayName = n; }
    public GenType getGenType() { return genType; }
    public void setGenType(GenType t) { this.genType = t; }
    public long getSeed() { return seed; }
    public void setSeed(long s) { this.seed = s; }
    public String getFlatLayers() { return flatLayers; }
    public void setFlatLayers(String l) { this.flatLayers = l; }
    public double getSpawnX() { return spawnX; }
    public void setSpawnX(double v) { this.spawnX = v; }
    public double getSpawnY() { return spawnY; }
    public void setSpawnY(double v) { this.spawnY = v; }
    public double getSpawnZ() { return spawnZ; }
    public void setSpawnZ(double v) { this.spawnZ = v; }
    public boolean isTimeLocked() { return timeLocked; }
    public void setTimeLocked(boolean b) { this.timeLocked = b; }
    public int getLockedTime() { return lockedTime; }
    public void setLockedTime(int t) { this.lockedTime = t; }
    public boolean isWeatherLocked() { return weatherLocked; }
    public void setWeatherLocked(boolean b) { this.weatherLocked = b; }
    public String getLockedWeather() { return lockedWeather; }
    public void setLockedWeather(String w) { this.lockedWeather = w; }
    public boolean isPvp() { return pvp; }
    public void setPvp(boolean b) { this.pvp = b; }
    public boolean isMobSpawning() { return mobSpawning; }
    public void setMobSpawning(boolean b) { this.mobSpawning = b; }
    public boolean isKeepInventory() { return keepInventory; }
    public void setKeepInventory(boolean b) { this.keepInventory = b; }
    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String d) { this.difficulty = d; }
    public boolean isPeaceful() { return "peaceful".equals(difficulty); }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean b) { this.enabled = b; }
    public String getEnabledMessage() { return enabledMessage; }
    public void setEnabledMessage(String m) { this.enabledMessage = m; }
    public java.util.Set<String> getWhitelist() { return whitelist; }
    public boolean isWhitelisted(String uuid) { return whitelist.contains(uuid); }
    public void addWhitelist(String uuid) { whitelist.add(uuid); }
    public void removeWhitelist(String uuid) { whitelist.remove(uuid); }
    /**
     * Check if a player UUID is allowed to enter this dimension.
     * Returns true if dimension is enabled, or player is whitelisted, or player is op.
     */
    public boolean canPlayerEnter(String uuid) {
        return enabled || whitelist.contains(uuid);
    }
    public boolean isConstructionMode() { return constructionMode; }
    public void setConstructionMode(boolean b) { this.constructionMode = b; }
    public boolean isCommandBlocks() { return commandBlocks; }
    public void setCommandBlocks(boolean b) { this.commandBlocks = b; }
    public boolean isEnderChest() { return enderChest; }
    public void setEnderChest(boolean b) { this.enderChest = b; }
    public boolean isGenerateStructures() { return generateStructures; }
    public void setGenerateStructures(boolean b) { this.generateStructures = b; }
    public boolean isCobblemonBlocked() { return cobblemonBlocked; }
    public void setCobblemonBlocked(boolean b) { this.cobblemonBlocked = b; }
    // Snapshot
    public boolean isSnapshotEnabled() { return snapshotEnabled; }
    public void setSnapshotEnabled(boolean b) { this.snapshotEnabled = b; }
    public ResetTrigger getSnapshotResetTrigger() { return snapshotResetTrigger; }
    public void setSnapshotResetTrigger(ResetTrigger t) { this.snapshotResetTrigger = t; }
    public int getSnapshotResetHour() { return snapshotResetHour; }
    public void setSnapshotResetHour(int h) { this.snapshotResetHour = Math.max(0, Math.min(23, h)); }
    public long getLastResetAt() { return lastResetAt; }
    public void setLastResetAt(long ms) { this.lastResetAt = ms; }
    public java.util.Set<String> getConstructionBuilders() { return constructionBuilders; }
    public boolean isConstructionBuilder(String uuid) { return constructionBuilders.contains(uuid); }
    public void addConstructionBuilder(String uuid) { constructionBuilders.add(uuid); }
    public void removeConstructionBuilder(String uuid) { constructionBuilders.remove(uuid); }
    public boolean isBuiltIn() { return builtIn; }
    public void setBuiltIn(boolean b) { this.builtIn = b; }

    /**
     * Full world identifier string: "cheeseguard:<id>" for custom, or vanilla like "minecraft:overworld".
     */
    public String getWorldId() {
        if (builtIn) return id; // already full ID like "minecraft:overworld"
        return "cheeseguard:" + id;
    }

    // ── JSON ─────────────────────────────────────────────────

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("displayName", displayName);
        o.addProperty("genType", genType.name());
        o.addProperty("seed", seed);
        o.addProperty("flatLayers", flatLayers);
        o.addProperty("spawnX", spawnX);
        o.addProperty("spawnY", spawnY);
        o.addProperty("spawnZ", spawnZ);
        o.addProperty("timeLocked", timeLocked);
        o.addProperty("lockedTime", lockedTime);
        o.addProperty("weatherLocked", weatherLocked);
        o.addProperty("lockedWeather", lockedWeather);
        o.addProperty("pvp", pvp);
        o.addProperty("mobSpawning", mobSpawning);
        o.addProperty("keepInventory", keepInventory);
        o.addProperty("difficulty", difficulty);
        o.addProperty("builtIn", builtIn);
        o.addProperty("enabled", enabled);
        o.addProperty("enabledMessage", enabledMessage);
        com.google.gson.JsonArray wl = new com.google.gson.JsonArray();
        for (String uuid : whitelist) wl.add(uuid);
        o.add("whitelist", wl);
        o.addProperty("constructionMode", constructionMode);
        o.addProperty("commandBlocks", commandBlocks);
        o.addProperty("enderChest", enderChest);
        o.addProperty("generateStructures", generateStructures);
        o.addProperty("cobblemonBlocked", cobblemonBlocked);
        o.addProperty("snapshotEnabled", snapshotEnabled);
        o.addProperty("snapshotResetTrigger", snapshotResetTrigger.name());
        o.addProperty("snapshotResetHour", snapshotResetHour);
        o.addProperty("lastResetAt", lastResetAt);
        com.google.gson.JsonArray builders = new com.google.gson.JsonArray();
        for (String uuid : constructionBuilders) builders.add(uuid);
        o.add("constructionBuilders", builders);
        return o;
    }

    public static DimensionConfig fromJson(JsonObject o) {
        DimensionConfig c = new DimensionConfig(
            o.get("id").getAsString(),
            o.has("displayName") ? o.get("displayName").getAsString() : o.get("id").getAsString()
        );
        if (o.has("genType")) try { c.genType = GenType.valueOf(o.get("genType").getAsString()); } catch (Exception ignored) {}
        if (o.has("seed")) c.seed = o.get("seed").getAsLong();
        if (o.has("flatLayers")) c.flatLayers = o.get("flatLayers").getAsString();
        if (o.has("spawnX")) c.spawnX = o.get("spawnX").getAsDouble();
        if (o.has("spawnY")) c.spawnY = o.get("spawnY").getAsDouble();
        if (o.has("spawnZ")) c.spawnZ = o.get("spawnZ").getAsDouble();
        if (o.has("timeLocked")) c.timeLocked = o.get("timeLocked").getAsBoolean();
        if (o.has("lockedTime")) c.lockedTime = o.get("lockedTime").getAsInt();
        if (o.has("weatherLocked")) c.weatherLocked = o.get("weatherLocked").getAsBoolean();
        if (o.has("lockedWeather")) c.lockedWeather = o.get("lockedWeather").getAsString();
        if (o.has("pvp")) c.pvp = o.get("pvp").getAsBoolean();
        if (o.has("mobSpawning")) c.mobSpawning = o.get("mobSpawning").getAsBoolean();
        if (o.has("keepInventory")) c.keepInventory = o.get("keepInventory").getAsBoolean();
        if (o.has("difficulty")) c.difficulty = o.get("difficulty").getAsString();
        if (o.has("builtIn")) c.builtIn = o.get("builtIn").getAsBoolean();
        if (o.has("enabled")) c.enabled = o.get("enabled").getAsBoolean();
        if (o.has("enabledMessage")) c.enabledMessage = o.get("enabledMessage").getAsString();
        if (o.has("whitelist")) {
            for (var el : o.getAsJsonArray("whitelist")) c.whitelist.add(el.getAsString());
        }
        if (o.has("constructionMode")) c.constructionMode = o.get("constructionMode").getAsBoolean();
        if (o.has("commandBlocks")) c.commandBlocks = o.get("commandBlocks").getAsBoolean();
        if (o.has("enderChest")) c.enderChest = o.get("enderChest").getAsBoolean();
        if (o.has("generateStructures")) c.generateStructures = o.get("generateStructures").getAsBoolean();
        if (o.has("cobblemonBlocked")) c.cobblemonBlocked = o.get("cobblemonBlocked").getAsBoolean();
        if (o.has("snapshotEnabled")) c.snapshotEnabled = o.get("snapshotEnabled").getAsBoolean();
        if (o.has("snapshotResetTrigger")) try { c.snapshotResetTrigger = ResetTrigger.valueOf(o.get("snapshotResetTrigger").getAsString()); } catch (Exception ignored) {}
        if (o.has("snapshotResetHour")) c.snapshotResetHour = o.get("snapshotResetHour").getAsInt();
        if (o.has("lastResetAt")) c.lastResetAt = o.get("lastResetAt").getAsLong();
        if (o.has("constructionBuilders")) {
            for (var el : o.getAsJsonArray("constructionBuilders")) c.constructionBuilders.add(el.getAsString());
        }
        return c;
    }
}
