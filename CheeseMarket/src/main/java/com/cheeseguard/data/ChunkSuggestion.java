package com.cheeseguard.data;

import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * A player suggestion for a chunk area to be added to a ChunkSet.
 * Created via /chunkstoclear suggest
 */
public class ChunkSuggestion {

    private final String id;              // unique ID
    private final String playerName;
    private final UUID   playerUuid;
    private final double x, y, z;
    private final String world;
    private final long   createdAt;       // epoch millis

    public ChunkSuggestion(String id, String playerName, UUID playerUuid,
                           double x, double y, double z, String world, long createdAt) {
        this.id = id;
        this.playerName = playerName;
        this.playerUuid = playerUuid;
        this.x = x;
        this.y = y;
        this.z = z;
        this.world = world;
        this.createdAt = createdAt;
    }

    public String getId()         { return id; }
    public String getPlayerName() { return playerName; }
    public UUID   getPlayerUuid() { return playerUuid; }
    public double getX()          { return x; }
    public double getY()          { return y; }
    public double getZ()          { return z; }
    public String getWorld()      { return world; }
    public long   getCreatedAt()  { return createdAt; }

    // ── JSON ──────────────────────────────────────────────────────────────────

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id",         id);
        o.addProperty("playerName", playerName);
        o.addProperty("playerUuid", playerUuid.toString());
        o.addProperty("x",          x);
        o.addProperty("y",          y);
        o.addProperty("z",          z);
        o.addProperty("world",      world);
        o.addProperty("createdAt",  createdAt);
        return o;
    }

    public static ChunkSuggestion fromJson(JsonObject o) {
        return new ChunkSuggestion(
            o.get("id").getAsString(),
            o.has("playerName") ? o.get("playerName").getAsString() : "?",
            UUID.fromString(o.get("playerUuid").getAsString()),
            o.get("x").getAsDouble(),
            o.get("y").getAsDouble(),
            o.get("z").getAsDouble(),
            o.has("world") ? o.get("world").getAsString() : "minecraft:overworld",
            o.get("createdAt").getAsLong()
        );
    }
}
