package com.cheeseguard.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.*;
import com.cheeseguard.data.EventAct;

/**
 * Full server event with lifecycle, phases, participants, scoring, restrictions.
 */
public class ServerEvent {

    // ── Enums ────────────────────────────────────────────────────────────────

    public enum State { IDLE, ANNOUNCED, QUEUE, COUNTDOWN, ACTIVE, ENDING, CLEANUP }

    public enum TriggerType {
        MANUAL("Manuel", "🖱"), SCHEDULED("Planifie", "⏰"),
        RECURRING("Recurrent", "🔁"), LUNAR("Lunaire", "🌙"),
        ZONE_ENTRY("Entree zone", "🚪");
        public final String label, icon;
        TriggerType(String l, String i) { label = l; icon = i; }
    }

    public enum EventMode { SERVER_EVENT, MINIGAME }

    public enum TeamMode { SOLO, DUOS, TEAMS, COOP }

    public enum VictoryCondition { TIMER, SCORE_MAX, FIRST_TO_X, SURVIVAL, COOP_GOAL }

    public enum DeathBehavior { RESPAWN_EVENT, BECOME_SPECTATOR }

    // ── Identity ─────────────────────────────────────────────────────────────

    private String id;
    private String name;
    private String description = "";
    private String displayColor = "gold";
    private EventMode mode = EventMode.SERVER_EVENT;
    private String miniGameType = ""; // MiniGameManager.MiniGameType name (e.g., "SPLEEF", "DEATHMATCH")
    private String fishingMode = ""; // "" = disabled, "REALISTIC" = catch to score, "ARCADE" = instant score
    private JsonObject miniGameConfig = new JsonObject(); // per-mini-game config (read by MiniGameManager)

    // ── Trigger ──────────────────────────────────────────────────────────────

    private TriggerType trigger = TriggerType.MANUAL;
    private String lunarEventId = "";
    private int scheduledDelayMinutes = 0;
    private int recurringIntervalMinutes = 1440;
    private String triggerZoneId = ""; // For ZONE_ENTRY trigger
    private boolean playerLaunchable = false; // Can players launch this event via /cg event launch?

    // ── Timing ───────────────────────────────────────────────────────────────

    private int announceDurationSeconds = 60;
    private int countdownSeconds = 10;
    private int cooldownMinutes = 0;
    private long lastRunTimestamp = 0;

    // ── Scope ────────────────────────────────────────────────────────────────

    private List<String> scopeZoneIds = new ArrayList<>(); // empty = global

    // ── Inscription ──────────────────────────────────────────────────────────

    private int minParticipants = 0;
    private int maxParticipants = 0; // 0 = unlimited
    private TeamMode teamMode = TeamMode.SOLO;
    private int teamSize = 2;
    private DeathBehavior deathBehavior = DeathBehavior.RESPAWN_EVENT;
    private boolean endOnAllDead = false;

    // ── Messages ─────────────────────────────────────────────────────────────

    private String announceMessage = "Un evenement va bientot commencer !";
    private String announceColor = "gold";
    private String startMessage = "L'evenement commence !";
    private String startColor = "green";
    private String endMessage = "L'evenement est termine !";
    private String endColor = "gray";

    // ── Discord ──────────────────────────────────────────────────────────────

    private boolean discordNotify = false;

    // ── Bossbar ──────────────────────────────────────────────────────────────

    private boolean bossbarEnabled = true;
    private String bossbarColor = "yellow"; // "pink","blue","red","green","yellow","purple","white"

    // ── Locations ────────────────────────────────────────────────────────────

    private double tpParticipantX, tpParticipantY, tpParticipantZ;
    private String tpParticipantWorld = "";
    // Separate respawn position used by RESPAWN_EVENT death behavior.
    // Falls back to tpParticipant coords if not set (empty world = not set).
    private double respawnX, respawnY, respawnZ;
    private String respawnWorld = "";
    private double tpSpectatorX, tpSpectatorY, tpSpectatorZ;
    private String tpSpectatorWorld = "";
    // Zone-based TP: if non-empty, use random positions from these zones instead of fixed coords
    private List<String> tpParticipantZoneIds = new ArrayList<>();
    private List<String> tpSpectatorZoneIds = new ArrayList<>();
    // Return mode: "saved" = saved position, "spawn" = world spawn, "coords" = custom coords
    private String returnMode = "saved";
    private double returnX, returnY, returnZ;
    private String returnWorld = "";

    // ── Kit ──────────────────────────────────────────────────────────────────

    private List<String> kitEntryCommands = new ArrayList<>();  // commands run on participant at start
    private List<String> kitExitCommands = new ArrayList<>();   // commands run on participant at end
    private boolean saveInventory = false;
    private boolean saveTeamCobblemon = false;

    // ── Restrictions ─────────────────────────────────────────────────────────

    private List<String> blacklistItems = new ArrayList<>();      // "minecraft:elytra"
    private List<String> whitelistItems = new ArrayList<>();
    private List<String> blacklistEnchants = new ArrayList<>();
    private List<String> blacklistPokemon = new ArrayList<>();    // "cobblemon:mewtwo"
    private boolean forceEmptyInventory = false;
    private boolean disablePokemonSummon = false;                 // recall pokemon every 3s during event
    private boolean voiceChatGroup = false;                        // auto-join participants into voicechat group

    // ── Flag overrides (temporary during event) ──────────────────────────────

    private Map<String, Map<String, Boolean>> flagOverrides = new HashMap<>(); // zoneId -> {flagId -> value}

    // ── Scoring ──────────────────────────────────────────────────────────────

    private VictoryCondition victoryCondition = VictoryCondition.TIMER;
    private int victoryTargetScore = 100; // For FIRST_TO_X and COOP_GOAL
    private boolean scoreboardVisible = true;

    private List<ScoreTrigger> scoreTriggers = new ArrayList<>();

    // ── Rewards ──────────────────────────────────────────────────────────────

    private List<EventReward> rewards = new ArrayList<>();

    // ── Phases (legacy) ──────────────────────────────────────────────────────
    private List<EventPhase> phases = new ArrayList<>();

    // ── Acts (new timeline system) ────────────────────────────────────────────
    private List<EventAct> acts = new ArrayList<>();
    private boolean useActSystem = false; // false = legacy phases, true = new acts

    // ── Scoreboard display config ─────────────────────────────────────────────
    // Position preset: "top_right", "top_left", "bottom_right", "bottom_left", "custom"
    private String scoreboardPosition = "top_right";
    private int scoreboardCustomX = 0;    // only used when position = "custom"
    private int scoreboardCustomY = 0;
    private int scoreboardMaxRows = 10;   // max rows displayed (0 = all)
    private boolean scoreboardCompact = false; // show only top 3
    private String scoreboardTitleColor = "gold";
    private String scoreboardTextColor = "white";
    private String scoreboardBgColor = ""; // "" = default vanilla bg
    private int scoreboardBgOpacity = 180; // 0-255
    private boolean scoreboardShowRank = true;  // show #1 #2 etc
    private boolean scoreboardShowScore = true;
    private String scoreboardPrefix = ""; // prefix before player name
    private int scoreboardFontSize = 1;   // 1=normal, future: scaleable

    // ── Chaining ─────────────────────────────────────────────────────────────

    private String chainEventId = ""; // event to start when this one ends
    private boolean lightningMobsOnEnd = false; // strike lightning on mobs near event zone at end
    private int lightningRadius = 50; // radius to search for mobs to strike
    // Optional custom center for lightning search. Falls back to tpParticipant coords if world is blank.
    private double lightningX, lightningY, lightningZ;
    private String lightningWorld = "";

    // ── Exclusion group ──────────────────────────────────────────────────────

    private String exclusionGroup = "";
    private int exclusionPriority = 0;

    // ═══ Runtime state (transient) ═══════════════════════════════════════════

    private transient State state = State.IDLE;
    private transient long stateStartedAt = 0;
    private transient int currentPhaseIndex = -1;
    private transient long phaseStartedAt = 0;
    private transient Map<UUID, EventParticipant> participants = new HashMap<>();
    private transient Map<String, Long> effectLastRun = new HashMap<>();
    private transient java.util.List<EventParticipant> rankedCache = null;
    public java.util.List<EventParticipant> getRankedCache() { return rankedCache; }
    public void setRankedCache(java.util.List<EventParticipant> r) { rankedCache = r; }
    // Timeline runtime state
    private transient boolean signalEnd = false;
    private transient Map<String, Double> activeMultipliers = new HashMap<>();  // type -> multiplier
    private transient Map<String, Boolean> runtimeFlagOverrides = new HashMap<>(); // zoneId:flagId -> value
    private transient String runtimeDeathBehaviorOverride = "";

    public boolean isSignalEnd() { return signalEnd; }
    public void setSignalEnd(boolean s) { signalEnd = s; }
    public void clearSignalEnd() { signalEnd = false; }

    public void setActiveMultiplier(String type, double value) {
        if (activeMultipliers == null) activeMultipliers = new HashMap<>();
        activeMultipliers.put(type, value);
    }
    public void clearActiveMultiplier(String type) {
        if (activeMultipliers != null) activeMultipliers.remove(type);
    }
    public double getActiveMultiplier(String type) {
        if (activeMultipliers == null) return 1.0;
        return activeMultipliers.getOrDefault(type, 1.0);
    }

    public void setFlagOverride(String key, boolean value) {
        if (runtimeFlagOverrides == null) runtimeFlagOverrides = new HashMap<>();
        runtimeFlagOverrides.put(key, value);
    }
    public void clearFlagOverride(String key) {
        if (runtimeFlagOverrides != null) runtimeFlagOverrides.remove(key);
    }
    public Boolean getRuntimeFlagOverride(String key) {
        if (runtimeFlagOverrides == null) return null;
        return runtimeFlagOverrides.get(key);
    }

    public String getRuntimeDeathBehaviorOverride() { return runtimeDeathBehaviorOverride != null ? runtimeDeathBehaviorOverride : ""; }
    public void setRuntimeDeathBehaviorOverride(String s) { runtimeDeathBehaviorOverride = s; }

    // ═══ Constructor ═════════════════════════════════════════════════════════

    public ServerEvent(String id, String name) { this.id = id; this.name = name; }

    // ═══ Getters/Setters (identity) ══════════════════════════════════════════

    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String n) { name = n; }
    public String getDescription() { return description; }
    public void setDescription(String d) { description = d; }
    public String getDisplayColor() { return displayColor; }
    public void setDisplayColor(String c) { displayColor = c; }
    public EventMode getMode() { return mode; }
    public void setMode(EventMode m) { mode = m; }
    public String getMiniGameType() { return miniGameType; }
    public void setMiniGameType(String t) { miniGameType = t; }
    public String getFishingMode() { return fishingMode; }
    public void setFishingMode(String m) { fishingMode = m; }
    public JsonObject getMiniGameConfig() { return miniGameConfig; }
    public void setMiniGameConfig(JsonObject cfg) { miniGameConfig = cfg != null ? cfg : new JsonObject(); }

    // trigger
    public TriggerType getTrigger() { return trigger; }
    public void setTrigger(TriggerType t) { trigger = t; }
    public String getLunarEventId() { return lunarEventId; }
    public void setLunarEventId(String id) { lunarEventId = id; }
    public int getScheduledDelayMinutes() { return scheduledDelayMinutes; }
    public void setScheduledDelayMinutes(int m) { scheduledDelayMinutes = m; }
    public int getRecurringIntervalMinutes() { return recurringIntervalMinutes; }
    public void setRecurringIntervalMinutes(int m) { recurringIntervalMinutes = m; }
    public String getTriggerZoneId() { return triggerZoneId; }
    public void setTriggerZoneId(String z) { triggerZoneId = z; }
    public boolean isPlayerLaunchable() { return playerLaunchable; }
    public void setPlayerLaunchable(boolean b) { playerLaunchable = b; }

    // timing
    public int getAnnounceDurationSeconds() { return announceDurationSeconds; }
    public void setAnnounceDurationSeconds(int s) { announceDurationSeconds = s; }
    public int getCountdownSeconds() { return countdownSeconds; }
    public void setCountdownSeconds(int s) { countdownSeconds = s; }
    public int getCooldownMinutes() { return cooldownMinutes; }
    public void setCooldownMinutes(int m) { cooldownMinutes = m; }
    public long getLastRunTimestamp() { return lastRunTimestamp; }
    public void setLastRunTimestamp(long t) { lastRunTimestamp = t; }

    // scope
    public List<String> getScopeZoneIds() { return scopeZoneIds; }
    public void setScopeZoneIds(List<String> z) { scopeZoneIds = z; }

    // inscription
    public int getMinParticipants() { return minParticipants; }
    public void setMinParticipants(int m) { minParticipants = m; }
    public int getMaxParticipants() { return maxParticipants; }
    public void setMaxParticipants(int m) { maxParticipants = m; }
    public TeamMode getTeamMode() { return teamMode; }
    public void setTeamMode(TeamMode m) { teamMode = m; }
    public int getTeamSize() { return teamSize; }
    public void setTeamSize(int s) { teamSize = s; }
    public DeathBehavior getDeathBehavior() { return deathBehavior; }
    public void setDeathBehavior(DeathBehavior d) { deathBehavior = d; }
    public boolean isEndOnAllDead() { return endOnAllDead; }
    public void setEndOnAllDead(boolean b) { endOnAllDead = b; }

    // messages
    public String getAnnounceMessage() { return announceMessage; }
    public void setAnnounceMessage(String m) { announceMessage = m; }
    public String getAnnounceColor() { return announceColor; }
    public void setAnnounceColor(String c) { announceColor = c; }
    public String getStartMessage() { return startMessage; }
    public void setStartMessage(String m) { startMessage = m; }
    public String getStartColor() { return startColor; }
    public void setStartColor(String c) { startColor = c; }
    public String getEndMessage() { return endMessage; }
    public void setEndMessage(String m) { endMessage = m; }
    public String getEndColor() { return endColor; }
    public void setEndColor(String c) { endColor = c; }

    // discord & bossbar
    public boolean isDiscordNotify() { return discordNotify; }
    public void setDiscordNotify(boolean b) { discordNotify = b; }
    public boolean isBossbarEnabled() { return bossbarEnabled; }
    public void setBossbarEnabled(boolean b) { bossbarEnabled = b; }
    public String getBossbarColor() { return bossbarColor; }
    public void setBossbarColor(String c) { bossbarColor = c; }

    // locations
    public double getTpParticipantX() { return tpParticipantX; }
    public void setTpParticipantX(double x) { tpParticipantX = x; }
    public double getTpParticipantY() { return tpParticipantY; }
    public void setTpParticipantY(double y) { tpParticipantY = y; }
    public double getTpParticipantZ() { return tpParticipantZ; }
    public void setTpParticipantZ(double z) { tpParticipantZ = z; }
    public String getTpParticipantWorld() { return tpParticipantWorld; }
    public void setTpParticipantWorld(String w) { tpParticipantWorld = w; }

    public double getRespawnX() { return respawnX; }
    public void setRespawnX(double x) { respawnX = x; }
    public double getRespawnY() { return respawnY; }
    public void setRespawnY(double y) { respawnY = y; }
    public double getRespawnZ() { return respawnZ; }
    public void setRespawnZ(double z) { respawnZ = z; }
    public String getRespawnWorld() { return respawnWorld; }
    public void setRespawnWorld(String w) { respawnWorld = w; }
    /** True if a dedicated respawn position has been configured. */
    public boolean hasRespawnPos() { return !respawnWorld.isBlank(); }
    public double getTpSpectatorX() { return tpSpectatorX; }
    public void setTpSpectatorX(double x) { tpSpectatorX = x; }
    public double getTpSpectatorY() { return tpSpectatorY; }
    public void setTpSpectatorY(double y) { tpSpectatorY = y; }
    public double getTpSpectatorZ() { return tpSpectatorZ; }
    public void setTpSpectatorZ(double z) { tpSpectatorZ = z; }
    public String getTpSpectatorWorld() { return tpSpectatorWorld; }
    public void setTpSpectatorWorld(String w) { tpSpectatorWorld = w; }
    public List<String> getTpParticipantZoneIds() { return tpParticipantZoneIds; }
    public void setTpParticipantZoneIds(List<String> ids) { tpParticipantZoneIds = ids != null ? ids : new ArrayList<>(); }
    public List<String> getTpSpectatorZoneIds() { return tpSpectatorZoneIds; }
    public void setTpSpectatorZoneIds(List<String> ids) { tpSpectatorZoneIds = ids != null ? ids : new ArrayList<>(); }
    public String getReturnMode() { return returnMode; }
    public void setReturnMode(String m) { returnMode = m; }
    public double getReturnX() { return returnX; }
    public void setReturnX(double x) { returnX = x; }
    public double getReturnY() { return returnY; }
    public void setReturnY(double y) { returnY = y; }
    public double getReturnZ() { return returnZ; }
    public void setReturnZ(double z) { returnZ = z; }
    public String getReturnWorld() { return returnWorld; }
    public void setReturnWorld(String w) { returnWorld = w; }

    // kit
    public List<String> getKitEntryCommands() { return kitEntryCommands; }
    public List<String> getKitExitCommands() { return kitExitCommands; }
    public boolean isSaveInventory() { return saveInventory; }
    public void setSaveInventory(boolean b) { saveInventory = b; }
    public boolean isSaveTeamCobblemon() { return saveTeamCobblemon; }
    public void setSaveTeamCobblemon(boolean b) { saveTeamCobblemon = b; }

    // restrictions
    public List<String> getBlacklistItems() { return blacklistItems; }
    public List<String> getWhitelistItems() { return whitelistItems; }
    public List<String> getBlacklistEnchants() { return blacklistEnchants; }
    public List<String> getBlacklistPokemon() { return blacklistPokemon; }
    public boolean isForceEmptyInventory() { return forceEmptyInventory; }
    public void setForceEmptyInventory(boolean b) { forceEmptyInventory = b; }
    public boolean isDisablePokemonSummon() { return disablePokemonSummon; }
    public void setDisablePokemonSummon(boolean b) { disablePokemonSummon = b; }
    public boolean isVoiceChatGroup() { return voiceChatGroup; }
    public void setVoiceChatGroup(boolean b) { voiceChatGroup = b; }

    // flag overrides
    public Map<String, Map<String, Boolean>> getFlagOverrides() { return flagOverrides; }

    // scoring
    public VictoryCondition getVictoryCondition() { return victoryCondition; }
    public void setVictoryCondition(VictoryCondition v) { victoryCondition = v; }
    public int getVictoryTargetScore() { return victoryTargetScore; }
    public void setVictoryTargetScore(int s) { victoryTargetScore = s; }
    public boolean isScoreboardVisible() { return scoreboardVisible; }
    public void setScoreboardVisible(boolean b) { scoreboardVisible = b; }
    public List<ScoreTrigger> getScoreTriggers() { return scoreTriggers; }

    // rewards
    public List<EventReward> getRewards() { return rewards; }

    // phases (legacy)
    public List<EventPhase> getPhases() { return phases; }
    public EventPhase getPhase(String phaseId) { for (EventPhase p : phases) if (p.getId().equals(phaseId)) return p; return null; }
    public void addPhase(EventPhase p) { phases.add(p); }
    public void removePhase(String phaseId) { phases.removeIf(p -> p.getId().equals(phaseId)); }

    // acts (new timeline system)
    public List<EventAct> getActs() { return acts; }
    public EventAct getAct(String actId) { for (EventAct a : acts) if (a.getId().equals(actId)) return a; return null; }
    public void addAct(EventAct a) { acts.add(a); }
    public void removeAct(String actId) { acts.removeIf(a -> a.getId().equals(actId)); }
    public boolean isUseActSystem() { return useActSystem; }
    public void setUseActSystem(boolean b) { useActSystem = b; }

    /** Auto-migrate legacy phases to acts if needed */
    public void migrateToActSystem() {
        if (useActSystem || !acts.isEmpty()) return;
        for (int i = 0; i < phases.size(); i++) {
            acts.add(EventAct.fromLegacyPhase(phases.get(i), i));
        }
        useActSystem = true;
    }

    /** Ensure at least one act exists */
    public void ensureDefaultAct() {
        if (acts.isEmpty()) {
            EventAct act = new EventAct();
            act.setName("Acte 1");
            act.setDurationSeconds(300);
            acts.add(act);
            useActSystem = true;
        }
    }

    // scoreboard display
    public String getScoreboardPosition() { return scoreboardPosition; }
    public void setScoreboardPosition(String p) { scoreboardPosition = p; }
    public int getScoreboardCustomX() { return scoreboardCustomX; }
    public void setScoreboardCustomX(int x) { scoreboardCustomX = x; }
    public int getScoreboardCustomY() { return scoreboardCustomY; }
    public void setScoreboardCustomY(int y) { scoreboardCustomY = y; }
    public int getScoreboardMaxRows() { return scoreboardMaxRows; }
    public void setScoreboardMaxRows(int r) { scoreboardMaxRows = r; }
    public boolean isScoreboardCompact() { return scoreboardCompact; }
    public void setScoreboardCompact(boolean b) { scoreboardCompact = b; }
    public String getScoreboardTitleColor() { return scoreboardTitleColor; }
    public void setScoreboardTitleColor(String c) { scoreboardTitleColor = c; }
    public String getScoreboardTextColor() { return scoreboardTextColor; }
    public void setScoreboardTextColor(String c) { scoreboardTextColor = c; }
    public String getScoreboardBgColor() { return scoreboardBgColor; }
    public void setScoreboardBgColor(String c) { scoreboardBgColor = c; }
    public int getScoreboardBgOpacity() { return scoreboardBgOpacity; }
    public void setScoreboardBgOpacity(int o) { scoreboardBgOpacity = Math.max(0, Math.min(255, o)); }
    public boolean isScoreboardShowRank() { return scoreboardShowRank; }
    public void setScoreboardShowRank(boolean b) { scoreboardShowRank = b; }
    public boolean isScoreboardShowScore() { return scoreboardShowScore; }
    public void setScoreboardShowScore(boolean b) { scoreboardShowScore = b; }
    public String getScoreboardPrefix() { return scoreboardPrefix; }
    public void setScoreboardPrefix(String p) { scoreboardPrefix = p; }
    public int getScoreboardFontSize() { return scoreboardFontSize; }
    public void setScoreboardFontSize(int s) { scoreboardFontSize = s; }

    /** Convenience: get all effects from all phases (for GUI counts and listings). */
    public List<EventEffect> getAllEffects() {
        List<EventEffect> all = new ArrayList<>();
        for (EventPhase p : phases) all.addAll(p.getEffects());
        return all;
    }

    /** Convenience: find an effect by id across all phases. Returns null if not found. */
    public EventEffect findEffect(String effectId) {
        for (EventPhase p : phases) { EventEffect e = p.getEffect(effectId); if (e != null) return e; }
        return null;
    }

    /** Convenience: find which phase contains an effect. */
    public EventPhase findPhaseForEffect(String effectId) {
        for (EventPhase p : phases) if (p.getEffect(effectId) != null) return p;
        return null;
    }

    /** Convenience: total duration in minutes (for display). */
    public int getDurationMinutes() { return Math.max(1, getTotalDurationSeconds() / 60); }

    // chaining & exclusion
    public String getChainEventId() { return chainEventId; }
    public void setChainEventId(String id) { chainEventId = id; }
    public boolean isLightningMobsOnEnd() { return lightningMobsOnEnd; }
    public void setLightningMobsOnEnd(boolean b) { lightningMobsOnEnd = b; }
    public int getLightningRadius() { return lightningRadius; }
    public void setLightningRadius(int r) { lightningRadius = r; }
    public double getLightningX() { return lightningX; }
    public void setLightningX(double v) { lightningX = v; }
    public double getLightningY() { return lightningY; }
    public void setLightningY(double v) { lightningY = v; }
    public double getLightningZ() { return lightningZ; }
    public void setLightningZ(double v) { lightningZ = v; }
    public String getLightningWorld() { return lightningWorld; }
    public void setLightningWorld(String w) { lightningWorld = w; }
    /** True if a dedicated lightning center has been configured. */
    public boolean hasLightningPos() { return !lightningWorld.isBlank(); }
    public String getExclusionGroup() { return exclusionGroup; }
    public void setExclusionGroup(String g) { exclusionGroup = g; }
    public int getExclusionPriority() { return exclusionPriority; }
    public void setExclusionPriority(int p) { exclusionPriority = p; }

    // ═══ Runtime state ═══════════════════════════════════════════════════════

    public State getState() { return state; }
    public void setState(State s) { state = s; stateStartedAt = System.currentTimeMillis(); }
    public long getStateStartedAt() { return stateStartedAt; }
    public boolean isActive() { return state == State.ACTIVE; }
    public boolean isRunning() { return state != State.IDLE; }

    public int getCurrentPhaseIndex() { return currentPhaseIndex; }
    public void setCurrentPhaseIndex(int i) { currentPhaseIndex = i; phaseStartedAt = System.currentTimeMillis(); }
    public long getPhaseStartedAt() { return phaseStartedAt; }
    public EventPhase getCurrentPhase() {
        return (currentPhaseIndex >= 0 && currentPhaseIndex < phases.size()) ? phases.get(currentPhaseIndex) : null;
    }

    public int getTotalDurationSeconds() {
        if (useActSystem) {
            if (acts.isEmpty()) return 300;
            int total = 0;
            for (EventAct a : acts) total += a.getEffectiveDurationSeconds();
            return total;
        }
        if (phases.isEmpty()) return 300;
        int total = 0;
        for (EventPhase p : phases) total += p.getDurationSeconds();
        return total;
    }

    public int getRemainingSeconds() {
        if (!isActive()) return getTotalDurationSeconds();
        EventPhase current = getCurrentPhase();
        if (current == null) return 0;
        long elapsed = System.currentTimeMillis() - phaseStartedAt;
        int phaseRemaining = Math.max(0, current.getDurationSeconds() - (int)(elapsed / 1000));
        // Add remaining phases
        for (int i = currentPhaseIndex + 1; i < phases.size(); i++) {
            phaseRemaining += phases.get(i).getDurationSeconds();
        }
        return phaseRemaining;
    }

    public Map<UUID, EventParticipant> getParticipants() { return participants; }
    public EventParticipant getParticipant(UUID uuid) { return participants.get(uuid); }
    public void addParticipant(EventParticipant p) { participants.put(p.getPlayerUuid(), p); }
    public void removeParticipant(UUID uuid) { participants.remove(uuid); }
    public int getParticipantCount() { return (int) participants.values().stream().filter(p -> p.getRole() == EventParticipant.Role.PARTICIPANT).count(); }
    public int getSpectatorCount() { return (int) participants.values().stream().filter(p -> p.getRole() == EventParticipant.Role.SPECTATOR).count(); }

    public long getEffectLastRun(String effectId) { return effectLastRun.getOrDefault(effectId, 0L); }
    public void setEffectLastRun(String effectId, long ms) { effectLastRun.put(effectId, ms); }
    public Map<String, Long> getEffectLastRunMap() { return effectLastRun; }

    public void resetRuntime() {
        state = State.IDLE;
        stateStartedAt = 0;
        currentPhaseIndex = -1;
        phaseStartedAt = 0;
        participants.clear();
        effectLastRun.clear();
        rankedCache = null;
    }

    // ═══ Nested: ScoreTrigger ════════════════════════════════════════════════

    public static class ScoreTrigger {
        public enum TriggerType {
            KILL_MOB, KILL_POKEMON, CATCH_POKEMON, FISH_CATCH, FISH_POKEMON, BLOCK_BREAK,
            BLOCK_PLACE, ITEM_PICKUP, PROJECTILE_HIT, ENTER_ZONE, PLAYER_KILL,
            STAY_IN_ZONE, REACH_ZONE, CRAFT_ITEM, WIN_BATTLE, CUSTOM_COMMAND
        }

        private TriggerType type;
        private String filter = "";   // entity/item/zone id filter
        private int points = 1;

        public ScoreTrigger(TriggerType type, String filter, int points) {
            this.type = type; this.filter = filter; this.points = points;
        }

        public TriggerType getType() { return type; }
        public void setType(TriggerType t) { type = t; }
        public String getFilter() { return filter; }
        public void setFilter(String f) { filter = f; }
        public int getPoints() { return points; }
        public void setPoints(int p) { points = p; }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("type", type.name());
            o.addProperty("filter", filter);
            o.addProperty("points", points);
            return o;
        }

        public static ScoreTrigger fromJson(JsonObject o) {
            return new ScoreTrigger(
                TriggerType.valueOf(o.get("type").getAsString()),
                o.has("filter") ? o.get("filter").getAsString() : "",
                o.has("points") ? o.get("points").getAsInt() : 1
            );
        }
    }

    // ═══ Nested: EventReward ═════════════════════════════════════════════════

    public static class EventReward {
        // rank: "1", "2", "3", "top10", "all", "lottery"
        private String rank;
        private List<String> commands = new ArrayList<>(); // with {player} placeholder
        private int lotteryCount = 1; // for rank="lottery"

        public EventReward(String rank) { this.rank = rank; }

        public String getRank() { return rank; }
        public void setRank(String r) { rank = r; }
        public List<String> getCommands() { return commands; }
        public int getLotteryCount() { return lotteryCount; }
        public void setLotteryCount(int c) { lotteryCount = c; }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("rank", rank);
            JsonArray a = new JsonArray();
            for (String c : commands) a.add(c);
            o.add("commands", a);
            if (lotteryCount > 1) o.addProperty("lotteryCount", lotteryCount);
            return o;
        }

        public static EventReward fromJson(JsonObject o) {
            EventReward r = new EventReward(o.get("rank").getAsString());
            if (o.has("commands")) for (JsonElement el : o.getAsJsonArray("commands")) r.commands.add(el.getAsString());
            if (o.has("lotteryCount")) r.lotteryCount = o.get("lotteryCount").getAsInt();
            return r;
        }
    }

    // ═══ JSON serialization ══════════════════════════════════════════════════

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id); o.addProperty("name", name); o.addProperty("description", description);
        o.addProperty("displayColor", displayColor); o.addProperty("mode", mode.name());
        o.addProperty("miniGameType", miniGameType);
        o.addProperty("fishingMode", fishingMode);
        if (miniGameConfig != null && miniGameConfig.size() > 0) o.add("miniGameConfig", miniGameConfig);
        o.addProperty("trigger", trigger.name()); o.addProperty("lunarEventId", lunarEventId);
        o.addProperty("scheduledDelay", scheduledDelayMinutes); o.addProperty("recurringInterval", recurringIntervalMinutes);
        o.addProperty("triggerZone", triggerZoneId); o.addProperty("playerLaunchable", playerLaunchable);
        o.addProperty("announceDuration", announceDurationSeconds); o.addProperty("countdown", countdownSeconds);
        o.addProperty("cooldown", cooldownMinutes); o.addProperty("lastRun", lastRunTimestamp);

        JsonArray zones = new JsonArray(); for (String z : scopeZoneIds) zones.add(z);
        o.add("scopeZones", zones);

        o.addProperty("minParticipants", minParticipants); o.addProperty("maxParticipants", maxParticipants);
        o.addProperty("teamMode", teamMode.name()); o.addProperty("teamSize", teamSize);
        o.addProperty("deathBehavior", deathBehavior.name());
        o.addProperty("endOnAllDead", endOnAllDead);
        o.addProperty("announceMessage", announceMessage); o.addProperty("announceColor", announceColor);
        o.addProperty("startMessage", startMessage); o.addProperty("startColor", startColor);
        o.addProperty("endMessage", endMessage); o.addProperty("endColor", endColor);
        o.addProperty("discordNotify", discordNotify);
        o.addProperty("bossbarEnabled", bossbarEnabled); o.addProperty("bossbarColor", bossbarColor);

        o.addProperty("tpPX", tpParticipantX); o.addProperty("tpPY", tpParticipantY); o.addProperty("tpPZ", tpParticipantZ); o.addProperty("tpPW", tpParticipantWorld);
        o.addProperty("respawnX", respawnX); o.addProperty("respawnY", respawnY); o.addProperty("respawnZ", respawnZ); o.addProperty("respawnW", respawnWorld);
        o.addProperty("tpSX", tpSpectatorX); o.addProperty("tpSY", tpSpectatorY); o.addProperty("tpSZ", tpSpectatorZ); o.addProperty("tpSW", tpSpectatorWorld);
        if (!tpParticipantZoneIds.isEmpty()) { JsonArray pz = new JsonArray(); tpParticipantZoneIds.forEach(pz::add); o.add("tpParticipantZoneIds", pz); }
        if (!tpSpectatorZoneIds.isEmpty()) { JsonArray sz = new JsonArray(); tpSpectatorZoneIds.forEach(sz::add); o.add("tpSpectatorZoneIds", sz); }
        o.addProperty("returnMode", returnMode); o.addProperty("retX", returnX); o.addProperty("retY", returnY); o.addProperty("retZ", returnZ); o.addProperty("retW", returnWorld);

        JsonArray ke = new JsonArray(); for (String c : kitEntryCommands) ke.add(c); o.add("kitEntry", ke);
        JsonArray kx = new JsonArray(); for (String c : kitExitCommands) kx.add(c); o.add("kitExit", kx);
        o.addProperty("saveInventory", saveInventory); o.addProperty("saveTeamCobblemon", saveTeamCobblemon);

        JsonArray bl = new JsonArray(); for (String i : blacklistItems) bl.add(i); o.add("blacklistItems", bl);
        JsonArray wl = new JsonArray(); for (String i : whitelistItems) wl.add(i); o.add("whitelistItems", wl);
        JsonArray be = new JsonArray(); for (String i : blacklistEnchants) be.add(i); o.add("blacklistEnchants", be);
        JsonArray bp = new JsonArray(); for (String i : blacklistPokemon) bp.add(i); o.add("blacklistPokemon", bp);
        o.addProperty("forceEmptyInventory", forceEmptyInventory);
        o.addProperty("disablePokemonSummon", disablePokemonSummon);
        o.addProperty("voiceChatGroup", voiceChatGroup);

        o.addProperty("victoryCondition", victoryCondition.name());
        o.addProperty("victoryTargetScore", victoryTargetScore); o.addProperty("scoreboardVisible", scoreboardVisible);

        JsonArray st = new JsonArray(); for (ScoreTrigger t : scoreTriggers) st.add(t.toJson()); o.add("scoreTriggers", st);
        JsonArray rw = new JsonArray(); for (EventReward r : rewards) rw.add(r.toJson()); o.add("rewards", rw);
        JsonArray ph = new JsonArray(); for (EventPhase p : phases) ph.add(p.toJson()); o.add("phases", ph);
        JsonArray ac = new JsonArray(); for (EventAct a : acts) ac.add(a.toJson()); o.add("acts", ac);
        o.addProperty("useActSystem", useActSystem);
        // Scoreboard display config
        o.addProperty("sbPosition", scoreboardPosition);
        o.addProperty("sbCustomX", scoreboardCustomX); o.addProperty("sbCustomY", scoreboardCustomY);
        o.addProperty("sbMaxRows", scoreboardMaxRows); o.addProperty("sbCompact", scoreboardCompact);
        o.addProperty("sbTitleColor", scoreboardTitleColor); o.addProperty("sbTextColor", scoreboardTextColor);
        o.addProperty("sbBgColor", scoreboardBgColor); o.addProperty("sbBgOpacity", scoreboardBgOpacity);
        o.addProperty("sbShowRank", scoreboardShowRank); o.addProperty("sbShowScore", scoreboardShowScore);
        o.addProperty("sbPrefix", scoreboardPrefix); o.addProperty("sbFontSize", scoreboardFontSize);

        o.addProperty("chainEvent", chainEventId);
        o.addProperty("lightningMobsOnEnd", lightningMobsOnEnd); o.addProperty("lightningRadius", lightningRadius);
        o.addProperty("lightningX", lightningX); o.addProperty("lightningY", lightningY); o.addProperty("lightningZ", lightningZ); o.addProperty("lightningW", lightningWorld);
        o.addProperty("exclusionGroup", exclusionGroup); o.addProperty("exclusionPriority", exclusionPriority);

        return o;
    }

    public static ServerEvent fromJson(JsonObject o) {
        ServerEvent e = new ServerEvent(o.get("id").getAsString(), o.get("name").getAsString());
        if (o.has("description")) e.description = o.get("description").getAsString();
        if (o.has("displayColor")) e.displayColor = o.get("displayColor").getAsString();
        if (o.has("mode")) try { e.mode = EventMode.valueOf(o.get("mode").getAsString()); } catch (Exception ignored) {}
        if (o.has("miniGameType")) e.miniGameType = o.get("miniGameType").getAsString();
        if (o.has("fishingMode")) e.fishingMode = o.get("fishingMode").getAsString();
        if (o.has("miniGameConfig") && o.get("miniGameConfig").isJsonObject()) e.miniGameConfig = o.getAsJsonObject("miniGameConfig");
        if (o.has("trigger")) try { e.trigger = TriggerType.valueOf(o.get("trigger").getAsString()); } catch (Exception ignored) {}
        if (o.has("lunarEventId")) e.lunarEventId = o.get("lunarEventId").getAsString();
        if (o.has("scheduledDelay")) e.scheduledDelayMinutes = o.get("scheduledDelay").getAsInt();
        if (o.has("recurringInterval")) e.recurringIntervalMinutes = o.get("recurringInterval").getAsInt();
        if (o.has("triggerZone")) e.triggerZoneId = o.get("triggerZone").getAsString();
        if (o.has("playerLaunchable")) e.playerLaunchable = o.get("playerLaunchable").getAsBoolean();
        if (o.has("announceDuration")) e.announceDurationSeconds = o.get("announceDuration").getAsInt();
        if (o.has("countdown")) e.countdownSeconds = o.get("countdown").getAsInt();
        if (o.has("cooldown")) e.cooldownMinutes = o.get("cooldown").getAsInt();
        if (o.has("lastRun")) e.lastRunTimestamp = o.get("lastRun").getAsLong();

        if (o.has("scopeZones")) for (JsonElement el : o.getAsJsonArray("scopeZones")) e.scopeZoneIds.add(el.getAsString());

        if (o.has("minParticipants")) e.minParticipants = o.get("minParticipants").getAsInt();
        if (o.has("maxParticipants")) e.maxParticipants = o.get("maxParticipants").getAsInt();
        if (o.has("teamMode")) try { e.teamMode = TeamMode.valueOf(o.get("teamMode").getAsString()); } catch (Exception ignored) {}
        if (o.has("teamSize")) e.teamSize = o.get("teamSize").getAsInt();
        if (o.has("deathBehavior")) try { e.deathBehavior = DeathBehavior.valueOf(o.get("deathBehavior").getAsString()); } catch (Exception ignored) {}
        if (o.has("endOnAllDead")) e.endOnAllDead = o.get("endOnAllDead").getAsBoolean();

        if (o.has("announceMessage")) e.announceMessage = o.get("announceMessage").getAsString();
        if (o.has("announceColor")) e.announceColor = o.get("announceColor").getAsString();
        if (o.has("startMessage")) e.startMessage = o.get("startMessage").getAsString();
        if (o.has("startColor")) e.startColor = o.get("startColor").getAsString();
        if (o.has("endMessage")) e.endMessage = o.get("endMessage").getAsString();
        if (o.has("endColor")) e.endColor = o.get("endColor").getAsString();
        if (o.has("discordNotify")) e.discordNotify = o.get("discordNotify").getAsBoolean();
        if (o.has("bossbarEnabled")) e.bossbarEnabled = o.get("bossbarEnabled").getAsBoolean();
        if (o.has("bossbarColor")) e.bossbarColor = o.get("bossbarColor").getAsString();

        if (o.has("tpPX")) e.tpParticipantX = o.get("tpPX").getAsDouble();
        if (o.has("tpPY")) e.tpParticipantY = o.get("tpPY").getAsDouble();
        if (o.has("tpPZ")) e.tpParticipantZ = o.get("tpPZ").getAsDouble();
        if (o.has("tpPW")) e.tpParticipantWorld = o.get("tpPW").getAsString();
        if (o.has("respawnX")) e.respawnX = o.get("respawnX").getAsDouble();
        if (o.has("respawnY")) e.respawnY = o.get("respawnY").getAsDouble();
        if (o.has("respawnZ")) e.respawnZ = o.get("respawnZ").getAsDouble();
        if (o.has("respawnW")) e.respawnWorld = o.get("respawnW").getAsString();
        if (o.has("tpSX")) e.tpSpectatorX = o.get("tpSX").getAsDouble();
        if (o.has("tpSY")) e.tpSpectatorY = o.get("tpSY").getAsDouble();
        if (o.has("tpSZ")) e.tpSpectatorZ = o.get("tpSZ").getAsDouble();
        if (o.has("tpSW")) e.tpSpectatorWorld = o.get("tpSW").getAsString();
        if (o.has("tpParticipantZoneIds") && o.get("tpParticipantZoneIds").isJsonArray()) {
            e.tpParticipantZoneIds = new ArrayList<>();
            for (var el : o.getAsJsonArray("tpParticipantZoneIds")) e.tpParticipantZoneIds.add(el.getAsString());
        }
        if (o.has("tpSpectatorZoneIds") && o.get("tpSpectatorZoneIds").isJsonArray()) {
            e.tpSpectatorZoneIds = new ArrayList<>();
            for (var el : o.getAsJsonArray("tpSpectatorZoneIds")) e.tpSpectatorZoneIds.add(el.getAsString());
        }
        if (o.has("returnMode")) e.returnMode = o.get("returnMode").getAsString();
        if (o.has("retX")) e.returnX = o.get("retX").getAsDouble();
        if (o.has("retY")) e.returnY = o.get("retY").getAsDouble();
        if (o.has("retZ")) e.returnZ = o.get("retZ").getAsDouble();
        if (o.has("retW")) e.returnWorld = o.get("retW").getAsString();

        if (o.has("kitEntry")) for (JsonElement el : o.getAsJsonArray("kitEntry")) e.kitEntryCommands.add(el.getAsString());
        if (o.has("kitExit")) for (JsonElement el : o.getAsJsonArray("kitExit")) e.kitExitCommands.add(el.getAsString());
        if (o.has("saveInventory")) e.saveInventory = o.get("saveInventory").getAsBoolean();
        if (o.has("saveTeamCobblemon")) e.saveTeamCobblemon = o.get("saveTeamCobblemon").getAsBoolean();

        if (o.has("blacklistItems")) for (JsonElement el : o.getAsJsonArray("blacklistItems")) e.blacklistItems.add(el.getAsString());
        if (o.has("whitelistItems")) for (JsonElement el : o.getAsJsonArray("whitelistItems")) e.whitelistItems.add(el.getAsString());
        if (o.has("blacklistEnchants")) for (JsonElement el : o.getAsJsonArray("blacklistEnchants")) e.blacklistEnchants.add(el.getAsString());
        if (o.has("blacklistPokemon")) for (JsonElement el : o.getAsJsonArray("blacklistPokemon")) e.blacklistPokemon.add(el.getAsString());
        if (o.has("forceEmptyInventory")) e.forceEmptyInventory = o.get("forceEmptyInventory").getAsBoolean();
        if (o.has("disablePokemonSummon")) e.disablePokemonSummon = o.get("disablePokemonSummon").getAsBoolean();
        if (o.has("voiceChatGroup")) e.voiceChatGroup = o.get("voiceChatGroup").getAsBoolean();

        if (o.has("victoryCondition")) try { e.victoryCondition = VictoryCondition.valueOf(o.get("victoryCondition").getAsString()); } catch (Exception ignored) {}
        if (o.has("victoryTargetScore")) e.victoryTargetScore = o.get("victoryTargetScore").getAsInt();
        if (o.has("scoreboardVisible")) e.scoreboardVisible = o.get("scoreboardVisible").getAsBoolean();

        if (o.has("scoreTriggers")) for (JsonElement el : o.getAsJsonArray("scoreTriggers")) {
            try { e.scoreTriggers.add(ScoreTrigger.fromJson(el.getAsJsonObject())); } catch (Exception ignored) {}
        }
        if (o.has("rewards")) for (JsonElement el : o.getAsJsonArray("rewards")) {
            try { e.rewards.add(EventReward.fromJson(el.getAsJsonObject())); } catch (Exception ignored) {}
        }
        if (o.has("phases")) for (JsonElement el : o.getAsJsonArray("phases")) {
            try { e.phases.add(EventPhase.fromJson(el.getAsJsonObject())); } catch (Exception ignored) {}
        }
        if (o.has("acts")) for (JsonElement el : o.getAsJsonArray("acts")) {
            try { e.acts.add(EventAct.fromJson(el.getAsJsonObject())); } catch (Exception ignored) {}
        }
        if (o.has("useActSystem")) e.useActSystem = o.get("useActSystem").getAsBoolean();
        // Scoreboard
        if (o.has("sbPosition")) e.scoreboardPosition = o.get("sbPosition").getAsString();
        if (o.has("sbCustomX")) e.scoreboardCustomX = o.get("sbCustomX").getAsInt();
        if (o.has("sbCustomY")) e.scoreboardCustomY = o.get("sbCustomY").getAsInt();
        if (o.has("sbMaxRows")) e.scoreboardMaxRows = o.get("sbMaxRows").getAsInt();
        if (o.has("sbCompact")) e.scoreboardCompact = o.get("sbCompact").getAsBoolean();
        if (o.has("sbTitleColor")) e.scoreboardTitleColor = o.get("sbTitleColor").getAsString();
        if (o.has("sbTextColor")) e.scoreboardTextColor = o.get("sbTextColor").getAsString();
        if (o.has("sbBgColor")) e.scoreboardBgColor = o.get("sbBgColor").getAsString();
        if (o.has("sbBgOpacity")) e.scoreboardBgOpacity = o.get("sbBgOpacity").getAsInt();
        if (o.has("sbShowRank")) e.scoreboardShowRank = o.get("sbShowRank").getAsBoolean();
        if (o.has("sbShowScore")) e.scoreboardShowScore = o.get("sbShowScore").getAsBoolean();
        if (o.has("sbPrefix")) e.scoreboardPrefix = o.get("sbPrefix").getAsString();
        if (o.has("sbFontSize")) e.scoreboardFontSize = o.get("sbFontSize").getAsInt();

        if (o.has("chainEvent")) e.chainEventId = o.get("chainEvent").getAsString();
        if (o.has("lightningMobsOnEnd")) e.lightningMobsOnEnd = o.get("lightningMobsOnEnd").getAsBoolean();
        if (o.has("lightningRadius")) e.lightningRadius = o.get("lightningRadius").getAsInt();
        if (o.has("lightningX")) e.lightningX = o.get("lightningX").getAsDouble();
        if (o.has("lightningY")) e.lightningY = o.get("lightningY").getAsDouble();
        if (o.has("lightningZ")) e.lightningZ = o.get("lightningZ").getAsDouble();
        if (o.has("lightningW")) e.lightningWorld = o.get("lightningW").getAsString();
        if (o.has("exclusionGroup")) e.exclusionGroup = o.get("exclusionGroup").getAsString();
        if (o.has("exclusionPriority")) e.exclusionPriority = o.get("exclusionPriority").getAsInt();

        return e;
    }
}
