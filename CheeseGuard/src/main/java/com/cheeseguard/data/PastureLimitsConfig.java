package com.cheeseguard.data;

import com.google.gson.JsonObject;

public class PastureLimitsConfig {

    private int maxPerChunk = 1;
    private int maxPerChunkPerPlayer = 1;

    public int getMaxPerChunk() { return maxPerChunk; }
    public int getMaxPerChunkPerPlayer() { return maxPerChunkPerPlayer; }

    public void setMaxPerChunk(int v) { this.maxPerChunk = Math.max(0, v); }
    public void setMaxPerChunkPerPlayer(int v) { this.maxPerChunkPerPlayer = Math.max(0, v); }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("maxPerChunk", maxPerChunk);
        o.addProperty("maxPerChunkPerPlayer", maxPerChunkPerPlayer);
        return o;
    }

    public static PastureLimitsConfig fromJson(JsonObject o) {
        PastureLimitsConfig c = new PastureLimitsConfig();
        if (o.has("maxPerChunk")) c.maxPerChunk = Math.max(0, o.get("maxPerChunk").getAsInt());
        if (o.has("maxPerChunkPerPlayer")) c.maxPerChunkPerPlayer = Math.max(0, o.get("maxPerChunkPerPlayer").getAsInt());
        return c;
    }
}
