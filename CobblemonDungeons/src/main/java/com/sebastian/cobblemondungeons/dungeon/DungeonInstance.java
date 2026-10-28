package com.sebastian.cobblemondungeons.dungeon;

import com.sebastian.cobblemondungeons.config.DungeonConfig;
import java.util.*;

public class DungeonInstance {
    private final DungeonConfig config;
    private final int tier;
    private final Set<UUID> players = new HashSet<>();
    private final Set<String> collectedKeys = new HashSet<>();
    private int alphasDefeated = 0;
    private int gemsFound = 0;
    private int guardiansDefeated = 0;
    private int stakesFound = 0;
    private int timeLeft;
    private boolean bossDefeated = false;
    private boolean portalsActivated = false;
    private final Set<net.minecraft.util.math.BlockPos> portalPositions = new HashSet<>();
    private final Set<UUID> bossRoomVisitors = new HashSet<>();
    private int lives;
    private int finishDelayTicks = -1;
    private boolean alphasSpawned = false;
    private boolean stakesSpawned = false;
    private boolean bossSpawned = false;
    private int bossPhase = 1;
    private UUID bossUuid;
    private String spawnedBossSpecies;
    private int spawnedBossLevel;
    private boolean spawnedBossShiny;
    private String spawnedBossName;
    private double bossRewardChance = 100.0;
    private List<DungeonConfig.CompletionRewardConfig> bossRewards = new ArrayList<>();
    private final Set<net.minecraft.util.math.BlockPos> discoveredAlphaSpawners = new HashSet<>();
    private final Set<net.minecraft.util.math.BlockPos> discoveredStakeSpawners = new HashSet<>();
    private List<net.minecraft.util.math.BlockPos> alphaSpawnerPositions = new ArrayList<>();
    private Set<net.minecraft.util.math.BlockPos> alphaSpawnedPositions = new HashSet<>();
    private int alphaSpawnTarget = 0;
    private boolean alphaSpawnerDebugLogged = false;
    private final ArrayDeque<Long> alphaSpawnerScanQueue = new ArrayDeque<>();
    private final Set<Long> alphaSpawnerScanQueued = new HashSet<>();
    private final ArrayDeque<Long> stakeSpawnerScanQueue = new ArrayDeque<>();
    private final Set<Long> stakeSpawnerScanQueued = new HashSet<>();
    private boolean stakeSpawnerScanStarted = false;
    private int guardianSpawnTarget = 0;
    private int stakeSpawnTarget = 0;
    private boolean areGuardiansSpawned = false;
    private boolean guardianSpawnerScanStarted = false;
    private final Set<net.minecraft.util.math.BlockPos> discoveredGuardianSpawners = new HashSet<>();
    private List<net.minecraft.util.math.BlockPos> guardianSpawnerPositions = new ArrayList<>();
    private List<net.minecraft.util.math.BlockPos> stakeSpawnerPositions = new ArrayList<>();
    private Set<net.minecraft.util.math.BlockPos> guardianSpawnedPositions = new HashSet<>();
    private boolean guardianSpawnerDebugLogged = false;
    private final ArrayDeque<Long> guardianSpawnerScanQueue = new ArrayDeque<>();
    private final Set<Long> guardianSpawnerScanQueued = new HashSet<>();
    private final ArrayDeque<Long> chestScanQueue = new ArrayDeque<>();
    private final Set<Long> chestScanQueued = new HashSet<>();
    private final ArrayDeque<TrainerSpawnTask> trainerSpawnQueue = new ArrayDeque<>();
    private final Set<net.minecraft.util.math.BlockPos> lootedChests = new HashSet<>();
    private final Map<net.minecraft.util.math.BlockPos, net.minecraft.inventory.SimpleInventory> chestInventories = new HashMap<>();
    private final Set<net.minecraft.util.math.BlockPos> discoveredChests = new HashSet<>();
    private final Set<net.minecraft.util.math.BlockPos> chestsWithGems = new HashSet<>();
    private boolean gemsAssigned = false;
    private boolean registryPopulated = false;
    private boolean teleported = false;
    private final long startTime;
    private UUID partyId;
    private final int locationIndex;

    public DungeonInstance(DungeonConfig config, Set<UUID> partyMembers, int tier) {
        this(config, partyMembers, null, tier, partyMembers.size(), 0);
    }

    public DungeonInstance(DungeonConfig config, Set<UUID> partyMembers, UUID partyId, int tier) {
        this(config, partyMembers, partyId, tier, partyMembers.size(), 0);
    }

    public DungeonInstance(DungeonConfig config, Set<UUID> partyMembers, UUID partyId, int tier, int expectedSize) {
        this(config, partyMembers, partyId, tier, expectedSize, 0);
    }

    public DungeonInstance(DungeonConfig config, Set<UUID> partyMembers, UUID partyId, int tier, int expectedSize, int locationIndex) {
        this.config = config;
        this.tier = tier;
        this.players.addAll(partyMembers);
        this.partyId = partyId;
        this.locationIndex = locationIndex;
        this.timeLeft = config.requirements.timeLimitSeconds;
        this.startTime = System.currentTimeMillis();
        this.alphaSpawnTarget = config.requirements.requiredAlphas;
        this.guardianSpawnTarget = config.requirements.requiredGuardians;
        this.stakeSpawnTarget = config.requirements.requiredStakes;
        this.areGuardiansSpawned = config.requirements.requiredGuardians <= 0;
        this.stakesSpawned = config.requirements.requiredStakes <= 0;
        
        int size = expectedSize > 0 ? expectedSize : partyMembers.size();
        if (size >= 4) {
            this.lives = config.requirements.largePartyLives;
        } else if (size >= 2) {
            this.lives = config.requirements.smallPartyLives;
        } else {
            this.lives = config.requirements.soloLives;
        }
    }

    public int getLives() {
        return lives;
    }

    public void decrementLives() {
        if (lives > 0) {
            lives--;
        }
    }

    public int getFinishDelayTicks() { return finishDelayTicks; }
    public void setFinishDelayTicks(int finishDelayTicks) { this.finishDelayTicks = finishDelayTicks; }

    public DungeonConfig getConfig() {
        return config;
    }

    public int getTier() {
        return tier;
    }

    public Set<UUID> getPlayers() {
        return players;
    }

    public UUID getPartyId() {
        return partyId;
    }

    public long getStartTime() {
        return startTime;
    }

    public void collectKey(String keyId) {
        collectedKeys.add(keyId);
    }

    public void addAlphaDefeated() { alphasDefeated++; }
    public void addGemFound() { gemsFound++; }
    public void addGuardianDefeated() { guardiansDefeated++; }
    public void addStakeFound() { stakesFound++; }
    public void tickTime() { if (timeLeft > 0) timeLeft--; }
    public int getTimeLeft() { return timeLeft; }

    public int getAlphasDefeated() { return alphasDefeated; }
    public int getGemsFound() { return gemsFound; }
    public int getGuardiansDefeated() { return guardiansDefeated; }
    public int getStakesFound() { return stakesFound; }

    public boolean arePortalsActivated() { return portalsActivated; }
    public void setPortalsActivated(boolean activated) { this.portalsActivated = activated; }
    public java.util.Set<net.minecraft.util.math.BlockPos> getPortalPositions() { return portalPositions; }
    public void addPortalPosition(net.minecraft.util.math.BlockPos pos) { portalPositions.add(pos); }

    public void addBossRoomVisitor(UUID player) { bossRoomVisitors.add(player); }
    public boolean hasVisitedBossRoom(UUID player) { return bossRoomVisitors.contains(player); }

    public boolean canAccessBoss() {
        return collectedKeys.containsAll(config.requirements.requiredKeyItems) &&
               alphasDefeated >= config.requirements.requiredAlphas &&
               gemsFound >= config.requirements.requiredGems &&
               guardiansDefeated >= config.requirements.requiredGuardians &&
               stakesFound >= config.requirements.requiredStakes;
    }

    public void setBossDefeated(boolean defeated) {
        this.bossDefeated = defeated;
    }

    public boolean isBossDefeated() {
        return bossDefeated;
    }

    public boolean areAlphasSpawned() {
        return alphasSpawned;
    }

    public void setAlphasSpawned(boolean alphasSpawned) {
        this.alphasSpawned = alphasSpawned;
    }

    public boolean areStakesSpawned() {
        return stakesSpawned;
    }

    public void setStakesSpawned(boolean stakesSpawned) {
        this.stakesSpawned = stakesSpawned;
    }

    public boolean isBossSpawned() {
        return bossSpawned;
    }

    public void setBossSpawned(boolean bossSpawned) {
        this.bossSpawned = bossSpawned;
    }

    public int getBossPhase() {
        return bossPhase;
    }

    public void setBossPhase(int bossPhase) {
        this.bossPhase = bossPhase;
    }

    public UUID getBossUuid() {
        return bossUuid;
    }

    public void setBossUuid(UUID bossUuid) {
        this.bossUuid = bossUuid;
    }

    public String getSpawnedBossSpecies() { return spawnedBossSpecies; }
    public void setSpawnedBossSpecies(String species) { this.spawnedBossSpecies = species; }
    public int getSpawnedBossLevel() { return spawnedBossLevel; }
    public void setSpawnedBossLevel(int level) { this.spawnedBossLevel = level; }
    public boolean isSpawnedBossShiny() { return spawnedBossShiny; }
    public void setSpawnedBossShiny(boolean shiny) { this.spawnedBossShiny = shiny; }
    public String getSpawnedBossName() { return spawnedBossName; }
    public void setSpawnedBossName(String name) { this.spawnedBossName = name; }
    public double getBossRewardChance() { return bossRewardChance; }
    public void setBossRewardChance(double chance) { this.bossRewardChance = chance; }

    public List<DungeonConfig.CompletionRewardConfig> getBossRewards() { return bossRewards; }
    public void setBossRewards(List<DungeonConfig.CompletionRewardConfig> rewards) { this.bossRewards = rewards != null ? rewards : new ArrayList<>(); }

    public int getLocationIndex() {
        return locationIndex;
    }

    public DungeonConfig.LocationsConfig getActiveLocations() {
        List<DungeonConfig.LocationsConfig> all = config.getAllLocations();
        if (locationIndex >= 0 && locationIndex < all.size()) {
            return all.get(locationIndex);
        }
        return config.locations;
    }

    public DungeonConfig.WorldPos getBossSpawnPos() {
        DungeonConfig.LocationsConfig active = getActiveLocations();
        if (active.bossSpawnPos != null) return active.bossSpawnPos;
        return config.boss.bossSpawnPos;
    }

    public void setAlphaSpawnerPositions(List<net.minecraft.util.math.BlockPos> positions, int targetCount) {
        this.alphaSpawnerPositions.clear();
        if (positions != null) {
            this.alphaSpawnerPositions.addAll(positions);
        }
        this.alphaSpawnedPositions.clear();
        this.alphaSpawnTarget = targetCount;
    }

    public void setAlphaSpawnTarget(int targetCount) {
        this.alphaSpawnTarget = targetCount;
    }

    public boolean addAlphaSpawnerPosition(net.minecraft.util.math.BlockPos pos) {
        if (pos == null) return false;
        if (alphaSpawnerPositions.contains(pos)) return false;
        alphaSpawnerPositions.add(pos);
        return true;
    }

    public boolean addDiscoveredAlphaSpawner(net.minecraft.util.math.BlockPos pos) {
        if (pos == null) return false;
        return discoveredAlphaSpawners.add(pos);
    }

    public Set<net.minecraft.util.math.BlockPos> getDiscoveredAlphaSpawners() {
        return discoveredAlphaSpawners;
    }

    public List<net.minecraft.util.math.BlockPos> getAlphaSpawnerPositions() {
        return alphaSpawnerPositions;
    }

    public boolean isAlphaSpawnerSpawned(net.minecraft.util.math.BlockPos pos) {
        return alphaSpawnedPositions.contains(pos);
    }

    public boolean addDiscoveredStakeSpawner(net.minecraft.util.math.BlockPos pos) {
        return discoveredStakeSpawners.add(pos);
    }

    public List<net.minecraft.util.math.BlockPos> getStakeSpawnerPositions() {
        return stakeSpawnerPositions;
    }

    public boolean addStakeSpawnerPosition(net.minecraft.util.math.BlockPos pos) {
        if (pos == null) return false;
        if (stakeSpawnerPositions.contains(pos)) return false;
        stakeSpawnerPositions.add(pos);
        return true;
    }

    public void setStakeSpawnTarget(int targetCount) {
        this.stakeSpawnTarget = targetCount;
    }

    public int getStakeSpawnTarget() {
        return stakeSpawnTarget;
    }

    public java.util.Set<net.minecraft.util.math.BlockPos> getDiscoveredStakeSpawners() {
        return discoveredStakeSpawners;
    }

    public void enqueueStakeSpawnerScanChunk(long chunkKey) {
        if (stakeSpawnerScanQueued.add(chunkKey)) {
            stakeSpawnerScanQueue.add(chunkKey);
        }
    }

    public Long pollStakeSpawnerScanChunk() {
        return stakeSpawnerScanQueue.poll();
    }

    public boolean hasStakeSpawnerScanQueue() {
        return !stakeSpawnerScanQueue.isEmpty();
    }

    public boolean isStakeSpawnerScanComplete() {
        return stakeSpawnerScanQueue.isEmpty() && !stakeSpawnerScanQueued.isEmpty();
    }

    public boolean isStakeSpawnerScanStarted() {
        return stakeSpawnerScanStarted;
    }

    public void setStakeSpawnerScanStarted(boolean started) {
        this.stakeSpawnerScanStarted = started;
    }

    public void markAlphaSpawnerSpawned(net.minecraft.util.math.BlockPos pos) {
        alphaSpawnedPositions.add(pos);
    }

    public int getAlphaSpawnTarget() {
        return alphaSpawnTarget;
    }

    public int getAlphaSpawnedCount() {
        return alphaSpawnedPositions.size();
    }

    public boolean isAlphaSpawnerDebugLogged() {
        return alphaSpawnerDebugLogged;
    }

    public void setAlphaSpawnerDebugLogged(boolean logged) {
        this.alphaSpawnerDebugLogged = logged;
    }

    public void resetAlphaSpawnerScanQueue() {
        alphaSpawnerScanQueue.clear();
        alphaSpawnerScanQueued.clear();
    }

    public void enqueueAlphaSpawnerScanChunk(long chunkKey) {
        if (alphaSpawnerScanQueued.add(chunkKey)) {
            alphaSpawnerScanQueue.add(chunkKey);
        }
    }

    public Long pollAlphaSpawnerScanChunk() {
        return alphaSpawnerScanQueue.poll();
    }

    public boolean hasAlphaSpawnerScanQueue() {
        return !alphaSpawnerScanQueue.isEmpty();
    }

    public boolean isAlphaSpawnerScanComplete() {
        return alphaSpawnerScanQueue.isEmpty() && !alphaSpawnerScanQueued.isEmpty();
    }

    public boolean areGuardiansSpawned() {
        return areGuardiansSpawned;
    }

    public void setGuardiansSpawned(boolean guardiansSpawned) {
        this.areGuardiansSpawned = guardiansSpawned;
    }

    public boolean isGuardianSpawnerScanStarted() {
        return guardianSpawnerScanStarted;
    }

    public void setGuardianSpawnerScanStarted(boolean started) {
        this.guardianSpawnerScanStarted = started;
    }

    public boolean isGuardianSpawnerScanComplete() {
        return guardianSpawnerScanQueue.isEmpty() && !guardianSpawnerScanQueued.isEmpty();
    }

    public void setGuardianSpawnTarget(int targetCount) {
        this.guardianSpawnTarget = targetCount;
    }

    public int getGuardianSpawnTarget() {
        return guardianSpawnTarget;
    }

    public int getGuardianSpawnedCount() {
        return guardianSpawnedPositions.size();
    }

    public boolean addDiscoveredGuardianSpawner(net.minecraft.util.math.BlockPos pos) {
        return discoveredGuardianSpawners.add(pos);
    }

    public Set<net.minecraft.util.math.BlockPos> getDiscoveredGuardianSpawners() {
        return discoveredGuardianSpawners;
    }

    public List<net.minecraft.util.math.BlockPos> getGuardianSpawnerPositions() {
        return guardianSpawnerPositions;
    }

    public boolean isGuardianSpawnerSpawned(net.minecraft.util.math.BlockPos pos) {
        return guardianSpawnedPositions.contains(pos);
    }

    public void markGuardianSpawnerSpawned(net.minecraft.util.math.BlockPos pos) {
        guardianSpawnedPositions.add(pos);
    }

    public boolean addGuardianSpawnerPosition(net.minecraft.util.math.BlockPos pos) {
        if (pos == null) return false;
        if (guardianSpawnerPositions.contains(pos)) return false;
        guardianSpawnerPositions.add(pos);
        return true;
    }

    public boolean isGuardianSpawnerDebugLogged() {
        return guardianSpawnerDebugLogged;
    }

    public void setGuardianSpawnerDebugLogged(boolean logged) {
        this.guardianSpawnerDebugLogged = logged;
    }

    public void resetGuardianSpawnerScanQueue() {
        guardianSpawnerScanQueue.clear();
        guardianSpawnerScanQueued.clear();
    }

    public void enqueueGuardianSpawnerScanChunk(long chunkKey) {
        if (guardianSpawnerScanQueued.add(chunkKey)) {
            guardianSpawnerScanQueue.add(chunkKey);
        }
    }

    public Long pollGuardianSpawnerScanChunk() {
        return guardianSpawnerScanQueue.poll();
    }

    public void enqueueChestScanChunk(long chunkKey) {
        if (chestScanQueued.add(chunkKey)) {
            chestScanQueue.add(chunkKey);
        }
    }

    public Long pollChestScanChunk() {
        return chestScanQueue.poll();
    }

    public boolean hasChestScanQueue() {
        return !chestScanQueue.isEmpty();
    }

    public boolean isChestScanComplete() {
        return chestScanQueue.isEmpty() && !chestScanQueued.isEmpty();
    }

    public boolean hasGuardianSpawnerScanQueue() {
        return !guardianSpawnerScanQueue.isEmpty();
    }

    public void enqueueTrainerSpawn(TrainerSpawnTask task) {
        trainerSpawnQueue.add(task);
    }

    public TrainerSpawnTask pollTrainerSpawn() {
        return trainerSpawnQueue.poll();
    }

    public boolean hasTrainerSpawnsPending() {
        return !trainerSpawnQueue.isEmpty();
    }

    public static record TrainerSpawnTask(String trainerId, double x, double y, double z, int level, boolean invulnerable, boolean isGuardian) {}

    public boolean isChestLooted(net.minecraft.util.math.BlockPos pos) {
        return lootedChests.contains(pos);
    }

    public void markChestLooted(net.minecraft.util.math.BlockPos pos) {
        lootedChests.add(pos);
    }

    public net.minecraft.inventory.SimpleInventory getChestInventory(net.minecraft.util.math.BlockPos pos) {
        return chestInventories.get(pos);
    }

    public void setChestInventory(net.minecraft.util.math.BlockPos pos, net.minecraft.inventory.SimpleInventory inventory) {
        chestInventories.put(pos, inventory);
    }

    public boolean addDiscoveredChest(net.minecraft.util.math.BlockPos pos) {
        return discoveredChests.add(pos);
    }

    public Set<net.minecraft.util.math.BlockPos> getDiscoveredChests() {
        return discoveredChests;
    }

    public boolean hasGem(net.minecraft.util.math.BlockPos pos) {
        return chestsWithGems.contains(pos);
    }

    public void assignGems() {
        int required = config.requirements.requiredGems;
        if (required <= 0 || discoveredChests.isEmpty()) {
            if (required <= 0) gemsAssigned = true;
            return;
        }
        
        if (chestsWithGems.size() >= required) {
            gemsAssigned = true;
            return;
        }

        List<net.minecraft.util.math.BlockPos> pool = new ArrayList<>();
        for (net.minecraft.util.math.BlockPos pos : discoveredChests) {
            if (!chestsWithGems.contains(pos)) {
                pool.add(pos);
            }
        }
        
        if (pool.isEmpty()) return;

        Collections.shuffle(pool);
        
        int stillNeeded = required - chestsWithGems.size();
        int toAssign = Math.min(stillNeeded, pool.size());
        for (int i = 0; i < toAssign; i++) {
            chestsWithGems.add(pool.get(i));
        }
        
        if (chestsWithGems.size() >= required) {
            gemsAssigned = true;
        }
        com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("Assigned " + toAssign + " more gem(s) to chests in dungeon " + config.general.name + " (Total: " + chestsWithGems.size() + "/" + required + ")");
    }

    public boolean areGemsAssigned() {
        return gemsAssigned;
    }

    public boolean isRegistryPopulated() {
        return registryPopulated;
    }

    public void setRegistryPopulated(boolean populated) {
        this.registryPopulated = populated;
    }

    public boolean isTeleported() {
        return teleported;
    }

    public void setTeleported(boolean teleported) {
        this.teleported = teleported;
    }

    public boolean isReadyForPlayers() {
        // Alphas spawn on proximity, so we don't strictly need them fully scanned to start,
        // but Stakes and Guardians should be spawned to avoid "pop-up" effects.
        return areStakesSpawned() && areGuardiansSpawned() && !hasTrainerSpawnsPending();
    }
}
