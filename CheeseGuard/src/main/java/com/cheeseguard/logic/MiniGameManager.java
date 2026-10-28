package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.EventParticipant;
import com.cheeseguard.data.RaceTrackConfig;
import com.cheeseguard.data.Region;
import com.cheeseguard.data.ServerEvent;
import com.google.gson.*;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.TypedActionResult;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.world.Heightmap;
import net.fabricmc.fabric.api.event.player.UseItemCallback;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages mini-game specific logic for events.
 * Each mini-game type has its own setup, tick, and cleanup behavior.
 */
public class MiniGameManager {

    // ── Mini-game types ──────────────────────────────────────────────────────

    public enum MiniGameType {
        // Combat
        DEATHMATCH("Deathmatch", "Dernier survivant", "⚔", false),
        TEAM_BATTLE("Bataille equipe", "Equipe vs equipe", "⚔", false),
        MOB_ARENA("Arene de mobs", "Survivre aux vagues", "\uD83D\uDC79", false),

        // Construction
        SPEED_BUILD("Speed Build", "Construire le plus vite", "\uD83C\uDFD7", false),
        BLOCK_PARTY("Block Party", "Placer le bon bloc", "\uD83C\uDFB2", false),

        // Deplacement
        PARKOUR("Parkour", "Course a obstacles", "\uD83C\uDFC3", false),
        RACE("Course", "Premier arrive", "\uD83C\uDFC1", true),
        ELYTRA_RACE("Elytra Race", "Course en elytra", "\uD83E\uDD85", false),
        SPLEEF("Spleef", "Detruire le sol", "\u2744", true),

        // Collection
        SCAVENGER_HUNT("Chasse au tresor", "Trouver les objets", "\uD83D\uDD0E", false),
        FISHING_CONTEST("Concours peche", "Pecher le plus", "\uD83C\uDFA3", false),
        MINING_RACE("Course au minage", "Miner le plus", "\u26CF", false),

        // Pokemon (Cobblemon)
        POKEMON_CATCH("Catch Contest", "Attraper le plus", "\uD83D\uDD34", false),
        POKEMON_BATTLE("Tournoi Pokemon", "Batailles Pokemon", "\u26A1", false),
        SHINY_HUNT("Shiny Hunt", "Trouver un shiny", "\u2728", false),

        // Social
        HIDE_SEEK("Cache-cache", "Se cacher ou chercher", "\uD83D\uDC41", false),
        KING_OF_HILL("Roi de la colline", "Rester au sommet", "\uD83D\uDC51", false),

        // Puzzle
        TRIVIA("Quiz", "Repondre aux questions", "\u2753", false),
        TREASURE_TRAIL("Piste au tresor", "Suivre les indices", "\uD83D\uDDFA", false),

        // Pool / Water
        THIMBLE("De a Coudre", "Sauter dans l'eau, dernier survivant", "\uD83E\uDDF5", true),

        // Prop Hunt
        PROP_HUNT("Prop Hunt", "Chasseurs vs Pokemon caches", "\uD83C\uDFAD", true),

        // Custom
        CUSTOM("Custom", "Logique personnalisee", "\u2699", true);

        public final String displayName, description, icon;
        public final boolean enabled;

        MiniGameType(String d, String desc, String i, boolean enabled) {
            displayName = d; description = desc; icon = i; this.enabled = enabled;
        }
    }

    // ── Active mini-game state ───────────────────────────────────────────────

    private static class MiniGameState {
        MiniGameType type;
        String eventId;
        ZoneSnapshot snapshot;
        int waveNumber = 0;
        long lastWaveSpawn = 0;
        Set<UUID> eliminated = new HashSet<>();
        Map<UUID, Integer> zoneTimers = new HashMap<>(); // for KING_OF_HILL, STAY_IN_ZONE
        List<UUID> entityWaypoints = new ArrayList<>(); // tracked entities
        int triviaQuestionIndex = 0;

        MiniGameState(MiniGameType type, String eventId) {
            this.type = type;
            this.eventId = eventId;
        }
    }

    private final Map<String, MiniGameState> activeGames = new ConcurrentHashMap<>();
    private final Map<String, ThimbleState> thimbleStates = new ConcurrentHashMap<>();
    private MinecraftServer server;

    // ── Thimble (Dé à Coudre) state ──────────────────────────────────────────

    private static class ThimbleState {
        List<UUID> turnOrder = new ArrayList<>();
        int currentTurnIndex = 0;
        long turnStartTick = 0;
        int turnTimeoutTicks = 600;        // 30 seconds
        UUID currentJumper = null;
        boolean jumperLeftPlatform = false;
        boolean jumperTouchedGround = false; // true if jumper hit solid ground before water → fail
        double platformX, platformY, platformZ;
        String platformWorld;
        int poolMinX, poolMinY, poolMinZ;
        int poolMaxX, poolMaxY, poolMaxZ;
        String worldId;
        // Pixel art template: encoded BlockPos (asLong) → original BlockState
        Map<Long, BlockState> pixelArtTemplate = new HashMap<>();
        String borderBlock = "minecraft:stone"; // block defining pool contour
        int roundNumber = 1;
        boolean gameStarted = false;
        long gameStartTick = 0;
        Set<UUID> eliminated = new HashSet<>();
        boolean waitingForPrepPhase = false; // true = don't tick until prep act ends
        int initialWaterBlocks = 0;         // total pixel art blocks at start
        Map<UUID, Integer> successfulJumps = new HashMap<>(); // per-player jump count
        Map<UUID, Integer> lives = new HashMap<>(); // per-player lives (default 1)
        long turnAdvancedTick = 0; // tick when last turn was advanced (cooldown)
        // Sudden death
        boolean suddenDeath = false;
        BlockPos suddenDeathHolePos = null;        // current open hole position
        BlockState suddenDeathOriginalBlock = null; // block state that was at the hole before opening
        // Sudden death TP (overrides platform TP during sudden death)
        double suddenDeathX, suddenDeathY, suddenDeathZ;
        String suddenDeathWorld;
        List<String> suddenDeathZoneIds = new ArrayList<>();
        boolean hasSuddenDeathTp = false; // true if configured
        // Platform zone snapshots (show/hide on sudden death swap)
        ZoneSnapshot platformSnapshot = null;        // normal platform zone
        ZoneSnapshot sdPlatformSnapshot = null;       // sudden death platform zone
    }

    private static final Identifier ID_SLOWNESS     = Identifier.of("minecraft", "slowness");
    private static final Identifier ID_JUMP_BOOST   = Identifier.of("minecraft", "jump_boost");
    private static final Identifier ID_RESISTANCE   = Identifier.of("minecraft", "resistance");
    private static final Identifier ID_SLOW_FALLING = Identifier.of("minecraft", "slow_falling");
    private static final int THIMBLE_MAX_LIVES = 3; // default, overridable via miniGameConfig

    // ── Mini-game config helpers ──────────────────────────────────────────────

    private static int getConfigInt(ServerEvent event, String key, int defaultVal) {
        JsonObject cfg = event.getMiniGameConfig();
        return (cfg != null && cfg.has(key) && cfg.get(key).isJsonPrimitive()) ? cfg.get(key).getAsInt() : defaultVal;
    }

    private static boolean getConfigBool(ServerEvent event, String key, boolean defaultVal) {
        JsonObject cfg = event.getMiniGameConfig();
        return (cfg != null && cfg.has(key) && cfg.get(key).isJsonPrimitive()) ? cfg.get(key).getAsBoolean() : defaultVal;
    }

    private static String getConfigString(ServerEvent event, String key, String defaultVal) {
        JsonObject cfg = event.getMiniGameConfig();
        return (cfg != null && cfg.has(key) && cfg.get(key).isJsonPrimitive()) ? cfg.get(key).getAsString() : defaultVal;
    }

    private static JsonArray getConfigJsonArray(ServerEvent event, String key) {
        JsonObject cfg = event.getMiniGameConfig();
        if (cfg != null && cfg.has(key) && cfg.get(key).isJsonArray()) {
            return cfg.getAsJsonArray(key);
        }
        return new JsonArray();
    }

    // ── Spleef Enhanced state ─────────────────────────────────────────────────

    private static class SpleefState {
        int eliminationY = 0;
        List<LayerInfo> layers = new ArrayList<>();
        List<ZoneSnapshot> snapshots = new ArrayList<>();
        int countdownRemaining = -1;
        boolean gameActive = false;
        long lastTickMs = 0;
        long gameStartMs = 0;
        Map<UUID, Long> lastScoreTick = new HashMap<>();
        Map<UUID, Integer> lastKnownLayer = new HashMap<>();
        // Degradation state
        int degradingLayerIndex = 0; // frontmost layer being degraded (0 = top)
        long lastDegradeMs = 0;
        // Warning queue: blocks marked for removal with visual warning first
        List<BlockPos> warningQueue = new ArrayList<>(); // blocks currently showing warning particles
        long warningStartMs = 0; // when the current warning batch started
        String degradeWorldId = null; // cached world ID for degradation
    }

    private static class LayerInfo {
        String zoneId;
        int minY, maxY;
        String name;
        // Degradation: list of breakable block positions in this layer
        List<BlockPos> breakableBlocks = new ArrayList<>();
        int totalBlocks = 0;
        int removedBlocks = 0;
        boolean fullyDegraded = false;       // all blocks gone
        boolean thresholdReached = false;    // threshold hit, next layer started
        boolean degradationAnnounced = false; // "commence a s'effriter" shown

        LayerInfo(String zoneId, int minY, int maxY, String name) {
            this.zoneId = zoneId; this.minY = minY; this.maxY = maxY; this.name = name;
        }
    }

    private final Map<String, SpleefState> spleefStates = new ConcurrentHashMap<>();

    // ── Race (Pokemon Race) state ────────────────────────────────────────────

    private static class RaceState {
        // ── Legacy (zone-based) mode fields ──
        List<String> checkpointZoneIds = new ArrayList<>();
        Map<String, double[]> zoneCenters = new HashMap<>(); // zoneId -> [cx, cy, cz]
        Map<String, int[]> zoneBounds = new HashMap<>();     // zoneId -> [minX, minY, minZ, maxX, maxY, maxZ]
        Map<String, String> zoneWorlds = new HashMap<>();    // zoneId -> worldId
        List<PowerUpDef> powerUps = new ArrayList<>();

        // ── Track config mode fields ──
        RaceTrackConfig trackConfig;  // null = legacy mode
        List<RaceTrackConfig.MarkerRegion> trackCheckpoints; // sorted by order
        List<RaceTrackConfig.MarkerRegion> trackPowerups;
        RaceTrackConfig.MarkerRegion trackStartLine;
        int totalLaps = 1;
        double margin = 1.5;
        boolean orderedCheckpoints = true;
        boolean teleportOnOutOfBounds = true;
        boolean hideValidatedCheckpoints = false;
        boolean freezeDuringCountdown = true;
        int[] globalBoundsMin;
        int[] globalBoundsMax;
        String raceWorld;
        Map<UUID, Integer> playerCurrentLap = new HashMap<>();
        Map<UUID, Set<Integer>> playerValidatedCps = new HashMap<>(); // unordered mode
        Map<UUID, double[]> playerLastCpCenter = new HashMap<>(); // for OOB teleport
        Set<Integer> consumedPowerups = new HashSet<>(); // single-use consumed
        Map<String, RaceTrackConfig.PowerUpTypeConfig> powerupTypeConfigs = new LinkedHashMap<>();
        ParticleEffect nextCheckpointParticle = ParticleTypes.HAPPY_VILLAGER;
        ParticleEffect validatedCheckpointParticle = ParticleTypes.FLAME;
        String movementMode = "any";

        // ── Common fields ──
        Map<UUID, Integer> playerNextCheckpoint = new HashMap<>();
        Map<UUID, Long> finishTimes = new HashMap<>();
        Map<UUID, String> playerTransformCommands = new HashMap<>();
        List<UUID> finishOrder = new ArrayList<>();
        int countdownRemaining = -1;
        boolean raceActive = false;
        long lastTickMs = 0;
        long raceStartMs = 0;
        boolean endTimerActive = false;
        long endTimerStartMs = 0;
        int endTimerDurationSeconds = 30;
        boolean transformsApplied = false;
        Map<String, Long> playerPowerUpCooldowns = new HashMap<>(); // cooldown key -> timestamp
        Map<UUID, double[]> prevPositions = new HashMap<>(); // trajectory interpolation
        Map<UUID, double[]> frozenPositions = new HashMap<>(); // countdown position enforcement
        Map<BlockPos, BlockState> savedBlocks = new HashMap<>(); // original blocks before marker replacement

        boolean isTrackConfigMode() { return trackConfig != null; }
    }

    private static class PowerUpDef {
        String zoneId;
        String effectType;    // "speed", "jump", "slow", "levitation", "resistance", "invisibility", "blindness", "glowing"
        int amplifier;
        int durationTicks;
        int cooldownSeconds;
        String sound;
        String particleType;
    }

    private final Map<String, RaceState> raceStates = new ConcurrentHashMap<>();

    // ── Prop Hunt state ─────────────────────────────────────────────────────

    static class PropHuntSpeciesEntry {
        String species;       // e.g. "pikachu", "charizard shiny"
        String itemId;        // e.g. "cobblemon:poke_ball" or "" for default paper
        boolean hasCustomIcon; // true if custom PNG uploaded
    }

    private static class PropHuntState {
        Set<UUID> hunters = new HashSet<>();
        Set<UUID> hiders = new HashSet<>();
        Set<UUID> eliminatedHiders = new HashSet<>();
        Set<UUID> eliminatedHunters = new HashSet<>();
        Map<UUID, String> hiderSpecies = new HashMap<>();    // uuid → current species
        List<String> speciesList = new ArrayList<>();         // configured species
        List<PropHuntSpeciesEntry> speciesEntries = new ArrayList<>(); // structured species entries
        int hunterCount = 2;
        int hunterStartHealth = 20;       // half-hearts (20 = 10 hearts)
        int penaltyHalfHearts = 2;        // half-hearts lost per wrong hit (2 = 1 heart)
        int hideGraceSeconds = 30;
        int gameDurationSeconds = 300;
        int decoyCountPerSpecies = 5;
        boolean attacksEnabled = false;   // CobbleShift projectile attacks for hiders
        String hunterWeapon = "bow";      // bow / crossbow / sword / bow_sword
        int hunterArrowCount = 64;
        int transformCooldownTicks = 200;
        String hunterTpZoneId = "";
        List<String> spawnZoneIds = new ArrayList<>();
        long gameStartTick = 0;
        boolean huntersReleased = false;
        Map<UUID, Long> lastPenaltyTick = new HashMap<>(); // rate-limit penalties
        Map<UUID, Long> lastTransformTick = new HashMap<>(); // transform cooldown per hider
        Map<UUID, Long> lastSeenOnlineTick = new HashMap<>(); // disconnect grace period
        int decoySpawnDelayTicks = -1; // >0 = countdown to spawn decoys
        boolean decoysSpawned = false;
        int hiderStartHealth = 6;      // half-hearts for hiders (3 hearts default)
        int hiderDamagePerHit = 2;     // half-hearts per hunter hit on hider (1 heart)
        Map<UUID, Integer> hiderHealth = new HashMap<>(); // virtual health tracking
        // Hunter powers
        Map<UUID, List<HunterPower>> hunterAvailablePowers = new HashMap<>(); // per-player assigned powers
        Map<UUID, Integer> hunterPowerIndex = new HashMap<>(); // index into player's available list
        Map<UUID, Map<String, Long>> hunterPowerLastUsed = new HashMap<>(); // uuid -> {powerName -> lastUsedTick}
        Map<String, Integer> hunterPowerCooldowns = new LinkedHashMap<>(); // powerName -> cooldownTicks
        Set<String> hunterPowersEnabled = new LinkedHashSet<>(); // set of enabled power names
        int hunterPowerCount = 3; // how many powers each hunter gets
        long unfreezeAtTick = -1; // for FREEZE power scheduled unfreeze
        // Hider powers
        Map<UUID, List<HiderPower>> hiderAvailablePowers = new HashMap<>(); // per-player assigned powers
        Map<UUID, Integer> hiderPowerIndex = new HashMap<>(); // index into player's available list
        Map<UUID, Map<String, Long>> hiderPowerLastUsed = new HashMap<>(); // uuid -> {powerName -> lastUsedTick}
        Map<String, Integer> hiderPowerCooldowns = new LinkedHashMap<>(); // powerName -> cooldownTicks
        Set<String> hiderPowersEnabled = new LinkedHashSet<>(); // set of enabled power names
        int hiderPowerCount = 2; // how many powers each hider gets
        // Compass tracking (hunter power)
        Map<UUID, Long> compassEndTick = new HashMap<>(); // hunter UUID → tick when compass display expires
        // Trap tracking (hunter power)
        List<TrapEntry> activeTraps = new ArrayList<>();
        // Invisibility re-transform tracking
        Map<UUID, Long> invisEndTick = new HashMap<>(); // hider UUID → tick to re-transform
        Map<UUID, String> invisSavedSpecies = new HashMap<>(); // hider UUID → species to restore
        // Shield tracking (one-shot absorb, 60s expiration)
        Set<UUID> shieldActive = new HashSet<>();
        Map<UUID, Long> shieldExpireTick = new HashMap<>();
        // Echolocation / Hunter's Mark / Smokescreen tick tracking
        Map<UUID, Long> echoEndTick = new HashMap<>();
        Map<UUID, UUID> hunterMarkTarget = new HashMap<>();
        Map<UUID, Long> hunterMarkEndTick = new HashMap<>();
        Map<UUID, Long> smokescreenEndTick = new HashMap<>();
        Map<UUID, double[]> smokescreenPos = new HashMap<>(); // hider UUID -> [x, y, z]
        // Mimic tracking
        Map<UUID, Long> mimicEndTick = new HashMap<>();
        Map<UUID, UUID> mimicArmorStandId = new HashMap<>(); // hider UUID -> armor stand UUID
    }

    static class TrapEntry {
        double x, y, z;
        String worldId;
        UUID hunterUuid;
        TrapEntry(double x, double y, double z, String worldId, UUID hunterUuid) {
            this.x = x; this.y = y; this.z = z; this.worldId = worldId; this.hunterUuid = hunterUuid;
        }
    }

    enum HunterPower {
        SONAR("\uD83D\uDCE1", "cheeseguard.prophunt.power.sonar", "cheeseguard.prophunt.desc.sonar"),
        SCRAMBLE("\uD83D\uDD00", "cheeseguard.prophunt.power.scramble", "cheeseguard.prophunt.desc.scramble"),
        REVEAL("\uD83D\uDC41", "cheeseguard.prophunt.power.reveal", "cheeseguard.prophunt.desc.reveal"),
        FREEZE("\u2744", "cheeseguard.prophunt.power.freeze", "cheeseguard.prophunt.desc.freeze"),
        COMPASS("\uD83E\uDDED", "cheeseguard.prophunt.power.compass", "cheeseguard.prophunt.desc.compass"),
        TRAP("\uD83D\uDD78", "cheeseguard.prophunt.power.trap", "cheeseguard.prophunt.desc.trap"),
        ECHOLOCATION("\uD83E\uDD87", "cheeseguard.prophunt.power.echolocation", "cheeseguard.prophunt.desc.echolocation"),
        FLARE("\uD83D\uDD25", "cheeseguard.prophunt.power.flare", "cheeseguard.prophunt.desc.flare"),
        SNIFF("\uD83D\uDC43", "cheeseguard.prophunt.power.sniff", "cheeseguard.prophunt.desc.sniff"),
        HUNTER_MARK("\uD83C\uDFAF", "cheeseguard.prophunt.power.hunter_mark", "cheeseguard.prophunt.desc.hunter_mark");

        final String icon;
        final String translationKey;
        final String descKey;
        HunterPower(String icon, String translationKey, String descKey) {
            this.icon = icon;
            this.translationKey = translationKey;
            this.descKey = descKey;
        }
    }

    enum HiderPower {
        DECOY("\uD83C\uDFAD", "cheeseguard.prophunt.power.decoy", "cheeseguard.prophunt.desc.decoy"),
        INVISIBILITY("\uD83D\uDC7B", "cheeseguard.prophunt.power.invisibility", "cheeseguard.prophunt.desc.invisibility"),
        SMOKESCREEN("\uD83D\uDCA8", "cheeseguard.prophunt.power.smokescreen", "cheeseguard.prophunt.desc.smokescreen"),
        MIMIC("\uD83D\uDDFF", "cheeseguard.prophunt.power.mimic", "cheeseguard.prophunt.desc.mimic"),
        TAUNT("\uD83D\uDCE2", "cheeseguard.prophunt.power.taunt", "cheeseguard.prophunt.desc.taunt"),
        SHIELD("\uD83D\uDEE1", "cheeseguard.prophunt.power.shield", "cheeseguard.prophunt.desc.shield"),
        HASTE("\uD83D\uDCA8", "cheeseguard.prophunt.power.haste", "cheeseguard.prophunt.desc.haste"),
        DISTRACT("\uD83C\uDF86", "cheeseguard.prophunt.power.distract", "cheeseguard.prophunt.desc.distract");

        final String icon;
        final String translationKey;
        final String descKey;
        HiderPower(String icon, String translationKey, String descKey) {
            this.icon = icon;
            this.translationKey = translationKey;
            this.descKey = descKey;
        }
    }

    private final Map<String, PropHuntState> propHuntStates = new ConcurrentHashMap<>();

    // ── Queue system ─────────────────────────────────────────────────────────

    private final Map<String, List<UUID>> queues = new ConcurrentHashMap<>(); // eventId -> player queue

    public void init(MinecraftServer server) {
        this.server = server;

        // Prop Hunt: handle right-click on transform papers
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient()) return TypedActionResult.pass(player.getStackInHand(hand));
            if (!(player instanceof ServerPlayerEntity sp)) return TypedActionResult.pass(player.getStackInHand(hand));
            ItemStack held = sp.getStackInHand(hand);
            if (held.isEmpty()) return TypedActionResult.pass(held);
            net.minecraft.component.type.NbtComponent customData = held.get(DataComponentTypes.CUSTOM_DATA);
            if (customData == null) return TypedActionResult.pass(held);
            net.minecraft.nbt.NbtCompound nbt = customData.copyNbt();

            if (!nbt.contains("prophunt_species")) return TypedActionResult.pass(held);

            // Check if player is a hider in an active Prop Hunt
            for (var entry : propHuntStates.entrySet()) {
                PropHuntState phs = entry.getValue();
                if (!phs.hiders.contains(sp.getUuid())) continue;
                if (phs.eliminatedHiders.contains(sp.getUuid())) continue;

                // Extract species from custom data tag
                String speciesFromTag = nbt.getString("prophunt_species");
                String matchedSpecies = null;
                for (String s : phs.speciesList) {
                    if (s.equalsIgnoreCase(speciesFromTag)) {
                        matchedSpecies = s;
                        break;
                    }
                }
                if (matchedSpecies == null) continue;

                // Check cooldown
                long now = this.server.getTicks();
                Long lastTransform = phs.lastTransformTick.get(sp.getUuid());
                if (lastTransform != null && now - lastTransform < phs.transformCooldownTicks) {
                    int remaining = (int) ((phs.transformCooldownTicks - (now - lastTransform)) / 20);
                    sp.sendMessage(Text.translatable("cheeseguard.prophunt.cooldown", remaining)
                        .formatted(Formatting.RED), true);
                    return TypedActionResult.fail(held);
                }

                // Transform!
                phs.lastTransformTick.put(sp.getUuid(), now);
                CobbleShiftHook.transformPlayer(sp, matchedSpecies, phs.attacksEnabled);
                phs.hiderSpecies.put(sp.getUuid(), matchedSpecies);
                sp.sendMessage(Text.translatable("cheeseguard.prophunt.transformed", matchedSpecies)
                    .formatted(Formatting.GREEN), true);
                sp.playSound(net.minecraft.sound.SoundEvents.ENTITY_ITEM_PICKUP, 1f, 1.2f);
                return TypedActionResult.success(held);
            }

            return TypedActionResult.pass(held);
        });
    }

    // ── Queue management ─────────────────────────────────────────────────────

    public boolean joinQueue(String eventId, UUID playerId) {
        queues.computeIfAbsent(eventId, k -> new ArrayList<>());
        List<UUID> queue = queues.get(eventId);
        if (queue.contains(playerId)) return false;
        queue.add(playerId);
        return true;
    }

    public void leaveQueue(String eventId, UUID playerId) {
        List<UUID> queue = queues.get(eventId);
        if (queue != null) queue.remove(playerId);
    }

    public List<UUID> getQueue(String eventId) {
        return queues.getOrDefault(eventId, Collections.emptyList());
    }

    public int getQueueSize(String eventId) {
        return queues.containsKey(eventId) ? queues.get(eventId).size() : 0;
    }

    /**
     * Check if queue has enough players to start. If so, move them to the event.
     * @return true if event should start
     */
    public boolean checkQueueReady(ServerEvent event) {
        List<UUID> queue = queues.get(event.getId());
        if (queue == null || queue.isEmpty()) return false;
        if (event.getMinParticipants() > 0 && queue.size() < event.getMinParticipants()) return false;

        EventManager em = CheeseGuardMod.getInstance().getEventManager();
        int max = event.getMaxParticipants() > 0 ? event.getMaxParticipants() : queue.size();
        int toAdd = Math.min(max, queue.size());
        List<UUID> selected = new ArrayList<>(queue.subList(0, toAdd));

        for (UUID uuid : selected) {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(uuid);
            if (pl != null) {
                em.joinEvent(event, pl, EventParticipant.Role.PARTICIPANT);
            }
            queue.remove(uuid);
        }
        return true;
    }

    public void clearQueue(String eventId) {
        queues.remove(eventId);
    }

    // ── Mini-game lifecycle ──────────────────────────────────────────────────

    /**
     * Called when a mini-game event starts. Sets up type-specific logic.
     */
    public void onEventStart(ServerEvent event, MiniGameType type) {
        MiniGameState state = new MiniGameState(type, event.getId());
        activeGames.put(event.getId(), state);

        switch (type) {
            case SPLEEF -> {
                initSpleefEnhanced(event, state);
            }
            case SPEED_BUILD -> {
                // Clear the build area
                if (!event.getScopeZoneIds().isEmpty()) {
                    String zoneId = event.getScopeZoneIds().getFirst();
                    state.snapshot = new ZoneSnapshot("build_" + event.getId(), zoneId);
                    state.snapshot.capture(server);
                    state.snapshot.clear(server);
                }
            }
            case MOB_ARENA -> {
                state.waveNumber = 0;
                state.lastWaveSpawn = System.currentTimeMillis();
            }
            case DEATHMATCH, HIDE_SEEK -> {
                // All participants start alive
                state.eliminated.clear();
            }
            case PROP_HUNT -> {
                initPropHunt(event, state);
            }
            case THIMBLE -> {
                initThimble(event, state);
            }
            case RACE -> {
                initRace(event, state);
            }
            default -> {}
        }

        CheeseGuardMod.LOGGER.info("[CG] Mini-game started: {} (type={})", event.getName(), type.displayName);
    }

    /**
     * Called every second for active mini-games.
     */
    public void tickMiniGame(ServerEvent event) {
        MiniGameState state = activeGames.get(event.getId());
        if (state == null) return;

        switch (state.type) {
            case DEATHMATCH -> tickDeathmatch(event, state);
            case MOB_ARENA -> tickMobArena(event, state);
            case SPLEEF -> tickSpleef(event, state);
            case KING_OF_HILL -> tickKingOfHill(event, state);
            case THIMBLE -> {
                ThimbleState ts = thimbleStates.get(event.getId());
                if (ts != null) tickThimble(event, ts);
            }
            case RACE -> {
                RaceState rs = raceStates.get(event.getId());
                if (rs != null) tickRace(event, rs);
            }
            case PROP_HUNT -> {
                PropHuntState phs = propHuntStates.get(event.getId());
                if (phs != null) tickPropHunt(event, phs, state);
            }
            default -> {} // Other types use standard scoring triggers
        }
    }

    /**
     * Called when a mini-game event ends. Cleans up type-specific state.
     */
    public void onEventEnd(ServerEvent event) {
        MiniGameState state = activeGames.remove(event.getId());
        if (state == null) return;

        // Thimble cleanup
        ThimbleState ts = thimbleStates.remove(event.getId());
        if (ts != null) {
            cleanupThimble(event, ts);
        }

        // Race cleanup
        RaceState rs = raceStates.remove(event.getId());
        if (rs != null) {
            cleanupRace(event, rs);
        }

        // Prop Hunt cleanup
        PropHuntState phs = propHuntStates.remove(event.getId());
        if (phs != null) {
            cleanupPropHunt(event, phs);
        }

        // Spleef Enhanced cleanup — restore all layer snapshots
        SpleefState ss = spleefStates.remove(event.getId());
        if (ss != null) {
            for (ZoneSnapshot snap : ss.snapshots) {
                if (!snap.isEmpty()) {
                    snap.restore(server);
                }
            }
            CheeseGuardMod.LOGGER.info("[CG] Spleef Enhanced: {} layers restored for '{}'", ss.snapshots.size(), event.getName());
        }

        // Restore snapshots (non-Spleef, legacy single snapshot)
        if (state.snapshot != null && !state.snapshot.isEmpty()) {
            state.snapshot.restore(server);
            CheeseGuardMod.LOGGER.info("[CG] Mini-game '{}': zone restored", event.getName());
        }

        // Thimble: restore pixel art template (selective — only interior positions) + platforms
        if (ts != null) {
            // Restore pixel art blocks (replace water back to original blocks)
            if (!ts.pixelArtTemplate.isEmpty()) {
                ServerWorld tWorld = getWorldById(ts.worldId);
                if (tWorld != null) {
                    int restored = 0;
                    for (var entry : ts.pixelArtTemplate.entrySet()) {
                        BlockPos pos = BlockPos.fromLong(entry.getKey());
                        tWorld.setBlockState(pos, entry.getValue(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                        restored++;
                    }
                    CheeseGuardMod.LOGGER.info("[CG] Thimble: restored {} pixel art blocks", restored);
                }
            }
            // Restore platform zones
            if (ts.platformSnapshot != null) {
                ts.platformSnapshot.restore(server);
            }
            if (ts.sdPlatformSnapshot != null) {
                ts.sdPlatformSnapshot.restore(server);
            }
        }

        // Remove tracked entities
        for (UUID entityUuid : state.entityWaypoints) {
            for (var world : server.getWorlds()) {
                Entity entity = world.getEntity(entityUuid);
                if (entity != null) {
                    entity.discard();
                    break;
                }
            }
        }

        clearQueue(event.getId());
    }

    /**
     * Called when a player leaves an active mini-game event (via leave item or command).
     * Marks them eliminated and handles Thimble-specific logic (active jumper leaving).
     */
    public void onPlayerLeave(ServerEvent event, UUID playerUuid) {
        MiniGameState state = activeGames.get(event.getId());
        if (state == null) return;
        state.eliminated.add(playerUuid);

        if (state.type == MiniGameType.THIMBLE) {
            ThimbleState ts = thimbleStates.get(event.getId());
            if (ts != null) {
                ts.eliminated.add(playerUuid);
                if (playerUuid.equals(ts.currentJumper)) {
                    // Active jumper is leaving — advance the turn
                    ts.currentJumper = null;
                    if (!checkThimbleWinner(event, ts)) {
                        advanceThimbleTurn(event, ts, false);
                    }
                } else {
                    // Not the active jumper — just check if game should end
                    checkThimbleWinner(event, ts);
                }
            }
        }

        if (state.type == MiniGameType.SPLEEF) {
            SpleefState ss = spleefStates.get(event.getId());
            if (ss != null) {
                ss.lastKnownLayer.remove(playerUuid);
                ss.lastScoreTick.remove(playerUuid);
            }
        }

        if (state.type == MiniGameType.PROP_HUNT) {
            PropHuntState phs = propHuntStates.get(event.getId());
            if (phs != null) {
                phs.eliminatedHiders.add(playerUuid);
                phs.eliminatedHunters.add(playerUuid);
                // Revert transform + clear items
                ServerPlayerEntity pl = server.getPlayerManager().getPlayer(playerUuid);
                if (pl != null) {
                    CobbleShiftHook.revertPlayer(pl);
                    clearPropHuntItems(pl);
                } else {
                    CobbleShiftHook.addPendingRevert(playerUuid);
                }
            }
        }

        if (state.type == MiniGameType.RACE) {
            RaceState raceState = raceStates.get(event.getId());
            if (raceState != null) {
                raceState.playerNextCheckpoint.remove(playerUuid);
                raceState.playerTransformCommands.remove(playerUuid);
                raceState.playerCurrentLap.remove(playerUuid);
                raceState.playerValidatedCps.remove(playerUuid);
                raceState.playerLastCpCenter.remove(playerUuid);
                raceState.prevPositions.remove(playerUuid);
                boolean revert = raceState.isTrackConfigMode()
                    ? raceState.trackConfig.isRevertTransformsOnEnd()
                    : getConfigBool(event, "revertTransformsOnEnd", true);
                if (revert && CobbleShiftHook.isAvailable()) {
                    ServerPlayerEntity pl = server.getPlayerManager().getPlayer(playerUuid);
                    if (pl != null) CobbleShiftHook.revertPlayer(pl);
                }
            }
        }
    }

    // ── Type-specific tick logic ─────────────────────────────────────────────

    private void tickDeathmatch(ServerEvent event, MiniGameState state) {
        EventManager em = CheeseGuardMod.getInstance().getEventManager();
        // Check for dead participants — mark them eliminated
        int alive = 0;
        for (var p : event.getParticipants().values()) {
            if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
            if (state.eliminated.contains(p.getPlayerUuid())) continue;
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (pl == null || pl.isDead()) {
                state.eliminated.add(p.getPlayerUuid());
                broadcast(event, "☠ " + p.getPlayerName() + " est elimine !", "red");
            } else {
                alive++;
            }
        }
        // Last player standing wins
        if (alive <= 1 && event.getParticipants().size() > 1) {
            // Find winner
            for (var p : event.getParticipants().values()) {
                if (p.getRole() == EventParticipant.Role.PARTICIPANT && !state.eliminated.contains(p.getPlayerUuid())) {
                    em.addScore(event, p.getPlayerUuid(), 100);
                    break;
                }
            }
            em.endEvent(event);
        }
    }

    // ── Spleef Enhanced ─────────────────────────────────────────────────────

    private void initSpleefEnhanced(ServerEvent event, MiniGameState state) {
        SpleefState ss = new SpleefState();

        // Capture each scope zone as a layer
        List<String> zoneIds = event.getScopeZoneIds();
        for (int i = 0; i < zoneIds.size(); i++) {
            String zoneId = zoneIds.get(i);
            Region region = CheeseGuardMod.getInstance().getRegionManager().getStorage().getRegion(zoneId);
            if (region == null) continue;

            ZoneSnapshot snap = new ZoneSnapshot("spleef_" + event.getId() + "_" + i, zoneId);
            int saved = snap.capture(server);
            ss.snapshots.add(snap);
            ss.layers.add(new LayerInfo(zoneId, region.getMinY(), region.getMaxY(), "Couche " + (i + 1)));
            CheeseGuardMod.LOGGER.info("[CG] Spleef: layer {} ({}) saved {} blocks", i + 1, zoneId, saved);
        }

        // Sort layers by maxY descending (top layer = index 0)
        ss.layers.sort((a, b) -> Integer.compare(b.maxY, a.maxY));

        // Elimination Y = below the lowest layer
        if (!ss.layers.isEmpty()) {
            ss.eliminationY = ss.layers.get(ss.layers.size() - 1).minY - 1;
        }

        // Scan breakable blocks in each layer for progressive degradation
        boolean degradeEnabled = getConfigBool(event, "degradeEnabled", false);
        if (degradeEnabled) {
            for (LayerInfo layer : ss.layers) {
                Region reg = CheeseGuardMod.getInstance().getRegionManager().getStorage().getRegion(layer.zoneId);
                if (reg == null) continue;
                ServerWorld world = null;
                for (var sw : server.getWorlds()) {
                    if (sw.getRegistryKey().getValue().toString().equals(reg.getWorld())) {
                        world = sw;
                        break;
                    }
                }
                if (world == null) continue;
                for (int x = reg.getMinX(); x <= reg.getMaxX(); x++) {
                    for (int y = reg.getMinY(); y <= reg.getMaxY(); y++) {
                        for (int z = reg.getMinZ(); z <= reg.getMaxZ(); z++) {
                            BlockPos pos = new BlockPos(x, y, z);
                            BlockState bs = world.getBlockState(pos);
                            if (!bs.isAir()) {
                                layer.breakableBlocks.add(pos);
                            }
                        }
                    }
                }
                layer.totalBlocks = layer.breakableBlocks.size();
                // Shuffle for random removal order
                Collections.shuffle(layer.breakableBlocks);
                CheeseGuardMod.LOGGER.info("[CG] Spleef: layer '{}' has {} breakable blocks", layer.name, layer.totalBlocks);
            }
        }

        // Read countdown from config
        ss.countdownRemaining = getConfigInt(event, "countdownSeconds", 5);
        ss.gameActive = ss.countdownRemaining <= 0;
        ss.lastTickMs = System.currentTimeMillis();
        if (ss.gameActive) {
            ss.gameStartMs = System.currentTimeMillis();
            ss.lastDegradeMs = ss.gameStartMs;
        }

        // TP all participants to the arena, spectators to spectator spot (zone-based or fixed coords)
        for (var p : event.getParticipants().values()) {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (pl == null) continue;
            tpToZoneOrCoords(pl, event, p.getRole() != EventParticipant.Role.PARTICIPANT);
        }

        // Freeze all participants during countdown
        if (ss.countdownRemaining > 0) {
            for (var p : event.getParticipants().values()) {
                if (p.getRole() == EventParticipant.Role.PARTICIPANT) {
                    ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
                    if (pl != null) {
                        freezePlayer(pl);
                    }
                }
            }
        }

        spleefStates.put(event.getId(), ss);
        CheeseGuardMod.LOGGER.info("[CG] Spleef Enhanced: {} layers, elimination Y={}, countdown={}s",
            ss.layers.size(), ss.eliminationY, ss.countdownRemaining);
    }

    private void tickSpleef(ServerEvent event, MiniGameState state) {
        SpleefState ss = spleefStates.get(event.getId());
        if (ss == null) {
            // Fallback: basic elimination if no enhanced state (shouldn't happen)
            return;
        }
        EventManager em = CheeseGuardMod.getInstance().getEventManager();
        long now = System.currentTimeMillis();

        // ── Countdown phase ──
        if (ss.countdownRemaining > 0) {
            // Re-apply freeze each tick to keep players immobile
            for (var fp : event.getParticipants().values()) {
                if (fp.getRole() == EventParticipant.Role.PARTICIPANT) {
                    ServerPlayerEntity fpl = server.getPlayerManager().getPlayer(fp.getPlayerUuid());
                    if (fpl != null) freezePlayer(fpl);
                }
            }
            if (now - ss.lastTickMs >= 1000) {
                ss.lastTickMs = now;
                ss.countdownRemaining--;

                if (ss.countdownRemaining > 0 && ss.countdownRemaining <= 3) {
                    // Title: 3, 2, 1
                    String color = ss.countdownRemaining == 1 ? "red" : ss.countdownRemaining == 2 ? "gold" : "yellow";
                    for (var p : event.getParticipants().values()) {
                        ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
                        if (pl != null) {
                            sendTitle(pl, Formatting.byName(color) + "" + ss.countdownRemaining, "", 0, 20, 5);
                            playSoundTo(pl, "block.note_block.hat", 1f, 1f);
                        }
                    }
                }

                if (ss.countdownRemaining <= 0) {
                    // GO!
                    ss.gameActive = true;
                    ss.gameStartMs = System.currentTimeMillis();
                    ss.lastDegradeMs = ss.gameStartMs;
                    for (var p : event.getParticipants().values()) {
                        if (p.getRole() == EventParticipant.Role.PARTICIPANT) {
                            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
                            if (pl != null) {
                                unfreezePlayer(pl);
                                sendTitle(pl, Formatting.GREEN + "GO !", "", 0, 30, 10);
                                playSoundTo(pl, "entity.ender_dragon.growl", 0.6f, 1.5f);
                            }
                        }
                    }
                }
            }
            return;
        }

        // ── Active phase ──
        if (!ss.gameActive) return;

        int survivalPtsPerSec = getConfigInt(event, "survivalPointsPerSec", 1);
        int victoryPoints = getConfigInt(event, "victoryPoints", 100);
        boolean layerTransition = getConfigBool(event, "layerTransitionMessage", true);
        // eliminationMessage config removed — use translatable key

        int alive = 0;
        for (var p : event.getParticipants().values()) {
            if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
            if (state.eliminated.contains(p.getPlayerUuid())) continue;

            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());

            // Check elimination
            if (pl == null || pl.getY() < ss.eliminationY) {
                state.eliminated.add(p.getPlayerUuid());
                broadcastText(event, Text.translatable("cheeseguard.spleef.eliminated", p.getPlayerName()).formatted(Formatting.AQUA));

                // TP to spectator area and apply ghost spectator mode (Rotom + fly)
                if (pl != null) {
                    tpToZoneOrCoords(pl, event, true);
                    playSoundTo(pl, "entity.blaze.death", 1f, 0.8f);
                    em.becomeEventSpectator(event, pl);
                } else {
                    p.setRole(EventParticipant.Role.SPECTATOR);
                }
                ss.lastKnownLayer.remove(p.getPlayerUuid());
                ss.lastScoreTick.remove(p.getPlayerUuid());
                continue;
            }

            alive++;

            // Survival scoring
            if (survivalPtsPerSec > 0) {
                long lastScore = ss.lastScoreTick.getOrDefault(p.getPlayerUuid(), 0L);
                if (now - lastScore >= 1000) {
                    ss.lastScoreTick.put(p.getPlayerUuid(), now);
                    em.addScore(event, p.getPlayerUuid(), survivalPtsPerSec);
                }
            }

            // Layer transition detection
            if (layerTransition && !ss.layers.isEmpty()) {
                int currentLayer = getCurrentSpleefLayer(ss, pl.getY());
                int prevLayer = ss.lastKnownLayer.getOrDefault(p.getPlayerUuid(), 0);
                if (currentLayer > prevLayer && prevLayer >= 0) {
                    broadcastText(event, Text.translatable("cheeseguard.spleef.layer_reached", p.getPlayerName(), ss.layers.get(Math.min(currentLayer, ss.layers.size() - 1)).name).formatted(Formatting.YELLOW));
                }
                ss.lastKnownLayer.put(p.getPlayerUuid(), currentLayer);
            }
        }

        // ── Progressive degradation (2-phase: warning particles → remove) ──
        boolean degradeEnabled = getConfigBool(event, "degradeEnabled", false);
        if (degradeEnabled && !ss.layers.isEmpty()) {
            int degradeDelay = getConfigInt(event, "degradeDelaySeconds", 30);
            int degradeRate = getConfigInt(event, "degradeBlocksPerTick", 5);
            int degradeThreshold = getConfigInt(event, "degradeThresholdPercent", 80);
            int warningDurationMs = 2000; // 2 seconds of warning particles before removal

            // Resolve the world once and cache it
            ServerWorld degradeWorld = null;
            if (ss.degradeWorldId != null) {
                for (var sw : server.getWorlds()) {
                    if (sw.getRegistryKey().getValue().toString().equals(ss.degradeWorldId)) {
                        degradeWorld = sw;
                        break;
                    }
                }
            } else if (ss.degradingLayerIndex < ss.layers.size()) {
                Region degradeReg = CheeseGuardMod.getInstance().getRegionManager().getStorage().getRegion(
                    ss.layers.get(ss.degradingLayerIndex).zoneId);
                if (degradeReg != null) {
                    ss.degradeWorldId = degradeReg.getWorld();
                    for (var sw : server.getWorlds()) {
                        if (sw.getRegistryKey().getValue().toString().equals(ss.degradeWorldId)) {
                            degradeWorld = sw;
                            break;
                        }
                    }
                }
            }

            long gameElapsed = (now - ss.gameStartMs) / 1000;
            if (gameElapsed >= degradeDelay && degradeWorld != null) {

                // Phase 1: If warning blocks exist, show particles and check if ready to remove
                if (!ss.warningQueue.isEmpty()) {
                    // Spawn warning particles on each warned block
                    for (BlockPos wpos : ss.warningQueue) {
                        degradeWorld.spawnParticles(
                            net.minecraft.particle.ParticleTypes.SMOKE,
                            wpos.getX() + 0.5, wpos.getY() + 1.0, wpos.getZ() + 0.5,
                            3, 0.3, 0.2, 0.3, 0.01
                        );
                        degradeWorld.spawnParticles(
                            net.minecraft.particle.ParticleTypes.FLAME,
                            wpos.getX() + 0.5, wpos.getY() + 0.8, wpos.getZ() + 0.5,
                            1, 0.2, 0.1, 0.2, 0.005
                        );
                    }

                    // After warning duration, actually remove the blocks
                    if (now - ss.warningStartMs >= warningDurationMs) {
                        for (BlockPos wpos : ss.warningQueue) {
                            BlockState existing = degradeWorld.getBlockState(wpos);
                            if (!existing.isAir()) {
                                degradeWorld.setBlockState(wpos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                                degradeWorld.syncWorldEvent(2001, wpos, Block.getRawIdFromState(existing));
                            }
                            // Attribute to correct layer by Y coordinate
                            for (LayerInfo layer : ss.layers) {
                                if (wpos.getY() >= layer.minY && wpos.getY() <= layer.maxY) {
                                    layer.removedBlocks++;
                                    break;
                                }
                            }
                        }
                        ss.warningQueue.clear();

                        // Mark fully degraded layers (all blocks gone)
                        for (LayerInfo layer : ss.layers) {
                            if (!layer.fullyDegraded && layer.breakableBlocks.isEmpty() && layer.removedBlocks >= layer.totalBlocks) {
                                layer.fullyDegraded = true;
                            }
                        }

                        // Check if frontmost layer hit threshold → start next layer
                        if (ss.degradingLayerIndex < ss.layers.size()) {
                            LayerInfo front = ss.layers.get(ss.degradingLayerIndex);
                            if (!front.thresholdReached && front.totalBlocks > 0) {
                                int pctRemoved = (front.removedBlocks * 100) / front.totalBlocks;
                                if (pctRemoved >= degradeThreshold) {
                                    front.thresholdReached = true;
                                    broadcastText(event, Text.translatable("cheeseguard.spleef.layer_destroyed", front.name).formatted(Formatting.DARK_RED));
                                    for (var p2 : event.getParticipants().values()) {
                                        ServerPlayerEntity pl2 = server.getPlayerManager().getPlayer(p2.getPlayerUuid());
                                        if (pl2 != null) playSoundTo(pl2, "entity.wither.break_block", 0.8f, 1f);
                                    }
                                    ss.degradingLayerIndex++;
                                    // Don't reset degradeWorldId — all spleef layers are in the same world
                                }
                            }
                        }
                    }
                }

                // Phase 2: Queue next batch of blocks for warning (every second, if no pending warning)
                // Iterates ALL active layers (0 to degradingLayerIndex) so upper layers keep degrading
                if (ss.warningQueue.isEmpty() && now - ss.lastDegradeMs >= 1000) {
                    ss.lastDegradeMs = now;

                    int maxLayer = Math.min(ss.degradingLayerIndex, ss.layers.size() - 1);
                    for (int li = 0; li <= maxLayer; li++) {
                        LayerInfo layer = ss.layers.get(li);
                        if (layer.fullyDegraded) continue;

                        // Announce degradation start for this layer (per-layer)
                        if (!layer.degradationAnnounced) {
                            layer.degradationAnnounced = true;
                            broadcastText(event, Text.translatable("cheeseguard.spleef.layer_degrading", layer.name).formatted(Formatting.RED));
                            for (var p2 : event.getParticipants().values()) {
                                ServerPlayerEntity pl2 = server.getPlayerManager().getPlayer(p2.getPlayerUuid());
                                if (pl2 != null) playSoundTo(pl2, "block.glass.break", 1f, 0.8f);
                            }
                        }

                        // Pick next batch of blocks from this layer
                        int queued = 0;
                        while (queued < degradeRate && !layer.breakableBlocks.isEmpty()) {
                            BlockPos pos = layer.breakableBlocks.remove(layer.breakableBlocks.size() - 1);
                            BlockState existing = degradeWorld.getBlockState(pos);
                            if (!existing.isAir()) {
                                ss.warningQueue.add(pos);
                                queued++;
                            } else {
                                layer.removedBlocks++; // already gone (player broke it)
                            }
                        }

                        // Check if layer is now fully empty
                        if (layer.breakableBlocks.isEmpty()) {
                            layer.fullyDegraded = true;
                        }
                    }

                    if (!ss.warningQueue.isEmpty()) {
                        ss.warningStartMs = now;
                        for (BlockPos wpos : ss.warningQueue) {
                            degradeWorld.spawnParticles(
                                net.minecraft.particle.ParticleTypes.CRIT,
                                wpos.getX() + 0.5, wpos.getY() + 0.5, wpos.getZ() + 0.5,
                                8, 0.4, 0.2, 0.4, 0.1
                            );
                        }
                    }
                }
            }
        }

        // Victory check
        if (alive <= 1 && event.getParticipants().size() > 1) {
            for (var p : event.getParticipants().values()) {
                if (p.getRole() == EventParticipant.Role.PARTICIPANT && !state.eliminated.contains(p.getPlayerUuid())) {
                    em.addScore(event, p.getPlayerUuid(), victoryPoints);
                    broadcastText(event, Text.translatable("cheeseguard.spleef.victory", p.getPlayerName()).formatted(Formatting.GOLD));

                    // Visual-only fireworks (no damage)
                    ServerPlayerEntity winner = server.getPlayerManager().getPlayer(p.getPlayerUuid());
                    if (winner != null && winner.getWorld() instanceof ServerWorld winWorld) {
                        for (int i = 0; i < 3; i++) {
                            int ox = (int)((Math.random() - 0.5) * 4);
                            int oz = (int)((Math.random() - 0.5) * 4);
                            spawnFirework(winWorld, winner.getBlockPos().add(ox, 1, oz));
                        }
                    }
                    break;
                }
            }
            em.endEvent(event);
        }
    }

    /** Returns the layer index (0 = top, increasing = deeper) for a given Y position. */
    private int getCurrentSpleefLayer(SpleefState ss, double playerY) {
        for (int i = 0; i < ss.layers.size(); i++) {
            if (playerY >= ss.layers.get(i).minY) return i;
        }
        return ss.layers.size(); // below all layers
    }

    private void sendTitle(ServerPlayerEntity p, String title, String sub, int fi, int stay, int fo) {
        p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket(fi, stay, fo));
        p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.SubtitleS2CPacket(Text.literal(sub)));
        p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleS2CPacket(Text.literal(title)));
    }

    private void playSoundTo(ServerPlayerEntity p, String soundId, float volume, float pitch) {
        Identifier id = Identifier.tryParse("minecraft:" + soundId);
        if (id != null) {
            net.minecraft.sound.SoundEvent snd = net.minecraft.sound.SoundEvent.of(id);
            p.playSoundToPlayer(snd, net.minecraft.sound.SoundCategory.MASTER, volume, pitch);
        }
    }

    private void tickMobArena(ServerEvent event, MiniGameState state) {
        long now = System.currentTimeMillis();
        // Spawn waves every 30 seconds
        if (now - state.lastWaveSpawn >= 30000) {
            state.waveNumber++;
            state.lastWaveSpawn = now;
            broadcast(event, "⚠ Vague " + state.waveNumber + " !", "red");
            // Use kit entry commands or effect ENTITY_SPAWN for wave spawning
            // Admins configure the spawn commands in the event's effects
        }
        // Check for all dead
        boolean anyAlive = false;
        for (var p : event.getParticipants().values()) {
            if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (pl != null && !pl.isDead()) { anyAlive = true; break; }
        }
        if (!anyAlive && event.getParticipantCount() > 0) {
            broadcast(event, "☠ Tous les joueurs sont tombes a la vague " + state.waveNumber + " !", "red");
            CheeseGuardMod.getInstance().getEventManager().endEvent(event);
        }
    }

    private void tickKingOfHill(ServerEvent event, MiniGameState state) {
        if (event.getScopeZoneIds().isEmpty()) return;
        String hillZone = event.getScopeZoneIds().getFirst(); // First zone = the hill

        for (var p : event.getParticipants().values()) {
            if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (pl == null) continue;

            String worldId = pl.getServerWorld().getRegistryKey().getValue().toString();
            var regions = CheeseGuardMod.getInstance().getRegionManager()
                .getRegionsAt(worldId, (int) pl.getX(), (int) pl.getY(), (int) pl.getZ());
            boolean onHill = regions.stream().anyMatch(r -> r.getId().equals(hillZone));

            if (onHill) {
                int timer = state.zoneTimers.getOrDefault(p.getPlayerUuid(), 0) + 1;
                state.zoneTimers.put(p.getPlayerUuid(), timer);
                // Score 1 point per second on the hill
                CheeseGuardMod.getInstance().getEventManager().addScore(event, p.getPlayerUuid(), 1);
            }
        }
    }

    // ── Thimble (Dé à Coudre) ────────────────────────────────────────────────

    private void initThimble(ServerEvent event, MiniGameState state) {
        ThimbleState ts = new ThimbleState();

        // Read pool zone bounds (NO full snapshot — we use pixelArtTemplate for selective restore)
        if (!event.getScopeZoneIds().isEmpty()) {
            String zoneId = event.getScopeZoneIds().getFirst();
            Region region = CheeseGuardMod.getInstance().getRegionManager().getStorage().getRegion(zoneId);
            if (region != null) {
                ts.poolMinX = region.getMinX(); ts.poolMinY = region.getMinY(); ts.poolMinZ = region.getMinZ();
                ts.poolMaxX = region.getMaxX(); ts.poolMaxY = region.getMaxY(); ts.poolMaxZ = region.getMaxZ();
                ts.worldId = region.getWorld();
            }
        }

        // Platform position = event TP participant coords
        ts.platformX = event.getTpParticipantX();
        ts.platformY = event.getTpParticipantY();
        ts.platformZ = event.getTpParticipantZ();
        ts.platformWorld = event.getTpParticipantWorld();

        // Read border block from config (defines pool contour shape)
        ts.borderBlock = getConfigString(event, "borderBlock", "minecraft:stone");

        // Scan pool zone: use flood-fill per Y layer to identify interior blocks only
        ServerWorld world = getWorldById(ts.worldId);
        if (world != null) {
            Identifier borderId = Identifier.tryParse(ts.borderBlock);
            Block borderBlockType = borderId != null ? Registries.BLOCK.get(borderId) : Blocks.STONE;
            int sizeX = ts.poolMaxX - ts.poolMinX + 1;
            int sizeZ = ts.poolMaxZ - ts.poolMinZ + 1;

            for (int y = ts.poolMinY; y <= ts.poolMaxY; y++) {
                // Build a 2D grid for this Y layer: true = border (wall), false = not border
                boolean[][] isBorder = new boolean[sizeX][sizeZ];
                for (int x = 0; x < sizeX; x++) {
                    for (int z = 0; z < sizeZ; z++) {
                        BlockState bs = world.getBlockState(new BlockPos(ts.poolMinX + x, y, ts.poolMinZ + z));
                        isBorder[x][z] = bs.getBlock() == borderBlockType;
                    }
                }

                // Flood fill from all edges to mark exterior cells
                boolean[][] isExterior = new boolean[sizeX][sizeZ];
                Deque<int[]> queue = new ArrayDeque<>();
                // Seed edges
                for (int x = 0; x < sizeX; x++) {
                    if (!isBorder[x][0]) { isExterior[x][0] = true; queue.add(new int[]{x, 0}); }
                    if (!isBorder[x][sizeZ - 1]) { isExterior[x][sizeZ - 1] = true; queue.add(new int[]{x, sizeZ - 1}); }
                }
                for (int z = 1; z < sizeZ - 1; z++) {
                    if (!isBorder[0][z]) { isExterior[0][z] = true; queue.add(new int[]{0, z}); }
                    if (!isBorder[sizeX - 1][z]) { isExterior[sizeX - 1][z] = true; queue.add(new int[]{sizeX - 1, z}); }
                }
                // BFS flood fill
                int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                while (!queue.isEmpty()) {
                    int[] cell = queue.poll();
                    for (int[] d : dirs) {
                        int nx = cell[0] + d[0], nz = cell[1] + d[1];
                        if (nx >= 0 && nx < sizeX && nz >= 0 && nz < sizeZ && !isExterior[nx][nz] && !isBorder[nx][nz]) {
                            isExterior[nx][nz] = true;
                            queue.add(new int[]{nx, nz});
                        }
                    }
                }

                // Interior = not border AND not exterior
                for (int x = 0; x < sizeX; x++) {
                    for (int z = 0; z < sizeZ; z++) {
                        if (isBorder[x][z] || isExterior[x][z]) continue;
                        BlockPos pos = new BlockPos(ts.poolMinX + x, y, ts.poolMinZ + z);
                        BlockState bs = world.getBlockState(pos);
                        if (bs.isAir() || bs.getBlock() == Blocks.WATER) continue;
                        // Interior non-air non-water block = pixel art
                        ts.pixelArtTemplate.put(pos.asLong(), bs);
                        world.setBlockState(pos, Blocks.WATER.getDefaultState(),
                            Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                    }
                }
            }
            ts.initialWaterBlocks = ts.pixelArtTemplate.size();
            CheeseGuardMod.LOGGER.info("[CG] Thimble: scanned pixel art template (flood-fill) — {} interior blocks saved, border={}",
                ts.pixelArtTemplate.size(), ts.borderBlock);
        }

        // Platform zone snapshots: capture both, hide sudden death platform at start
        String platformZoneId = getConfigString(event, "platformZoneId", "");
        if (!platformZoneId.isBlank()) {
            ts.platformSnapshot = new ZoneSnapshot("thimble_platform_" + event.getId(), platformZoneId);
            ts.platformSnapshot.capture(server);
            CheeseGuardMod.LOGGER.info("[CG] Thimble: captured normal platform zone '{}' ({} blocks)",
                platformZoneId, ts.platformSnapshot.getBlockCount());
        }
        String sdPlatformZoneId = getConfigString(event, "suddenDeathPlatformZoneId", "");
        if (!sdPlatformZoneId.isBlank()) {
            ts.sdPlatformSnapshot = new ZoneSnapshot("thimble_sd_platform_" + event.getId(), sdPlatformZoneId);
            ts.sdPlatformSnapshot.capture(server);
            // Hide the sudden death platform at game start (not needed yet)
            ts.sdPlatformSnapshot.clear(server);
            CheeseGuardMod.LOGGER.info("[CG] Thimble: captured & hidden SD platform zone '{}' ({} blocks)",
                sdPlatformZoneId, ts.sdPlatformSnapshot.getBlockCount());
        }

        // Read config-driven parameters
        int startingLives = getConfigInt(event, "startingLives", 1);
        int turnTimeoutSeconds = getConfigInt(event, "turnTimeoutSeconds", 30);
        ts.turnTimeoutTicks = turnTimeoutSeconds * 20;

        // Sudden death TP (optional override for platform TP during sudden death)
        String sdZonesCsv = getConfigString(event, "suddenDeathTpZoneIds", "");
        if (!sdZonesCsv.isBlank()) {
            for (String z : sdZonesCsv.split(",")) {
                String trimmed = z.trim();
                if (!trimmed.isEmpty()) ts.suddenDeathZoneIds.add(trimmed);
            }
            if (!ts.suddenDeathZoneIds.isEmpty()) ts.hasSuddenDeathTp = true;
        }
        if (!ts.hasSuddenDeathTp) {
            String sdWorld = getConfigString(event, "suddenDeathTpWorld", "");
            if (!sdWorld.isEmpty()) {
                ts.suddenDeathX = getConfigInt(event, "suddenDeathTpX", 0);
                ts.suddenDeathY = getConfigInt(event, "suddenDeathTpY", 0);
                ts.suddenDeathZ = getConfigInt(event, "suddenDeathTpZ", 0);
                ts.suddenDeathWorld = sdWorld;
                ts.hasSuddenDeathTp = true;
            }
        }

        // Build turn order from participants
        for (var p : event.getParticipants().values()) {
            if (p.getRole() == EventParticipant.Role.PARTICIPANT) {
                ts.turnOrder.add(p.getPlayerUuid());
                ts.lives.put(p.getPlayerUuid(), startingLives);
            }
        }
        Collections.shuffle(ts.turnOrder); // random turn order

        ts.gameStartTick = server.getTicks();
        ts.gameStarted = true;
        thimbleStates.put(event.getId(), ts);

        // TP all participants to spectator spot (they wait there until their turn)
        // Also TP any SPECTATOR-role players to spectator spot (zone-based or fixed coords)
        for (var p : event.getParticipants().values()) {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (pl != null) {
                tpToZoneOrCoords(pl, event, true);
            }
        }

        // If event has multiple acts (prep phase), defer first turn until prep act ends
        if (event.getActs().size() > 1) {
            ts.waitingForPrepPhase = true;
            CheeseGuardMod.LOGGER.info("[CG] Thimble: initialized with {} players, waiting for prep phase to end",
                ts.turnOrder.size());
        } else {
            // No prep phase — start first turn immediately
            advanceThimbleTurn(event, ts, true);
            CheeseGuardMod.LOGGER.info("[CG] Thimble: initialized with {} players, first turn started",
                ts.turnOrder.size());
        }

        CheeseGuardMod.LOGGER.info("[CG] Thimble: pool zone bounds [{},{},{}]-[{},{},{}]",
            ts.poolMinX, ts.poolMinY, ts.poolMinZ, ts.poolMaxX, ts.poolMaxY, ts.poolMaxZ);
    }

    /**
     * 1Hz tick for Thimble — timeout check and actionbar display.
     */
    private void tickThimble(ServerEvent event, ThimbleState ts) {
        if (!ts.gameStarted) return;

        // If waiting for prep phase to end, check if we've moved past act 0
        if (ts.waitingForPrepPhase) {
            TimelineExecutor tl = CheeseGuardMod.getInstance().getEventManager().getTimelineExecutor();
            if (tl != null && tl.getCurrentActIndex(event.getId()) > 0) {
                ts.waitingForPrepPhase = false;
                advanceThimbleTurn(event, ts, true);
                CheeseGuardMod.LOGGER.info("[CG] Thimble: prep phase done, starting first turn");
            }
            return;
        }

        if (ts.currentJumper == null) return;

        int elapsed = server.getTicks() - (int) ts.turnStartTick;
        int remaining = (ts.turnTimeoutTicks - elapsed) / 20; // seconds remaining

        // Actionbar for current jumper
        ServerPlayerEntity jumper = server.getPlayerManager().getPlayer(ts.currentJumper);
        if (jumper != null) {
            if (ts.suddenDeath) {
                jumper.sendMessage(Text.translatable("cheeseguard.minigame.thimble_sudden_death", remaining).formatted(Formatting.RED), true);
            } else {
                int blocksLeft = countWaterBlocks(ts);
                jumper.sendMessage(Text.translatable("cheeseguard.minigame.thimble_jump", remaining, blocksLeft).formatted(Formatting.YELLOW), true);
            }
        }

        // Scoreboard is updated only on state changes (jumps, eliminations, turn changes)

        // Waiting players are free to move around the spectator area.
        // Only re-TP if they wander very far (>30 blocks) as a safety net.
        for (UUID uuid : ts.turnOrder) {
            if (ts.eliminated.contains(uuid)) continue;
            if (uuid.equals(ts.currentJumper)) continue;
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(uuid);
            if (pl != null) {
                // Make sure they're not frozen (cleanup from prep phase or previous turn)
                unfreezePlayer(pl);
                // Distance check: use fixed spectator coords as reference (skip if zone-based)
                if (!event.getTpSpectatorZoneIds().isEmpty()) continue; // zone mode: no distance enforcement
                if (!event.getTpSpectatorWorld().isBlank()) {
                    double dx = pl.getX() - event.getTpSpectatorX();
                    double dz = pl.getZ() - event.getTpSpectatorZ();
                    if (dx * dx + dz * dz > 900) { // > 30 blocks from spectator spot
                        tpPlayer(pl, event.getTpSpectatorWorld(), event.getTpSpectatorX(), event.getTpSpectatorY(), event.getTpSpectatorZ());
                    }
                }
            }
        }

        // Timeout check
        if (elapsed >= ts.turnTimeoutTicks) {
            if (jumper != null) {
                removeJumperProtection(jumper);
                jumper.setHealth(jumper.getMaxHealth());
                String name = jumper.getName().getString();
                int livesLeft = ts.lives.getOrDefault(ts.currentJumper, 1) - 1;
                ts.lives.put(ts.currentJumper, livesLeft);
                playSoundAll(event, "minecraft:block.anvil.land", 0.5f, 0.5f);
                if (livesLeft > 0) {
                    broadcastText(event, Text.translatable("cheeseguard.thimble.timeout_life", name, livesLeft).formatted(Formatting.YELLOW));
                } else {
                    int jumps = ts.successfulJumps.getOrDefault(ts.currentJumper, 0);
                    broadcastText(event, Text.translatable("cheeseguard.thimble.timeout_eliminated", name, jumps).formatted(Formatting.RED));
                    eliminateThimblePlayer(event, ts, ts.currentJumper);
                }
            }
            if (ts.suddenDeath) openRandomSuddenDeathHole(ts);
            if (!checkThimbleWinner(event, ts)) {
                advanceThimbleTurn(event, ts, false);
            }
        }
    }

    /**
     * High-frequency tick for Thimble (every 4 ticks) — landing detection.
     */
    public void tickHighFrequency(List<ServerEvent> events, int tickCounter) {
        for (var entry : activeGames.entrySet()) {
            MiniGameType type = entry.getValue().type;
            if (type != MiniGameType.THIMBLE && type != MiniGameType.RACE) continue;
            ServerEvent event = events.stream()
                .filter(e -> e.getId().equals(entry.getKey())).findFirst().orElse(null);
            if (event == null || event.getState() != ServerEvent.State.ACTIVE) continue;
            if (type == MiniGameType.THIMBLE) {
                ThimbleState ts = thimbleStates.get(event.getId());
                if (ts != null) tickThimbleHighFreq(event, ts);
            } else if (type == MiniGameType.RACE) {
                RaceState rs = raceStates.get(event.getId());
                if (rs != null && rs.raceActive) tickRaceHighFreq(event, rs);
            }
        }
    }

    private void tickThimbleHighFreq(ServerEvent event, ThimbleState ts) {
        if (!ts.gameStarted || ts.waitingForPrepPhase || ts.currentJumper == null) return;
        // Cooldown after turn advance: ignore landing detection for 1 second (20 ticks)
        if (server.getTicks() - ts.turnAdvancedTick < 20) return;
        ServerPlayerEntity jumper = server.getPlayerManager().getPlayer(ts.currentJumper);
        if (jumper == null) {
            // Player disconnected — eliminate and advance
            broadcastText(event, Text.translatable("cheeseguard.thimble.disconnected").formatted(Formatting.RED));
            eliminateThimblePlayer(event, ts, ts.currentJumper);
            if (!checkThimbleWinner(event, ts)) advanceThimbleTurn(event, ts, false);
            return;
        }

        double playerY = jumper.getY();

        // Phase 1: detect if jumper has left the platform (Y drops below platform - 2)
        if (!ts.jumperLeftPlatform) {
            if (playerY < ts.platformY - 2.0) {
                ts.jumperLeftPlatform = true;
            }
            return; // don't check landing until they've actually jumped
        }

        // Phase 2: detect landing in the pool area
        int px = (int) Math.floor(jumper.getX());
        int pz = (int) Math.floor(jumper.getZ());

        // Check if player is within pool X/Z bounds
        if (px < ts.poolMinX || px > ts.poolMaxX || pz < ts.poolMinZ || pz > ts.poolMaxZ) {
            // Outside pool horizontally — check if they've landed (on ground or fallen below pool)
            if (playerY <= ts.poolMaxY + 1 && (jumper.isOnGround() || playerY <= ts.poolMinY - 1)) {
                handleMissedPool(event, ts, jumper);
            }
            return;
        }

        // Check if player has reached pool Y level (on ground or in water)
        if (playerY > ts.poolMaxY + 1) return; // still falling above pool

        // Use MC physics for reliable landing detection
        boolean inWater = jumper.isTouchingWater();
        boolean onGround = jumper.isOnGround();

        if (!inWater && !onGround) return; // still falling

        // Track if jumper touched solid ground first (edge/border hit)
        // If they touched ground before water, it's a fail even if they slide into water
        if (onGround && !inWater) {
            ts.jumperTouchedGround = true;
        }

        // If touching water but already touched ground first → treat as solid landing (fail)
        boolean effectiveWaterLanding = inWater && !ts.jumperTouchedGround;

        handlePoolLanding(event, ts, jumper, effectiveWaterLanding);
    }

    /**
     * Handle a pool landing — player is within pool X/Z bounds and has either touched water or ground.
     * Uses MC physics booleans for reliable detection (no false positives from block edge clipping).
     */
    private void handlePoolLanding(ServerEvent event, ThimbleState ts, ServerPlayerEntity jumper, boolean landedInWater) {
        // Read configurable values
        int maxLives = getConfigInt(event, "maxLives", THIMBLE_MAX_LIVES);
        int pointsPerJump = getConfigInt(event, "pointsPerJump", 1);
        int thimbleBonusPoints = getConfigInt(event, "thimbleBonusPoints", 4);
        boolean suddenDeathEnabled = getConfigBool(event, "suddenDeathEnabled", true);
        int suddenDeathPoints = getConfigInt(event, "suddenDeathPoints", 2);

        removeJumperProtection(jumper);
        jumper.setHealth(jumper.getMaxHealth());

        if (landedInWater) {
            // ── LANDED IN WATER — success ──
            ServerWorld world = getWorldById(ts.worldId);
            if (world == null) return;
            BlockPos feetPos = jumper.getBlockPos();
            BlockState blockAtFeet = world.getBlockState(feetPos);
            boolean feetInWater = blockAtFeet.getBlock() == Blocks.WATER;

            if (ts.suddenDeath) {
                // SUDDEN DEATH — survived this turn
                ts.successfulJumps.merge(ts.currentJumper, 1, Integer::sum);
                CheeseGuardMod.getInstance().getEventManager().addScore(event, ts.currentJumper, suddenDeathPoints);
                String name = jumper.getName().getString();
                spawnWoolParticles(world, feetInWater ? feetPos : feetPos.down());
                broadcastText(event, Text.translatable("cheeseguard.thimble.sudden_death_survived", name, suddenDeathPoints).formatted(Formatting.GREEN));
                playSoundAll(event, "minecraft:entity.player.splash", 0.8f, 1f);
                openRandomSuddenDeathHole(ts);
                if (!checkThimbleWinner(event, ts)) advanceThimbleTurn(event, ts, false);
            } else {
                // NORMAL MODE — scan down to deepest water block in column
                BlockPos replacePos = feetInWater ? feetPos : feetPos.down();
                while (world.getBlockState(replacePos.down()).getBlock() == Blocks.WATER && replacePos.getY() > ts.poolMinY) {
                    replacePos = replacePos.down();
                }

                boolean thimbleBonus = isThimbleBonus(world, replacePos);

                // Reveal the original pixel art block from the template
                BlockState templateBlock = ts.pixelArtTemplate.get(replacePos.asLong());
                if (templateBlock != null) {
                    world.setBlockState(replacePos, templateBlock, Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                } else {
                    // Fallback: position not in template (edge case) — place stone as visual indicator
                    world.setBlockState(replacePos, Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                }

                int points = thimbleBonus ? thimbleBonusPoints : pointsPerJump;
                CheeseGuardMod.getInstance().getEventManager().addScore(event, ts.currentJumper, points);
                ts.successfulJumps.merge(ts.currentJumper, 1, Integer::sum);

                String name = jumper.getName().getString();
                spawnWoolParticles(world, replacePos);
                if (thimbleBonus) {
                    int curLives = ts.lives.getOrDefault(ts.currentJumper, 1);
                    if (curLives < maxLives) {
                        int newLives = ts.lives.merge(ts.currentJumper, 1, Integer::sum);
                        broadcastText(event, Text.translatable("cheeseguard.thimble.bonus_life", name, newLives, maxLives, points).formatted(Formatting.GOLD));
                    } else {
                        broadcastText(event, Text.translatable("cheeseguard.thimble.bonus_max", name, maxLives, maxLives, points).formatted(Formatting.GOLD));
                    }
                    playSoundAll(event, "minecraft:ui.toast.challenge_complete", 1f, 1.2f);
                    spawnFirework(world, replacePos);
                } else {
                    int curLives = ts.lives.getOrDefault(ts.currentJumper, 1);
                    broadcastText(event, Text.translatable("cheeseguard.thimble.landed_water", name, points, curLives, maxLives).formatted(Formatting.GREEN));
                    playSoundAll(event, "minecraft:entity.player.splash", 0.8f, 1f);
                }

                if (!checkThimbleWinner(event, ts)) {
                    advanceThimbleTurn(event, ts, false);
                }
            }
        } else {
            // ── LANDED ON SOLID — not in water ──
            handleSolidLanding(event, ts, jumper);
        }
    }

    /** Handle player missing the pool entirely (landed outside pool bounds). */
    private void handleMissedPool(ServerEvent event, ThimbleState ts, ServerPlayerEntity jumper) {
        String name = jumper.getName().getString();
        removeJumperProtection(jumper);
        jumper.setHealth(jumper.getMaxHealth()); // heal — the life system handles punishment
        int livesLeft = ts.lives.getOrDefault(ts.currentJumper, 1) - 1;
        ts.lives.put(ts.currentJumper, livesLeft);
        spawnFailEffects(jumper);
        if (livesLeft > 0) {
            broadcastText(event, Text.translatable("cheeseguard.thimble.missed_pool_life", name, livesLeft).formatted(Formatting.YELLOW));
            if (ts.suddenDeath) openRandomSuddenDeathHole(ts);
            if (!checkThimbleWinner(event, ts)) advanceThimbleTurn(event, ts, false);
        } else {
            int jumps = ts.successfulJumps.getOrDefault(ts.currentJumper, 0);
            broadcastText(event, Text.translatable("cheeseguard.thimble.missed_pool_eliminated", name, jumps).formatted(Formatting.RED));
            playSoundAll(event, "minecraft:entity.lightning_bolt.thunder", 0.6f, 0.8f);
            eliminateThimblePlayer(event, ts, ts.currentJumper);
            if (ts.suddenDeath) openRandomSuddenDeathHole(ts);
            if (!checkThimbleWinner(event, ts)) advanceThimbleTurn(event, ts, false);
        }
    }

    /** Handle player landing on a solid block inside the pool area. */
    private void handleSolidLanding(ServerEvent event, ThimbleState ts, ServerPlayerEntity jumper) {
        String name = jumper.getName().getString();
        removeJumperProtection(jumper);
        jumper.setHealth(jumper.getMaxHealth());
        int livesLeft = ts.lives.getOrDefault(ts.currentJumper, 1) - 1;
        ts.lives.put(ts.currentJumper, livesLeft);
        spawnFailEffects(jumper);
        if (livesLeft > 0) {
            int jumps = ts.successfulJumps.getOrDefault(ts.currentJumper, 0);
            broadcastText(event, Text.translatable("cheeseguard.thimble.solid_life", name, livesLeft, jumps).formatted(Formatting.YELLOW));
            if (ts.suddenDeath) openRandomSuddenDeathHole(ts);
            if (!checkThimbleWinner(event, ts)) advanceThimbleTurn(event, ts, false);
        } else {
            int jumps = ts.successfulJumps.getOrDefault(ts.currentJumper, 0);
            broadcastText(event, Text.translatable("cheeseguard.thimble.solid_eliminated", name, jumps).formatted(Formatting.RED));
            playSoundAll(event, "minecraft:entity.lightning_bolt.thunder", 0.6f, 0.8f);
            eliminateThimblePlayer(event, ts, ts.currentJumper);
            if (ts.suddenDeath) openRandomSuddenDeathHole(ts);
            if (!checkThimbleWinner(event, ts)) advanceThimbleTurn(event, ts, false);
        }
    }

    /**
     * Check if all 4 horizontal neighbors of the landing position are solid (not water/air).
     * This is the "dé à coudre" (thimble) bonus condition.
     */
    private boolean isThimbleBonus(ServerWorld world, BlockPos pos) {
        BlockPos[] neighbors = {pos.north(), pos.south(), pos.east(), pos.west()};
        for (BlockPos n : neighbors) {
            BlockState ns = world.getBlockState(n);
            if (ns.isAir() || ns.getBlock() == Blocks.WATER) return false;
        }
        return true;
    }

    private void advanceThimbleTurn(ServerEvent event, ThimbleState ts, boolean firstTurn) {
        // Send previous jumper to spectator spot (if different from next)
        UUID previousJumper = ts.currentJumper;
        if (!firstTurn) {
            ts.currentTurnIndex++;
        }

        // Find next alive player
        int attempts = 0;
        while (attempts < ts.turnOrder.size()) {
            int idx = ts.currentTurnIndex % ts.turnOrder.size();
            UUID candidate = ts.turnOrder.get(idx);
            if (!ts.eliminated.contains(candidate)) {
                // Check if we wrapped around (new round)
                if (!firstTurn && idx == 0 && attempts > 0) {
                    ts.roundNumber++;
                    broadcastText(event, Text.translatable("cheeseguard.thimble.round", ts.roundNumber).formatted(Formatting.AQUA));
                }

                // TP previous jumper to spectator spot (if different from new jumper)
                if (previousJumper != null && !previousJumper.equals(candidate) && !ts.eliminated.contains(previousJumper)) {
                    ServerPlayerEntity prev = server.getPlayerManager().getPlayer(previousJumper);
                    if (prev != null) {
                        tpToZoneOrCoords(prev, event, true);
                        unfreezePlayer(prev);
                    }
                }

                ts.currentJumper = candidate;
                ts.jumperLeftPlatform = false;
                ts.jumperTouchedGround = false;
                ts.turnStartTick = server.getTicks();
                ts.turnAdvancedTick = server.getTicks(); // cooldown for landing detection

                ServerPlayerEntity pl = server.getPlayerManager().getPlayer(candidate);
                if (pl != null) {
                    // TP to platform — use sudden death TP if configured and in sudden death
                    if (ts.suddenDeath && ts.hasSuddenDeathTp) {
                        if (!ts.suddenDeathZoneIds.isEmpty()) {
                            EventManager emRef = CheeseGuardMod.getInstance().getEventManager();
                            EventManager.TpTarget sdTp = emRef.resolveRandomZonePosition(ts.suddenDeathZoneIds);
                            if (sdTp != null) {
                                tpPlayer(pl, sdTp.worldId(), sdTp.x(), sdTp.y(), sdTp.z());
                            } else {
                                tpPlayer(pl, ts.platformWorld, ts.platformX, ts.platformY, ts.platformZ);
                            }
                        } else {
                            tpPlayer(pl, ts.suddenDeathWorld, ts.suddenDeathX, ts.suddenDeathY, ts.suddenDeathZ);
                        }
                    } else {
                        tpPlayer(pl, ts.platformWorld, ts.platformX, ts.platformY, ts.platformZ);
                    }
                    unfreezePlayer(pl);
                    // Full heal + Resistance 255 (invisible) to prevent physical death from fall
                    pl.setHealth(pl.getMaxHealth());
                    giveJumperProtection(pl);

                    EventParticipant ep = event.getParticipants().get(candidate);
                    String name = ep != null ? ep.getPlayerName() : pl.getName().getString();
                    int score = ep != null ? ep.getScore() : 0;
                    int playerLives = ts.lives.getOrDefault(candidate, 1);
                    String livesStr = " ❤x" + playerLives;

                    if (ts.suddenDeath) {
                        broadcastText(event, Text.translatable("cheeseguard.thimble.turn_sudden_death", name, score, livesStr).formatted(Formatting.RED));
                        playSound(pl, "minecraft:block.note_block.bell", 0.5f, 0.8f);
                        // Spawn particles at the hole to help locate it
                        if (ts.suddenDeathHolePos != null) {
                            ServerWorld world = getWorldById(ts.worldId);
                            if (world != null) {
                                double hx = ts.suddenDeathHolePos.getX() + 0.5;
                                double hy = ts.suddenDeathHolePos.getY() + 1.0;
                                double hz = ts.suddenDeathHolePos.getZ() + 0.5;
                                world.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD, hx, hy + 2.0, hz, 15, 0.1, 0.8, 0.1, 0.03);
                            }
                        }
                    } else {
                        int waterLeft = countWaterBlocks(ts);
                        broadcastText(event, Text.translatable("cheeseguard.thimble.turn_normal", name, score, livesStr, waterLeft).formatted(Formatting.YELLOW));
                        playSound(pl, "minecraft:block.note_block.bell", 0.5f, 1.2f);
                    }
                } else {
                    // Player offline — skip
                    eliminateThimblePlayer(event, ts, candidate);
                    ts.currentTurnIndex++;
                    attempts++;
                    continue;
                }
                updateThimbleScoreboard(event, ts);
                return;
            }
            ts.currentTurnIndex++;
            attempts++;
        }

        // No players left — should have been caught by checkThimbleWinner
        CheeseGuardMod.getInstance().getEventManager().endEvent(event);
    }

    private void eliminateThimblePlayer(ServerEvent event, ThimbleState ts, UUID uuid) {
        ts.eliminated.add(uuid);
        // Also mark in MiniGameState
        MiniGameState mgs = activeGames.get(event.getId());
        if (mgs != null) mgs.eliminated.add(uuid);

        ServerPlayerEntity pl = server.getPlayerManager().getPlayer(uuid);
        if (pl != null) {
            pl.setHealth(pl.getMaxHealth());
            pl.getHungerManager().setFoodLevel(20);
            // TP to spectator area and apply ghost spectator mode (Rotom + fly)
            tpToZoneOrCoords(pl, event, true);
            unfreezePlayer(pl);
            EventManager em = CheeseGuardMod.getInstance().getEventManager();
            em.becomeEventSpectator(event, pl);
            pl.sendMessage(Text.translatable("cheeseguard.minigame.eliminated")
                .formatted(Formatting.RED), false);
        } else {
            // Offline player — just set role
            EventParticipant p = event.getParticipants().get(uuid);
            if (p != null) p.setRole(EventParticipant.Role.SPECTATOR);
        }
    }

    /**
     * Check if only 1 player remains — declare winner and end event.
     * Also triggers sudden death when pool is full and >1 player alive.
     * @return true if game is over
     */
    private boolean checkThimbleWinner(ServerEvent event, ThimbleState ts) {
        List<UUID> alive = ts.turnOrder.stream()
            .filter(u -> !ts.eliminated.contains(u))
            .toList();

        // Solo mode: if only 1 player total, never end — let them practice
        if (ts.turnOrder.size() <= 1) return false;

        if (alive.size() <= 1) {
            if (!alive.isEmpty()) {
                UUID winner = alive.getFirst();
                int jumps = ts.successfulJumps.getOrDefault(winner, 0);
                CheeseGuardMod.getInstance().getEventManager().addScore(event, winner, 100);
                ServerPlayerEntity pl = server.getPlayerManager().getPlayer(winner);
                String name = pl != null ? pl.getName().getString() : "?";
                broadcastText(event, Text.translatable("cheeseguard.thimble.victory", name, jumps, ts.roundNumber).formatted(Formatting.GOLD));
                playSoundAll(event, "minecraft:ui.toast.challenge_complete", 1f, 1f);
                // Spawn victory fireworks
                ServerWorld world = getWorldById(ts.worldId);
                if (world != null) {
                    int cx = (ts.poolMinX + ts.poolMaxX) / 2;
                    int cz = (ts.poolMinZ + ts.poolMaxZ) / 2;
                    for (int i = 0; i < 3; i++) {
                        spawnFirework(world, new BlockPos(cx + (i - 1) * 2, ts.poolMaxY + 2, cz));
                    }
                }
            }
            CheeseGuardMod.getInstance().getEventManager().endEvent(event);
            return true;
        }

        // Check if pool is full (no water left) → trigger sudden death (if enabled)
        if (!ts.suddenDeath && countWaterBlocks(ts) == 0 && alive.size() > 1) {
            boolean suddenDeathEnabled = getConfigBool(event, "suddenDeathEnabled", true);
            if (suddenDeathEnabled) {
                enterSuddenDeath(event, ts);
            } else {
                // Sudden death disabled — end event immediately, highest score wins
                broadcastText(event, Text.translatable("cheeseguard.thimble.pool_full_end").formatted(Formatting.GOLD));
                CheeseGuardMod.getInstance().getEventManager().endEvent(event);
                return true;
            }
        }

        return false;
    }

    // ── Sudden Death ──────────────────────────────────────────────────────

    /**
     * Enter sudden death mode: pool is full, >1 player alive.
     * Open one random surface hole for players to aim at.
     */
    private void enterSuddenDeath(ServerEvent event, ThimbleState ts) {
        ts.suddenDeath = true;
        broadcastText(event, Text.translatable("cheeseguard.thimble.sudden_death_start").formatted(Formatting.RED));
        broadcastText(event, Text.translatable("cheeseguard.thimble.sudden_death_rules").formatted(Formatting.RED));
        playSoundAll(event, "minecraft:entity.wither.spawn", 0.6f, 1.2f);

        // Reset all alive players to 1 life for sudden death
        for (UUID uuid : ts.turnOrder) {
            if (!ts.eliminated.contains(uuid)) {
                ts.lives.put(uuid, 1);
            }
        }

        // Swap platforms: hide normal, show sudden death
        if (ts.platformSnapshot != null) {
            ts.platformSnapshot.clear(server);
            CheeseGuardMod.LOGGER.info("[CG] Thimble: normal platform hidden for sudden death");
        }
        if (ts.sdPlatformSnapshot != null) {
            ts.sdPlatformSnapshot.restore(server);
            CheeseGuardMod.LOGGER.info("[CG] Thimble: SD platform revealed for sudden death");
        }

        // Open the first hole
        openRandomSuddenDeathHole(ts);
    }

    /**
     * Open a random 1-block hole on the surface of the pool.
     * Closes the previous hole if any.
     */
    private void openRandomSuddenDeathHole(ThimbleState ts) {
        ServerWorld world = getWorldById(ts.worldId);
        if (world == null) return;

        // Close previous hole (restore original block)
        if (ts.suddenDeathHolePos != null && ts.suddenDeathOriginalBlock != null) {
            world.setBlockState(ts.suddenDeathHolePos, ts.suddenDeathOriginalBlock,
                Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
        }

        // Collect all revealed (solid) template blocks at the top Y level of the pool
        List<BlockPos> surfaceCandidates = new ArrayList<>();
        int surfaceY = ts.poolMaxY; // top layer of the pool
        for (long posLong : ts.pixelArtTemplate.keySet()) {
            BlockPos bp = BlockPos.fromLong(posLong);
            if (bp.getY() != surfaceY) continue;
            BlockState bs = world.getBlockState(bp);
            // Only pick positions that have been revealed (solid, not water/air)
            if (!bs.isAir() && bs.getBlock() != Blocks.WATER) {
                surfaceCandidates.add(bp);
            }
        }

        if (surfaceCandidates.isEmpty()) {
            // Fallback: shouldn't happen, but if pool surface has no solid blocks
            CheeseGuardMod.LOGGER.warn("[CG] Thimble sudden death: no surface candidates found!");
            return;
        }

        // Pick a random surface block
        BlockPos holePos = surfaceCandidates.get(new Random().nextInt(surfaceCandidates.size()));
        ts.suddenDeathOriginalBlock = world.getBlockState(holePos);
        ts.suddenDeathHolePos = holePos;

        // Remove the block to create a water hole (set to water)
        world.setBlockState(holePos, Blocks.WATER.getDefaultState(),
            Block.NOTIFY_LISTENERS | Block.FORCE_STATE);

        // Spawn particles at the hole to make it visible
        double hx = holePos.getX() + 0.5, hy = holePos.getY() + 1.0, hz = holePos.getZ() + 0.5;
        world.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD, hx, hy + 1.5, hz, 20, 0.2, 1.0, 0.2, 0.05);
        world.spawnParticles(net.minecraft.particle.ParticleTypes.SOUL_FIRE_FLAME, hx, hy, hz, 8, 0.3, 0.1, 0.3, 0.01);
    }

    /**
     * Update the Thimble scoreboard display names with lives (❤) indicators.
     * Uses "scoreboard players display name" (1.20.3+) to show hearts next to player names.
     * Called only on state changes (jump, elimination, turn change), not every tick.
     */
    private void updateThimbleScoreboard(ServerEvent event, ThimbleState ts) {
        String objName = "cg_" + event.getId();
        // Use silent source to avoid command feedback in chat
        var src = server.getCommandSource().withSilent();
        var cmd = server.getCommandManager();

        for (UUID uuid : ts.turnOrder) {
            EventParticipant ep = event.getParticipants().get(uuid);
            if (ep == null) continue;
            String name = ep.getPlayerName();
            boolean isElim = ts.eliminated.contains(uuid);
            boolean isCurrent = uuid.equals(ts.currentJumper);
            int playerLives = ts.lives.getOrDefault(uuid, 0);

            // Build JSON text component for the display name
            String json;
            if (isElim) {
                json = "[{\"text\":\"" + name + " ☠\",\"color\":\"gray\",\"strikethrough\":true}]";
            } else {
                String hearts = "❤".repeat(Math.min(playerLives, 5));
                if (playerLives > 5) hearts += "x" + playerLives;
                String arrow = isCurrent ? "▶ " : "";
                String nameColor = isCurrent ? "yellow" : "white";
                json = "[{\"text\":\"" + arrow + name + " \",\"color\":\"" + nameColor + "\"},{\"text\":\"" + hearts + "\",\"color\":\"red\"}]";
            }

            // Set the display name for this player's score entry
            cmd.executeWithPrefix(src, "scoreboard players display name " + name + " " + objName + " " + json);
        }
    }

    private void cleanupThimble(ServerEvent event, ThimbleState ts) {
        // Close sudden death hole if open
        if (ts.suddenDeath && ts.suddenDeathHolePos != null && ts.suddenDeathOriginalBlock != null) {
            ServerWorld world = getWorldById(ts.worldId);
            if (world != null) {
                world.setBlockState(ts.suddenDeathHolePos, ts.suddenDeathOriginalBlock,
                    Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            }
        }
        // Platform zone restore is deferred — done AFTER pool snapshot restore in cleanupMiniGame()
        // to avoid being overwritten if the pool zone overlaps the platform zones.
        // Remove all effects and heal all remaining players
        for (UUID uuid : ts.turnOrder) {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(uuid);
            if (pl != null) {
                unfreezePlayer(pl);
                removeJumperProtection(pl);
                pl.setHealth(pl.getMaxHealth());
            }
        }
    }

    // ── Race (Pokemon Race) ────────────────────────────────────────────────

    private void initRace(ServerEvent event, MiniGameState state) {
        RaceState rs = new RaceState();
        List<String> transformCommands = new ArrayList<>();
        String transformMode = "command";
        int countdownSec = 5;
        int totalCheckpoints;

        // ═══ Try track config mode first ═══
        RaceTrackConfig trackCfg = RaceTrackConfig.load(event.getId());
        if (trackCfg != null && !trackCfg.getCheckpoints().isEmpty()) {
            rs.trackConfig = trackCfg;
            rs.trackCheckpoints = new ArrayList<>(trackCfg.getCheckpoints());
            // Sort by explicit order if any checkpoint has been ordered
            if (trackCfg.isOrderedCheckpoints()) {
                boolean hasExplicitOrder = rs.trackCheckpoints.stream().anyMatch(cp -> cp.getOrder() >= 0);
                if (hasExplicitOrder) {
                    rs.trackCheckpoints.sort(Comparator.comparingInt(RaceTrackConfig.MarkerRegion::getOrder));
                }
            }
            rs.trackPowerups = new ArrayList<>(trackCfg.getPowerups());
            rs.trackStartLine = trackCfg.getStartLine();

            rs.totalLaps = trackCfg.getLaps();
            rs.margin = trackCfg.getCheckpointMargin();
            rs.orderedCheckpoints = trackCfg.isOrderedCheckpoints();
            rs.teleportOnOutOfBounds = trackCfg.isTeleportOnOutOfBounds();
            rs.hideValidatedCheckpoints = trackCfg.isHideValidatedCheckpoints();
            rs.freezeDuringCountdown = trackCfg.isFreezeDuringCountdown();
            rs.endTimerDurationSeconds = trackCfg.getEndTimerSeconds();
            rs.powerupTypeConfigs = new LinkedHashMap<>(trackCfg.getPowerupTypes());
            rs.nextCheckpointParticle = resolveParticle(trackCfg.getNextCheckpointParticle());
            rs.validatedCheckpointParticle = resolveParticle(trackCfg.getValidatedCheckpointParticle());
            rs.movementMode = trackCfg.getMovementMode() != null ? trackCfg.getMovementMode() : "any";
            rs.globalBoundsMin = trackCfg.getZoneBoundsMin();
            rs.globalBoundsMax = trackCfg.getZoneBoundsMax();
            rs.raceWorld = trackCfg.getZoneWorld();

            totalCheckpoints = rs.trackCheckpoints.size();
            transformCommands = new ArrayList<>(trackCfg.getTransformCommands());
            transformMode = trackCfg.getTransformMode();
            countdownSec = trackCfg.getCountdownSeconds();

            // Replace marker blocks in the world
            replaceMarkerBlocks(rs);

        } else {
            // ═══ Legacy zone-based mode ═══
            JsonArray cpArr = getConfigJsonArray(event, "checkpointZoneIds");
            for (int i = 0; i < cpArr.size(); i++) {
                rs.checkpointZoneIds.add(cpArr.get(i).getAsString());
            }
            totalCheckpoints = rs.checkpointZoneIds.size();

            // Pre-cache zone centers for particles
            var regionStorage = CheeseGuardMod.getInstance().getRegionManager().getStorage();
            for (String zoneId : new HashSet<>(rs.checkpointZoneIds)) {
                Region zone = regionStorage.getRegion(zoneId);
                if (zone == null) {
                    CheeseGuardMod.LOGGER.warn("[CG] Race: checkpoint zone '{}' not found", zoneId);
                    continue;
                }
                double cx = (zone.getMinX() + zone.getMaxX()) / 2.0 + 0.5;
                double cy = zone.getMaxY() + 1.5;
                double cz = (zone.getMinZ() + zone.getMaxZ()) / 2.0 + 0.5;
                rs.zoneCenters.put(zoneId, new double[]{cx, cy, cz});
                rs.zoneBounds.put(zoneId, new int[]{zone.getMinX(), zone.getMinY(), zone.getMinZ(),
                                                      zone.getMaxX(), zone.getMaxY(), zone.getMaxZ()});
                rs.zoneWorlds.put(zoneId, zone.getWorld());
            }

            // Parse power-up zones
            JsonArray puArr = getConfigJsonArray(event, "powerUpZones");
            for (int i = 0; i < puArr.size(); i++) {
                JsonObject puObj = puArr.get(i).getAsJsonObject();
                PowerUpDef pu = new PowerUpDef();
                pu.zoneId = puObj.has("zoneId") ? puObj.get("zoneId").getAsString() : "";
                pu.effectType = puObj.has("effectType") ? puObj.get("effectType").getAsString() : "speed";
                pu.amplifier = puObj.has("amplifier") ? puObj.get("amplifier").getAsInt() : 0;
                pu.durationTicks = puObj.has("durationTicks") ? puObj.get("durationTicks").getAsInt() : 100;
                pu.cooldownSeconds = puObj.has("cooldownSeconds") ? puObj.get("cooldownSeconds").getAsInt() : 10;
                pu.sound = puObj.has("sound") ? puObj.get("sound").getAsString() : "block.beacon.activate";
                pu.particleType = puObj.has("particleType") ? puObj.get("particleType").getAsString() : "HAPPY_VILLAGER";
                if (!pu.zoneId.isEmpty()) {
                    rs.powerUps.add(pu);
                    if (!rs.zoneCenters.containsKey(pu.zoneId)) {
                        Region puZone = regionStorage.getRegion(pu.zoneId);
                        if (puZone != null) {
                            double cx = (puZone.getMinX() + puZone.getMaxX()) / 2.0 + 0.5;
                            double cy = puZone.getMaxY() + 1.5;
                            double cz = (puZone.getMinZ() + puZone.getMaxZ()) / 2.0 + 0.5;
                            rs.zoneCenters.put(pu.zoneId, new double[]{cx, cy, cz});
                            rs.zoneWorlds.put(pu.zoneId, puZone.getWorld());
                        }
                    }
                }
            }

            // Parse transforms from event miniGameConfig
            JsonArray cmdArr = getConfigJsonArray(event, "transformCommands");
            for (int i = 0; i < cmdArr.size(); i++) {
                String cmd = cmdArr.get(i).getAsString().trim();
                if (!cmd.isEmpty()) transformCommands.add(cmd);
            }
            transformMode = getConfigString(event, "transformMode", "command");
            countdownSec = getConfigInt(event, "countdownSeconds", 5);
            rs.freezeDuringCountdown = getConfigBool(event, "freezeDuringCountdown", true);
            rs.endTimerDurationSeconds = getConfigInt(event, "endTimerSeconds", 30);
        }

        // ═══ Abort if no checkpoints ═══
        if (totalCheckpoints == 0) {
            CheeseGuardMod.LOGGER.warn("[CG] Race '{}': no checkpoints configured!", event.getName());
            broadcastText(event, Text.translatable("cheeseguard.race.no_checkpoints").formatted(Formatting.RED));
            server.execute(() -> {
                EventManager emAbort = CheeseGuardMod.getInstance().getEventManager();
                if (emAbort != null) emAbort.endEvent(event);
            });
            return;
        }

        // ═══ Common: TP participants, assign transforms ═══
        EventManager em = CheeseGuardMod.getInstance().getEventManager();
        final String finalTransformMode = transformMode;
        int idx = 0;
        for (var p : event.getParticipants().values()) {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (pl == null) continue;
            // TP participants to start line center if available, otherwise use event TP zones
            if (p.getRole() == EventParticipant.Role.PARTICIPANT && rs.trackStartLine != null && rs.raceWorld != null) {
                double[] center = rs.trackStartLine.getCenter();
                int topY = rs.trackStartLine.getMax()[1] + 1;
                tpPlayer(pl, rs.raceWorld, center[0], topY, center[2]);
            } else {
                tpToZoneOrCoords(pl, event, p.getRole() == EventParticipant.Role.SPECTATOR);
            }
            if (p.getRole() == EventParticipant.Role.PARTICIPANT) {
                rs.playerNextCheckpoint.put(p.getPlayerUuid(), 0);
                if (rs.isTrackConfigMode()) {
                    rs.playerCurrentLap.put(p.getPlayerUuid(), 0);
                    if (!rs.orderedCheckpoints) {
                        rs.playerValidatedCps.put(p.getPlayerUuid(), new HashSet<>());
                    }
                }
                if (!transformCommands.isEmpty()) {
                    String cmd = transformCommands.get(idx % transformCommands.size());
                    rs.playerTransformCommands.put(p.getPlayerUuid(), cmd);
                    idx++;
                }
            }
        }

        // Apply transforms
        if (!transformCommands.isEmpty()) {
            for (var p : event.getParticipants().values()) {
                if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
                ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
                if (pl == null) continue;
                String cmd = rs.playerTransformCommands.get(p.getPlayerUuid());
                if (cmd == null) continue;
                if (finalTransformMode.equals("species")) {
                    CobbleShiftHook.transformPlayer(pl, cmd);
                } else {
                    String resolved = cmd.replace("{player}", pl.getName().getString());
                    String clean = resolved.startsWith("/") ? resolved.substring(1) : resolved;
                    try {
                        server.getCommandManager().getDispatcher()
                            .execute(clean, server.getCommandSource().withSilent());
                    } catch (Exception e) {
                        CheeseGuardMod.LOGGER.warn("[CG] Race transform command failed: '{}' — {}", clean, e.getMessage());
                    }
                }
            }
            rs.transformsApplied = true;
        }

        // Start countdown
        if (countdownSec > 0) {
            rs.countdownRemaining = countdownSec;
            for (var p : event.getParticipants().values()) {
                if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
                ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
                if (pl != null) {
                    if (rs.freezeDuringCountdown) freezePlayer(pl);
                    // Save position for containment enforcement during countdown
                    rs.frozenPositions.put(p.getPlayerUuid(), new double[]{pl.getX(), pl.getY(), pl.getZ()});
                }
            }
        } else {
            rs.raceActive = true;
            rs.raceStartMs = System.currentTimeMillis();
        }

        rs.lastTickMs = System.currentTimeMillis();
        raceStates.put(event.getId(), rs);
        int puCount = rs.isTrackConfigMode() ? rs.trackPowerups.size() : rs.powerUps.size();
        CheeseGuardMod.LOGGER.info("[CG] Race initialized: '{}' — {} checkpoints, {} transforms, {} power-ups, mode={}",
            event.getName(), totalCheckpoints, transformCommands.size(), puCount,
            rs.isTrackConfigMode() ? "trackConfig" : "legacy");
    }

    /** Replace marker blocks in the world when starting a race (track config mode). */
    private void replaceMarkerBlocks(RaceState rs) {
        if (rs.raceWorld == null) return;
        ServerWorld world = getWorldById(rs.raceWorld);
        if (world == null) return;

        // Checkpoints → configured replacement block
        Block cpRepl = resolveBlock(rs.trackConfig.getCheckpointReplacementBlock());
        BlockState cpReplState = cpRepl.getDefaultState();
        for (RaceTrackConfig.MarkerRegion cp : rs.trackCheckpoints) {
            if (cp.getBlocks() == null) continue;
            for (int[] pos : cp.getBlocks()) {
                BlockPos bp = new BlockPos(pos[0], pos[1], pos[2]);
                rs.savedBlocks.putIfAbsent(bp, world.getBlockState(bp));
                world.setBlockState(bp, cpReplState, Block.NOTIFY_LISTENERS);
            }
        }

        // Powerups → configured replacement block
        for (RaceTrackConfig.MarkerRegion pu : rs.trackPowerups) {
            if (pu.getBlocks() == null) continue;
            Block replacement = Blocks.AIR;
            RaceTrackConfig.PowerUpTypeConfig puCfg = rs.powerupTypeConfigs.get(pu.getMarkerType());
            if (puCfg != null && puCfg.getReplacementBlock() != null) {
                Identifier blockId = Identifier.tryParse(puCfg.getReplacementBlock());
                if (blockId != null) {
                    Block resolved = Registries.BLOCK.get(blockId);
                    if (resolved != null) replacement = resolved;
                }
            }
            BlockState replState = replacement.getDefaultState();
            for (int[] pos : pu.getBlocks()) {
                BlockPos bp = new BlockPos(pos[0], pos[1], pos[2]);
                rs.savedBlocks.putIfAbsent(bp, world.getBlockState(bp));
                world.setBlockState(bp, replState, Block.NOTIFY_LISTENERS);
            }
        }

        // Start line → configured replacement block
        Block slRepl = resolveBlock(rs.trackConfig.getStartLineReplacementBlock());
        BlockState slReplState = slRepl.getDefaultState();
        if (rs.trackStartLine != null && rs.trackStartLine.getBlocks() != null) {
            for (int[] pos : rs.trackStartLine.getBlocks()) {
                BlockPos bp = new BlockPos(pos[0], pos[1], pos[2]);
                rs.savedBlocks.putIfAbsent(bp, world.getBlockState(bp));
                world.setBlockState(bp, slReplState, Block.NOTIFY_LISTENERS);
            }
        }
    }

    /** Handle a player finishing the race (shared by legacy + track config modes). */
    private void handleRaceFinish(ServerEvent event, RaceState rs, EventParticipant p,
                                   ServerPlayerEntity pl, UUID uuid, long now,
                                   int finishBonus, int positionBonus, String finishSound) {
        EventManager em = CheeseGuardMod.getInstance().getEventManager();
        rs.finishTimes.put(uuid, now);
        rs.finishOrder.add(uuid);
        int position = rs.finishOrder.size();

        if (positionBonus > 0) {
            int bonus = position == 1 ? positionBonus : position == 2 ? positionBonus / 2 : position == 3 ? positionBonus / 4 : 0;
            if (bonus > 0) em.addScore(event, uuid, bonus);
        }
        if (finishBonus > 0) em.addScore(event, uuid, finishBonus);

        String medal = position == 1 ? "🥇" : position == 2 ? "🥈" : position == 3 ? "🥉" : "🏁";
        broadcastText(event, Text.translatable("cheeseguard.race.player_finished", medal, p.getPlayerName(), "#" + position).formatted(Formatting.GREEN));
        if (position == 1) {
            playSoundAll(event, finishSound, 1f, 1f);
        }

        ServerWorld pWorld = pl.getServerWorld();
        for (int fw = 0; fw < 3; fw++) {
            spawnFirework(pWorld, pl.getBlockPos().add(fw - 1, 0, 0));
        }

        if (position == 1 && !rs.endTimerActive) {
            rs.endTimerActive = true;
            rs.endTimerStartMs = now;
            broadcastText(event, Text.translatable("cheeseguard.race.end_timer", rs.endTimerDurationSeconds).formatted(Formatting.GOLD));
        }
    }

    /**
     * Every-tick race checkpoint detection with trajectory interpolation.
     * Runs every tick (20Hz) to reliably detect fast-moving players crossing checkpoints.
     */
    public void tickRaceCheckpoints(List<ServerEvent> events) {
        for (var entry : activeGames.entrySet()) {
            if (entry.getValue().type != MiniGameType.RACE) continue;
            ServerEvent event = events.stream()
                .filter(e -> e.getId().equals(entry.getKey())).findFirst().orElse(null);
            if (event == null || event.getState() != ServerEvent.State.ACTIVE) continue;
            RaceState rs = raceStates.get(event.getId());
            if (rs == null || !rs.raceActive) continue;
            tickRaceCheckpointsInner(event, rs);
        }
    }

    private void tickRaceCheckpointsInner(ServerEvent event, RaceState rs) {
        EventManager em = CheeseGuardMod.getInstance().getEventManager();
        MiniGameState state = activeGames.get(event.getId());
        int totalCheckpoints;
        int pointsPerCp, finishBonus, positionBonus;
        String cpSound, finishSound;

        if (rs.isTrackConfigMode()) {
            totalCheckpoints = rs.trackCheckpoints.size();
            pointsPerCp = rs.trackConfig.getPointsPerCheckpoint();
            finishBonus = rs.trackConfig.getFinishBonusPoints();
            positionBonus = rs.trackConfig.getPositionBonusPoints();
            cpSound = "block.note_block.bell";
            finishSound = "ui.toast.challenge_complete";
        } else {
            totalCheckpoints = rs.checkpointZoneIds.size();
            pointsPerCp = getConfigInt(event, "pointsPerCheckpoint", 10);
            finishBonus = getConfigInt(event, "finishBonusPoints", 100);
            positionBonus = getConfigInt(event, "positionBonusPoints", 50);
            cpSound = getConfigString(event, "checkpointSound", "block.note_block.bell");
            finishSound = getConfigString(event, "finishSound", "ui.toast.challenge_complete");
        }
        long now = System.currentTimeMillis();

        for (var p : event.getParticipants().values()) {
            if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
            UUID uuid = p.getPlayerUuid();
            if (rs.finishTimes.containsKey(uuid)) continue;
            if (state != null && state.eliminated.contains(uuid)) continue;
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(uuid);
            if (pl == null) continue;

            double cx = pl.getX(), cy = pl.getY(), cz = pl.getZ();

            if (rs.isTrackConfigMode()) {
                // ═══ Track config mode ═══

                // Out-of-bounds detection
                if (rs.teleportOnOutOfBounds && rs.globalBoundsMin != null && rs.globalBoundsMax != null) {
                    if (!isPointInGlobalBounds(rs, pl.getServerWorld(), cx, cy, cz)) {
                        double[] lastCp = rs.playerLastCpCenter.get(uuid);
                        if (lastCp != null) {
                            tpPlayer(pl, rs.raceWorld, lastCp[0], lastCp[1], lastCp[2]);
                        } else if (rs.trackStartLine != null && rs.trackStartLine.getCenter() != null) {
                            double[] sc = rs.trackStartLine.getCenter();
                            tpPlayer(pl, rs.raceWorld, sc[0], sc[1], sc[2]);
                        }
                        pl.sendMessage(Text.translatable("cheeseguard.race.out_of_bounds").formatted(Formatting.RED), false);
                        playSound(pl, "minecraft:entity.enderman.teleport", 0.8f, 0.8f);
                        rs.prevPositions.put(uuid, new double[]{pl.getX(), pl.getY(), pl.getZ()});
                        continue;
                    }
                }

                if (rs.orderedCheckpoints) {
                    // ── Ordered mode ──
                    Integer nextIdx = rs.playerNextCheckpoint.get(uuid);
                    if (nextIdx == null || nextIdx >= totalCheckpoints) { rs.prevPositions.put(uuid, new double[]{cx, cy, cz}); continue; }

                    RaceTrackConfig.MarkerRegion targetCp = rs.trackCheckpoints.get(nextIdx);
                    boolean inCp = isPlayerInMarkerWithTrajectory(pl, rs, uuid, targetCp);
                    rs.prevPositions.put(uuid, new double[]{cx, cy, cz});

                    if (inCp) {
                        rs.playerNextCheckpoint.put(uuid, nextIdx + 1);
                        rs.playerLastCpCenter.put(uuid, targetCp.getCenter());
                        if (pointsPerCp > 0) em.addScore(event, uuid, pointsPerCp);

                        if (nextIdx + 1 >= totalCheckpoints) {
                            // Lap complete
                            int currentLap = rs.playerCurrentLap.getOrDefault(uuid, 0) + 1;
                            rs.playerCurrentLap.put(uuid, currentLap);
                            if (currentLap < rs.totalLaps) {
                                rs.playerNextCheckpoint.put(uuid, 0);
                                pl.sendMessage(Text.translatable("cheeseguard.race.lap_complete", currentLap, rs.totalLaps).formatted(Formatting.GOLD), false);
                                playSound(pl, cpSound, 1f, 1.5f);
                            } else {
                                handleRaceFinish(event, rs, p, pl, uuid, now, finishBonus, positionBonus, finishSound);
                            }
                        } else {
                            pl.sendMessage(Text.translatable("cheeseguard.minigame.checkpoint", (nextIdx + 1), totalCheckpoints).formatted(Formatting.GREEN), false);
                            playSound(pl, cpSound, 1f, 1f);
                            pl.getServerWorld().spawnParticles(rs.isTrackConfigMode() ? rs.nextCheckpointParticle : ParticleTypes.HAPPY_VILLAGER, cx, cy + 1, cz, 10, 0.5, 0.5, 0.5, 0.01);
                        }
                    } else {
                        // Wrong-order feedback (only if player is actually inside another checkpoint)
                        for (int i = 0; i < totalCheckpoints; i++) {
                            if (i == nextIdx) continue;
                            if (isPlayerInMarkerRegion(pl, rs.trackCheckpoints.get(i), rs.margin)) {
                                pl.sendMessage(Text.translatable("cheeseguard.race.wrong_order", nextIdx + 1).formatted(Formatting.YELLOW), true);
                                break;
                            }
                        }
                    }
                } else {
                    // ── Unordered mode ──
                    Set<Integer> validated = rs.playerValidatedCps.get(uuid);
                    if (validated == null) { rs.prevPositions.put(uuid, new double[]{cx, cy, cz}); continue; }
                    rs.prevPositions.put(uuid, new double[]{cx, cy, cz});

                    for (int i = 0; i < totalCheckpoints; i++) {
                        if (validated.contains(i)) continue;
                        if (isPlayerInMarkerWithTrajectory(pl, rs, uuid, rs.trackCheckpoints.get(i))) {
                            validated.add(i);
                            rs.playerLastCpCenter.put(uuid, rs.trackCheckpoints.get(i).getCenter());
                            if (pointsPerCp > 0) em.addScore(event, uuid, pointsPerCp);

                            pl.sendMessage(Text.translatable("cheeseguard.minigame.checkpoint", validated.size(), totalCheckpoints).formatted(Formatting.GREEN), false);
                            playSound(pl, cpSound, 1f, 1f);
                            pl.getServerWorld().spawnParticles(rs.isTrackConfigMode() ? rs.nextCheckpointParticle : ParticleTypes.HAPPY_VILLAGER, cx, cy + 1, cz, 10, 0.5, 0.5, 0.5, 0.01);

                            if (validated.size() >= totalCheckpoints) {
                                int currentLap = rs.playerCurrentLap.getOrDefault(uuid, 0) + 1;
                                rs.playerCurrentLap.put(uuid, currentLap);
                                if (currentLap < rs.totalLaps) {
                                    validated.clear();
                                    rs.playerNextCheckpoint.put(uuid, 0);
                                    pl.sendMessage(Text.translatable("cheeseguard.race.lap_complete", currentLap, rs.totalLaps).formatted(Formatting.GOLD), false);
                                    playSound(pl, cpSound, 1f, 1.5f);
                                } else {
                                    handleRaceFinish(event, rs, p, pl, uuid, now, finishBonus, positionBonus, finishSound);
                                }
                            }
                            break; // one checkpoint per tick
                        }
                    }
                }

            } else {
                // ═══ Legacy zone-based mode ═══
                Integer nextIdx = rs.playerNextCheckpoint.get(uuid);
                if (nextIdx == null || nextIdx >= totalCheckpoints) continue;
                String targetZoneId = rs.checkpointZoneIds.get(nextIdx);
                boolean inZone = isPlayerInZoneWithTrajectory(pl, rs, uuid, targetZoneId, cx, cy, cz);
                rs.prevPositions.put(uuid, new double[]{cx, cy, cz});

                if (inZone) {
                    rs.playerNextCheckpoint.put(uuid, nextIdx + 1);
                    if (pointsPerCp > 0) em.addScore(event, uuid, pointsPerCp);
                    if (nextIdx + 1 >= totalCheckpoints) {
                        handleRaceFinish(event, rs, p, pl, uuid, now, finishBonus, positionBonus, finishSound);
                    } else {
                        pl.sendMessage(Text.translatable("cheeseguard.minigame.checkpoint", (nextIdx + 1), totalCheckpoints).formatted(Formatting.GREEN), false);
                        playSound(pl, cpSound, 1f, 1f);
                        pl.getServerWorld().spawnParticles(ParticleTypes.HAPPY_VILLAGER, cx, cy + 1, cz, 10, 0.5, 0.5, 0.5, 0.01);
                    }
                }
            }
        }
    }

    /**
     * High-frequency race tick (every 4 ticks = 5x/sec).
     * Handles power-up detection.
     */
    private void tickRaceHighFreq(ServerEvent event, RaceState rs) {
        // Power-up detection (still at 4-tick frequency — fine for power-ups)
        tickPowerUps(event, rs);
    }

    /**
     * Low-frequency race tick (1Hz). Handles countdown, actionbar, particles, end conditions.
     */
    private void tickRace(ServerEvent event, RaceState rs) {
        long now = System.currentTimeMillis();
        if (now - rs.lastTickMs < 900) return; // ~1Hz
        rs.lastTickMs = now;

        EventManager em = CheeseGuardMod.getInstance().getEventManager();
        MiniGameState state = activeGames.get(event.getId());

        // ── Phase: Countdown ──
        if (rs.countdownRemaining > 0) {
            for (var p : event.getParticipants().values()) {
                if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
                ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
                if (pl == null) continue;
                if (rs.freezeDuringCountdown) freezePlayer(pl);
                // Position enforcement: teleport back if moved > 2 blocks from frozen position
                double[] frozen = rs.frozenPositions.get(p.getPlayerUuid());
                if (frozen != null) {
                    double dx = pl.getX() - frozen[0];
                    double dy = pl.getY() - frozen[1];
                    double dz = pl.getZ() - frozen[2];
                    if (dx * dx + dy * dy + dz * dz > 4.0) {
                        tpPlayer(pl, pl.getServerWorld().getRegistryKey().getValue().toString(),
                                 frozen[0], frozen[1], frozen[2]);
                    }
                }
            }
            Formatting color = rs.countdownRemaining <= 3 ? Formatting.RED : Formatting.YELLOW;
            for (var p : event.getParticipants().values()) {
                ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
                if (pl != null) {
                    sendTitle(pl, color + "" + rs.countdownRemaining, "", 0, 25, 5);
                    playSound(pl, "minecraft:block.note_block.hat", 1f, 1f);
                }
            }
            rs.countdownRemaining--;
            return;
        }

        // Countdown just reached 0 — GO!
        if (!rs.raceActive && rs.countdownRemaining == 0) {
            rs.countdownRemaining = -1;
            rs.raceActive = true;
            rs.raceStartMs = now;
            for (var p : event.getParticipants().values()) {
                if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
                ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
                if (pl != null) {
                    unfreezePlayer(pl);
                    sendTitle(pl, Formatting.GREEN + "" + Formatting.BOLD + "GO !", "", 0, 20, 10);
                    playSound(pl, "minecraft:entity.ender_dragon.growl", 0.6f, 1.2f);
                }
            }
            return;
        }

        if (!rs.raceActive) return;

        // ── Movement mode enforcement ──
        if (!"any".equals(rs.movementMode)) {
            for (var p : event.getParticipants().values()) {
                if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
                UUID uid = p.getPlayerUuid();
                if (rs.finishTimes.containsKey(uid)) continue;
                if (state != null && state.eliminated.contains(uid)) continue;
                ServerPlayerEntity mpl = server.getPlayerManager().getPlayer(uid);
                if (mpl == null) continue;
                if ("foot".equals(rs.movementMode) && mpl.hasVehicle()) {
                    mpl.stopRiding();
                    mpl.sendMessage(Text.translatable("cheeseguard.race.foot_only").formatted(Formatting.RED), true);
                }
            }
        }

        // ── Actionbar updates (1Hz is enough) ──
        int totalCheckpoints = rs.isTrackConfigMode() ? rs.trackCheckpoints.size() : rs.checkpointZoneIds.size();
        boolean showActionbar = getConfigBool(event, "showActionbar", true);
        for (var p : event.getParticipants().values()) {
            if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
            UUID uuid = p.getPlayerUuid();
            if (rs.finishTimes.containsKey(uuid)) continue;
            if (state != null && state.eliminated.contains(uuid)) continue;
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(uuid);
            if (pl == null) continue;

            int cpProgress;
            if (rs.isTrackConfigMode() && !rs.orderedCheckpoints) {
                Set<Integer> validated = rs.playerValidatedCps.get(uuid);
                cpProgress = validated != null ? validated.size() : 0;
            } else {
                Integer nextIdx = rs.playerNextCheckpoint.get(uuid);
                cpProgress = nextIdx != null ? nextIdx : 0;
            }

            if (showActionbar) {
                Text bar = Text.translatable("cheeseguard.minigame.checkpoint", Math.min(cpProgress + 1, totalCheckpoints), totalCheckpoints).formatted(Formatting.GOLD);
                if (rs.isTrackConfigMode() && rs.totalLaps > 1) {
                    int currentLap = rs.playerCurrentLap.getOrDefault(uuid, 0) + 1;
                    bar = bar.copy().append(Text.literal("  🔄 " + currentLap + "/" + rs.totalLaps).formatted(Formatting.AQUA));
                }
                if (rs.endTimerActive) {
                    int remaining = rs.endTimerDurationSeconds - (int) ((now - rs.endTimerStartMs) / 1000);
                    bar = bar.copy().append(Text.literal("  \u23F1 " + Math.max(0, remaining) + "s").formatted(Formatting.RED));
                }
                pl.sendMessage(bar, true);
            }
        }

        // Spawn checkpoint highlights (particles at next checkpoint per player)
        spawnCheckpointHighlights(event, rs);

        // ── End conditions ──
        int finishedCount = rs.finishOrder.size();
        int totalParticipants = 0;
        int eliminated = (state != null) ? state.eliminated.size() : 0;
        for (var p : event.getParticipants().values()) {
            if (p.getRole() == EventParticipant.Role.PARTICIPANT) totalParticipants++;
        }
        if (rs.endTimerActive && now - rs.endTimerStartMs >= rs.endTimerDurationSeconds * 1000L) {
            broadcastText(event, Text.translatable("cheeseguard.race.time_expired").formatted(Formatting.RED));
            em.endEvent(event);
            return;
        }
        if (finishedCount + eliminated >= totalParticipants && totalParticipants > 0) {
            broadcastText(event, Text.translatable("cheeseguard.race.all_finished").formatted(Formatting.GREEN));
            em.endEvent(event);
        }
    }

    private void tickPowerUps(ServerEvent event, RaceState rs) {
        if (rs.isTrackConfigMode()) {
            tickPowerUpsTrackConfig(event, rs);
            return;
        }
        // ═══ Legacy zone-based mode ═══
        if (rs.powerUps.isEmpty()) return;
        long now = System.currentTimeMillis();
        MiniGameState state = activeGames.get(event.getId());
        var regionManager = CheeseGuardMod.getInstance().getRegionManager();

        for (var p : event.getParticipants().values()) {
            if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
            UUID uuid = p.getPlayerUuid();
            if (rs.finishTimes.containsKey(uuid)) continue;
            if (state != null && state.eliminated.contains(uuid)) continue;
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(uuid);
            if (pl == null) continue;

            for (PowerUpDef pu : rs.powerUps) {
                boolean inPuZone = isPlayerInZone(pl, regionManager, pu.zoneId);
                if (!inPuZone) continue;
                String cdKey = uuid + "_" + pu.zoneId;
                Long lastUse = rs.playerPowerUpCooldowns.get(cdKey);
                if (lastUse != null && now - lastUse < pu.cooldownSeconds * 1000L) continue;
                Identifier effectId = resolveEffectId(pu.effectType);
                if (effectId != null) {
                    var effectEntry = Registries.STATUS_EFFECT.getEntry(effectId);
                    effectEntry.ifPresent(e -> pl.addStatusEffect(
                        new StatusEffectInstance(e, pu.durationTicks, pu.amplifier, false, true, true)));
                }
                playSound(pl, pu.sound, 1f, 1.2f);
                pl.getServerWorld().spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                    pl.getX(), pl.getY() + 1.5, pl.getZ(), 12, 0.5, 0.5, 0.5, 0.02);
                String effectLabel = pu.effectType.substring(0, 1).toUpperCase() + pu.effectType.substring(1);
                String ampLabel = pu.amplifier > 0 ? " " + (pu.amplifier + 1) : "";
                broadcastText(event, Text.translatable("cheeseguard.race.powerup_pickup", p.getPlayerName(), effectLabel + ampLabel).formatted(Formatting.AQUA));
                rs.playerPowerUpCooldowns.put(cdKey, now);
            }
        }
        for (PowerUpDef pu : rs.powerUps) {
            double[] center = rs.zoneCenters.get(pu.zoneId);
            String puWorld = rs.zoneWorlds.get(pu.zoneId);
            if (center == null || puWorld == null) continue;
            ServerWorld world = getWorldById(puWorld);
            if (world == null) continue;
            world.spawnParticles(ParticleTypes.END_ROD, center[0], center[1] - 0.5, center[2], 2, 1.0, 0.5, 1.0, 0.01);
        }
    }

    /** Track config mode power-up detection with typed configs. */
    private void tickPowerUpsTrackConfig(ServerEvent event, RaceState rs) {
        if (rs.trackPowerups.isEmpty()) return;
        long now = System.currentTimeMillis();
        MiniGameState state = activeGames.get(event.getId());

        for (var p : event.getParticipants().values()) {
            if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
            UUID uuid = p.getPlayerUuid();
            if (rs.finishTimes.containsKey(uuid)) continue;
            if (state != null && state.eliminated.contains(uuid)) continue;
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(uuid);
            if (pl == null) continue;

            for (int puIdx = 0; puIdx < rs.trackPowerups.size(); puIdx++) {
                if (rs.consumedPowerups.contains(puIdx)) continue;
                RaceTrackConfig.MarkerRegion puRegion = rs.trackPowerups.get(puIdx);
                if (!isPlayerInMarkerRegion(pl, puRegion, rs.margin)) continue;

                RaceTrackConfig.PowerUpTypeConfig puCfg = rs.powerupTypeConfigs.get(puRegion.getMarkerType());
                if (puCfg == null) continue;

                String cdKey = uuid + "_pu" + puIdx;
                Long lastUse = rs.playerPowerUpCooldowns.get(cdKey);
                if (lastUse != null && now - lastUse < puCfg.getCooldownSeconds() * 1000L) continue;

                // Apply effect
                Identifier effectId = Identifier.tryParse(puCfg.getEffectId());
                if (effectId != null) {
                    var effectEntry = Registries.STATUS_EFFECT.getEntry(effectId);
                    if (puCfg.isTargetSelf()) {
                        effectEntry.ifPresent(e -> pl.addStatusEffect(
                            new StatusEffectInstance(e, puCfg.getDurationTicks(), puCfg.getAmplifier(), false, true, true)));
                    } else {
                        // Target others in radius
                        int radius = puCfg.getTargetRadius();
                        for (var otherP : event.getParticipants().values()) {
                            if (otherP.getPlayerUuid().equals(uuid)) continue;
                            ServerPlayerEntity otherPl = server.getPlayerManager().getPlayer(otherP.getPlayerUuid());
                            if (otherPl == null || otherPl.squaredDistanceTo(pl) > radius * radius) continue;
                            effectEntry.ifPresent(e -> otherPl.addStatusEffect(
                                new StatusEffectInstance(e, puCfg.getDurationTicks(), puCfg.getAmplifier(), false, true, true)));
                        }
                    }
                }

                playSound(pl, puCfg.getSound(), 1f, 1.2f);
                pl.getServerWorld().spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                    pl.getX(), pl.getY() + 1.5, pl.getZ(), 12, 0.5, 0.5, 0.5, 0.02);

                // Broadcast with human-readable effect name
                String effectLabel = puCfg.getEffectId();
                if (effectLabel.contains(":")) effectLabel = effectLabel.substring(effectLabel.indexOf(':') + 1);
                effectLabel = effectLabel.substring(0, 1).toUpperCase() + effectLabel.substring(1).replace('_', ' ');
                String ampLabel = puCfg.getAmplifier() > 0 ? " " + (puCfg.getAmplifier() + 1) : "";
                broadcastText(event, Text.translatable("cheeseguard.race.powerup_pickup", p.getPlayerName(), effectLabel + ampLabel).formatted(Formatting.AQUA));

                rs.playerPowerUpCooldowns.put(cdKey, now);
                if (puCfg.isSingleUse()) rs.consumedPowerups.add(puIdx);
            }
        }

        // Ambient particles at non-consumed powerup positions
        if (rs.raceWorld == null) return;
        ServerWorld world = getWorldById(rs.raceWorld);
        if (world == null) return;
        for (int puIdx = 0; puIdx < rs.trackPowerups.size(); puIdx++) {
            if (rs.consumedPowerups.contains(puIdx)) continue;
            double[] center = rs.trackPowerups.get(puIdx).getCenter();
            if (center == null) continue;
            world.spawnParticles(ParticleTypes.END_ROD, center[0], center[1] + 0.5, center[2], 2, 1.0, 0.5, 1.0, 0.01);
        }
    }

    private void spawnCheckpointHighlights(ServerEvent event, RaceState rs) {
        if (rs.isTrackConfigMode()) {
            spawnCheckpointHighlightsTrackConfig(event, rs);
            return;
        }
        // ═══ Legacy zone-based mode ═══
        MiniGameState state = activeGames.get(event.getId());
        Set<String> renderedZones = new HashSet<>();

        for (var p : event.getParticipants().values()) {
            if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
            UUID uuid = p.getPlayerUuid();
            if (rs.finishTimes.containsKey(uuid)) continue;
            if (state != null && state.eliminated.contains(uuid)) continue;
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(uuid);
            if (pl == null) continue;

            Integer nextIdx = rs.playerNextCheckpoint.get(uuid);
            if (nextIdx == null || nextIdx >= rs.checkpointZoneIds.size()) continue;

            String targetZoneId = rs.checkpointZoneIds.get(nextIdx);
            double[] center = rs.zoneCenters.get(targetZoneId);
            int[] bounds = rs.zoneBounds.get(targetZoneId);
            String zoneWorld = rs.zoneWorlds.get(targetZoneId);
            if (center == null || bounds == null || zoneWorld == null) continue;

            ServerWorld world = getWorldById(zoneWorld);
            if (world == null || !world.equals(pl.getServerWorld())) continue;
            double dist = Math.sqrt(Math.pow(pl.getX() - center[0], 2) + Math.pow(pl.getZ() - center[2], 2));
            if (dist > 120) continue;

            boolean isFinishLine = (nextIdx + 1 >= rs.checkpointZoneIds.size());
            if (renderedZones.add(targetZoneId)) {
                renderCheckpointParticles(world, center, bounds, isFinishLine);
            }
        }
        // Legacy power-up particles
        for (PowerUpDef pu : rs.powerUps) {
            double[] puCenter = rs.zoneCenters.get(pu.zoneId);
            String puWorld = rs.zoneWorlds.get(pu.zoneId);
            if (puCenter == null || puWorld == null) continue;
            ServerWorld world = getWorldById(puWorld);
            if (world == null) continue;
            world.spawnParticles(ParticleTypes.DRAGON_BREATH, puCenter[0], puCenter[1] - 0.5, puCenter[2], 4, 0.6, 0.4, 0.6, 0.005);
            world.spawnParticles(ParticleTypes.WITCH, puCenter[0], puCenter[1], puCenter[2], 2, 0.4, 0.2, 0.4, 0.01);
        }
    }

    /** Track config mode checkpoint + powerup particle rendering. */
    private void spawnCheckpointHighlightsTrackConfig(ServerEvent event, RaceState rs) {
        if (rs.raceWorld == null) return;
        ServerWorld world = getWorldById(rs.raceWorld);
        if (world == null) return;
        MiniGameState state = activeGames.get(event.getId());

        // Collect which checkpoints to render as "next" or "validated"
        Set<Integer> renderNext = new HashSet<>();
        Set<Integer> renderValidated = new HashSet<>();

        for (var p : event.getParticipants().values()) {
            if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
            UUID uuid = p.getPlayerUuid();
            if (rs.finishTimes.containsKey(uuid)) continue;
            if (state != null && state.eliminated.contains(uuid)) continue;
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(uuid);
            if (pl == null || !pl.getServerWorld().equals(world)) continue;

            if (rs.orderedCheckpoints) {
                Integer nextIdx = rs.playerNextCheckpoint.get(uuid);
                if (nextIdx == null || nextIdx >= rs.trackCheckpoints.size()) continue;
                renderNext.add(nextIdx);
                for (int i = 0; i < nextIdx; i++) renderValidated.add(i);
            } else {
                Set<Integer> validated = rs.playerValidatedCps.get(uuid);
                if (validated == null) continue;
                for (int i = 0; i < rs.trackCheckpoints.size(); i++) {
                    if (validated.contains(i)) renderValidated.add(i);
                    else renderNext.add(i);
                }
            }
        }

        // Render "next" checkpoints with full beacon + outline
        for (int idx : renderNext) {
            RaceTrackConfig.MarkerRegion cp = rs.trackCheckpoints.get(idx);
            boolean isFinish = rs.orderedCheckpoints && (idx + 1 >= rs.trackCheckpoints.size());
            renderCheckpointParticles(world, cp.getCenter(),
                new int[]{cp.getMin()[0], cp.getMin()[1], cp.getMin()[2], cp.getMax()[0], cp.getMax()[1], cp.getMax()[2]},
                isFinish);
        }

        // Render validated checkpoints (dimmer)
        if (!rs.hideValidatedCheckpoints) {
            for (int idx : renderValidated) {
                if (renderNext.contains(idx)) continue;
                RaceTrackConfig.MarkerRegion cp = rs.trackCheckpoints.get(idx);
                double[] center = cp.getCenter();
                world.spawnParticles(rs.validatedCheckpointParticle, center[0], cp.getMax()[1] + 1.5, center[2], 3, 0.3, 0.3, 0.3, 0.005);
            }
        }

        // Powerup particles (non-consumed only)
        for (int puIdx = 0; puIdx < rs.trackPowerups.size(); puIdx++) {
            if (rs.consumedPowerups.contains(puIdx)) continue;
            double[] puCenter = rs.trackPowerups.get(puIdx).getCenter();
            if (puCenter == null) continue;
            world.spawnParticles(ParticleTypes.DRAGON_BREATH, puCenter[0], puCenter[1] + 0.5, puCenter[2], 4, 0.6, 0.4, 0.6, 0.005);
            world.spawnParticles(ParticleTypes.WITCH, puCenter[0], puCenter[1] + 1.0, puCenter[2], 2, 0.4, 0.2, 0.4, 0.01);
        }
    }

    /** Render checkpoint particles (beacon column, border outline, corner pillars). Shared by both modes. */
    private void renderCheckpointParticles(ServerWorld world, double[] center, int[] bounds, boolean isFinishLine) {
        int minX = bounds[0], minY = bounds[1], minZ = bounds[2];
        int maxX = bounds[3], maxY = bounds[4], maxZ = bounds[5];
        // Beacon column
        for (int y = 0; y < 8; y++) {
            world.spawnParticles(isFinishLine ? ParticleTypes.FLAME : ParticleTypes.END_ROD,
                center[0], maxY + 1.0 + y * 1.5, center[2], 2, 0.1, 0.2, 0.1, 0.001);
        }
        // Border outline
        double outlineY = minY + 0.2;
        for (double x = minX; x <= maxX + 1; x += 0.8) {
            world.spawnParticles(isFinishLine ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.ENCHANT, x, outlineY, minZ, 1, 0, 0, 0, 0);
            world.spawnParticles(isFinishLine ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.ENCHANT, x, outlineY, maxZ + 1, 1, 0, 0, 0, 0);
        }
        for (double z = minZ; z <= maxZ + 1; z += 0.8) {
            world.spawnParticles(isFinishLine ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.ENCHANT, minX, outlineY, z, 1, 0, 0, 0, 0);
            world.spawnParticles(isFinishLine ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.ENCHANT, maxX + 1, outlineY, z, 1, 0, 0, 0, 0);
        }
        // Corner pillars
        for (int y = 0; y < 3; y++) {
            double cy = minY + y * 1.0;
            world.spawnParticles(ParticleTypes.END_ROD, minX, cy, minZ, 1, 0, 0, 0, 0);
            world.spawnParticles(ParticleTypes.END_ROD, maxX + 1, cy, minZ, 1, 0, 0, 0, 0);
            world.spawnParticles(ParticleTypes.END_ROD, minX, cy, maxZ + 1, 1, 0, 0, 0, 0);
            world.spawnParticles(ParticleTypes.END_ROD, maxX + 1, cy, maxZ + 1, 1, 0, 0, 0, 0);
        }
        if (isFinishLine) {
            world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, center[0], maxY + 2.0, center[2], 8, 1.0, 0.5, 1.0, 0.02);
        }
    }

    private void cleanupRace(ServerEvent event, RaceState rs) {
        // Restore original marker blocks (wool etc.)
        if (!rs.savedBlocks.isEmpty() && rs.raceWorld != null) {
            ServerWorld world = getWorldById(rs.raceWorld);
            if (world != null) {
                for (var entry : rs.savedBlocks.entrySet()) {
                    world.setBlockState(entry.getKey(), entry.getValue(), Block.NOTIFY_LISTENERS);
                }
                CheeseGuardMod.LOGGER.info("[CG] Race '{}': restored {} marker blocks", event.getName(), rs.savedBlocks.size());
            }
        }

        // Revert transforms (online immediately, offline → pending revert on next join)
        boolean revert = rs.isTrackConfigMode()
            ? rs.trackConfig.isRevertTransformsOnEnd()
            : getConfigBool(event, "revertTransformsOnEnd", true);
        if (rs.transformsApplied && revert && CobbleShiftHook.isAvailable()) {
            for (var p : event.getParticipants().values()) {
                if (p.getRole() != EventParticipant.Role.PARTICIPANT) continue;
                ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
                if (pl != null) {
                    CobbleShiftHook.revertPlayer(pl);
                } else {
                    CobbleShiftHook.addPendingRevert(p.getPlayerUuid());
                }
            }
        }

        // Unfreeze all (safety)
        for (var p : event.getParticipants().values()) {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (pl != null) {
                unfreezePlayer(pl);
                EventLeaveHandler.removeLeaveItem(pl);
            }
        }

        // Broadcast final ranking
        if (!rs.finishOrder.isEmpty()) {
            Text header = Text.translatable("cheeseguard.race.final_ranking").formatted(Formatting.GOLD, Formatting.BOLD);
            int pos = 0;
            Text ranking = header;
            for (UUID uuid : rs.finishOrder) {
                pos++;
                if (pos > 5) break;
                var part = event.getParticipant(uuid);
                String name = part != null ? part.getPlayerName() : uuid.toString().substring(0, 8);
                String medal = pos == 1 ? "\uD83E\uDD47" : pos == 2 ? "\uD83E\uDD48" : pos == 3 ? "\uD83E\uDD49" : "  " + pos + ".";
                ranking = ranking.copy().append(Text.literal("\n ").formatted(Formatting.WHITE)).append(Text.literal(medal + " " + name).formatted(Formatting.WHITE));
            }
            Text finalRanking = ranking;
            for (var p : event.getParticipants().values()) {
                ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
                if (pl != null) pl.sendMessage(finalRanking, false);
            }
        }

        CheeseGuardMod.LOGGER.info("[CG] Race '{}': cleaned up — {} finishers", event.getName(), rs.finishOrder.size());
    }

    /** Resolve a power-up effect type string to its MC Identifier. */
    private static Identifier resolveEffectId(String effectType) {
        return switch (effectType.toLowerCase()) {
            case "speed" -> Identifier.of("minecraft", "speed");
            case "jump" -> Identifier.of("minecraft", "jump_boost");
            case "slow" -> Identifier.of("minecraft", "slowness");
            case "levitation" -> Identifier.of("minecraft", "levitation");
            case "resistance" -> Identifier.of("minecraft", "resistance");
            case "invisibility" -> Identifier.of("minecraft", "invisibility");
            case "blindness" -> Identifier.of("minecraft", "blindness");
            case "glowing" -> Identifier.of("minecraft", "glowing");
            default -> null;
        };
    }

    private void freezePlayer(ServerPlayerEntity p) {
        // Slowness 255 to prevent horizontal movement
        var slowOpt = Registries.STATUS_EFFECT.getEntry(ID_SLOWNESS);
        slowOpt.ifPresent(e -> p.addStatusEffect(new StatusEffectInstance(e, 40, 254, false, false, false)));
        // No Resistance — frozen players should remain vulnerable to attacks (sitting ducks)
        // No Jump Boost — high amplifiers (128) can launch players into the sky on jump.
        // Slowness 255 already prevents meaningful horizontal movement; a small vertical jump
        // during the 2-second freeze is harmless and far better than a sky-launch death.
        // Reset velocity to prevent jumping / flying — clamp Y to 0 or negative (gravity)
        p.setVelocity(0, Math.min(0, p.getVelocity().y), 0);
        p.velocityModified = true;
    }

    private void unfreezePlayer(ServerPlayerEntity p) {
        var slowOpt = Registries.STATUS_EFFECT.getEntry(ID_SLOWNESS);
        slowOpt.ifPresent(e -> p.removeStatusEffect(e));
        // Reset velocity to zero to prevent accumulated momentum from applying
        p.setVelocity(0, 0, 0);
        p.velocityModified = true;
        // Give short Slow Falling as safety net in case player is airborne
        var fallOpt = Registries.STATUS_EFFECT.getEntry(ID_SLOW_FALLING);
        fallOpt.ifPresent(e -> p.addStatusEffect(new StatusEffectInstance(e, 60, 0, false, false, false)));
        // Reset fall distance so no accumulated fall damage applies
        p.fallDistance = 0;
    }

    /** Give Resistance 255 (invisible, no particles) to the active jumper to prevent physical death. */
    private void giveJumperProtection(ServerPlayerEntity p) {
        var resOpt = Registries.STATUS_EFFECT.getEntry(ID_RESISTANCE);
        // Duration = 40 seconds (800 ticks), more than enough for a jump turn. Invisible (false, false, false).
        resOpt.ifPresent(e -> p.addStatusEffect(new StatusEffectInstance(e, 800, 254, false, false, false)));
    }

    /** Remove jump protection after landing. */
    private void removeJumperProtection(ServerPlayerEntity p) {
        var resOpt = Registries.STATUS_EFFECT.getEntry(ID_RESISTANCE);
        resOpt.ifPresent(e -> p.removeStatusEffect(e));
    }

    /** Spawn negative visual/audio effects when a player fails a jump. */
    private void spawnFailEffects(ServerPlayerEntity p) {
        ServerWorld world = p.getServerWorld();
        double px = p.getX(), py = p.getY() + 1.0, pz = p.getZ();
        // Red smoke + damage indicator particles
        world.spawnParticles(net.minecraft.particle.ParticleTypes.DAMAGE_INDICATOR, px, py, pz, 8, 0.3, 0.4, 0.3, 0.01);
        world.spawnParticles(net.minecraft.particle.ParticleTypes.LARGE_SMOKE, px, py, pz, 12, 0.4, 0.3, 0.4, 0.02);
        world.spawnParticles(net.minecraft.particle.ParticleTypes.ANGRY_VILLAGER, px, py + 0.5, pz, 4, 0.3, 0.2, 0.3, 0.01);
        // Fail sound to the player + everyone nearby
        playSound(p, "minecraft:entity.villager.no", 1f, 0.8f);
        Identifier sndId = Identifier.tryParse("minecraft:block.anvil.land");
        if (sndId != null) {
            var snd = net.minecraft.sound.SoundEvent.of(sndId);
            for (ServerPlayerEntity nearby : server.getPlayerManager().getPlayerList()) {
                if (nearby.squaredDistanceTo(p) < 2500) { // 50 blocks
                    nearby.playSoundToPlayer(snd, net.minecraft.sound.SoundCategory.MASTER, 1.0f, 0.6f);
                }
            }
        }
    }

    /** Play a sound to a single player. */
    private void playSound(ServerPlayerEntity p, String soundId, float volume, float pitch) {
        Identifier id = Identifier.tryParse(soundId);
        if (id != null) p.playSoundToPlayer(net.minecraft.sound.SoundEvent.of(id), net.minecraft.sound.SoundCategory.MASTER, volume, pitch);
    }

    /** Play a sound to all participants of an event. */
    private void playSoundAll(ServerEvent event, String soundId, float volume, float pitch) {
        Identifier id = Identifier.tryParse(soundId);
        if (id == null) return;
        var snd = net.minecraft.sound.SoundEvent.of(id);
        for (var p : event.getParticipants().values()) {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (pl != null) pl.playSoundToPlayer(snd, net.minecraft.sound.SoundCategory.MASTER, volume, pitch);
        }
    }

    /** Count remaining water blocks at pixel art template positions (unrevealed blocks). */
    private int countWaterBlocks(ThimbleState ts) {
        ServerWorld world = getWorldById(ts.worldId);
        if (world == null) return 0;
        int count = 0;
        for (long posLong : ts.pixelArtTemplate.keySet()) {
            BlockPos pos = BlockPos.fromLong(posLong);
            if (world.getBlockState(pos).getBlock() == Blocks.WATER) count++;
        }
        return count;
    }

    /** Spawn particle burst at a position (wool placement feedback). */
    private void spawnWoolParticles(ServerWorld world, BlockPos pos) {
        double px = pos.getX() + 0.5, py = pos.getY() + 1.0, pz = pos.getZ() + 0.5;
        world.spawnParticles(net.minecraft.particle.ParticleTypes.HAPPY_VILLAGER, px, py, pz, 12, 0.5, 0.4, 0.5, 0.02);
        world.spawnParticles(net.minecraft.particle.ParticleTypes.NOTE, px, py + 0.3, pz, 5, 0.4, 0.3, 0.4, 0.01);
        world.spawnParticles(net.minecraft.particle.ParticleTypes.SPLASH, px, py, pz, 15, 0.4, 0.2, 0.4, 0.05);
        world.spawnParticles(net.minecraft.particle.ParticleTypes.BUBBLE, px, py, pz, 10, 0.3, 0.3, 0.3, 0.02);
    }

    /** Spawn a visual-only firework at a position (no damage/knockback). */
    private void spawnFirework(ServerWorld world, BlockPos pos) {
        double px = pos.getX() + 0.5, py = pos.getY() + 1.5, pz = pos.getZ() + 0.5;
        // Rocket with empty explosions = trail animation, zero damage
        net.minecraft.item.ItemStack rocketStack = new net.minecraft.item.ItemStack(net.minecraft.item.Items.FIREWORK_ROCKET);
        rocketStack.set(net.minecraft.component.DataComponentTypes.FIREWORKS,
            new net.minecraft.component.type.FireworksComponent(1, java.util.List.of()));
        net.minecraft.entity.projectile.FireworkRocketEntity firework =
            new net.minecraft.entity.projectile.FireworkRocketEntity(world, px, py, pz, rocketStack);
        world.spawnEntity(firework);
        // Colorful particle burst to compensate for empty explosions
        world.spawnParticles(net.minecraft.particle.ParticleTypes.FIREWORK, px, py + 3, pz, 30, 1.5, 1.5, 1.5, 0.1);
    }

    private void tpPlayer(ServerPlayerEntity pl, String worldId, double x, double y, double z) {
        ServerWorld target = null;
        if (worldId != null && !worldId.isBlank()) {
            target = getWorldById(worldId);
        }
        if (target == null) target = pl.getServerWorld();
        pl.teleport(target, x, y, z, Set.of(), pl.getYaw(), pl.getPitch());
    }

    private void tpPlayer(ServerPlayerEntity pl, double x, double y, double z) {
        tpPlayer(pl, null, x, y, z);
    }

    /**
     * Check if a player's hitbox overlaps with a specific zone.
     * Tests multiple points (feet, body, head) with proper Math.floor for negative coords.
     * This is the authoritative check for race checkpoint / power-up detection.
     */
    /**
     * Check if a player is in a zone, with trajectory interpolation for fast-moving players.
     * Samples 4 points along the prev→current movement path, with 0.5 block margin.
     */
    private boolean isPlayerInZoneWithTrajectory(ServerPlayerEntity pl, RaceState rs, UUID uuid,
                                                   String zoneId, double cx, double cy, double cz) {
        var regionManager = CheeseGuardMod.getInstance().getRegionManager();
        Region zone = regionManager.getStorage().getRegion(zoneId);
        if (zone == null) return false;
        String worldId = pl.getServerWorld().getRegistryKey().getValue().toString();

        // Check current position first (with margin)
        if (isPointInZone(zone, worldId, cx, cy, cz)) return true;

        // Trajectory interpolation: check intermediate points between prev and current
        double[] prev = rs.prevPositions.get(uuid);
        if (prev != null) {
            double dx = cx - prev[0], dy = cy - prev[1], dz = cz - prev[2];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > 0.5) { // only interpolate if moved significantly
                int steps = Math.min((int)(dist / 0.5) + 1, 8); // sample every ~0.5 blocks, max 8
                for (int i = 1; i < steps; i++) {
                    double t = (double) i / steps;
                    double ix = prev[0] + dx * t;
                    double iy = prev[1] + dy * t;
                    double iz = prev[2] + dz * t;
                    if (isPointInZone(zone, worldId, ix, iy, iz)) return true;
                }
            }
        }
        return false;
    }

    /** Check if a point (with 0.8-block hitbox margin) is inside a zone. */
    private boolean isPointInZone(Region zone, String worldId, double px, double py, double pz) {
        int minBx = (int) Math.floor(px - 0.8);
        int maxBx = (int) Math.floor(px + 0.8);
        int minBz = (int) Math.floor(pz - 0.8);
        int maxBz = (int) Math.floor(pz + 0.8);
        int feetY = (int) Math.floor(py);
        int bodyY = (int) Math.floor(py + 0.9);
        int headY = (int) Math.floor(py + 1.8);
        for (int bx = minBx; bx <= maxBx; bx++) {
            for (int bz = minBz; bz <= maxBz; bz++) {
                if (zone.contains(worldId, bx, feetY, bz)) return true;
                if (zone.contains(worldId, bx, bodyY, bz)) return true;
                if (zone.contains(worldId, bx, headY, bz)) return true;
            }
        }
        return false;
    }

    // ── Bounding box helpers (track config mode) ─────────────────────────────

    /** Check if a player is in a marker region's bounding box with trajectory interpolation. */
    private boolean isPlayerInMarkerWithTrajectory(ServerPlayerEntity pl, RaceState rs, UUID uuid,
                                                    RaceTrackConfig.MarkerRegion region) {
        double cx = pl.getX(), cy = pl.getY(), cz = pl.getZ();
        if (isPointInBoundingBox(region.getMin(), region.getMax(), cx, cy, cz, rs.margin)) return true;
        double[] prev = rs.prevPositions.get(uuid);
        if (prev != null) {
            double dx = cx - prev[0], dy = cy - prev[1], dz = cz - prev[2];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > 0.5) {
                int steps = Math.min((int)(dist / 0.5) + 1, 8);
                for (int i = 1; i < steps; i++) {
                    double t = (double) i / steps;
                    if (isPointInBoundingBox(region.getMin(), region.getMax(),
                        prev[0] + dx * t, prev[1] + dy * t, prev[2] + dz * t, rs.margin)) return true;
                }
            }
        }
        return false;
    }

    /** Check if a player is in a marker region's bounding box (no trajectory). */
    private boolean isPlayerInMarkerRegion(ServerPlayerEntity pl, RaceTrackConfig.MarkerRegion region, double margin) {
        return isPointInBoundingBox(region.getMin(), region.getMax(), pl.getX(), pl.getY(), pl.getZ(), margin);
    }

    /** Check if a point (with margin) is inside a bounding box. Player is ~1.8 tall, ~0.6 wide. */
    private boolean isPointInBoundingBox(int[] min, int[] max, double px, double py, double pz, double margin) {
        return px + margin >= min[0] && px - margin <= max[0] + 1
            && pz + margin >= min[2] && pz - margin <= max[2] + 1
            && py + 1.8 >= min[1] && py <= max[1] + 1;
    }

    /** Check if a point is within the global race zone bounds (for OOB detection). */
    private static ParticleEffect resolveParticle(String name) {
        if (name == null || name.isEmpty()) return ParticleTypes.HAPPY_VILLAGER;
        return switch (name.toUpperCase()) {
            case "FLAME" -> ParticleTypes.FLAME;
            case "HEART" -> ParticleTypes.HEART;
            case "END_ROD" -> ParticleTypes.END_ROD;
            case "SOUL_FIRE_FLAME" -> ParticleTypes.SOUL_FIRE_FLAME;
            case "FIREWORK" -> ParticleTypes.FIREWORK;
            case "COMPOSTER" -> ParticleTypes.COMPOSTER;
            case "GLOW" -> ParticleTypes.GLOW;
            case "ELECTRIC_SPARK" -> ParticleTypes.ELECTRIC_SPARK;
            case "ENCHANT" -> ParticleTypes.ENCHANT;
            case "BUBBLE" -> ParticleTypes.BUBBLE;
            case "CLOUD" -> ParticleTypes.CLOUD;
            case "CRIT" -> ParticleTypes.CRIT;
            case "DAMAGE_INDICATOR" -> ParticleTypes.DAMAGE_INDICATOR;
            case "NOTE" -> ParticleTypes.NOTE;
            default -> ParticleTypes.HAPPY_VILLAGER;
        };
    }

    private static Block resolveBlock(String blockIdStr) {
        if (blockIdStr == null || blockIdStr.isEmpty()) return Blocks.AIR;
        Identifier id = Identifier.tryParse(blockIdStr);
        if (id == null) return Blocks.AIR;
        Block b = Registries.BLOCK.get(id);
        return b != null ? b : Blocks.AIR;
    }

    private boolean isPointInGlobalBounds(RaceState rs, ServerWorld world, double px, double py, double pz) {
        if (rs.globalBoundsMin == null || rs.globalBoundsMax == null || rs.raceWorld == null) return true;
        String worldId = world.getRegistryKey().getValue().toString();
        if (!worldId.equals(rs.raceWorld)) return false;
        return px >= rs.globalBoundsMin[0] && px <= rs.globalBoundsMax[0] + 1
            && py >= rs.globalBoundsMin[1] - 5 && py <= rs.globalBoundsMax[1] + 10
            && pz >= rs.globalBoundsMin[2] && pz <= rs.globalBoundsMax[2] + 1;
    }

    /**
     * Check if any part of a player's hitbox overlaps with a specific zone.
     * Player hitbox is 0.6 wide (±0.3 from center) and ~1.8 tall.
     * Used for non-race zone checks (thimble, spleef, etc.).
     */
    private boolean isPlayerInZone(ServerPlayerEntity pl, RegionManager regionManager, String zoneId) {
        Region zone = regionManager.getStorage().getRegion(zoneId);
        if (zone == null) return false;
        String worldId = pl.getServerWorld().getRegistryKey().getValue().toString();
        return isPointInZone(zone, worldId, pl.getX(), pl.getY(), pl.getZ());
    }

    /** TP a player to a random position in the given zones, or fallback to fixed coords. */
    private void tpToZoneOrCoords(ServerPlayerEntity pl, ServerEvent event, boolean isSpectator) {
        List<String> zoneIds = isSpectator ? event.getTpSpectatorZoneIds() : event.getTpParticipantZoneIds();
        if (!zoneIds.isEmpty()) {
            List<double[]> candidates = new ArrayList<>();
            var regionStorage = CheeseGuardMod.getInstance().getRegionManager().getStorage();
            for (String zoneId : zoneIds) {
                Region zone = regionStorage.getRegion(zoneId);
                if (zone == null) continue;
                String wid = zone.getWorld();
                ServerWorld world = getWorldById(wid);
                for (int x = zone.getMinX(); x <= zone.getMaxX(); x++) {
                    for (int z = zone.getMinZ(); z <= zone.getMaxZ(); z++) {
                        double safeY = findSafeSurface(world, x, z, zone.getMinY(), zone.getMaxY());
                        candidates.add(new double[]{x + 0.5, safeY, z + 0.5, 0});
                    }
                }
                if (!candidates.isEmpty()) {
                    double[] pick = candidates.get(new Random().nextInt(candidates.size()));
                    tpPlayer(pl, wid, pick[0], pick[1], pick[2]);
                    return;
                }
            }
        }
        // Fallback: fixed coordinates
        String w = isSpectator ? event.getTpSpectatorWorld() : event.getTpParticipantWorld();
        double x = isSpectator ? event.getTpSpectatorX() : event.getTpParticipantX();
        double y = isSpectator ? event.getTpSpectatorY() : event.getTpParticipantY();
        double z = isSpectator ? event.getTpSpectatorZ() : event.getTpParticipantZ();
        if (w != null && !w.isBlank()) tpPlayer(pl, w, x, y, z);
    }

    /**
     * Find a safe Y to stand on at (x, z) within the zone Y range.
     * Scans upward from minY: looks for a solid block with 2 passable blocks above.
     * Falls back to maxY + 1 (above the zone) if no safe spot found.
     */
    private double findSafeSurface(ServerWorld world, int x, int z, int minY, int maxY) {
        if (world == null) return maxY + 1.0;
        for (int y = Math.max(minY - 1, world.getBottomY()); y <= maxY; y++) {
            var below = new net.minecraft.util.math.BlockPos(x, y, z);
            var feet  = new net.minecraft.util.math.BlockPos(x, y + 1, z);
            var head  = new net.minecraft.util.math.BlockPos(x, y + 2, z);
            boolean solidGround = !world.getBlockState(below).getCollisionShape(world, below).isEmpty();
            boolean feetClear = world.getBlockState(feet).getCollisionShape(world, feet).isEmpty();
            boolean headClear = world.getBlockState(head).getCollisionShape(world, head).isEmpty();
            if (solidGround && feetClear && headClear) {
                return y + 1.0;
            }
        }
        return maxY + 1.0;
    }

    private ServerWorld getWorldById(String worldId) {
        if (server == null || worldId == null || worldId.isBlank()) return server != null ? server.getOverworld() : null;
        for (ServerWorld w : server.getWorlds()) {
            if (w.getRegistryKey().getValue().toString().equals(worldId)) return w;
        }
        return server.getOverworld();
    }

    // ── Entity waypoint system ───────────────────────────────────────────────

    public void trackEntity(String eventId, UUID entityUuid) {
        MiniGameState state = activeGames.get(eventId);
        if (state != null) {
            state.entityWaypoints.add(entityUuid);
        }
    }

    public List<UUID> getTrackedEntities(String eventId) {
        MiniGameState state = activeGames.get(eventId);
        return state != null ? state.entityWaypoints : Collections.emptyList();
    }

    // ── Prop Hunt ─────────────────────────────────────────────────────────────

    private void initPropHunt(ServerEvent event, MiniGameState state) {
        PropHuntState phs = new PropHuntState();

        // Read config
        phs.hunterStartHealth = getConfigInt(event, "hunterStartHearts", 10) * 2; // hearts → half-hearts
        phs.penaltyHalfHearts = getConfigInt(event, "penaltyHearts", 1) * 2;      // hearts → half-hearts
        phs.hideGraceSeconds = getConfigInt(event, "hideGraceSeconds", 30);
        phs.gameDurationSeconds = getConfigInt(event, "gameDurationSeconds", 300);
        phs.decoyCountPerSpecies = Math.min(getConfigInt(event, "decoyCountPerHider", 5), 50);
        phs.attacksEnabled = getConfigBool(event, "attacksEnabled", false);
        phs.hunterWeapon = getConfigString(event, "hunterWeapon", "bow");
        phs.hunterArrowCount = getConfigInt(event, "hunterArrowCount", 64);
        phs.transformCooldownTicks = getConfigInt(event, "transformCooldownTicks", 200);
        phs.hunterTpZoneId = getConfigString(event, "hunterTpZoneId", "");
        phs.hiderStartHealth = getConfigInt(event, "hiderHearts", 3) * 2; // hearts → half-hearts
        phs.hiderDamagePerHit = 2; // fixed at 1 heart per hit
        phs.gameStartTick = server.getTicks();

        // Hunter power config (per-power enable/cooldown)
        JsonObject cfg = event.getMiniGameConfig();
        JsonObject hunterPowerCfg = cfg != null && cfg.has("hunterPowerConfig") ? cfg.getAsJsonObject("hunterPowerConfig") : new JsonObject();
        for (HunterPower hp : HunterPower.values()) {
            String name = hp.name();
            JsonObject powerObj = hunterPowerCfg.has(name) ? hunterPowerCfg.getAsJsonObject(name) : new JsonObject();
            boolean enabled = !powerObj.has("enabled") || powerObj.get("enabled").getAsBoolean(); // default true
            int cooldown = powerObj.has("cooldown") ? powerObj.get("cooldown").getAsInt() : 30;
            phs.hunterPowerCooldowns.put(name, cooldown * 20); // seconds -> ticks
            if (enabled) phs.hunterPowersEnabled.add(name);
        }
        phs.hunterPowerCount = getConfigInt(event, "hunterPowerCount", 3);

        // Hider power config (per-power enable/cooldown)
        JsonObject hiderPowerCfg = cfg != null && cfg.has("hiderPowerConfig") ? cfg.getAsJsonObject("hiderPowerConfig") : new JsonObject();
        for (HiderPower hdp : HiderPower.values()) {
            String name = hdp.name();
            JsonObject powerObj = hiderPowerCfg.has(name) ? hiderPowerCfg.getAsJsonObject(name) : new JsonObject();
            boolean enabled = !powerObj.has("enabled") || powerObj.get("enabled").getAsBoolean();
            int cooldown = powerObj.has("cooldown") ? powerObj.get("cooldown").getAsInt() : 20;
            phs.hiderPowerCooldowns.put(name, cooldown * 20);
            if (enabled) phs.hiderPowersEnabled.add(name);
        }
        phs.hiderPowerCount = getConfigInt(event, "hiderPowerCount", 2);

        // Parse species list — new format (JsonArray) or legacy CSV string
        if (cfg != null && cfg.has("speciesList") && cfg.get("speciesList").isJsonArray()) {
            for (JsonElement el : cfg.getAsJsonArray("speciesList")) {
                JsonObject obj = el.getAsJsonObject();
                PropHuntSpeciesEntry entry = new PropHuntSpeciesEntry();
                entry.species = obj.has("species") ? obj.get("species").getAsString().trim() : "";
                entry.itemId = obj.has("itemId") ? obj.get("itemId").getAsString().trim() : "";
                entry.hasCustomIcon = obj.has("hasCustomIcon") && obj.get("hasCustomIcon").getAsBoolean();
                if (!entry.species.isEmpty()) {
                    phs.speciesEntries.add(entry);
                    phs.speciesList.add(entry.species);
                }
            }
        } else {
            // Legacy: comma-separated string
            String speciesCsv = getConfigString(event, "speciesList", "");
            if (!speciesCsv.isBlank()) {
                for (String s : speciesCsv.split(",")) {
                    String trimmed = s.trim();
                    if (!trimmed.isEmpty()) {
                        phs.speciesList.add(trimmed);
                        PropHuntSpeciesEntry entry = new PropHuntSpeciesEntry();
                        entry.species = trimmed;
                        phs.speciesEntries.add(entry);
                    }
                }
            }
        }

        // Validate species list
        if (phs.speciesList.isEmpty()) {
            CheeseGuardMod.LOGGER.error("[CG] Prop Hunt: speciesList is empty! Aborting — configure species in the event timeline.");
            // Schedule end on next tick to avoid re-entrancy (onEventStart → endEvent)
            server.execute(() -> {
                EventManager emAbort = CheeseGuardMod.getInstance().getEventManager();
                if (emAbort != null) emAbort.endEvent(event);
            });
            return;
        }

        // Parse spawn zone IDs
        JsonArray szArr = getConfigJsonArray(event, "spawnZoneIds");
        for (int i = 0; i < szArr.size(); i++) {
            phs.spawnZoneIds.add(szArr.get(i).getAsString());
        }

        // Assign roles: randomly pick hunterCount hunters, rest are hiders
        List<UUID> allParticipants = new ArrayList<>();
        for (var p : event.getParticipants().values()) {
            if (p.getRole() == EventParticipant.Role.PARTICIPANT) {
                allParticipants.add(p.getPlayerUuid());
            }
        }
        Collections.shuffle(allParticipants);

        // hunterCount: can be absolute number "2" or ratio "1/4"
        String hunterCountRaw = getConfigString(event, "hunterCount", "2");
        int computedHunterCount;
        if (hunterCountRaw.contains("/")) {
            try {
                String[] hcParts = hunterCountRaw.split("/");
                double ratio = Double.parseDouble(hcParts[0].trim()) / Double.parseDouble(hcParts[1].trim());
                computedHunterCount = Math.max(1, (int) Math.round(allParticipants.size() * ratio));
            } catch (NumberFormatException e) {
                computedHunterCount = 2;
            }
        } else {
            try {
                computedHunterCount = Integer.parseInt(hunterCountRaw.trim());
            } catch (NumberFormatException e) {
                computedHunterCount = 2;
            }
        }
        phs.hunterCount = computedHunterCount;

        int huntersPicked = Math.min(phs.hunterCount, allParticipants.size() - 1); // at least 1 hider
        for (int i = 0; i < allParticipants.size(); i++) {
            UUID uuid = allParticipants.get(i);
            if (i < huntersPicked) {
                phs.hunters.add(uuid);
            } else {
                phs.hiders.add(uuid);
            }
        }

        EventManager em = CheeseGuardMod.getInstance().getEventManager();

        // Setup hunters: blindness + freeze
        for (UUID hunterUuid : phs.hunters) {
            ServerPlayerEntity hunter = server.getPlayerManager().getPlayer(hunterUuid);
            if (hunter == null) continue;

            // Blindness for grace period
            Identifier blindnessId = Identifier.of("minecraft", "blindness");
            StatusEffect blindnessEffect = Registries.STATUS_EFFECT.get(blindnessId);
            if (blindnessEffect != null) {
                hunter.addStatusEffect(new StatusEffectInstance(
                    Registries.STATUS_EFFECT.getEntry(blindnessEffect),
                    phs.hideGraceSeconds * 20, 0, false, false, true));
            }

            // Freeze (slowness 255 + mining fatigue to prevent movement)
            freezePlayer(hunter);

            // Title
            hunter.sendMessage(Text.translatable("cheeseguard.prophunt.role_hunter")
                .formatted(Formatting.RED), false);
            hunter.sendMessage(Text.translatable("cheeseguard.prophunt.hunter_wait", phs.hideGraceSeconds)
                .formatted(Formatting.GRAY), false);

            // TP hunter to hunter zone if configured
            if (!phs.hunterTpZoneId.isBlank()) {
                tpToZone(hunter, phs.hunterTpZoneId);
            }
        }

        // Assign N random powers from enabled set to each hunter
        if (!phs.hunterPowersEnabled.isEmpty() && phs.hunterPowerCount > 0) {
            List<HunterPower> enabledHunterPowers = new ArrayList<>();
            for (HunterPower hp : HunterPower.values()) {
                if (phs.hunterPowersEnabled.contains(hp.name())) enabledHunterPowers.add(hp);
            }
            Random rng = new Random();
            for (UUID hunterUuid : phs.hunters) {
                List<HunterPower> playerPowers = new ArrayList<>(enabledHunterPowers);
                Collections.shuffle(playerPowers, rng);
                int count = Math.min(phs.hunterPowerCount, playerPowers.size());
                playerPowers = playerPowers.subList(0, count);
                phs.hunterAvailablePowers.put(hunterUuid, new ArrayList<>(playerPowers));
                phs.hunterPowerIndex.put(hunterUuid, 0);
                phs.hunterPowerLastUsed.put(hunterUuid, new HashMap<>());
                ServerPlayerEntity hunter = server.getPlayerManager().getPlayer(hunterUuid);
                if (hunter != null && !playerPowers.isEmpty()) {
                    HunterPower first = playerPowers.get(0);
                    int cd = phs.hunterPowerCooldowns.getOrDefault(first.name(), 600) / 20;
                    sendPowerHud(hunter, first.translationKey, first.icon, first.descKey, cd, cd, true);
                }
            }
        }

        // Setup hiders: transform into random Pokémon
        Random random = new Random();
        List<String> speciesPool = new ArrayList<>(phs.speciesList);
        for (UUID hiderUuid : phs.hiders) {
            ServerPlayerEntity hider = server.getPlayerManager().getPlayer(hiderUuid);
            if (hider == null) continue;

            // Pick random species
            if (!speciesPool.isEmpty()) {
                String species = speciesPool.get(random.nextInt(speciesPool.size()));
                CobbleShiftHook.transformPlayer(hider, species, phs.attacksEnabled);
                phs.hiderSpecies.put(hiderUuid, species);
            }

            // Title
            hider.sendMessage(Text.translatable("cheeseguard.prophunt.role_hider")
                .formatted(Formatting.GREEN), false);
            hider.sendMessage(Text.translatable("cheeseguard.prophunt.hider_hide", phs.hideGraceSeconds)
                .formatted(Formatting.GRAY), false);

            // Give transform items (one per species in the list)
            giveTransformItems(hider, phs);

            // Init virtual health for hider
            phs.hiderHealth.put(hiderUuid, phs.hiderStartHealth);

            // Make hiders immune to direct PVP damage (use virtual health system instead)
            Identifier resistId = Identifier.of("minecraft", "resistance");
            StatusEffect resistEffect = Registries.STATUS_EFFECT.get(resistId);
            if (resistEffect != null) {
                hider.addStatusEffect(new StatusEffectInstance(
                    Registries.STATUS_EFFECT.getEntry(resistEffect),
                    phs.gameDurationSeconds * 20 + 200, 4, false, false, false)); // Resistance V (level 4, 0-indexed), invisible
            }
        }

        // Assign N random powers from enabled set to each hider
        if (!phs.hiderPowersEnabled.isEmpty() && phs.hiderPowerCount > 0) {
            List<HiderPower> enabledHiderPowers = new ArrayList<>();
            for (HiderPower hdp : HiderPower.values()) {
                if (phs.hiderPowersEnabled.contains(hdp.name())) enabledHiderPowers.add(hdp);
            }
            Random rng2 = new Random();
            for (UUID hiderUuid : phs.hiders) {
                List<HiderPower> playerPowers = new ArrayList<>(enabledHiderPowers);
                Collections.shuffle(playerPowers, rng2);
                int count = Math.min(phs.hiderPowerCount, playerPowers.size());
                playerPowers = playerPowers.subList(0, count);
                phs.hiderAvailablePowers.put(hiderUuid, new ArrayList<>(playerPowers));
                phs.hiderPowerIndex.put(hiderUuid, 0);
                phs.hiderPowerLastUsed.put(hiderUuid, new HashMap<>());
                ServerPlayerEntity hider = server.getPlayerManager().getPlayer(hiderUuid);
                if (hider != null && !playerPowers.isEmpty()) {
                    HiderPower first = playerPowers.get(0);
                    int cd = phs.hiderPowerCooldowns.getOrDefault(first.name(), 400) / 20;
                    sendPowerHud(hider, first.translationKey, first.icon, first.descKey, cd, cd, true);
                }
            }
        }

        // Decoys will be spawned after ~3 seconds delay from the tick loop (ticked every 1s)
        // to ensure chunks are loaded after player teleportation
        phs.decoySpawnDelayTicks = 3;

        // Apply zone flag overrides (nametag hide + minimap hide + item drop deny)
        for (String zoneId : event.getScopeZoneIds()) {
            event.setFlagOverride(zoneId + ":nametag-hide", false); // DENY = hidden
            event.setFlagOverride(zoneId + ":minimap-hide", false); // DENY = hidden
            event.setFlagOverride(zoneId + ":item-drop", false);    // DENY = no dropping items
        }

        // Send hide packets immediately to all participants (don't wait for 0.5s tick cycle)
        for (var p : event.getParticipants().values()) {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (pl != null && net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(pl, com.cheeseguard.network.GuiSyncPayload.ID)) {
                try {
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(pl,
                        new com.cheeseguard.network.GuiSyncPayload("NAMETAG_ZONE", "{\"hide\":true}"));
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(pl,
                        new com.cheeseguard.network.GuiSyncPayload("MINIMAP_ZONE", "{\"hide\":true}"));
                } catch (Exception ignored) {}
            }
        }

        propHuntStates.put(event.getId(), phs);

        // Create live sidebar scoreboard
        createPropHuntScoreboard(event);
        updatePropHuntScoreboard(event, phs);

        CheeseGuardMod.LOGGER.info("[CG] Prop Hunt started: {} hunters, {} hiders, {} species",
            phs.hunters.size(), phs.hiders.size(), phs.speciesList.size());
    }

    private void tickPropHunt(ServerEvent event, PropHuntState phs, MiniGameState state) {
        EventManager em = CheeseGuardMod.getInstance().getEventManager();
        long currentTick = server.getTicks();
        long elapsed = (currentTick - phs.gameStartTick) / 20;

        // Delayed decoy spawn (wait for chunks to load after player TP)
        if (!phs.decoysSpawned && phs.decoySpawnDelayTicks > 0) {
            phs.decoySpawnDelayTicks--;
            if (phs.decoySpawnDelayTicks <= 0) {
                spawnDecoys(event, phs);
                phs.decoysSpawned = true;
            }
        }

        // Freeze visual: continuous snowflake particles while frozen
        if (phs.unfreezeAtTick > 0 && currentTick < phs.unfreezeAtTick) {
            for (UUID hiderUuid : phs.hiders) {
                if (phs.eliminatedHiders.contains(hiderUuid)) continue;
                ServerPlayerEntity hider = server.getPlayerManager().getPlayer(hiderUuid);
                if (hider != null) {
                    hider.getServerWorld().spawnParticles(
                        ParticleTypes.SNOWFLAKE,
                        hider.getX(), hider.getY() + 1, hider.getZ(),
                        3, 0.3, 0.5, 0.3, 0.01);
                }
            }
        }

        // Unfreeze hiders after freeze power expires
        if (phs.unfreezeAtTick > 0 && currentTick >= phs.unfreezeAtTick) {
            phs.unfreezeAtTick = -1;
            for (UUID hiderUuid : phs.hiders) {
                if (phs.eliminatedHiders.contains(hiderUuid)) continue;
                ServerPlayerEntity hider = server.getPlayerManager().getPlayer(hiderUuid);
                if (hider != null) {
                    unfreezePlayer(hider);
                    hider.sendMessage(Text.translatable("cheeseguard.prophunt.unfreeze").formatted(Formatting.GREEN), true);
                }
            }
        }

        // Compass: expire old entries & compute per-hunter compass info for actionbar
        for (var compassEntry : new HashMap<>(phs.compassEndTick).entrySet()) {
            if (currentTick > compassEntry.getValue()) {
                phs.compassEndTick.remove(compassEntry.getKey());
                ServerPlayerEntity h = server.getPlayerManager().getPlayer(compassEntry.getKey());
                if (h != null) h.sendMessage(Text.translatable("cheeseguard.prophunt.compass_expired").formatted(Formatting.GRAY), true);
            }
        }
        // Build compass text per hunter (used later in the actionbar)
        Map<UUID, String> compassTexts = new HashMap<>();
        for (var compassEntry : phs.compassEndTick.entrySet()) {
            ServerPlayerEntity hunter = server.getPlayerManager().getPlayer(compassEntry.getKey());
            if (hunter == null) continue;
            // Find nearest alive hider
            double nearestDist = Double.MAX_VALUE;
            ServerPlayerEntity nearestHider = null;
            for (UUID hiderUuid : phs.hiders) {
                if (phs.eliminatedHiders.contains(hiderUuid)) continue;
                ServerPlayerEntity h = server.getPlayerManager().getPlayer(hiderUuid);
                if (h == null || h.getServerWorld() != hunter.getServerWorld()) continue;
                double dist = hunter.squaredDistanceTo(h);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearestHider = h;
                }
            }
            if (nearestHider != null) {
                int dist = (int) Math.sqrt(nearestDist);
                String dir = getCardinalDirection(hunter, nearestHider);
                compassTexts.put(compassEntry.getKey(), "\u00a7b\uD83E\uDDED " + dist + "m \u00a7e" + dir);
                // Proximity ping — closer = more frequent
                if (dist < 30) {
                    int pingInterval = Math.max(5, dist); // ticks between pings (5 at closest, 30 at furthest)
                    if (currentTick % pingInterval == 0) {
                        hunter.playSound(net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.3f, 1.5f + (1.0f - dist / 30.0f));
                    }
                }
            }
        }

        // Trap: check if any hider stepped on a trap
        Iterator<TrapEntry> trapIt = phs.activeTraps.iterator();
        while (trapIt.hasNext()) {
            TrapEntry trap = trapIt.next();
            for (UUID hiderUuid : phs.hiders) {
                if (phs.eliminatedHiders.contains(hiderUuid)) continue;
                ServerPlayerEntity hider = server.getPlayerManager().getPlayer(hiderUuid);
                if (hider == null) continue;
                String hiderWorld = hider.getServerWorld().getRegistryKey().getValue().toString();
                if (!hiderWorld.equals(trap.worldId)) continue;
                double dx = hider.getX() - trap.x, dz = hider.getZ() - trap.z, dy = hider.getY() - trap.y;
                if (dx * dx + dz * dz < 4 && Math.abs(dy) < 2) { // within 2 blocks
                    // Trigger trap!
                    trapIt.remove();
                    // Slowness for 3 seconds
                    Identifier slowId = Identifier.of("minecraft", "slowness");
                    StatusEffect slowEffect = Registries.STATUS_EFFECT.get(slowId);
                    if (slowEffect != null) {
                        hider.addStatusEffect(new StatusEffectInstance(
                            Registries.STATUS_EFFECT.getEntry(slowEffect),
                            60, 3, false, true, true)); // 3s, Slowness IV
                    }
                    hider.sendMessage(Text.translatable("cheeseguard.prophunt.trap_triggered")
                        .formatted(Formatting.RED), true);
                    hider.playSound(net.minecraft.sound.SoundEvents.ENTITY_SPIDER_AMBIENT, 1f, 0.5f);
                    // Particles at trigger
                    hider.getServerWorld().spawnParticles(
                        ParticleTypes.CRIT,
                        trap.x, trap.y + 1, trap.z,
                        30, 0.5, 0.5, 0.5, 0.1);
                    // Notify the hunter who placed the trap
                    ServerPlayerEntity trapper = server.getPlayerManager().getPlayer(trap.hunterUuid);
                    if (trapper != null) {
                        trapper.sendMessage(Text.translatable("cheeseguard.prophunt.trap_caught",
                            hider.getName().getString()).formatted(Formatting.GREEN), true);
                    }
                    break; // one trap triggers once
                }
            }
        }

        // Trap indicator: subtle particles visible only to the trap owner (every 40 ticks)
        if (currentTick % 40 == 0) {
            for (TrapEntry trap : phs.activeTraps) {
                ServerPlayerEntity owner = server.getPlayerManager().getPlayer(trap.hunterUuid);
                if (owner == null) continue;
                owner.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.ParticleS2CPacket(
                    ParticleTypes.ENCHANT, false,
                    trap.x, trap.y + 0.3, trap.z, 0.2f, 0.1f, 0.2f, 0.01f, 3));
            }
        }

        // Invisibility: re-transform hiders when invisibility expires
        for (var invisEntry : new HashMap<>(phs.invisEndTick).entrySet()) {
            if (currentTick >= invisEntry.getValue()) {
                phs.invisEndTick.remove(invisEntry.getKey());
                ServerPlayerEntity hider = server.getPlayerManager().getPlayer(invisEntry.getKey());
                String savedSpecies = phs.invisSavedSpecies.remove(invisEntry.getKey());
                if (hider != null && savedSpecies != null && !phs.eliminatedHiders.contains(invisEntry.getKey())) {
                    CobbleShiftHook.transformPlayer(hider, savedSpecies, phs.attacksEnabled);
                    hider.sendMessage(Text.translatable("cheeseguard.prophunt.invis_ended")
                        .formatted(Formatting.YELLOW), true);
                }
            }
        }

        // Echolocation: spawn electric spark particles at hider positions
        for (var echoEntry : new HashMap<>(phs.echoEndTick).entrySet()) {
            if (currentTick > echoEntry.getValue()) {
                phs.echoEndTick.remove(echoEntry.getKey());
                ServerPlayerEntity h = server.getPlayerManager().getPlayer(echoEntry.getKey());
                if (h != null) h.sendMessage(Text.translatable("cheeseguard.prophunt.echo_expired").formatted(Formatting.GRAY), true);
                continue;
            }
            for (UUID hiderUuid : phs.hiders) {
                if (phs.eliminatedHiders.contains(hiderUuid)) continue;
                ServerPlayerEntity hider = server.getPlayerManager().getPlayer(hiderUuid);
                if (hider == null) continue;
                hider.getServerWorld().spawnParticles(
                    ParticleTypes.ELECTRIC_SPARK,
                    hider.getX(), hider.getY() + 2.2, hider.getZ(),
                    10, 0.5, 0.5, 0.5, 0.02);
            }
        }

        // Hunter's Mark: particles above marked target
        for (var markEntry : new HashMap<>(phs.hunterMarkEndTick).entrySet()) {
            if (currentTick > markEntry.getValue()) {
                phs.hunterMarkEndTick.remove(markEntry.getKey());
                phs.hunterMarkTarget.remove(markEntry.getKey());
                continue;
            }
            UUID targetUuid = phs.hunterMarkTarget.get(markEntry.getKey());
            if (targetUuid == null) continue;
            ServerPlayerEntity target = server.getPlayerManager().getPlayer(targetUuid);
            if (target == null) continue;
            target.getServerWorld().spawnParticles(
                ParticleTypes.END_ROD,
                target.getX(), target.getY() + 2.5, target.getZ(),
                5, 0.2, 0.5, 0.2, 0.02);
        }

        // Smokescreen: particles follow the hider + blindness to nearby hunters
        for (var smokeEntry : new HashMap<>(phs.smokescreenEndTick).entrySet()) {
            if (currentTick > smokeEntry.getValue()) {
                phs.smokescreenEndTick.remove(smokeEntry.getKey());
                phs.smokescreenPos.remove(smokeEntry.getKey());
                ServerPlayerEntity s = server.getPlayerManager().getPlayer(smokeEntry.getKey());
                if (s != null) s.sendMessage(Text.translatable("cheeseguard.prophunt.smoke_expired").formatted(Formatting.GRAY), true);
                continue;
            }
            // Smoke follows the hider's current position
            ServerPlayerEntity smoker = server.getPlayerManager().getPlayer(smokeEntry.getKey());
            if (smoker == null) { phs.smokescreenEndTick.remove(smokeEntry.getKey()); phs.smokescreenPos.remove(smokeEntry.getKey()); continue; }
            double sx = smoker.getX(), sy = smoker.getY(), sz = smoker.getZ();
            smoker.getServerWorld().spawnParticles(
                ParticleTypes.CAMPFIRE_COSY_SMOKE,
                sx, sy + 1, sz, 15, 2.5, 1.5, 2.5, 0.01);
            // Blindness to hunters within 5 blocks of hider's current position
            Identifier blindId = Identifier.of("minecraft", "blindness");
            StatusEffect blindEffect = Registries.STATUS_EFFECT.get(blindId);
            if (blindEffect != null) {
                for (UUID hunterUuid : phs.hunters) {
                    if (phs.eliminatedHunters.contains(hunterUuid)) continue;
                    ServerPlayerEntity hunter = server.getPlayerManager().getPlayer(hunterUuid);
                    if (hunter == null || hunter.getServerWorld() != smoker.getServerWorld()) continue;
                    double dx = hunter.getX() - sx, dz = hunter.getZ() - sz;
                    if (dx*dx + dz*dz <= 25) { // 5 blocks
                        hunter.addStatusEffect(new StatusEffectInstance(
                            Registries.STATUS_EFFECT.getEntry(blindEffect), 20, 0, false, false, true));
                    }
                }
            }
        }

        // Shield active indicator: periodic particles every 40 ticks
        if (currentTick % 40 == 0) {
            for (UUID shieldUuid : phs.shieldActive) {
                ServerPlayerEntity shieldPlayer = server.getPlayerManager().getPlayer(shieldUuid);
                if (shieldPlayer != null) {
                    shieldPlayer.getServerWorld().spawnParticles(
                        ParticleTypes.END_ROD,
                        shieldPlayer.getX(), shieldPlayer.getY() + 1, shieldPlayer.getZ(),
                        5, 0.3, 0.5, 0.3, 0.02);
                }
            }
        }

        // Shield expiration check
        for (var shieldIt = phs.shieldExpireTick.entrySet().iterator(); shieldIt.hasNext(); ) {
            var shieldEntry = shieldIt.next();
            if (currentTick >= shieldEntry.getValue()) {
                shieldIt.remove();
                phs.shieldActive.remove(shieldEntry.getKey());
                ServerPlayerEntity shieldPlayer = server.getPlayerManager().getPlayer(shieldEntry.getKey());
                if (shieldPlayer != null) {
                    shieldPlayer.sendMessage(Text.translatable("cheeseguard.prophunt.shield_expired")
                        .formatted(Formatting.RED), true);
                }
            }
        }

        // Mimic: cleanup when expired
        for (var mimicEntry : new HashMap<>(phs.mimicEndTick).entrySet()) {
            if (currentTick >= mimicEntry.getValue()) {
                phs.mimicEndTick.remove(mimicEntry.getKey());
                ServerPlayerEntity hider = server.getPlayerManager().getPlayer(mimicEntry.getKey());
                // Kill mimic armor stand
                try {
                    server.getCommandManager().getDispatcher().execute(
                        "kill @e[tag=ph_mimic,limit=1,sort=nearest]", server.getCommandSource().withSilent());
                } catch (Exception ignored) {}
                // Restore hider
                if (hider != null && !phs.eliminatedHiders.contains(mimicEntry.getKey())) {
                    unfreezePlayer(hider);
                    String savedSpecies = phs.invisSavedSpecies.remove(mimicEntry.getKey());
                    if (savedSpecies != null) {
                        CobbleShiftHook.transformPlayer(hider, savedSpecies, phs.attacksEnabled);
                    }
                    hider.sendMessage(Text.translatable("cheeseguard.prophunt.mimic_ended").formatted(Formatting.YELLOW), true);
                }
            }
        }

        // Update power HUD cooldown display every second
        if (currentTick % 20 == 0) {
            for (UUID hunterUuid : phs.hunters) {
                if (phs.eliminatedHunters.contains(hunterUuid)) continue;
                ServerPlayerEntity hunter = server.getPlayerManager().getPlayer(hunterUuid);
                if (hunter == null) continue;
                List<HunterPower> available = phs.hunterAvailablePowers.get(hunterUuid);
                if (available == null || available.isEmpty()) continue;
                int hpIdx = phs.hunterPowerIndex.getOrDefault(hunterUuid, 0);
                HunterPower hp = available.get(hpIdx % available.size());
                Map<String, Long> playerCooldowns = phs.hunterPowerLastUsed.getOrDefault(hunterUuid, Collections.emptyMap());
                Long lastUsed = playerCooldowns.get(hp.name());
                int cooldownTicks = phs.hunterPowerCooldowns.getOrDefault(hp.name(), 600);
                int remaining = 0;
                boolean ready = true;
                if (lastUsed != null) {
                    long elapsedPower = currentTick - lastUsed;
                    if (elapsedPower < cooldownTicks) {
                        remaining = (int) ((cooldownTicks - elapsedPower) / 20);
                        ready = false;
                    }
                }
                sendPowerHud(hunter, hp.translationKey, hp.icon, hp.descKey, cooldownTicks / 20, remaining, ready);
            }
            for (UUID hiderUuid : phs.hiders) {
                if (phs.eliminatedHiders.contains(hiderUuid)) continue;
                ServerPlayerEntity hider = server.getPlayerManager().getPlayer(hiderUuid);
                if (hider == null) continue;
                List<HiderPower> available = phs.hiderAvailablePowers.get(hiderUuid);
                if (available == null || available.isEmpty()) continue;
                int hdpIdx = phs.hiderPowerIndex.getOrDefault(hiderUuid, 0);
                HiderPower hdp = available.get(hdpIdx % available.size());
                Map<String, Long> playerCooldowns = phs.hiderPowerLastUsed.getOrDefault(hiderUuid, Collections.emptyMap());
                Long lastUsed = playerCooldowns.get(hdp.name());
                int cooldownTicks = phs.hiderPowerCooldowns.getOrDefault(hdp.name(), 400);
                int remaining = 0;
                boolean ready = true;
                if (lastUsed != null) {
                    long elapsedPower = currentTick - lastUsed;
                    if (elapsedPower < cooldownTicks) {
                        remaining = (int) ((cooldownTicks - elapsedPower) / 20);
                        ready = false;
                    }
                }
                sendPowerHud(hider, hdp.translationKey, hdp.icon, hdp.descKey, cooldownTicks / 20, remaining, ready);
            }

            // Update live sidebar scoreboard every second
            updatePropHuntScoreboard(event, phs);
        }

        // Track online presence for disconnect grace period
        for (UUID uuid : phs.hunters) {
            if (server.getPlayerManager().getPlayer(uuid) != null) phs.lastSeenOnlineTick.put(uuid, currentTick);
        }
        for (UUID uuid : phs.hiders) {
            if (server.getPlayerManager().getPlayer(uuid) != null) phs.lastSeenOnlineTick.put(uuid, currentTick);
        }

        // Phase 1: Grace period — countdown for hunters release
        if (!phs.huntersReleased) {
            int remaining = phs.hideGraceSeconds - (int) elapsed;
            if (remaining <= 0) {
                // Release hunters!
                phs.huntersReleased = true;
                for (UUID hunterUuid : phs.hunters) {
                    if (phs.eliminatedHunters.contains(hunterUuid)) continue;
                    ServerPlayerEntity hunter = server.getPlayerManager().getPlayer(hunterUuid);
                    if (hunter == null) continue;

                    unfreezePlayer(hunter);
                    hunter.removeStatusEffect(Registries.STATUS_EFFECT.getEntry(
                        Registries.STATUS_EFFECT.get(Identifier.of("minecraft", "blindness"))));

                    // Set hunter health
                    var maxHealthAttr = hunter.getAttributeInstance(
                        net.minecraft.entity.attribute.EntityAttributes.GENERIC_MAX_HEALTH);
                    if (maxHealthAttr != null) maxHealthAttr.setBaseValue(phs.hunterStartHealth);
                    hunter.setHealth(phs.hunterStartHealth);

                    // Give weapons
                    giveHunterWeapons(hunter, phs);
                }
                broadcastText(event, Text.translatable("cheeseguard.prophunt.hunters_released")
                    .formatted(Formatting.RED));
                for (var p : event.getParticipants().values()) {
                    ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
                    if (pl != null) {
                        pl.playSound(net.minecraft.sound.SoundEvents.ENTITY_WITHER_SPAWN, 1f, 1f);
                    }
                }
            } else if (remaining <= 5) {
                broadcastText(event, Text.translatable("cheeseguard.prophunt.countdown", remaining)
                    .formatted(Formatting.YELLOW));
            }
        }

        // Phase 2: Check eliminations
        if (phs.huntersReleased) {
            // Check hunters (dead from penalties)
            for (UUID hunterUuid : phs.hunters) {
                if (phs.eliminatedHunters.contains(hunterUuid)) continue;
                ServerPlayerEntity hunter = server.getPlayerManager().getPlayer(hunterUuid);
                if (hunter != null && hunter.isDead()) {
                    phs.eliminatedHunters.add(hunterUuid);
                    state.eliminated.add(hunterUuid);
                    em.becomeEventSpectator(event, hunter);
                    broadcastText(event, Text.translatable("cheeseguard.prophunt.hunter_eliminated",
                        getParticipantName(event, hunterUuid)).formatted(Formatting.GREEN));
                } else if (hunter == null) {
                    // Offline — eliminate after 30s grace period
                    Long lastSeen = phs.lastSeenOnlineTick.get(hunterUuid);
                    if (lastSeen != null && currentTick - lastSeen > 600) {
                        phs.eliminatedHunters.add(hunterUuid);
                        state.eliminated.add(hunterUuid);
                        broadcastText(event, Text.translatable("cheeseguard.prophunt.hunter_eliminated",
                            getParticipantName(event, hunterUuid)).formatted(Formatting.GREEN));
                    }
                }
            }

            // Check hiders (killed by hunters)
            for (UUID hiderUuid : phs.hiders) {
                if (phs.eliminatedHiders.contains(hiderUuid)) continue;
                ServerPlayerEntity hider = server.getPlayerManager().getPlayer(hiderUuid);
                if (hider != null && hider.isDead()) {
                    phs.eliminatedHiders.add(hiderUuid);
                    state.eliminated.add(hiderUuid);
                    CobbleShiftHook.revertPlayer(hider);
                    em.becomeEventSpectator(event, hider);
                    // Award point to last attacker
                    if (hider.getLastAttacker() instanceof ServerPlayerEntity hunterKiller) {
                        em.addScore(event, hunterKiller.getUuid(), 1);
                    }
                    broadcastText(event, Text.translatable("cheeseguard.prophunt.hider_found",
                        getParticipantName(event, hiderUuid)).formatted(Formatting.GOLD));
                } else if (hider == null) {
                    // Offline — eliminate after 30s grace period
                    Long lastSeen = phs.lastSeenOnlineTick.get(hiderUuid);
                    if (lastSeen != null && currentTick - lastSeen > 600) {
                        phs.eliminatedHiders.add(hiderUuid);
                        state.eliminated.add(hiderUuid);
                        CobbleShiftHook.addPendingRevert(hiderUuid);
                        broadcastText(event, Text.translatable("cheeseguard.prophunt.hider_found",
                            getParticipantName(event, hiderUuid)).formatted(Formatting.GOLD));
                    }
                }
            }
        }

        // Win conditions
        int aliveHiders = phs.hiders.size() - phs.eliminatedHiders.size();
        int aliveHunters = phs.hunters.size() - phs.eliminatedHunters.size();

        if (phs.huntersReleased && aliveHiders <= 0) {
            // Hunters win: all hiders eliminated before timer
            broadcastText(event, Text.translatable("cheeseguard.prophunt.hunters_win")
                .formatted(Formatting.GOLD));
            for (UUID h : phs.hunters) {
                if (!phs.eliminatedHunters.contains(h)) em.addScore(event, h, 10);
            }
            broadcastPropHuntResults(event, phs, true);
            em.endEvent(event);
            return;
        }
        if (phs.huntersReleased && aliveHunters <= 0) {
            // Hiders win: all hunters eliminated
            broadcastText(event, Text.translatable("cheeseguard.prophunt.hiders_win")
                .formatted(Formatting.GREEN));
            for (UUID h : phs.hiders) {
                if (!phs.eliminatedHiders.contains(h)) em.addScore(event, h, 10);
            }
            broadcastPropHuntResults(event, phs, false);
            em.endEvent(event);
            return;
        }
        if (elapsed >= phs.gameDurationSeconds) {
            // Timeout: hiders win (any hider survived to the end)
            broadcastText(event, Text.translatable("cheeseguard.prophunt.timeout_win")
                .formatted(Formatting.GREEN));
            for (UUID h : phs.hiders) {
                if (!phs.eliminatedHiders.contains(h)) em.addScore(event, h, 10);
            }
            broadcastPropHuntResults(event, phs, false);
            em.endEvent(event);
            return;
        }

        // Actionbar for all participants (includes compass info for active compass holders)
        int timeLeft = phs.gameDurationSeconds - (int) elapsed;
        String mins = String.valueOf(timeLeft / 60);
        String secs = String.format("%02d", timeLeft % 60);
        String baseActionbar = "\u00a7c\uD83C\uDFF9 " + aliveHunters + "/" + phs.hunters.size()
            + "  \u00a7a\uD83D\uDC3E " + aliveHiders + "/" + phs.hiders.size()
            + "  \u00a7f\u23F1 " + mins + ":" + secs;
        for (var p : event.getParticipants().values()) {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (pl != null) {
                String playerActionbar = baseActionbar;
                // Append compass info if this player has an active compass
                String compassText = compassTexts.get(p.getPlayerUuid());
                if (compassText != null) {
                    playerActionbar += "  " + compassText;
                }
                pl.sendMessage(Text.literal(playerActionbar), true);
            }
        }
    }

    /**
     * Called from mixins when a player hits an entity.
     * Applies penalty to hunters who hit wild Pokémon (not players) in a Prop Hunt game.
     */
    public void onPropHuntEntityHit(ServerPlayerEntity attacker, Entity target) {
        // Skip if target is a player (player-as-pokémon → normal PVP, no penalty)
        if (target instanceof ServerPlayerEntity) return;

        String targetType = Registries.ENTITY_TYPE.getId(target.getType()).toString();
        if (!targetType.startsWith("cobblemon:")) return; // only penalize hitting Cobblemon entities

        for (var entry : propHuntStates.entrySet()) {
            String eventId = entry.getKey();
            PropHuntState phs = entry.getValue();

            if (!phs.huntersReleased) continue; // no penalty during grace
            if (!phs.hunters.contains(attacker.getUuid())) continue;
            if (phs.eliminatedHunters.contains(attacker.getUuid())) continue;

            // Rate-limit: max 1 penalty per second per hunter to avoid rapid-fire abuse
            long now = server.getTicks();
            Long lastPenalty = phs.lastPenaltyTick.get(attacker.getUuid());
            if (lastPenalty != null && now - lastPenalty < 20) continue;
            phs.lastPenaltyTick.put(attacker.getUuid(), now);

            // Apply penalty damage
            attacker.damage(attacker.getDamageSources().magic(), phs.penaltyHalfHearts);
            attacker.sendMessage(Text.translatable("cheeseguard.prophunt.penalty", phs.penaltyHalfHearts / 2)
                .formatted(Formatting.RED), true);
            attacker.playSound(net.minecraft.sound.SoundEvents.ENTITY_VILLAGER_NO, 1f, 0.8f);
            break; // only one active PropHunt per player
        }
    }

    /**
     * Called when a hunter attacks a hider in PropHunt.
     * Applies virtual damage to the hider's health pool.
     */
    public void onPropHuntPlayerHit(ServerPlayerEntity attacker, ServerPlayerEntity target) {
        for (var entry : propHuntStates.entrySet()) {
            PropHuntState phs = entry.getValue();
            if (!phs.huntersReleased) continue;
            if (!phs.hunters.contains(attacker.getUuid())) continue;
            if (!phs.hiders.contains(target.getUuid())) continue;
            if (phs.eliminatedHiders.contains(target.getUuid())) continue;
            if (phs.eliminatedHunters.contains(attacker.getUuid())) continue;

            // Rate-limit: max 1 hit per 0.5s per hunter to avoid rapid-fire
            long now = server.getTicks();
            Long lastHit = phs.lastPenaltyTick.get(attacker.getUuid());
            if (lastHit != null && now - lastHit < 10) break; // 0.5s cooldown between hits
            phs.lastPenaltyTick.put(attacker.getUuid(), now);

            // Check shield
            if (phs.shieldActive.contains(target.getUuid())) {
                phs.shieldActive.remove(target.getUuid());
                phs.shieldExpireTick.remove(target.getUuid());
                target.sendMessage(Text.translatable("cheeseguard.prophunt.shield_blocked").formatted(Formatting.GOLD), true);
                attacker.sendMessage(Text.translatable("cheeseguard.prophunt.shield_blocked_attacker").formatted(Formatting.YELLOW), true);
                target.playSound(net.minecraft.sound.SoundEvents.ITEM_SHIELD_BLOCK, 1f, 1f);
                // Knockback the attacker away from the shielded target
                double dx = attacker.getX() - target.getX();
                double dz = attacker.getZ() - target.getZ();
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len > 0) {
                    attacker.setVelocity(dx / len * 0.8, 0.3, dz / len * 0.8);
                    attacker.velocityModified = true;
                }
                break; // absorbed, no damage
            }

            // Apply fixed damage to hider virtual health
            Integer currentHealth = phs.hiderHealth.get(target.getUuid());
            if (currentHealth == null) currentHealth = phs.hiderStartHealth;
            currentHealth -= phs.hiderDamagePerHit;
            phs.hiderHealth.put(target.getUuid(), currentHealth);

            // Visual feedback
            int heartsLeft = Math.max(0, currentHealth / 2);
            target.sendMessage(Text.translatable("cheeseguard.prophunt.hider_hit", heartsLeft)
                .formatted(Formatting.RED), true);
            attacker.sendMessage(Text.translatable("cheeseguard.prophunt.hunter_hit_player",
                target.getName().getString(), heartsLeft)
                .formatted(Formatting.GREEN), true);
            target.playSound(net.minecraft.sound.SoundEvents.ENTITY_PLAYER_HURT, 1f, 1f);

            if (currentHealth <= 0) {
                // Hider eliminated — revert Pokémon form FIRST, then become Rotom spectator
                phs.eliminatedHiders.add(target.getUuid());
                EventManager em = CheeseGuardMod.getInstance().getEventManager();
                ServerEvent event2 = em.getEvent(entry.getKey());
                if (event2 != null) {
                    em.addScore(event2, attacker.getUuid(), 10);
                    broadcastText(event2, Text.translatable("cheeseguard.prophunt.hider_found",
                        target.getName().getString()).formatted(Formatting.YELLOW));
                    CobbleShiftHook.revertPlayer(target); // revert Pokémon form before Rotom
                    clearPropHuntItems(target);
                    em.becomeEventSpectator(event2, target); // applies Rotom + fly
                }
            }

            break;
        }
    }

    private void cleanupPropHunt(ServerEvent event, PropHuntState phs) {
        // Clear power HUD for all participants
        for (var p : event.getParticipants().values()) {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (pl != null) clearPowerHud(pl);
        }

        // Revert all transformations + remove Resistance effect from hiders
        for (UUID uuid : phs.hiders) {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(uuid);
            if (pl != null) {
                CobbleShiftHook.revertPlayer(pl);
                // Remove Resistance V granted during setup
                Identifier resistId = Identifier.of("minecraft", "resistance");
                StatusEffect resistEffect = Registries.STATUS_EFFECT.get(resistId);
                if (resistEffect != null) {
                    pl.removeStatusEffect(Registries.STATUS_EFFECT.getEntry(resistEffect));
                }
            } else {
                CobbleShiftHook.addPendingRevert(uuid);
            }
        }

        // Remove decoy entities via tag (tagged during spawn)
        try {
            server.getCommandManager().getDispatcher().execute(
                "kill @e[tag=ph_decoy]", server.getCommandSource().withSilent());
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.debug("[CG] PropHunt decoy cleanup command failed: {}", e.getMessage());
        }

        // Restore hunter max health
        for (UUID hunterUuid : phs.hunters) {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(hunterUuid);
            if (pl != null) {
                var maxHealthAttr = pl.getAttributeInstance(
                    net.minecraft.entity.attribute.EntityAttributes.GENERIC_MAX_HEALTH);
                if (maxHealthAttr != null) maxHealthAttr.setBaseValue(20.0);
                pl.setHealth(20f);
                unfreezePlayer(pl);
            }
        }

        // Clear zone flag overrides
        for (String zoneId : event.getScopeZoneIds()) {
            event.clearFlagOverride(zoneId + ":nametag-hide");
            event.clearFlagOverride(zoneId + ":minimap-hide");
            event.clearFlagOverride(zoneId + ":item-drop");
        }

        // Send explicit restore packets to all online participants (don't rely solely on ProtectionListener timing)
        for (var p : event.getParticipants().values()) {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (pl != null && net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(pl, com.cheeseguard.network.GuiSyncPayload.ID)) {
                try {
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(pl,
                        new com.cheeseguard.network.GuiSyncPayload("NAMETAG_ZONE", "{\"hide\":false}"));
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(pl,
                        new com.cheeseguard.network.GuiSyncPayload("MINIMAP_ZONE", "{\"hide\":false}"));
                } catch (Exception ignored) {}
            }
        }

        // Clean up trap, compass, invisibility, and new power state
        phs.activeTraps.clear();
        phs.compassEndTick.clear();
        phs.invisEndTick.clear();
        phs.echoEndTick.clear();
        phs.hunterMarkTarget.clear();
        phs.hunterMarkEndTick.clear();
        phs.smokescreenEndTick.clear();
        phs.smokescreenPos.clear();
        phs.shieldActive.clear();
        phs.shieldExpireTick.clear();
        phs.mimicEndTick.clear();
        // Kill mimic armor stands
        try { server.getCommandManager().getDispatcher().execute("kill @e[tag=ph_mimic]", server.getCommandSource().withSilent()); } catch (Exception ignored) {}
        // Re-transform any currently invisible hiders
        for (var invisEntry : phs.invisSavedSpecies.entrySet()) {
            ServerPlayerEntity hider = server.getPlayerManager().getPlayer(invisEntry.getKey());
            if (hider != null) {
                CobbleShiftHook.transformPlayer(hider, invisEntry.getValue(), false);
            }
        }
        phs.invisSavedSpecies.clear();

        // Clear all PropHunt items (papers, weapons, arrows) from participants
        for (var p : event.getParticipants().values()) {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (pl != null) {
                clearPropHuntItems(pl);
            }
        }

        // Remove live sidebar scoreboard
        removePropHuntScoreboard(event);

        CheeseGuardMod.LOGGER.info("[CG] Prop Hunt cleaned up for '{}'", event.getName());
    }

    // ── Prop Hunt helpers ────────────────────────────────────────────────────

    private void giveTransformItems(ServerPlayerEntity player, PropHuntState phs) {
        for (int i = 0; i < phs.speciesEntries.size() && i < 8; i++) { // max 8 items in hotbar
            PropHuntSpeciesEntry entry = phs.speciesEntries.get(i);
            if (entry.species.isEmpty()) continue;

            ItemStack stack;
            if (!entry.itemId.isEmpty()) {
                // Use specified item as base
                var item = Registries.ITEM.get(Identifier.of(entry.itemId));
                stack = new ItemStack(item != Items.AIR ? item : Items.PAPER);
            } else {
                stack = new ItemStack(Items.PAPER);
            }

            // Custom name
            String capitalize = entry.species.substring(0, 1).toUpperCase() + entry.species.substring(1);
            stack.set(DataComponentTypes.CUSTOM_NAME,
                Text.literal(capitalize).formatted(Formatting.AQUA));

            // Lore
            stack.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(
                java.util.List.of(Text.translatable("cheeseguard.prophunt.paper_lore").formatted(Formatting.GRAY))));

            // Custom data tag for detection (replaces item type check)
            net.minecraft.nbt.NbtCompound nbt = new net.minecraft.nbt.NbtCompound();
            nbt.putString("prophunt_species", entry.species);
            stack.set(DataComponentTypes.CUSTOM_DATA, net.minecraft.component.type.NbtComponent.of(nbt));

            // CustomModelData for custom icon (if uploaded)
            if (entry.hasCustomIcon) {
                PropHuntIconStorage icons = CheeseGuardMod.getInstance().getPropHuntIconStorage();
                if (icons != null) {
                    int cmd = icons.getCustomModelData(sanitizeSpeciesKey(entry.species));
                    if (cmd >= 0) {
                        stack.set(DataComponentTypes.CUSTOM_MODEL_DATA,
                            new net.minecraft.component.type.CustomModelDataComponent(cmd));
                    }
                }
            }

            player.getInventory().insertStack(stack);
        }
    }

    /**
     * Called from SwapOffhandMixin when a PropHunt player presses F (swap offhand).
     * Returns true if the action was consumed (player is in PropHunt and power was activated or on cooldown).
     */
    public boolean activatePower(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        for (var entry : propHuntStates.entrySet()) {
            PropHuntState phs = entry.getValue();
            String eventId = entry.getKey();

            // Check if hunter
            if (phs.hunters.contains(uuid) && !phs.eliminatedHunters.contains(uuid) && !phs.hunterPowersEnabled.isEmpty()) {
                // Get current power from player's available list
                List<HunterPower> available = phs.hunterAvailablePowers.get(uuid);
                if (available == null || available.isEmpty()) return true;
                int powerIdx = phs.hunterPowerIndex.getOrDefault(uuid, 0);
                HunterPower power = available.get(powerIdx % available.size());

                // Per-power cooldown check
                long now = server.getTicks();
                Map<String, Long> playerCooldowns = phs.hunterPowerLastUsed.computeIfAbsent(uuid, k -> new HashMap<>());
                Long lastUsed = playerCooldowns.get(power.name());
                int cooldownTicks = phs.hunterPowerCooldowns.getOrDefault(power.name(), 600);
                if (lastUsed != null && now - lastUsed < cooldownTicks) {
                    int remaining = (int) ((cooldownTicks - (now - lastUsed)) / 20);
                    player.sendMessage(Text.translatable("cheeseguard.prophunt.power_cooldown", remaining)
                        .formatted(Formatting.RED), true);
                    return true;
                }
                playerCooldowns.put(power.name(), now);

                EventManager em = CheeseGuardMod.getInstance().getEventManager();
                ServerEvent event = em.getEvent(eventId);
                if (event != null) {
                    executePower(power, player, phs, event);
                    sendPowerHud(player, power.translationKey, power.icon, power.descKey, cooldownTicks / 20, 0, false);
                }
                return true;
            }

            // Check if hider
            if (phs.hiders.contains(uuid) && !phs.eliminatedHiders.contains(uuid) && !phs.hiderPowersEnabled.isEmpty()) {
                // Get current power from player's available list
                List<HiderPower> available = phs.hiderAvailablePowers.get(uuid);
                if (available == null || available.isEmpty()) return true;
                int powerIdx = phs.hiderPowerIndex.getOrDefault(uuid, 0);
                HiderPower power = available.get(powerIdx % available.size());

                // Per-power cooldown check
                long now = server.getTicks();
                Map<String, Long> playerCooldowns = phs.hiderPowerLastUsed.computeIfAbsent(uuid, k -> new HashMap<>());
                Long lastUsed = playerCooldowns.get(power.name());
                int cooldownTicks = phs.hiderPowerCooldowns.getOrDefault(power.name(), 400);
                if (lastUsed != null && now - lastUsed < cooldownTicks) {
                    int remaining = (int) ((cooldownTicks - (now - lastUsed)) / 20);
                    player.sendMessage(Text.translatable("cheeseguard.prophunt.power_cooldown", remaining)
                        .formatted(Formatting.RED), true);
                    return true;
                }
                playerCooldowns.put(power.name(), now);

                EventManager em = CheeseGuardMod.getInstance().getEventManager();
                ServerEvent event = em.getEvent(eventId);
                if (event != null) {
                    executeHiderPower(power, player, phs, event);
                    sendPowerHud(player, power.translationKey, power.icon, power.descKey, cooldownTicks / 20, 0, false);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Cycle to the next power. Called from C2S packet when player middle-clicks.
     */
    public void cyclePower(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        for (var entry : propHuntStates.entrySet()) {
            PropHuntState phs = entry.getValue();

            if (phs.hunters.contains(uuid) && !phs.eliminatedHunters.contains(uuid) && !phs.hunterPowersEnabled.isEmpty()) {
                List<HunterPower> available = phs.hunterAvailablePowers.get(uuid);
                if (available == null || available.size() <= 1) return; // nothing to cycle
                int currentIdx = phs.hunterPowerIndex.getOrDefault(uuid, 0);
                int nextIdx = (currentIdx + 1) % available.size();
                phs.hunterPowerIndex.put(uuid, nextIdx);
                HunterPower power = available.get(nextIdx);
                // Check this power's specific cooldown
                Map<String, Long> playerCooldowns = phs.hunterPowerLastUsed.computeIfAbsent(uuid, k -> new HashMap<>());
                Long lastUsed = playerCooldowns.get(power.name());
                int cooldownTicks = phs.hunterPowerCooldowns.getOrDefault(power.name(), 600);
                int remaining = 0; boolean ready = true;
                if (lastUsed != null) {
                    long elapsed = server.getTicks() - lastUsed;
                    if (elapsed < cooldownTicks) { remaining = (int)((cooldownTicks - elapsed) / 20); ready = false; }
                }
                sendPowerHud(player, power.translationKey, power.icon, power.descKey, cooldownTicks / 20, remaining, ready);
                player.playSound(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.2f);
                return;
            }

            if (phs.hiders.contains(uuid) && !phs.eliminatedHiders.contains(uuid) && !phs.hiderPowersEnabled.isEmpty()) {
                List<HiderPower> available = phs.hiderAvailablePowers.get(uuid);
                if (available == null || available.size() <= 1) return; // nothing to cycle
                int currentIdx = phs.hiderPowerIndex.getOrDefault(uuid, 0);
                int nextIdx = (currentIdx + 1) % available.size();
                phs.hiderPowerIndex.put(uuid, nextIdx);
                HiderPower power = available.get(nextIdx);
                // Check this power's specific cooldown
                Map<String, Long> playerCooldowns = phs.hiderPowerLastUsed.computeIfAbsent(uuid, k -> new HashMap<>());
                Long lastUsed = playerCooldowns.get(power.name());
                int cooldownTicks = phs.hiderPowerCooldowns.getOrDefault(power.name(), 400);
                int remaining = 0; boolean ready = true;
                if (lastUsed != null) {
                    long elapsed = server.getTicks() - lastUsed;
                    if (elapsed < cooldownTicks) { remaining = (int)((cooldownTicks - elapsed) / 20); ready = false; }
                }
                sendPowerHud(player, power.translationKey, power.icon, power.descKey, cooldownTicks / 20, remaining, ready);
                player.playSound(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.2f);
                return;
            }
        }
    }

    private void sendPowerHud(ServerPlayerEntity player, String nameKey, String icon, String descKey, int cooldownTotal, int cooldownRemaining, boolean ready) {
        if (!net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, com.cheeseguard.network.GuiSyncPayload.ID)) return;
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("nameKey", nameKey);
        json.addProperty("icon", icon);
        json.addProperty("descKey", descKey);
        json.addProperty("cooldownTotal", cooldownTotal);
        json.addProperty("cooldownRemaining", cooldownRemaining);
        json.addProperty("ready", ready);
        try {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new com.cheeseguard.network.GuiSyncPayload("PROPHUNT_POWER", json.toString()));
        } catch (Exception ignored) {}
    }

    private void clearPowerHud(ServerPlayerEntity player) {
        if (!net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, com.cheeseguard.network.GuiSyncPayload.ID)) return;
        try {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new com.cheeseguard.network.GuiSyncPayload("PROPHUNT_POWER", "{\"clear\":true}"));
        } catch (Exception ignored) {}
    }

    private void executePower(HunterPower power, ServerPlayerEntity hunter, PropHuntState phs, ServerEvent event) {
        // Announce power activation
        broadcastText(event, Text.translatable("cheeseguard.prophunt.power_used",
            hunter.getName().getString(), Text.translatable(power.translationKey))
            .formatted(Formatting.LIGHT_PURPLE));
        hunter.playSound(net.minecraft.sound.SoundEvents.BLOCK_BEACON_ACTIVATE, 1f, 1.5f);
        // Brief title flash showing power name
        hunter.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket(0, 10, 5));
        hunter.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleS2CPacket(
            Text.literal(power.icon).formatted(Formatting.LIGHT_PURPLE)));
        hunter.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.SubtitleS2CPacket(
            Text.translatable(power.translationKey).formatted(Formatting.GRAY)));

        switch (power) {
            case SONAR -> executeSonar(phs);
            case SCRAMBLE -> executeScramble(phs);
            case REVEAL -> executeReveal(phs);
            case FREEZE -> executeFreeze(phs);
            case COMPASS -> executeCompass(hunter, phs);
            case TRAP -> executeTrap(hunter, phs);
            case ECHOLOCATION -> executeEcholocation(hunter, phs);
            case FLARE -> executeFlare(hunter, phs);
            case SNIFF -> executeSniff(hunter, phs);
            case HUNTER_MARK -> executeHunterMark(hunter, phs);
        }
    }

    private void executeSonar(PropHuntState phs) {
        for (UUID hiderUuid : phs.hiders) {
            if (phs.eliminatedHiders.contains(hiderUuid)) continue;
            ServerPlayerEntity hider = server.getPlayerManager().getPlayer(hiderUuid);
            if (hider == null) continue;
            String species = phs.hiderSpecies.getOrDefault(hiderUuid, "pikachu").split(" ")[0].toLowerCase();
            double x = hider.getX(), y = hider.getY(), z = hider.getZ();

            // Try multiple Cobblemon cry sound formats — the exact format varies by version
            boolean cryPlayed = false;
            String[] formats = {
                "cobblemon:pokemon." + species + ".cry",
                "cobblemon:" + species + ".cry",
                "cobblemon:cry." + species
            };
            for (String soundId : formats) {
                try {
                    String cmd = "playsound " + soundId + " player @a " + x + " " + y + " " + z + " 3 1";
                    server.getCommandManager().getDispatcher().execute(cmd, server.getCommandSource().withSilent());
                    cryPlayed = true;
                    break;
                } catch (Exception ignored) {}
            }

            // Fallback: distinctive detection sound that always works
            if (!cryPlayed) {
                try {
                    server.getCommandManager().getDispatcher().execute(
                        "playsound minecraft:entity.guardian.curse player @a " + x + " " + y + " " + z + " 3 1.2",
                        server.getCommandSource().withSilent());
                } catch (Exception ignored) {}
            }

            // Visual feedback: note particles above hider position
            hider.getServerWorld().spawnParticles(
                ParticleTypes.NOTE,
                x, y + 2.2, z, 5, 0.3, 0.3, 0.3, 0.5);

            // Notify hider that their position was detected
            hider.sendMessage(Text.translatable("cheeseguard.prophunt.sonar_detected")
                .formatted(Formatting.RED), true);
        }
    }

    private void executeScramble(PropHuntState phs) {
        Random random = new Random();
        for (UUID hiderUuid : phs.hiders) {
            if (phs.eliminatedHiders.contains(hiderUuid)) continue;
            ServerPlayerEntity hider = server.getPlayerManager().getPlayer(hiderUuid);
            if (hider == null) continue;
            String currentSpecies = phs.hiderSpecies.get(hiderUuid);
            // Pick a different species
            List<String> others = new ArrayList<>(phs.speciesList);
            others.remove(currentSpecies);
            if (others.isEmpty()) others = new ArrayList<>(phs.speciesList);
            String newSpecies = others.get(random.nextInt(others.size()));
            CobbleShiftHook.transformPlayer(hider, newSpecies, phs.attacksEnabled);
            phs.hiderSpecies.put(hiderUuid, newSpecies);
            hider.sendMessage(Text.translatable("cheeseguard.prophunt.scrambled", newSpecies)
                .formatted(Formatting.YELLOW), true);
        }
    }

    private void executeReveal(PropHuntState phs) {
        Identifier glowingId = Identifier.of("minecraft", "glowing");
        StatusEffect glowingEffect = Registries.STATUS_EFFECT.get(glowingId);
        if (glowingEffect == null) return;
        for (UUID hiderUuid : phs.hiders) {
            if (phs.eliminatedHiders.contains(hiderUuid)) continue;
            ServerPlayerEntity hider = server.getPlayerManager().getPlayer(hiderUuid);
            if (hider == null) continue;
            hider.addStatusEffect(new StatusEffectInstance(
                Registries.STATUS_EFFECT.getEntry(glowingEffect),
                40, 0, false, false, true)); // 2 seconds
            hider.sendMessage(Text.translatable("cheeseguard.prophunt.revealed")
                .formatted(Formatting.RED), true);
        }
    }

    private void executeFreeze(PropHuntState phs) {
        for (UUID hiderUuid : phs.hiders) {
            if (phs.eliminatedHiders.contains(hiderUuid)) continue;
            ServerPlayerEntity hider = server.getPlayerManager().getPlayer(hiderUuid);
            if (hider == null) continue;
            freezePlayer(hider);
            hider.sendMessage(Text.translatable("cheeseguard.prophunt.frozen")
                .formatted(Formatting.AQUA), true);
            // Ice particles around frozen hider
            hider.getServerWorld().spawnParticles(
                ParticleTypes.SNOWFLAKE,
                hider.getX(), hider.getY() + 1, hider.getZ(),
                15, 0.5, 0.5, 0.5, 0.02);
        }
        phs.unfreezeAtTick = server.getTicks() + 60; // 3 seconds
    }

    private void executeCompass(ServerPlayerEntity hunter, PropHuntState phs) {
        // Start compass tracking for 5 seconds
        phs.compassEndTick.put(hunter.getUuid(), (long) server.getTicks() + 100);
        hunter.sendMessage(Text.translatable("cheeseguard.prophunt.compass_active")
            .formatted(Formatting.AQUA), true);
    }

    private void executeTrap(ServerPlayerEntity hunter, PropHuntState phs) {
        String worldId = hunter.getServerWorld().getRegistryKey().getValue().toString();
        // Limit to 3 traps per hunter: remove oldest if at cap
        Iterator<TrapEntry> it = phs.activeTraps.iterator();
        int count = 0;
        TrapEntry oldest = null;
        while (it.hasNext()) {
            TrapEntry t = it.next();
            if (t.hunterUuid.equals(hunter.getUuid())) {
                count++;
                if (oldest == null) oldest = t;
            }
        }
        if (count >= 3 && oldest != null) {
            phs.activeTraps.remove(oldest);
        }
        phs.activeTraps.add(new TrapEntry(hunter.getX(), hunter.getY(), hunter.getZ(), worldId, hunter.getUuid()));
        hunter.sendMessage(Text.translatable("cheeseguard.prophunt.trap_placed")
            .formatted(Formatting.GREEN), true);
        // Visual feedback: particles at trap position + sound
        hunter.getServerWorld().spawnParticles(
            ParticleTypes.ENCHANT,
            hunter.getX(), hunter.getY() + 0.5, hunter.getZ(),
            20, 0.5, 0.3, 0.5, 0.05);
        hunter.playSound(net.minecraft.sound.SoundEvents.BLOCK_TRIPWIRE_ATTACH, 1f, 1f);
    }

    private void executeEcholocation(ServerPlayerEntity hunter, PropHuntState phs) {
        // Particles at hider positions for 3 seconds
        phs.echoEndTick.put(hunter.getUuid(), (long) server.getTicks() + 60);
        hunter.sendMessage(Text.translatable("cheeseguard.prophunt.echo_active").formatted(Formatting.AQUA), true);
    }

    private void executeFlare(ServerPlayerEntity hunter, PropHuntState phs) {
        // Glowing 1s on hiders within 15 blocks
        Identifier glowId = Identifier.of("minecraft", "glowing");
        StatusEffect glowEffect = Registries.STATUS_EFFECT.get(glowId);
        if (glowEffect == null) return;
        int found = 0;
        for (UUID hiderUuid : phs.hiders) {
            if (phs.eliminatedHiders.contains(hiderUuid)) continue;
            ServerPlayerEntity hider = server.getPlayerManager().getPlayer(hiderUuid);
            if (hider == null || hider.getServerWorld() != hunter.getServerWorld()) continue;
            if (hunter.squaredDistanceTo(hider) <= 225) { // 15 blocks squared
                hider.addStatusEffect(new StatusEffectInstance(
                    Registries.STATUS_EFFECT.getEntry(glowEffect), 20, 0, false, false, true));
                hider.sendMessage(Text.translatable("cheeseguard.prophunt.revealed").formatted(Formatting.RED), true);
                found++;
            }
        }
        hunter.sendMessage(Text.translatable("cheeseguard.prophunt.flare_result", found).formatted(Formatting.YELLOW), false);
    }

    private void executeSniff(ServerPlayerEntity hunter, PropHuntState phs) {
        int count = 0;
        for (UUID hiderUuid : phs.hiders) {
            if (phs.eliminatedHiders.contains(hiderUuid)) continue;
            ServerPlayerEntity hider = server.getPlayerManager().getPlayer(hiderUuid);
            if (hider == null || hider.getServerWorld() != hunter.getServerWorld()) continue;
            if (hunter.squaredDistanceTo(hider) <= 100) count++; // 10 blocks squared
        }
        hunter.sendMessage(Text.translatable("cheeseguard.prophunt.sniff_result", count).formatted(Formatting.YELLOW), false);
    }

    private void executeHunterMark(ServerPlayerEntity hunter, PropHuntState phs) {
        // Pick random alive hider, mark with particles for 5s
        List<UUID> aliveHiders = new ArrayList<>();
        for (UUID u : phs.hiders) {
            if (!phs.eliminatedHiders.contains(u) && server.getPlayerManager().getPlayer(u) != null) aliveHiders.add(u);
        }
        if (aliveHiders.isEmpty()) { hunter.sendMessage(Text.translatable("cheeseguard.prophunt.mark_no_targets").formatted(Formatting.RED), true); return; }
        UUID target = aliveHiders.get(new Random().nextInt(aliveHiders.size()));
        phs.hunterMarkTarget.put(hunter.getUuid(), target);
        phs.hunterMarkEndTick.put(hunter.getUuid(), (long) server.getTicks() + 100); // 5 seconds
        hunter.sendMessage(Text.translatable("cheeseguard.prophunt.mark_active").formatted(Formatting.GOLD), true);
        // Show target name to hunter
        ServerPlayerEntity markedPlayer = server.getPlayerManager().getPlayer(target);
        if (markedPlayer != null) {
            hunter.sendMessage(Text.translatable("cheeseguard.prophunt.mark_target_name",
                markedPlayer.getName().getString()).formatted(Formatting.GOLD), false);
            // Warn the marked target
            markedPlayer.sendMessage(Text.translatable("cheeseguard.prophunt.mark_warning")
                .formatted(Formatting.RED), true);
        }
    }

    private void executeHiderPower(HiderPower power, ServerPlayerEntity hider, PropHuntState phs, ServerEvent event) {
        // Hider powers activate silently — no broadcast
        hider.playSound(net.minecraft.sound.SoundEvents.BLOCK_BEACON_ACTIVATE, 1f, 1.5f);
        // Brief title flash showing power name
        hider.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket(0, 10, 5));
        hider.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleS2CPacket(
            Text.literal(power.icon).formatted(Formatting.LIGHT_PURPLE)));
        hider.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.SubtitleS2CPacket(
            Text.translatable(power.translationKey).formatted(Formatting.GRAY)));

        switch (power) {
            case DECOY -> executeDecoy(hider, phs);
            case INVISIBILITY -> executeInvisibility(hider, phs);
            case SMOKESCREEN -> executeSmokescreen(hider, phs);
            case MIMIC -> executeMimic(hider, phs);
            case TAUNT -> executeTaunt(hider, phs);
            case SHIELD -> executeShield(hider, phs);
            case HASTE -> executeHaste(hider);
            case DISTRACT -> executeDistract(hider, phs);
        }
    }

    private void executeDecoy(ServerPlayerEntity hider, PropHuntState phs) {
        String species = phs.hiderSpecies.getOrDefault(hider.getUuid(), "pikachu");
        double x = hider.getX(), y = hider.getY(), z = hider.getZ();
        Random random = new Random();
        int spawned = 0;
        // Use hider as command source (Cobblemon needs entity context for spawnpokemonat)
        var cmdSource = hider.getCommandSource().withLevel(4).withSilent();
        for (int i = 0; i < 3; i++) {
            double ox = x + (random.nextDouble() * 10 - 5);
            double oz = z + (random.nextDouble() * 10 - 5);
            try {
                String cmd = "spawnpokemonat " + (int)ox + " " + (int)y + " " + (int)oz + " " + species;
                server.getCommandManager().getDispatcher().execute(cmd, cmdSource);
                // Tag as decoy for cleanup (run on next tick to ensure entity exists)
                final double tagX = ox;
                final double tagZ = oz;
                final double tagY = y;
                server.execute(() -> {
                    try {
                        server.getCommandManager().getDispatcher().execute(
                            "tag @e[type=cobblemon:pokemon,x=" + (int)tagX + ",y=" + (int)(tagY-2) + ",z=" + (int)tagZ + ",dx=2,dy=5,dz=2,tag=!ph_decoy,limit=1] add ph_decoy",
                            cmdSource);
                    } catch (Exception ignored) {}
                });
                spawned++;
            } catch (Exception e) {
                CheeseGuardMod.LOGGER.warn("[CG] PropHunt decoy power spawn failed for {}: {}", species, e.getMessage());
            }
        }
        hider.sendMessage(Text.translatable("cheeseguard.prophunt.decoy_spawned", spawned)
            .formatted(Formatting.GREEN), true);
    }

    private void executeInvisibility(ServerPlayerEntity hider, PropHuntState phs) {
        String species = phs.hiderSpecies.getOrDefault(hider.getUuid(), "pikachu");
        phs.invisSavedSpecies.put(hider.getUuid(), species);
        phs.invisEndTick.put(hider.getUuid(), (long) server.getTicks() + 100); // 5 seconds

        // Apply invisibility FIRST — then revert transform so the human form is never visible
        Identifier invisId = Identifier.of("minecraft", "invisibility");
        StatusEffect invisEffect = Registries.STATUS_EFFECT.get(invisId);
        if (invisEffect != null) {
            hider.addStatusEffect(new StatusEffectInstance(
                Registries.STATUS_EFFECT.getEntry(invisEffect),
                110, 0, false, false, false)); // 5.5s (slightly longer to cover the revert window)
        }
        // Small delay before revert to ensure invisibility packet reaches clients first
        server.execute(() -> {
            CobbleShiftHook.revertPlayer(hider);
        });

        hider.sendMessage(Text.translatable("cheeseguard.prophunt.invis_active")
            .formatted(Formatting.AQUA), true);
    }

    private void executeSmokescreen(ServerPlayerEntity hider, PropHuntState phs) {
        phs.smokescreenEndTick.put(hider.getUuid(), (long) server.getTicks() + 80); // 4 seconds
        phs.smokescreenPos.put(hider.getUuid(), new double[]{hider.getX(), hider.getY(), hider.getZ()});
        hider.sendMessage(Text.translatable("cheeseguard.prophunt.smoke_active").formatted(Formatting.GRAY), true);
    }

    private void executeMimic(ServerPlayerEntity hider, PropHuntState phs) {
        // Invisible + frozen + spawn armor stand with block
        // Revert + invisibility on same tick (network packets batched)
        CobbleShiftHook.revertPlayer(hider);
        Identifier invisId = Identifier.of("minecraft", "invisibility");
        StatusEffect invisEffect = Registries.STATUS_EFFECT.get(invisId);
        if (invisEffect != null) {
            hider.addStatusEffect(new StatusEffectInstance(
                Registries.STATUS_EFFECT.getEntry(invisEffect), 110, 0, false, false, false)); // 5.5s to cover duration
        }
        freezePlayer(hider);
        // Re-apply slowness with longer duration to cover full mimic (freezePlayer sets 40 ticks, we need 110)
        var slowOpt = Registries.STATUS_EFFECT.getEntry(ID_SLOWNESS);
        slowOpt.ifPresent(e -> hider.addStatusEffect(new StatusEffectInstance(e, 110, 254, false, false, false)));
        // Use the block under the player for more convincing mimic
        BlockState groundBlock = hider.getServerWorld().getBlockState(hider.getBlockPos().down());
        String blockId = Registries.BLOCK.getId(groundBlock.getBlock()).toString();
        // Fallback to stone if air or non-solid
        if (groundBlock.isAir() || !groundBlock.isSolidBlock(hider.getServerWorld(), hider.getBlockPos().down())) {
            blockId = "minecraft:stone";
        }
        // Spawn armor stand with ground block head at player position
        try {
            String cmd = "summon minecraft:armor_stand " + hider.getX() + " " + hider.getY() + " " + hider.getZ()
                + " {Invisible:1b,NoGravity:1b,Small:0b,Tags:[\"ph_mimic\"],ArmorItems:[{},{},{},{id:\"" + blockId + "\",count:1}]}";
            server.getCommandManager().getDispatcher().execute(cmd, server.getCommandSource().withSilent());
        } catch (Exception ignored) {}
        String species = phs.hiderSpecies.getOrDefault(hider.getUuid(), "pikachu");
        phs.invisSavedSpecies.put(hider.getUuid(), species);
        phs.mimicEndTick.put(hider.getUuid(), (long) server.getTicks() + 100); // 5 seconds
        hider.sendMessage(Text.translatable("cheeseguard.prophunt.mimic_active").formatted(Formatting.GRAY), true);
    }

    private void executeTaunt(ServerPlayerEntity hider, PropHuntState phs) {
        String species = phs.hiderSpecies.getOrDefault(hider.getUuid(), "pikachu");
        Random rng = new Random();
        // Play cry from 4 random positions
        for (int i = 0; i < 4; i++) {
            double rx = hider.getX() + (rng.nextDouble() * 60 - 30);
            double rz = hider.getZ() + (rng.nextDouble() * 60 - 30);
            double ry = hider.getY();
            try {
                String cmd = "playsound cobblemon:pokemon." + species.split(" ")[0].toLowerCase()
                    + ".cry player @a " + rx + " " + ry + " " + rz + " 3 1";
                server.getCommandManager().getDispatcher().execute(cmd, server.getCommandSource().withSilent());
            } catch (Exception e) {
                try {
                    server.getCommandManager().getDispatcher().execute(
                        "playsound minecraft:block.note_block.pling player @a " + rx + " " + ry + " " + rz + " 3 1",
                        server.getCommandSource().withSilent());
                } catch (Exception ignored) {}
            }
        }
        hider.sendMessage(Text.translatable("cheeseguard.prophunt.taunt_active").formatted(Formatting.GREEN), false);
    }

    private void executeShield(ServerPlayerEntity hider, PropHuntState phs) {
        phs.shieldActive.add(hider.getUuid());
        phs.shieldExpireTick.put(hider.getUuid(), (long) server.getTicks() + 1200); // 60 seconds
        hider.sendMessage(Text.translatable("cheeseguard.prophunt.shield_active").formatted(Formatting.GOLD), true);
        hider.playSound(net.minecraft.sound.SoundEvents.ITEM_SHIELD_BLOCK, 1f, 1f);
        // Visual: golden particles around hider
        hider.getServerWorld().spawnParticles(
            ParticleTypes.END_ROD,
            hider.getX(), hider.getY() + 1, hider.getZ(),
            20, 0.5, 0.5, 0.5, 0.05);
    }

    private void executeHaste(ServerPlayerEntity hider) {
        Identifier speedId = Identifier.of("minecraft", "speed");
        StatusEffect speedEffect = Registries.STATUS_EFFECT.get(speedId);
        if (speedEffect != null) {
            hider.addStatusEffect(new StatusEffectInstance(
                Registries.STATUS_EFFECT.getEntry(speedEffect), 100, 2, false, true, true)); // 5s, Speed III
        }
        hider.sendMessage(Text.translatable("cheeseguard.prophunt.haste_active").formatted(Formatting.AQUA), true);
    }

    private void executeDistract(ServerPlayerEntity hider, PropHuntState phs) {
        Random rng = new Random();
        // Spawn 3 fireworks at random directions around the hider to create a convincing distraction
        for (int fw = 0; fw < 3; fw++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double dist = 15 + rng.nextDouble() * 20; // 15-35 blocks away
            double fx = hider.getX() + Math.cos(angle) * dist;
            double fz = hider.getZ() + Math.sin(angle) * dist;
            double fy = hider.getY();
            try {
                // 1.21.1 format: firework_item (not FireworksItem), LifeTime controls detonation delay
                int color1 = rng.nextInt(0xFFFFFF);
                int color2 = rng.nextInt(0xFFFFFF);
                String cmd = "summon minecraft:firework_rocket " + (int)fx + " " + (int)fy + " " + (int)fz
                    + " {LifeTime:20,firework_item:{id:\"minecraft:firework_rocket\",count:1,components:{\"minecraft:fireworks\":{explosions:[{shape:\"small_ball\",colors:[I;" + color1 + "," + color2 + "],has_trail:1b}],flight_duration:2}}}}";
                var src = hider.getCommandSource().withLevel(4).withSilent();
                server.getCommandManager().getDispatcher().execute(cmd, src);
            } catch (Exception e) {
                CheeseGuardMod.LOGGER.warn("[CG] PropHunt distract firework failed: {}", e.getMessage());
            }
        }
        // Also play a loud sound at the distraction area for audio cue
        try {
            double angle = rng.nextDouble() * Math.PI * 2;
            double dist = 25;
            String particleCmd = "particle minecraft:firework " +
                (int)(hider.getX() + Math.cos(angle) * dist) + " " + (int)(hider.getY() + 5) + " " +
                (int)(hider.getZ() + Math.sin(angle) * dist) + " 5 5 5 0.3 100 force";
            server.getCommandManager().getDispatcher().execute(particleCmd, hider.getCommandSource().withLevel(4).withSilent());
        } catch (Exception ignored) {}
        hider.sendMessage(Text.translatable("cheeseguard.prophunt.distract_active").formatted(Formatting.YELLOW), true);
    }

    private static String getCardinalDirection(ServerPlayerEntity from, ServerPlayerEntity to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double angle = Math.toDegrees(Math.atan2(-dx, dz)); // MC coordinate system
        if (angle < 0) angle += 360;
        if (angle < 22.5 || angle >= 337.5) return "S";
        if (angle < 67.5) return "SW";
        if (angle < 112.5) return "W";
        if (angle < 157.5) return "NW";
        if (angle < 202.5) return "N";
        if (angle < 247.5) return "NE";
        if (angle < 292.5) return "E";
        return "SE";
    }

    private static String sanitizeSpeciesKey(String species) {
        return species.toLowerCase().replaceAll("[^a-z0-9]", "_").replaceAll("_+", "_");
    }

    private void clearPropHuntItems(ServerPlayerEntity player) {
        // Clear items by prophunt_species custom data tag
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            net.minecraft.component.type.NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
            if (comp != null) {
                net.minecraft.nbt.NbtCompound cnbt = comp.copyNbt();
                if (cnbt.contains("prophunt_species")) {
                    player.getInventory().setStack(i, ItemStack.EMPTY);
                }
            }
        }
        // Also clear hunter weapons + arrows
        String name = player.getGameProfile().getName();
        String[] hunterItems = {"minecraft:bow", "minecraft:crossbow", "minecraft:iron_sword", "minecraft:arrow"};
        for (String item : hunterItems) {
            try {
                server.getCommandManager().getDispatcher().execute(
                    "clear " + name + " " + item, server.getCommandSource().withSilent());
            } catch (Exception ignored) {}
        }
    }

    private void giveHunterWeapons(ServerPlayerEntity hunter, PropHuntState phs) {
        String name = hunter.getGameProfile().getName();
        try {
            if ("bow".equals(phs.hunterWeapon) || "bow_sword".equals(phs.hunterWeapon)) {
                String bowCmd = "give " + name + " minecraft:bow[unbreakable={}] 1";
                server.getCommandManager().getDispatcher().execute(bowCmd,
                    server.getCommandSource().withSilent());
                String arrowCmd = "give " + name + " minecraft:arrow " + phs.hunterArrowCount;
                server.getCommandManager().getDispatcher().execute(arrowCmd,
                    server.getCommandSource().withSilent());
            }
            if ("crossbow".equals(phs.hunterWeapon)) {
                String xbowCmd = "give " + name + " minecraft:crossbow[unbreakable={}] 1";
                server.getCommandManager().getDispatcher().execute(xbowCmd,
                    server.getCommandSource().withSilent());
                String arrowCmd = "give " + name + " minecraft:arrow " + phs.hunterArrowCount;
                server.getCommandManager().getDispatcher().execute(arrowCmd,
                    server.getCommandSource().withSilent());
            }
            if ("sword".equals(phs.hunterWeapon) || "bow_sword".equals(phs.hunterWeapon)) {
                String swordCmd = "give " + name + " minecraft:iron_sword[unbreakable={}] 1";
                server.getCommandManager().getDispatcher().execute(swordCmd,
                    server.getCommandSource().withSilent());
            }
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.debug("[CG] PropHunt give hunter weapons failed: {}", e.getMessage());
        }
    }

    private void spawnDecoys(ServerEvent event, PropHuntState phs) {
        List<String> zones = phs.spawnZoneIds.isEmpty() ? event.getScopeZoneIds() : phs.spawnZoneIds;
        if (zones.isEmpty() || phs.speciesList.isEmpty()) {
            CheeseGuardMod.LOGGER.warn("[CG] PropHunt: no spawn zones or species configured, skipping decoys");
            return;
        }

        // Total decoys = decoyCountPerHider * number of alive hiders
        int totalDecoys = phs.decoyCountPerSpecies * phs.hiders.size();
        if (totalDecoys <= 0) return;

        RegionManager rm = CheeseGuardMod.getInstance().getRegionManager();
        Random random = new Random();
        int spawned = 0;
        int failed = 0;

        // Cobblemon commands require an entity in the command source (source.getEntity().getWorld())
        // Using server.getCommandSource() causes NPE — use a participant player instead
        ServerPlayerEntity sourcePlayer = null;
        for (var p : event.getParticipants().values()) {
            sourcePlayer = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (sourcePlayer != null) break;
        }
        if (sourcePlayer == null) {
            CheeseGuardMod.LOGGER.warn("[CG] PropHunt: no online participant for command source, skipping decoys");
            return;
        }

        // Distribute species evenly (round-robin) across total decoys
        for (int i = 0; i < totalDecoys; i++) {
            String species = phs.speciesList.get(i % phs.speciesList.size());
            // Pick a random zone
            String zoneId = zones.get(random.nextInt(zones.size()));
            Region region = rm.getStorage().getRegion(zoneId);
            if (region == null) {
                CheeseGuardMod.LOGGER.warn("[CG] PropHunt: spawn zone '{}' not found", zoneId);
                failed++;
                continue;
            }

            // Find the world for this zone — must be the exact world, no fallback
            ServerWorld world = null;
            for (var w : server.getWorlds()) {
                if (w.getRegistryKey().getValue().toString().equals(region.getWorld())) {
                    world = w;
                    break;
                }
            }
            if (world == null) {
                CheeseGuardMod.LOGGER.warn("[CG] PropHunt: world '{}' not loaded for zone '{}' — is anyone in that dimension?",
                    region.getWorld(), zoneId);
                failed++;
                continue;
            }

            // Random position within zone bounds (inclusive)
            int rangeX = region.getMaxX() - region.getMinX() + 1;
            int rangeZ = region.getMaxZ() - region.getMinZ() + 1;
            int x = region.getMinX() + random.nextInt(Math.max(1, rangeX));
            int z = region.getMinZ() + random.nextInt(Math.max(1, rangeZ));

            // Force chunk load
            world.getChunk(x >> 4, z >> 4);

            // Find valid surface Y by scanning downward from zone maxY
            // Heightmap doesn't work reliably in VOID dimensions
            int y = region.getMaxY();
            for (int scanY = region.getMaxY(); scanY >= region.getMinY(); scanY--) {
                BlockPos checkPos = new BlockPos(x, scanY, z);
                if (!world.getBlockState(checkPos).isAir() && world.getBlockState(checkPos.up()).isAir()) {
                    y = scanY + 1; // spawn ON TOP of the solid block
                    break;
                }
            }

            // Command source from a real player (Cobblemon needs entity context)
            var spawnSource = sourcePlayer.getCommandSource()
                .withWorld(world)
                .withPosition(new net.minecraft.util.math.Vec3d(x + 0.5, y + 0.5, z + 0.5))
                .withLevel(4)
                .withSilent();

            boolean spawnOk = false;
            try {
                String cmd = "spawnpokemonat " + x + " " + y + " " + z + " " + species;
                server.getCommandManager().getDispatcher().execute(cmd, spawnSource);
                spawnOk = true;
            } catch (Exception e) {
                CheeseGuardMod.LOGGER.warn("[CG] PropHunt decoy spawn failed for {} at {},{},{} in {}: {}",
                    species, x, y, z, region.getWorld(), e.getMessage());
                failed++;
            }

            // Tag the spawned entity for cleanup (cobblemon:pokemon is the entity type, filter by nearest)
            if (spawnOk) {
                try {
                    String tagCmd = "tag @e[type=cobblemon:pokemon,distance=..5,sort=nearest,limit=1,tag=!ph_decoy] add ph_decoy";
                    server.getCommandManager().getDispatcher().execute(tagCmd, spawnSource);
                } catch (Exception ignored) {}
                spawned++;
            }
        }

        CheeseGuardMod.LOGGER.info("[CG] PropHunt decoys: {} spawned ({} per hider, {} hiders, {} failed)",
            spawned, phs.decoyCountPerSpecies, phs.hiders.size(), failed);
    }

    private void tpToZone(ServerPlayerEntity player, String zoneId) {
        RegionManager rm = CheeseGuardMod.getInstance().getRegionManager();
        Region region = rm.getStorage().getRegion(zoneId);
        if (region == null) return;

        int cx = (region.getMinX() + region.getMaxX()) / 2;
        int cy = region.getMinY() + 1;
        int cz = (region.getMinZ() + region.getMaxZ()) / 2;

        ServerWorld world = null;
        for (var w : server.getWorlds()) {
            if (w.getRegistryKey().getValue().toString().equals(region.getWorld())) {
                world = w;
                break;
            }
        }
        if (world != null) {
            player.teleport(world, cx + 0.5, cy, cz + 0.5, player.getYaw(), player.getPitch());
        }
    }

    private String getParticipantName(ServerEvent event, UUID uuid) {
        EventParticipant p = event.getParticipant(uuid);
        return p != null ? p.getPlayerName() : uuid.toString().substring(0, 8);
    }

    public boolean isPropHuntActiveEvent(String eventId) {
        MiniGameState state = activeGames.get(eventId);
        return state != null && state.type == MiniGameType.PROP_HUNT && propHuntStates.containsKey(eventId);
    }

    // ── Player elimination ───────────────────────────────────────────────────

    public void eliminatePlayer(String eventId, UUID playerUuid) {
        MiniGameState state = activeGames.get(eventId);
        if (state != null) state.eliminated.add(playerUuid);
    }

    public boolean isEliminated(String eventId, UUID playerUuid) {
        MiniGameState state = activeGames.get(eventId);
        return state != null && state.eliminated.contains(playerUuid);
    }

    // ── Query ────────────────────────────────────────────────────────────────

    public boolean isActive(String eventId) { return activeGames.containsKey(eventId); }
    /** Returns true if this event has an active Thimble game (zone enforcement should be skipped). */
    public boolean isThimbleActiveJumperEvent(String eventId) {
        MiniGameState state = activeGames.get(eventId);
        return state != null && state.type == MiniGameType.THIMBLE && thimbleStates.containsKey(eventId);
    }
    /** Returns true if this event has an active Spleef game (zone enforcement should be skipped). */
    public boolean isSpleefActiveEvent(String eventId) {
        MiniGameState state = activeGames.get(eventId);
        return state != null && state.type == MiniGameType.SPLEEF && spleefStates.containsKey(eventId);
    }
    /** Returns true if this event has an active Race game (zone enforcement should be skipped). */
    public boolean isRaceActiveEvent(String eventId) {
        MiniGameState state = activeGames.get(eventId);
        return state != null && state.type == MiniGameType.RACE && raceStates.containsKey(eventId);
    }
    public MiniGameType getType(String eventId) {
        MiniGameState state = activeGames.get(eventId);
        return state != null ? state.type : null;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void broadcast(ServerEvent event, String msg, String color) {
        for (var p : event.getParticipants().values()) {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (pl != null) {
                pl.sendMessage(Text.literal(msg).formatted(EventManager.colorFromName(color)), false);
            }
        }
    }

    private void broadcastText(ServerEvent event, Text text) {
        for (var p : event.getParticipants().values()) {
            ServerPlayerEntity pl = server.getPlayerManager().getPlayer(p.getPlayerUuid());
            if (pl != null) {
                pl.sendMessage(text, false);
            }
        }
    }

    private void broadcastPropHuntResults(ServerEvent event, PropHuntState phs, boolean huntersWon) {
        StringBuilder sb = new StringBuilder();
        sb.append("\u00a7e\u00a7l\u2550\u2550\u2550 ").append(Text.translatable("cheeseguard.prophunt.results_header").getString()).append(" \u2550\u2550\u2550\n\n");

        if (huntersWon) {
            // Hunters won — show them first
            sb.append("\u00a76\u00a7l\u2606 ").append(Text.translatable("cheeseguard.prophunt.results_hunters").getString()).append(" \u00a7a(").append(Text.translatable("cheeseguard.prophunt.results_winners").getString()).append(")\n");
            appendTeamResults(sb, event, phs.hunters, phs.eliminatedHunters, null, null);
            sb.append("\n\u00a7b\uD83D\uDC3E ").append(Text.translatable("cheeseguard.prophunt.results_hiders").getString()).append(":\n");
            appendTeamResults(sb, event, phs.hiders, phs.eliminatedHiders, phs.hiderHealth, phs.hiderStartHealth);
        } else {
            // Hiders won — show them first
            sb.append("\u00a76\u00a7l\u2606 ").append(Text.translatable("cheeseguard.prophunt.results_hiders").getString()).append(" \u00a7a(").append(Text.translatable("cheeseguard.prophunt.results_winners").getString()).append(")\n");
            appendTeamResults(sb, event, phs.hiders, phs.eliminatedHiders, phs.hiderHealth, phs.hiderStartHealth);
            sb.append("\n\u00a7c\uD83C\uDFF9 ").append(Text.translatable("cheeseguard.prophunt.results_hunters").getString()).append(":\n");
            appendTeamResults(sb, event, phs.hunters, phs.eliminatedHunters, null, null);
        }

        broadcastText(event, Text.literal(sb.toString()));
    }

    private void appendTeamResults(StringBuilder sb, ServerEvent event, Set<UUID> team,
            Set<UUID> eliminated, Map<UUID, Integer> healthMap, Integer startHealth) {
        for (UUID uuid : team) {
            String name = getParticipantName(event, uuid);
            boolean alive = !eliminated.contains(uuid);
            sb.append(alive ? "  \u00a7a\u2713 " : "  \u00a7c\u2717 ").append(name);
            if (alive && healthMap != null && startHealth != null) {
                int hearts = Math.max(0, healthMap.getOrDefault(uuid, startHealth) / 2);
                sb.append(" \u00a7c").append(hearts).append("\u2764");
            }
            sb.append("\n");
        }
    }

    // ── PropHunt live sidebar scoreboard ────────────────────────────────────

    private void createPropHuntScoreboard(ServerEvent event) {
        String objName = "ph_" + event.getId().substring(0, Math.min(8, event.getId().length()));
        execCmd("scoreboard objectives add " + objName + " dummy \"\u00a7e\u00a7l\u2694 Prop Hunt\"");
        execCmd("scoreboard objectives setdisplay sidebar " + objName);
    }

    private void updatePropHuntScoreboard(ServerEvent event, PropHuntState phs) {
        String objName = "ph_" + event.getId().substring(0, Math.min(8, event.getId().length()));
        // Remove and recreate to clear stale lines
        execCmd("scoreboard objectives remove " + objName);
        execCmd("scoreboard objectives add " + objName + " dummy \"\u00a7e\u00a7l\u2694 Prop Hunt\"");
        execCmd("scoreboard objectives setdisplay sidebar " + objName);

        int line = 15;

        // Hunters header
        int aliveHunters = 0;
        for (UUID hu : phs.hunters) {
            if (!phs.eliminatedHunters.contains(hu)) aliveHunters++;
        }
        setScoreboardLine(objName, "\u00a7c\u00a7l\uD83C\uDFF9 Chasseurs (" + aliveHunters + "/" + phs.hunters.size() + ")", line--);
        for (UUID hu : phs.hunters) {
            if (line <= 2) break;
            String name = getParticipantName(event, hu);
            if (name.length() > 14) name = name.substring(0, 14);
            boolean alive = !phs.eliminatedHunters.contains(hu);
            String display = alive ? "\u00a7a \u2713 " + name : "\u00a78 \u2717 " + name;
            setScoreboardLine(objName, display, line--);
        }

        // Separator (unique via different color prefix)
        setScoreboardLine(objName, "\u00a70\u00a7r \u00a78\u2500\u2500\u2500\u2500\u2500\u2500\u2500", line--);

        // Hiders header
        int aliveHiders = 0;
        for (UUID hi : phs.hiders) {
            if (!phs.eliminatedHiders.contains(hi)) aliveHiders++;
        }
        setScoreboardLine(objName, "\u00a7b\u00a7l\uD83D\uDC3E Pok\u00e9mon (" + aliveHiders + "/" + phs.hiders.size() + ")", line--);
        for (UUID hi : phs.hiders) {
            if (line <= 2) break;
            String name = getParticipantName(event, hi);
            if (name.length() > 10) name = name.substring(0, 10);
            boolean alive = !phs.eliminatedHiders.contains(hi);
            if (alive) {
                int hearts = Math.max(0, phs.hiderHealth.getOrDefault(hi, phs.hiderStartHealth) / 2);
                setScoreboardLine(objName, "\u00a7a \u2713 " + name + " \u00a7c" + hearts + "\u2764", line--);
            } else {
                setScoreboardLine(objName, "\u00a78 \u2717 " + name, line--);
            }
        }

        // Separator 2
        setScoreboardLine(objName, "\u00a71\u00a7r \u00a78\u2500\u2500\u2500\u2500\u2500\u2500\u2500", line--);

        // Timer
        long elapsed = (server.getTicks() - phs.gameStartTick) / 20;
        int remaining = Math.max(0, phs.gameDurationSeconds - (int) elapsed);
        int min = remaining / 60, sec = remaining % 60;
        setScoreboardLine(objName, "\u00a7e\u23F1 " + min + ":" + String.format("%02d", sec), line--);
    }

    private void removePropHuntScoreboard(ServerEvent event) {
        String objName = "ph_" + event.getId().substring(0, Math.min(8, event.getId().length()));
        execCmd("scoreboard objectives remove " + objName);
    }

    private void setScoreboardLine(String objName, String text, int score) {
        // Scoreboard player names: use the formatted text directly
        execCmd("scoreboard players set \"" + text + "\" " + objName + " " + score);
    }

    private void execCmd(String cmd) {
        try { server.getCommandManager().executeWithPrefix(server.getCommandSource().withSilent(), cmd); }
        catch (Exception e) { CheeseGuardMod.LOGGER.debug("[CG] PropHunt exec err: {}", e.getMessage()); }
    }

    /**
     * Returns true if attacker is a hunter and target is a hider in the same PropHunt game.
     * Used by ProtectionListener to allow PVP between hunters and disguised hiders.
     */
    public boolean isPropHuntCombatAllowed(UUID attacker, UUID target) {
        for (var entry : propHuntStates.entrySet()) {
            PropHuntState phs = entry.getValue();
            if (!phs.huntersReleased) continue;
            if (phs.hunters.contains(attacker) && phs.hiders.contains(target)
                && !phs.eliminatedHunters.contains(attacker) && !phs.eliminatedHiders.contains(target)) {
                return true;
            }
        }
        return false;
    }

    public static MiniGameType[] getAllTypes() { return MiniGameType.values(); }
}
