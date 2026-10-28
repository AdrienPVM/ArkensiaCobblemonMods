package com.cobblecap.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A "tenure" — one continuous reign of a ranked Maître de la Ligue.
 * Created each time a player wins Conseil 4 in RANKED mode and ended when a new
 * ranked winner emerges (or remains open as the current tenure).
 * <p>
 * Even if the same player re-claims the title later, that creates a NEW tenure
 * with an empty failedAttempts map (user spec 2026-05-17 — fresh slate).
 * <p>
 * failedAttempts: per-challenger UUID, the count of losses + last fail timestamp.
 * Incremented when a player loses a Conseil 4 battle against the current Régis
 * NPC while in RANKED mode (cobblecap_mode score == 2).
 */
public class Tenure {
    public UUID id;                                      // unique tenure id
    public UUID championUuid;
    public String championName;
    public long startedAt;                               // epoch ms — when this player won
    public long endedAt;                                 // 0 if still current, else when dethroned
    public long bestTimeMs;                              // chrono recorded at the win (informational)
    public LinkedHashMap<String, FailRecord> failedAttempts = new LinkedHashMap<>();

    public static class FailRecord {
        public String playerName;
        public int count;
        public long lastFailAt;

        public FailRecord(String name, int count, long when) {
            this.playerName = name;
            this.count = count;
            this.lastFailAt = when;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("playerName", playerName);
            o.addProperty("count", count);
            o.addProperty("lastFailAt", lastFailAt);
            return o;
        }
        public static FailRecord fromJson(JsonObject o) {
            return new FailRecord(
                    o.has("playerName") ? o.get("playerName").getAsString() : "?",
                    o.has("count") ? o.get("count").getAsInt() : 0,
                    o.has("lastFailAt") ? o.get("lastFailAt").getAsLong() : 0L);
        }
    }

    public Tenure() {}

    public Tenure(UUID id, UUID championUuid, String championName, long startedAt, long bestTimeMs) {
        this.id = id;
        this.championUuid = championUuid;
        this.championName = championName;
        this.startedAt = startedAt;
        this.bestTimeMs = bestTimeMs;
    }

    /** Increment the loss tally for a given challenger. Caller persists. */
    public void recordFailure(UUID challengerUuid, String challengerName) {
        String key = challengerUuid.toString();
        FailRecord r = failedAttempts.get(key);
        long now = System.currentTimeMillis();
        if (r == null) {
            failedAttempts.put(key, new FailRecord(challengerName, 1, now));
        } else {
            r.count++;
            r.lastFailAt = now;
            r.playerName = challengerName;  // refresh in case the player renamed
        }
    }

    public boolean isCurrent() { return endedAt == 0L; }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id.toString());
        o.addProperty("championUuid", championUuid.toString());
        o.addProperty("championName", championName);
        o.addProperty("startedAt", startedAt);
        if (endedAt > 0) o.addProperty("endedAt", endedAt);
        if (bestTimeMs > 0) o.addProperty("bestTimeMs", bestTimeMs);
        JsonArray fails = new JsonArray();
        for (Map.Entry<String, FailRecord> e : failedAttempts.entrySet()) {
            JsonObject row = e.getValue().toJson();
            row.addProperty("uuid", e.getKey());
            fails.add(row);
        }
        o.add("failedAttempts", fails);
        return o;
    }

    public static Tenure fromJson(JsonObject o) {
        Tenure t = new Tenure();
        t.id = UUID.fromString(o.get("id").getAsString());
        t.championUuid = UUID.fromString(o.get("championUuid").getAsString());
        t.championName = o.get("championName").getAsString();
        t.startedAt = o.get("startedAt").getAsLong();
        t.endedAt = o.has("endedAt") ? o.get("endedAt").getAsLong() : 0L;
        t.bestTimeMs = o.has("bestTimeMs") ? o.get("bestTimeMs").getAsLong() : 0L;
        if (o.has("failedAttempts")) {
            for (var el : o.getAsJsonArray("failedAttempts")) {
                JsonObject row = el.getAsJsonObject();
                String key = row.has("uuid") ? row.get("uuid").getAsString() : UUID.randomUUID().toString();
                t.failedAttempts.put(key, FailRecord.fromJson(row));
            }
        }
        return t;
    }
}
