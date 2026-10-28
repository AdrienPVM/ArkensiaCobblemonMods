package com.cheesepatrol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PatrolData {

    private final UUID npcUuid;
    private final List<BlockPos> waypoints = new ArrayList<>();
    private final List<Integer> waypointWaitTicks = new ArrayList<>(); // per-waypoint wait (0 = smooth)
    private double speed = 0.5;
    private double playerDetectionRadius = 5.0;
    private boolean active = false;
    private int currentWaypointIndex = 0;
    private String dimension = "minecraft:overworld";
    private int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
    private String particleEffect = "none"; // none, sparkle, flame, soul, enchant, end_rod, cherry, smoke, dust
    private float particleYOffset = 0f; // -1.0 to 1.0 height offset
    private float particleZOffset = 0f; // -5.0 to 5.0 forward/backward offset (positive = behind, negative = ahead)
    private int particleRange = 16; // max distance in blocks to see trail particles
    private boolean pausedBySystem = false; // true = auto-disabled by failed respawns, false = admin stopped

    // Transient state (not persisted)
    private transient boolean waitingAtWaypoint = false;
    private transient int remainingWaitTicks = 0;
    private transient boolean lookingAtPlayer = false;
    private transient int missingTicks = 0;
    private transient int respawnAttempts = 0;
    private transient int stuckTicks = 0;
    private transient int stallTicks = 0;

    public PatrolData(UUID npcUuid) {
        this.npcUuid = npcUuid;
    }

    public UUID getNpcUuid() { return npcUuid; }
    public List<BlockPos> getWaypoints() { return waypoints; }
    public double getSpeed() { return speed; }
    public void setSpeed(double speed) { this.speed = Math.max(0.1, Math.min(5.0, speed)); }
    public double getPlayerDetectionRadius() { return playerDetectionRadius; }
    public void setPlayerDetectionRadius(double radius) { this.playerDetectionRadius = Math.max(1.0, Math.min(32.0, radius)); }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public int getCurrentWaypointIndex() { return currentWaypointIndex; }
    public void setCurrentWaypointIndex(int index) { this.currentWaypointIndex = index; }
    public String getDimension() { return dimension; }
    public void setDimension(String dimension) { this.dimension = dimension; }
    public boolean isWaitingAtWaypoint() { return waitingAtWaypoint; }
    public void setWaitingAtWaypoint(boolean waiting) { this.waitingAtWaypoint = waiting; }
    public int getRemainingWaitTicks() { return remainingWaitTicks; }
    public void setRemainingWaitTicks(int ticks) { this.remainingWaitTicks = ticks; }
    public boolean isLookingAtPlayer() { return lookingAtPlayer; }
    public void setLookingAtPlayer(boolean looking) { this.lookingAtPlayer = looking; }
    public List<Integer> getWaypointWaitTicks() { return waypointWaitTicks; }
    public int getMissingTicks() { return missingTicks; }
    public void incrementMissingTicks() { this.missingTicks++; }
    public void resetMissingTicks() { this.missingTicks = 0; this.respawnAttempts = 0; }
    public void resetMissingTicksOnly() { this.missingTicks = 0; } // keep respawnAttempts
    public int getRespawnAttempts() { return respawnAttempts; }
    public void incrementRespawnAttempts() { this.respawnAttempts++; }
    public int getStuckTicks() { return stuckTicks; }
    public void incrementStuckTicks() { this.stuckTicks++; }
    public void resetStuckTicks() { this.stuckTicks = 0; }
    public int getStallTicks() { return stallTicks; }
    public void incrementStallTicks() { this.stallTicks++; }
    public void resetStallTicks() { this.stallTicks = 0; }
    public boolean hasLastKnownPos() { return lastX != Integer.MIN_VALUE; }
    public int getLastX() { return lastX; }
    public int getLastY() { return lastY; }
    public int getLastZ() { return lastZ; }
    public void setLastKnownPos(int x, int y, int z) { this.lastX = x; this.lastY = y; this.lastZ = z; }
    public String getParticleEffect() { return particleEffect; }
    public void setParticleEffect(String effect) { this.particleEffect = effect != null ? effect : "none"; }
    public float getParticleYOffset() { return particleYOffset; }
    public void setParticleYOffset(float offset) { this.particleYOffset = Math.max(-5f, Math.min(5f, offset)); }
    public float getParticleZOffset() { return particleZOffset; }
    public void setParticleZOffset(float offset) { this.particleZOffset = Math.max(-5f, Math.min(5f, offset)); }
    public int getParticleRange() { return particleRange; }
    public void setParticleRange(int range) { this.particleRange = Math.max(16, Math.min(512, range)); }
    public boolean isPausedBySystem() { return pausedBySystem; }
    public void setPausedBySystem(boolean paused) { this.pausedBySystem = paused; }

    public void addWaypoint(BlockPos pos) {
        addWaypoint(pos, 0);
    }

    public void addWaypoint(BlockPos pos, int waitTicks) {
        waypoints.add(pos);
        waypointWaitTicks.add(waitTicks);
    }

    public void setWaypointWait(int index, int ticks) {
        if (index >= 0 && index < waypointWaitTicks.size()) {
            waypointWaitTicks.set(index, Math.max(0, ticks));
        }
    }

    public int getWaypointWait(int index) {
        if (index >= 0 && index < waypointWaitTicks.size()) {
            return waypointWaitTicks.get(index);
        }
        return 0;
    }

    public void removeWaypoint(int index) {
        if (index >= 0 && index < waypoints.size()) {
            waypoints.remove(index);
            waypointWaitTicks.remove(index);
            if (currentWaypointIndex >= waypoints.size()) {
                currentWaypointIndex = 0;
            }
        }
    }

    public void clearWaypoints() {
        waypoints.clear();
        waypointWaitTicks.clear();
        currentWaypointIndex = 0;
        active = false;
        pausedBySystem = false;
    }

    public BlockPos getCurrentWaypoint() {
        if (waypoints.isEmpty()) return null;
        return waypoints.get(currentWaypointIndex % waypoints.size());
    }

    public void advanceToNextWaypoint() {
        if (!waypoints.isEmpty()) {
            currentWaypointIndex = (currentWaypointIndex + 1) % waypoints.size();
        }
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("npcUuid", npcUuid.toString());
        obj.addProperty("speed", speed);
        obj.addProperty("playerDetectionRadius", playerDetectionRadius);
        obj.addProperty("active", active);
        obj.addProperty("currentWaypointIndex", currentWaypointIndex);
        obj.addProperty("dimension", dimension);

        if (lastX != Integer.MIN_VALUE) {
            obj.addProperty("lastX", lastX);
            obj.addProperty("lastY", lastY);
            obj.addProperty("lastZ", lastZ);
        }
        if (!"none".equals(particleEffect)) {
            obj.addProperty("particleEffect", particleEffect);
        }
        if (particleYOffset != 0f) {
            obj.addProperty("particleYOffset", particleYOffset);
        }
        if (particleZOffset != 0f) {
            obj.addProperty("particleZOffset", particleZOffset);
        }
        if (particleRange != 16) {
            obj.addProperty("particleRange", particleRange);
        }
        if (pausedBySystem) {
            obj.addProperty("pausedBySystem", true);
        }

        JsonArray wpArray = new JsonArray();
        for (int i = 0; i < waypoints.size(); i++) {
            BlockPos pos = waypoints.get(i);
            JsonObject wp = new JsonObject();
            wp.addProperty("x", pos.getX());
            wp.addProperty("y", pos.getY());
            wp.addProperty("z", pos.getZ());
            int wt = i < waypointWaitTicks.size() ? waypointWaitTicks.get(i) : 0;
            if (wt > 0) wp.addProperty("wait", wt);
            wpArray.add(wp);
        }
        obj.add("waypoints", wpArray);
        return obj;
    }

    public static PatrolData fromJson(JsonObject obj) {
        UUID uuid = UUID.fromString(obj.get("npcUuid").getAsString());
        PatrolData data = new PatrolData(uuid);
        if (obj.has("speed")) data.speed = obj.get("speed").getAsDouble();
        if (obj.has("playerDetectionRadius")) data.playerDetectionRadius = obj.get("playerDetectionRadius").getAsDouble();
        if (obj.has("active")) data.active = obj.get("active").getAsBoolean();
        if (obj.has("currentWaypointIndex")) data.currentWaypointIndex = obj.get("currentWaypointIndex").getAsInt();
        if (obj.has("dimension")) data.dimension = obj.get("dimension").getAsString();
        if (obj.has("lastX")) {
            data.lastX = obj.get("lastX").getAsInt();
            data.lastY = obj.get("lastY").getAsInt();
            data.lastZ = obj.get("lastZ").getAsInt();
        }
        if (obj.has("particleEffect")) data.particleEffect = obj.get("particleEffect").getAsString();
        if (obj.has("particleYOffset")) data.particleYOffset = obj.get("particleYOffset").getAsFloat();
        if (obj.has("particleZOffset")) data.particleZOffset = obj.get("particleZOffset").getAsFloat();
        if (obj.has("particleRange")) data.particleRange = obj.get("particleRange").getAsInt();
        if (obj.has("pausedBySystem")) data.pausedBySystem = obj.get("pausedBySystem").getAsBoolean();

        if (obj.has("waypoints")) {
            JsonArray wpArray = obj.getAsJsonArray("waypoints");
            for (int i = 0; i < wpArray.size(); i++) {
                JsonObject wp = wpArray.get(i).getAsJsonObject();
                data.waypoints.add(new BlockPos(wp.get("x").getAsInt(), wp.get("y").getAsInt(), wp.get("z").getAsInt()));
                data.waypointWaitTicks.add(wp.has("wait") ? wp.get("wait").getAsInt() : 0);
            }
        }
        return data;
    }
}
