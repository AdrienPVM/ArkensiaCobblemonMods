package com.cheesepatrol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PatrolManager {

    private static final Path DATA_PATH = Path.of("config", "cheesepatrol_data.json");
    private static final Path ENTITY_BACKUP_DIR = Path.of("config", "cheesepatrol_entities");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, PatrolData> patrols = new ConcurrentHashMap<>();

    // Debug mode: when false (default), suppresses verbose tick/patrol logs in console
    private static boolean debug = false;

    // Show toggle: playerUUID -> npcUUID they're watching
    private static final Map<UUID, UUID> showingParticles = new ConcurrentHashMap<>();
    private static int particleTickCounter = 0;
    private static final int PARTICLE_INTERVAL = 10; // refresh particles every 10 ticks

    // Dynamic chunk forceloading: NPC UUID -> set of packed chunk positions we forced
    private static final Map<UUID, Set<Long>> patrolForcedChunks = new ConcurrentHashMap<>();

    // Entity backup: track ticks since last backup
    private static final int BACKUP_INTERVAL = 1200; // 60 seconds
    private static final int RESPAWN_AFTER_TICKS = 600; // 30 seconds missing → respawn
    private static final int MAX_RESPAWN_ATTEMPTS = 10; // more resilient than 3
    private static final int RECOVERY_CHECK_INTERVAL = 6000; // 5 minutes — retry disabled patrols
    private static int backupTickCounter = 0;
    private static int recoveryTickCounter = 0;

    // Track whether patrols have been loaded (for mixin early protection)
    private static volatile boolean loaded = false;

    public static boolean isLoaded() { return loaded; }

    /** Check if a backup file exists for a UUID (used by mixin before load completes) */
    public static boolean hasBackupFile(UUID uuid) {
        return Files.exists(ENTITY_BACKUP_DIR.resolve(uuid + ".dat"));
    }

    public static void load() {
        patrols.clear();
        patrolForcedChunks.clear();
        showingParticles.clear();
        // Reset tick counters for clean state on reload
        backupTickCounter = 0;
        recoveryTickCounter = 0;
        particleTickCounter = 0;
        startupIntegrityDone = false;
        startupDelayTicks = 0;
        try {
            if (Files.exists(DATA_PATH)) {
                String json = Files.readString(DATA_PATH);
                JsonObject root = GSON.fromJson(json, JsonObject.class);
                if (root != null) {
                    // Load debug flag (default false)
                    if (root.has("debug")) debug = root.get("debug").getAsBoolean();
                    if (root.has("patrols")) {
                        JsonArray arr = root.getAsJsonArray("patrols");
                        for (int i = 0; i < arr.size(); i++) {
                            PatrolData data = PatrolData.fromJson(arr.get(i).getAsJsonObject());
                            patrols.put(data.getNpcUuid(), data);
                        }
                    }
                }
            }
        } catch (Exception e) {
            CheesePatrolMod.LOGGER.error("[CheesePatrol] Failed to load data", e);
        }
        loaded = true;
    }

    public static void save() {
        try {
            Files.createDirectories(DATA_PATH.getParent());
            JsonObject root = new JsonObject();
            // "debug": true/false — when true, verbose patrol logs appear in server console
            root.addProperty("debug", debug);
            JsonArray arr = new JsonArray();
            for (PatrolData data : patrols.values()) {
                arr.add(data.toJson());
            }
            root.add("patrols", arr);
            Files.writeString(DATA_PATH, GSON.toJson(root));
        } catch (Exception e) {
            CheesePatrolMod.LOGGER.error("[CheesePatrol] Failed to save data", e);
        }
    }

    public static PatrolData getOrCreate(UUID npcUuid) {
        return patrols.computeIfAbsent(npcUuid, PatrolData::new);
    }

    public static PatrolData get(UUID npcUuid) {
        return patrols.get(npcUuid);
    }

    public static void remove(UUID npcUuid, MinecraftServer server) {
        PatrolData patrol = patrols.remove(npcUuid);
        if (patrol != null && server != null) {
            releaseForcedChunks(server, patrol);
        }
        deleteEntityBackup(npcUuid);
    }

    public static Collection<PatrolData> getAll() {
        return patrols.values();
    }

    public static int getPatrolCount() {
        return patrols.size();
    }

    public static int getActivePatrolCount() {
        return (int) patrols.values().stream().filter(PatrolData::isActive).count();
    }

    public static void toggleShow(ServerPlayerEntity player, UUID npcUuid) {
        UUID playerUuid = player.getUuid();
        if (npcUuid.equals(showingParticles.get(playerUuid))) {
            showingParticles.remove(playerUuid);
        } else {
            showingParticles.put(playerUuid, npcUuid);
        }
    }

    public static boolean isShowing(ServerPlayerEntity player) {
        return showingParticles.containsKey(player.getUuid());
    }

    // Integrity check: runs once 5s after server start, then every 5 min
    private static boolean startupIntegrityDone = false;
    private static int startupDelayTicks = 0;
    private static final int STARTUP_INTEGRITY_DELAY = 100; // 5 seconds after start

    public static void tick(MinecraftServer server) {
        // Patrol movement — every tick for smooth navigation
        for (PatrolData patrol : patrols.values()) {
            if (!patrol.isActive() || patrol.getWaypoints().size() < 2) continue;
            tickPatrol(server, patrol);
        }

        // Startup: integrity check + kick-start ALL active patrols (once, 5s after start)
        if (!startupIntegrityDone) {
            startupDelayTicks++;
            if (startupDelayTicks >= STARTUP_INTEGRITY_DELAY) {
                startupIntegrityDone = true;
                tickEntityIntegrity(server);
                // Kick-start ALL active patrols — entities loaded with EasyNPC goals blocking navigation
                int kicked = 0;
                for (PatrolData patrol : patrols.values()) {
                    if (patrol.isActive() && patrol.getWaypoints().size() >= 2) {
                        if (kickStartPatrol(server, patrol)) kicked++;
                    }
                }
                if (kicked > 0 && debug) {
                    CheesePatrolMod.LOGGER.info("[CheesePatrol] Startup: kick-started {} active patrols", kicked);
                }
            }
        }

        // Periodic entity backup
        backupTickCounter++;
        if (backupTickCounter >= BACKUP_INTERVAL) {
            backupTickCounter = 0;
            backupAllEntities(server);
        }

        // Auto-recovery: periodically check ALL patrols for missing entities
        recoveryTickCounter++;
        if (recoveryTickCounter >= RECOVERY_CHECK_INTERVAL) {
            recoveryTickCounter = 0;
            tickEntityIntegrity(server);
        }

        // Particle display — every 10 ticks
        particleTickCounter++;
        if (particleTickCounter >= PARTICLE_INTERVAL) {
            particleTickCounter = 0;
            tickParticles(server);
        }
    }

    private static void tickParticles(MinecraftServer server) {
        if (showingParticles.isEmpty()) return;
        var iter = showingParticles.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null) { iter.remove(); continue; }
            PatrolData patrol = patrols.get(entry.getValue());
            if (patrol == null || patrol.getWaypoints().isEmpty()) { iter.remove(); continue; }
            PatrolMarker.showParticles(player, patrol);
        }
    }

    private static void tickPatrol(MinecraftServer server, PatrolData patrol) {
        Entity entity = findEntity(server, patrol.getNpcUuid(), patrol.getDimension());
        if (entity == null) {
            // Entity not loaded — forceload last known + current waypoint chunk to bring it back
            updateForcedChunks(server, patrol, null);
            // Also forceload the last known position chunk directly
            forceloadLastKnownChunk(server, patrol);
            patrol.incrementMissingTicks();

            // After 30 seconds missing, try to respawn from backup
            if (patrol.getMissingTicks() >= RESPAWN_AFTER_TICKS) {
                if (patrol.getRespawnAttempts() >= MAX_RESPAWN_ATTEMPTS) {
                    CheesePatrolMod.LOGGER.error("[CheesePatrol] NPC {} failed to respawn {} times - pausing patrol (integrity check retries every 5 min)",
                        patrol.getNpcUuid().toString().substring(0, 8), MAX_RESPAWN_ATTEMPTS);
                    patrol.setActive(false);
                    patrol.setPausedBySystem(true); // mark as system-paused for auto-resume
                    save();
                    // Note: entity integrity check (tickEntityIntegrity) will continue
                    // trying to respawn this NPC every 5 min regardless of active state
                    return;
                }
                patrol.incrementRespawnAttempts();
                if (debug) CheesePatrolMod.LOGGER.warn("[CheesePatrol] NPC {} not found after 30s - respawn attempt {}/{}",
                    patrol.getNpcUuid().toString().substring(0, 8), patrol.getRespawnAttempts(), MAX_RESPAWN_ATTEMPTS);
                Entity respawned = respawnEntityFromBackup(server, patrol);
                if (respawned != null) {
                    applyFlyingSafety(respawned, patrol);
                    // Kick-start patrol immediately after respawn
                    kickStartPatrol(server, patrol);
                }
                // Reset timer to give 30s for the entity to register before next attempt
                patrol.resetMissingTicksOnly();
            }
            return;
        }
        if (!(entity instanceof PathAwareEntity mob)) return;

        // Entity found — if it was previously missing, kick-start patrol
        if (patrol.getMissingTicks() > 0) {
            if (debug) CheesePatrolMod.LOGGER.info("[CheesePatrol] NPC {} found after {} ticks missing - kick-starting patrol",
                patrol.getNpcUuid().toString().substring(0, 8), patrol.getMissingTicks());
            backupEntity(entity, patrol.getNpcUuid());
            kickStartPatrol(server, patrol);
        }
        patrol.resetMissingTicks();

        // Protect entity: prevent despawn + reset fall distance
        mob.setPersistent();
        mob.fallDistance = 0;

        // Periodically clear EasyNPC goals that might block patrol navigation (every 5s)
        if (server.getTicks() % 100 == 0) {
            GoalSelector gs = getEasyNPCGoalSelector(entity);
            if (gs != null) {
                gs.clear(g -> true);
            }
        }

        // Track last known position for robust chunk loading on restart
        patrol.setLastKnownPos(entity.getBlockPos().getX(), entity.getBlockPos().getY(), entity.getBlockPos().getZ());

        // Stuck detection: if NPC hasn't made progress toward waypoint for 60s, teleport to it
        // Skip when NPC is intentionally paused (looking at player or waiting at waypoint)
        BlockPos stuckTarget = patrol.getCurrentWaypoint();
        if (stuckTarget != null && !patrol.isLookingAtPlayer() && !patrol.isWaitingAtWaypoint()) {
            double stuckDistSq = mob.squaredDistanceTo(stuckTarget.getX() + 0.5, stuckTarget.getY(), stuckTarget.getZ() + 0.5);
            if (stuckDistSq > 4.0) { // only check if far enough
                patrol.incrementStuckTicks();
                if (patrol.getStuckTicks() >= 1200) { // 60 seconds stuck
                    if (debug) CheesePatrolMod.LOGGER.warn("[CheesePatrol] NPC {} stuck for 60s at dist {} - teleporting to waypoint {}",
                        patrol.getNpcUuid().toString().substring(0, 8), String.format("%.1f", Math.sqrt(stuckDistSq)), patrol.getCurrentWaypointIndex() + 1);
                    mob.refreshPositionAndAngles(stuckTarget.getX() + 0.5, stuckTarget.getY(), stuckTarget.getZ() + 0.5, mob.getYaw(), mob.getPitch());
                    patrol.resetStuckTicks();
                    kickStartPatrol(server, patrol);
                }
            } else {
                patrol.resetStuckTicks(); // making progress → reset
            }
        }

        // Keep NPC chunk + target waypoint chunk forceloaded
        updateForcedChunks(server, patrol, entity.getBlockPos());

        // Trail particle effects (every 2 ticks for smooth trail without spam)
        if (!"none".equals(patrol.getParticleEffect()) && server.getTicks() % 2 == 0) {
            ServerWorld world = getWorld(server, patrol.getDimension());
            if (world != null) {
                PatrolMarker.spawnTrailParticles(world, entity, patrol.getParticleEffect(), patrol.getParticleYOffset(), patrol.getParticleZOffset(), patrol.getParticleRange());
            }
        }

        // Stalled detection: if NPC should be moving but velocity is near zero for 2s, force re-navigate
        if (!patrol.isWaitingAtWaypoint() && !patrol.isLookingAtPlayer()) {
            double vel = mob.getVelocity().horizontalLengthSquared();
            if (vel < 0.001) { // threshold includes EasyNPC idle micro-movement
                patrol.incrementStallTicks();
                if (patrol.getStallTicks() >= 100) { // 5 seconds stalled
                    patrol.resetStallTicks();
                    GoalSelector gs2 = getEasyNPCGoalSelector(entity);
                    if (gs2 != null) gs2.clear(g -> true);
                    mob.getNavigation().stop();
                    BlockPos wp = patrol.getCurrentWaypoint();
                    if (wp != null) {
                        double d = mob.squaredDistanceTo(wp.getX() + 0.5, wp.getY(), wp.getZ() + 0.5);
                        if (d < 2.25) {
                            // Already at waypoint — advance
                            patrol.advanceToNextWaypoint();
                            wp = patrol.getCurrentWaypoint();
                        }
                        if (wp != null) {
                            mob.getNavigation().startMovingTo(wp.getX() + 0.5, wp.getY(), wp.getZ() + 0.5, patrol.getSpeed());
                        }
                    }
                }
            } else {
                patrol.resetStallTicks();
            }
        }

        // Check for nearby players — if found, stop and look at them
        var nearestPlayer = mob.getWorld().getClosestPlayer(mob, patrol.getPlayerDetectionRadius());
        if (nearestPlayer != null) {
            mob.getNavigation().stop();
            mob.setVelocity(Vec3d.ZERO);
            mob.getLookControl().lookAt(nearestPlayer, 30.0f, 30.0f);
            patrol.setLookingAtPlayer(true);
            return;
        }

        if (patrol.isLookingAtPlayer()) {
            patrol.setLookingAtPlayer(false);
        }

        // Wait at waypoint if configured
        if (patrol.isWaitingAtWaypoint()) {
            patrol.setRemainingWaitTicks(patrol.getRemainingWaitTicks() - 1);
            if (patrol.getRemainingWaitTicks() <= 0) {
                patrol.setWaitingAtWaypoint(false);
                patrol.advanceToNextWaypoint();
                moveToCurrentWaypoint(mob, patrol);
            }
            return;
        }

        BlockPos target = patrol.getCurrentWaypoint();
        if (target == null) return;

        boolean flying = isAirWaypoint(mob, target);

        // Apply/remove NoGravity based on current segment
        mob.setNoGravity(flying);

        double tx = target.getX() + 0.5, ty = target.getY(), tz = target.getZ() + 0.5;
        double distSq = mob.squaredDistanceTo(tx, ty, tz);

        // Arrived threshold: must be very close to block center
        double arrivedThresholdSq = flying ? 25.0 : 2.25; // ground: ~1.5 blocks from center
        if (distSq < arrivedThresholdSq) {
            int waitTime = patrol.getWaypointWait(patrol.getCurrentWaypointIndex());
            if (waitTime > 0) {
                mob.getNavigation().stop();
                mob.setVelocity(Vec3d.ZERO);
                patrol.setWaitingAtWaypoint(true);
                patrol.setRemainingWaitTicks(waitTime);
            } else {
                patrol.advanceToNextWaypoint();
                moveToCurrentWaypoint(mob, patrol);
            }
            return;
        }

        if (flying) {
            // Direct flight: set velocity toward target
            double dx = tx - mob.getX();
            double dy = ty - mob.getY();
            double dz = tz - mob.getZ();
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            // Curve: blend direction toward next waypoint for smooth arc
            double blendRadius = 8.0;
            if (dist < blendRadius) {
                int nextIdx = (patrol.getCurrentWaypointIndex() + 1) % patrol.getWaypoints().size();
                BlockPos nextWp = patrol.getWaypoints().get(nextIdx);
                double t = 1.0 - (dist / blendRadius); // 0 at far, 1 at waypoint
                double blend = t * t * t * 0.7; // cubic ease-in, max 70% blend
                double nx = nextWp.getX() + 0.5 - mob.getX();
                double ny = nextWp.getY() - mob.getY();
                double nz = nextWp.getZ() + 0.5 - mob.getZ();
                dx = dx * (1.0 - blend) + nx * blend;
                dy = dy * (1.0 - blend) + ny * blend;
                dz = dz * (1.0 - blend) + nz * blend;
            }
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len > 0.01) {
                dx /= len; dy /= len; dz /= len;
                double flySpeed = patrol.getSpeed() * 0.15;
                mob.setVelocity(dx * flySpeed, dy * flySpeed, dz * flySpeed);
                mob.velocityModified = true;
                // Very smooth yaw interpolation (8% per tick)
                float targetYaw = (float) (Math.atan2(-dx, dz) * (180.0 / Math.PI));
                float currentYaw = mob.getYaw();
                float smoothYaw = currentYaw + MathHelper.wrapDegrees(targetYaw - currentYaw) * 0.08f;
                mob.setYaw(smoothYaw);
                mob.setHeadYaw(smoothYaw);
                mob.setBodyYaw(smoothYaw);
            }
        } else {
            // Ground movement: use direct velocity toward target, bypassing EasyNPC AI goals
            double dx = tx - mob.getX();
            double dz = tz - mob.getZ();
            double hLen = Math.sqrt(dx * dx + dz * dz);
            if (hLen > 0.3) {
                double walkSpeed = patrol.getSpeed() * 0.12;
                mob.setVelocity(dx / hLen * walkSpeed, mob.getVelocity().y, dz / hLen * walkSpeed);
                mob.velocityModified = true;
                // Smooth yaw toward target
                float targetYaw = (float) (Math.atan2(-dx, dz) * (180.0 / Math.PI));
                float currentYaw = mob.getYaw();
                float smoothYaw = currentYaw + MathHelper.wrapDegrees(targetYaw - currentYaw) * 0.15f;
                mob.setYaw(smoothYaw);
                mob.setHeadYaw(smoothYaw);
                mob.setBodyYaw(smoothYaw);
            }
        }
    }

    /**
     * Force kick-start a patrol: clear EasyNPC goals, stop navigation, reset state,
     * and immediately start moving to the current waypoint.
     * Called by /npcpatrol start to ensure NPC actually moves.
     */
    public static boolean kickStartPatrol(MinecraftServer server, PatrolData patrol) {
        Entity entity = findEntity(server, patrol.getNpcUuid(), patrol.getDimension());
        if (entity == null) return false;
        if (!(entity instanceof PathAwareEntity mob)) return false;

        // Clear EasyNPC AI goals that might block pathfinding
        GoalSelector gs = getEasyNPCGoalSelector(entity);
        if (gs != null) {
            gs.clear(g -> true);
        }

        // Stop current navigation + velocity
        mob.getNavigation().stop();
        mob.setVelocity(Vec3d.ZERO);

        // Reset patrol state
        patrol.setLookingAtPlayer(false);
        patrol.setWaitingAtWaypoint(false);

        // Apply flying safety if needed
        applyFlyingSafety(entity, patrol);

        // If already at current waypoint, advance to next (skip wait on kick-start)
        BlockPos wp = patrol.getCurrentWaypoint();
        if (wp != null) {
            double distSq = mob.squaredDistanceTo(wp.getX() + 0.5, wp.getY(), wp.getZ() + 0.5);
            if (distSq < 2.25) {
                patrol.advanceToNextWaypoint();
            }
        }

        // Force immediate move to current waypoint
        moveToCurrentWaypoint(mob, patrol);

        // Forceload patrol chunks
        forceloadPatrolChunks(server, patrol);

        if (debug) CheesePatrolMod.LOGGER.info("[CheesePatrol] Kick-started patrol for NPC {} at waypoint {}/{}",
            patrol.getNpcUuid().toString().substring(0, 8),
            patrol.getCurrentWaypointIndex() + 1, patrol.getWaypoints().size());
        return true;
    }

    private static void moveToCurrentWaypoint(PathAwareEntity mob, PatrolData patrol) {
        BlockPos wp = patrol.getCurrentWaypoint();
        if (wp == null) return;
        boolean flying = isAirWaypoint(mob, wp);
        mob.setNoGravity(flying);
        if (!flying) {
            mob.getNavigation().startMovingTo(wp.getX() + 0.5, wp.getY(), wp.getZ() + 0.5, patrol.getSpeed());
        }
        // Flying movement handled in tickPatrol each tick
    }

    /**
     * Checks if a waypoint requires flying (no solid ground below within 3 blocks).
     */
    private static boolean isAirWaypoint(PathAwareEntity mob, BlockPos target) {
        if (!(mob.getWorld() instanceof ServerWorld world)) return false;
        for (int dy = 0; dy >= -3; dy--) {
            BlockState state = world.getBlockState(target.add(0, dy, 0));
            if (state.isSolidBlock(world, target.add(0, dy, 0))) return false;
        }
        return true; // No solid ground within 3 blocks below — need to fly
    }

    /**
     * Entity integrity check: ensures ALL registered NPCs exist in the world.
     * If an NPC is missing (active or inactive), attempts to respawn from backup.
     * This prevents silent entity loss regardless of patrol active state.
     */
    private static void tickEntityIntegrity(MinecraftServer server) {
        boolean changed = false;
        for (PatrolData patrol : patrols.values()) {
            Entity entity = findEntity(server, patrol.getNpcUuid(), patrol.getDimension());
            if (entity != null) {
                // Entity exists — ensure it's persistent
                if (entity instanceof PathAwareEntity mob) {
                    mob.setPersistent();
                }
                // If entity is present but patrol was system-paused, auto-resume
                if (patrol.isPausedBySystem() && !patrol.isActive() && patrol.getWaypoints().size() >= 2) {
                    if (debug) CheesePatrolMod.LOGGER.info("[CheesePatrol] Auto-resuming patrol {} - entity found, was system-paused",
                        patrol.getNpcUuid().toString().substring(0, 8));
                    patrol.setActive(true);
                    patrol.setPausedBySystem(false);
                    patrol.resetMissingTicks();
                    kickStartPatrol(server, patrol);
                    changed = true;
                }
                continue;
            }

            // Entity missing — try to respawn from backup
            if (!hasBackupFile(patrol.getNpcUuid())) {
                if (debug) CheesePatrolMod.LOGGER.warn("[CheesePatrol] NPC {} missing with NO backup - cannot recover",
                    patrol.getNpcUuid().toString().substring(0, 8));
                continue;
            }

            // Forceload the chunk first
            forceloadLastKnownChunk(server, patrol);

            // Try respawn
            if (debug) CheesePatrolMod.LOGGER.warn("[CheesePatrol] Integrity check: NPC {} missing (patrol {}) - respawning from backup",
                patrol.getNpcUuid().toString().substring(0, 8),
                patrol.isActive() ? "active" : "inactive");
            Entity respawned = respawnEntityFromBackup(server, patrol);
            if (respawned != null) {
                applyFlyingSafety(respawned, patrol);
                if (respawned instanceof PathAwareEntity mob) {
                    mob.setPersistent();
                }
                patrol.resetMissingTicks();

                // Auto-resume patrol route if it was system-paused (not admin-stopped)
                if (patrol.isPausedBySystem() && patrol.getWaypoints().size() >= 2) {
                    patrol.setActive(true);
                    patrol.setPausedBySystem(false);
                    kickStartPatrol(server, patrol);
                    if (debug) CheesePatrolMod.LOGGER.info("[CheesePatrol] Integrity check: NPC {} respawned AND patrol auto-resumed from waypoint {}",
                        patrol.getNpcUuid().toString().substring(0, 8), patrol.getCurrentWaypointIndex());
                } else {
                    if (debug) CheesePatrolMod.LOGGER.info("[CheesePatrol] Integrity check: NPC {} respawned at {}, {}, {} (patrol stays {})",
                        patrol.getNpcUuid().toString().substring(0, 8),
                        (int) respawned.getX(), (int) respawned.getY(), (int) respawned.getZ(),
                        patrol.isActive() ? "active" : "inactive");
                }
                changed = true;
            } else {
                CheesePatrolMod.LOGGER.error("[CheesePatrol] Integrity check: FAILED to respawn NPC {} from backup",
                    patrol.getNpcUuid().toString().substring(0, 8));
            }
        }
        if (changed) save();
    }

    /** Forceload the last known position chunk when entity is missing */
    private static void forceloadLastKnownChunk(MinecraftServer server, PatrolData patrol) {
        if (!patrol.hasLastKnownPos()) return;
        ServerWorld world = getWorld(server, patrol.getDimension());
        if (world == null) return;
        long packed = ChunkPos.toLong(patrol.getLastX() >> 4, patrol.getLastZ() >> 4);
        if (!world.getForcedChunks().contains(packed)) {
            world.setChunkForced((int) packed, (int) (packed >>> 32), true);
            // Track it so it gets cleaned up later
            patrolForcedChunks.computeIfAbsent(patrol.getNpcUuid(), k -> new HashSet<>()).add(packed);
        }
    }

    /** Apply NoGravity to a respawned entity if its current waypoint is a flying one */
    private static void applyFlyingSafety(Entity entity, PatrolData patrol) {
        if (!(entity instanceof PathAwareEntity mob)) return;
        BlockPos target = patrol.getCurrentWaypoint();
        if (target != null && isAirWaypoint(mob, target)) {
            mob.setNoGravity(true);
        }
        mob.fallDistance = 0;
    }

    /** Forceload all chunks for a single patrol (used by recovery) */
    private static void forceloadPatrolChunks(MinecraftServer server, PatrolData patrol) {
        ServerWorld world = getWorld(server, patrol.getDimension());
        if (world == null) return;
        Set<Long> chunks = new HashSet<>();
        if (patrol.hasLastKnownPos()) {
            long packed = ChunkPos.toLong(patrol.getLastX() >> 4, patrol.getLastZ() >> 4);
            chunks.add(packed);
            if (!world.getForcedChunks().contains(packed)) {
                world.setChunkForced((int) packed, (int) (packed >>> 32), true);
            }
        }
        for (BlockPos wp : patrol.getWaypoints()) {
            long packed = ChunkPos.toLong(wp.getX() >> 4, wp.getZ() >> 4);
            chunks.add(packed);
            if (!world.getForcedChunks().contains(packed)) {
                world.setChunkForced((int) packed, (int) (packed >>> 32), true);
            }
        }
        patrolForcedChunks.put(patrol.getNpcUuid(), chunks);
    }

    private static Entity findEntity(MinecraftServer server, UUID uuid, String dimension) {
        for (ServerWorld world : server.getWorlds()) {
            if (dimension != null && !world.getRegistryKey().getValue().toString().equals(dimension)) continue;
            Entity entity = world.getEntity(uuid);
            if (entity != null) return entity;
        }
        // Fallback: search all worlds if dimension doesn't match
        if (dimension != null) {
            for (ServerWorld world : server.getWorlds()) {
                Entity entity = world.getEntity(uuid);
                if (entity != null) return entity;
            }
        }
        return null;
    }

    /**
     * Tries to get the GoalSelector from an EasyNPC entity via reflection.
     * Returns null if not an EasyNPC or if reflection fails.
     */
    public static GoalSelector getEasyNPCGoalSelector(Entity entity) {
        try {
            // EasyNPC interface declares getEntityGoalSelector()
            Method method = entity.getClass().getMethod("getEntityGoalSelector");
            Object result = method.invoke(entity);
            if (result instanceof GoalSelector gs) return gs;
        } catch (Exception ignored) {
            // Not an EasyNPC entity or method not found
        }
        return null;
    }

    // ── Entity NBT backup & respawn ─────────────────────────────────────────

    /** Save entity NBT to disk for respawn if it disappears. */
    public static void backupEntity(Entity entity, UUID npcUuid) {
        try {
            Files.createDirectories(ENTITY_BACKUP_DIR);
            NbtCompound nbt = new NbtCompound();
            entity.saveNbt(nbt);
            NbtIo.writeCompressed(nbt, ENTITY_BACKUP_DIR.resolve(npcUuid + ".dat"));
        } catch (Exception e) {
            CheesePatrolMod.LOGGER.error("[CheesePatrol] Failed to backup entity {}", npcUuid, e);
        }
    }

    /** Backup ALL registered patrol entities (active AND inactive). Called periodically + on server stop. */
    public static void backupAllEntities(MinecraftServer server) {
        for (PatrolData patrol : patrols.values()) {
            Entity entity = findEntity(server, patrol.getNpcUuid(), patrol.getDimension());
            if (entity != null) {
                backupEntity(entity, patrol.getNpcUuid());
            }
        }
    }

    /** Respawn entity from saved NBT backup. Returns the new entity or null. */
    private static Entity respawnEntityFromBackup(MinecraftServer server, PatrolData patrol) {
        Path nbtPath = ENTITY_BACKUP_DIR.resolve(patrol.getNpcUuid() + ".dat");
        if (!Files.exists(nbtPath)) {
            if (debug) CheesePatrolMod.LOGGER.warn("[CheesePatrol] No backup found for NPC {}", patrol.getNpcUuid().toString().substring(0, 8));
            return null;
        }

        try {
            NbtCompound nbt = NbtIo.readCompressed(nbtPath, NbtSizeTracker.ofUnlimitedBytes());
            ServerWorld world = getWorld(server, patrol.getDimension());
            if (world == null) {
                CheesePatrolMod.LOGGER.error("[CheesePatrol] Cannot respawn NPC {} - dimension '{}' not found",
                    patrol.getNpcUuid().toString().substring(0, 8), patrol.getDimension());
                return null;
            }

            // Use EntityType.loadEntityWithPassengers to create the entity from full NBT
            final Entity[] result = {null};
            EntityType.loadEntityWithPassengers(nbt, world, entity -> {
                if (world.spawnEntity(entity)) {
                    result[0] = entity;
                }
                return entity;
            });

            if (result[0] != null) {
                // Teleport to current waypoint so NPC doesn't walk across the map
                BlockPos wp = patrol.getCurrentWaypoint();
                if (wp != null) {
                    result[0].refreshPositionAndAngles(wp.getX() + 0.5, wp.getY(), wp.getZ() + 0.5, result[0].getYaw(), result[0].getPitch());
                    patrol.setLastKnownPos(wp.getX(), wp.getY(), wp.getZ());
                    if (debug) CheesePatrolMod.LOGGER.info("[CheesePatrol] Respawned NPC {} at waypoint {}, {}, {}",
                        patrol.getNpcUuid().toString().substring(0, 8), wp.getX(), wp.getY(), wp.getZ());
                } else {
                    if (debug) CheesePatrolMod.LOGGER.info("[CheesePatrol] Respawned NPC {} from backup at {}, {}, {}",
                        patrol.getNpcUuid().toString().substring(0, 8),
                        (int) result[0].getX(), (int) result[0].getY(), (int) result[0].getZ());
                }
                return result[0];
            } else {
                if (debug) CheesePatrolMod.LOGGER.warn("[CheesePatrol] spawnEntity returned false for NPC {} - UUID conflict or invalid NBT",
                    patrol.getNpcUuid().toString().substring(0, 8));
            }
        } catch (Exception e) {
            CheesePatrolMod.LOGGER.error("[CheesePatrol] Corrupted backup for NPC {} - deleting to prevent retry loop: {}",
                patrol.getNpcUuid().toString().substring(0, 8), e.getMessage());
            try { Files.deleteIfExists(nbtPath); } catch (Exception ignored) {}
        }
        return null;
    }

    /** Delete backup file when patrol is cleared. */
    private static void deleteEntityBackup(UUID npcUuid) {
        try {
            Files.deleteIfExists(ENTITY_BACKUP_DIR.resolve(npcUuid + ".dat"));
        } catch (Exception ignored) {}
    }

    // ── Dynamic chunk forceloading ──────────────────────────────────────────

    /**
     * Updates forceloaded chunks for a patrol: loads NPC chunk + target waypoint chunk,
     * unloads chunks no longer needed. Only 2 chunks per NPC at most.
     */
    private static void updateForcedChunks(MinecraftServer server, PatrolData patrol, BlockPos entityPos) {
        ServerWorld world = getWorld(server, patrol.getDimension());
        if (world == null) return;

        UUID npcId = patrol.getNpcUuid();
        Set<Long> needed = new HashSet<>();

        // NPC's current chunk
        if (entityPos != null) {
            needed.add(ChunkPos.toLong(entityPos.getX() >> 4, entityPos.getZ() >> 4));
        }
        // Target waypoint's chunk
        BlockPos target = patrol.getCurrentWaypoint();
        if (target != null) {
            needed.add(ChunkPos.toLong(target.getX() >> 4, target.getZ() >> 4));
        }

        Set<Long> current = patrolForcedChunks.getOrDefault(npcId, Collections.emptySet());

        // Unforce chunks no longer needed by this patrol
        for (long packed : current) {
            if (!needed.contains(packed) && !isChunkNeededByOtherPatrol(npcId, packed)) {
                world.setChunkForced((int) packed, (int) (packed >>> 32), false);
            }
        }

        // Force new chunks (skip already-forced to avoid interfering with /forceload)
        for (long packed : needed) {
            if (!current.contains(packed) && !world.getForcedChunks().contains(packed)) {
                world.setChunkForced((int) packed, (int) (packed >>> 32), true);
            }
        }

        if (needed.isEmpty()) {
            patrolForcedChunks.remove(npcId);
        } else {
            patrolForcedChunks.put(npcId, needed);
        }
    }

    private static boolean isChunkNeededByOtherPatrol(UUID excludeNpc, long packedChunk) {
        for (Map.Entry<UUID, Set<Long>> e : patrolForcedChunks.entrySet()) {
            if (!e.getKey().equals(excludeNpc) && e.getValue().contains(packedChunk)) {
                return true;
            }
        }
        return false;
    }

    /** Release all forceloaded chunks for a single patrol (on stop/clear/remove). */
    public static void releaseForcedChunks(MinecraftServer server, PatrolData patrol) {
        UUID npcId = patrol.getNpcUuid();
        Set<Long> chunks = patrolForcedChunks.remove(npcId);
        if (chunks == null || chunks.isEmpty()) return;

        ServerWorld world = getWorld(server, patrol.getDimension());
        if (world == null) return;

        for (long packed : chunks) {
            if (!isChunkNeededByOtherPatrol(npcId, packed)) {
                world.setChunkForced((int) packed, (int) (packed >>> 32), false);
            }
        }
    }

    /**
     * Immediately forceload chunks for ALL registered patrols at server start.
     * - Active patrols: forceload last-known-pos + ALL waypoint chunks
     * - Inactive patrols: forceload ONLY last-known-pos chunk (to keep the NPC entity alive)
     * Called BEFORE any entity ticking so entities don't despawn.
     */
    public static void forceloadActivePatrolChunks(MinecraftServer server) {
        int chunkCount = 0;
        for (PatrolData patrol : patrols.values()) {
            ServerWorld world = getWorld(server, patrol.getDimension());
            if (world == null) continue;

            Set<Long> chunks = new HashSet<>();

            // ALL patrols (active or not): forceload last known NPC position chunk
            // This ensures the entity stays loaded and isn't lost on restart
            if (patrol.hasLastKnownPos()) {
                long packed = ChunkPos.toLong(patrol.getLastX() >> 4, patrol.getLastZ() >> 4);
                chunks.add(packed);
                if (!world.getForcedChunks().contains(packed)) {
                    world.setChunkForced((int) packed, (int) (packed >>> 32), true);
                    chunkCount++;
                }
            }

            // Active patrols only: also forceload ALL waypoint chunks
            if (patrol.isActive() && patrol.getWaypoints().size() >= 2) {
                for (BlockPos wp : patrol.getWaypoints()) {
                    long packed = ChunkPos.toLong(wp.getX() >> 4, wp.getZ() >> 4);
                    chunks.add(packed);
                    if (!world.getForcedChunks().contains(packed)) {
                        world.setChunkForced((int) packed, (int) (packed >>> 32), true);
                        chunkCount++;
                    }
                }
            }

            if (!chunks.isEmpty()) {
                patrolForcedChunks.put(patrol.getNpcUuid(), chunks);
            }
        }
        if (chunkCount > 0) {
            CheesePatrolMod.LOGGER.info("[CheesePatrol] Pre-loaded {} chunks for {} patrol(s)", chunkCount, patrols.size());
        }
    }

    /** Release all patrol-forceloaded chunks (on server stop). */
    public static void releaseAllForcedChunks(MinecraftServer server) {
        for (PatrolData patrol : patrols.values()) {
            releaseForcedChunks(server, patrol);
        }
        patrolForcedChunks.clear();
    }

    private static ServerWorld getWorld(MinecraftServer server, String dimension) {
        for (ServerWorld sw : server.getWorlds()) {
            if (sw.getRegistryKey().getValue().toString().equals(dimension)) return sw;
        }
        return null;
    }
}
