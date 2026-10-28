package com.cheeseguard.data;

import com.google.gson.JsonObject;

/**
 * One prison record. Used for both active prisoners (releasedAt == 0)
 * and historical entries (releasedAt > 0).
 */
public class PrisonEntry {

    public static final String RELEASE_EXPIRED = "EXPIRED";
    public static final String RELEASE_PARDONED = "PARDONED";

    private String playerUuid;
    private String playerName;
    private String reason;
    private long startedAt;     // epoch ms
    private long durationMs;
    private long endsAt;        // startedAt + durationMs

    private String issuedBy;        // uuid of admin (or "console")
    private String issuedByName;

    // History fields — 0/null while the entry is active.
    private long releasedAt;
    private String releaseReason;   // RELEASE_EXPIRED | RELEASE_PARDONED
    private String releasedBy;      // uuid (null if EXPIRED)
    private String releasedByName;

    public PrisonEntry() {}

    public PrisonEntry(String playerUuid, String playerName, String reason,
                       long startedAt, long durationMs,
                       String issuedBy, String issuedByName) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.reason = reason;
        this.startedAt = startedAt;
        this.durationMs = durationMs;
        this.endsAt = startedAt + durationMs;
        this.issuedBy = issuedBy;
        this.issuedByName = issuedByName;
    }

    public boolean isActive() { return releasedAt == 0L; }
    public boolean hasExpired(long nowMs) { return nowMs >= endsAt; }
    public long remainingMs(long nowMs) { return Math.max(0L, endsAt - nowMs); }

    public String getPlayerUuid()    { return playerUuid; }
    public String getPlayerName()    { return playerName; }
    public String getReason()        { return reason; }
    public long   getStartedAt()     { return startedAt; }
    public long   getDurationMs()    { return durationMs; }
    public long   getEndsAt()        { return endsAt; }
    public String getIssuedBy()      { return issuedBy; }
    public String getIssuedByName()  { return issuedByName; }
    public long   getReleasedAt()    { return releasedAt; }
    public String getReleaseReason() { return releaseReason; }
    public String getReleasedBy()    { return releasedBy; }
    public String getReleasedByName(){ return releasedByName; }

    public void setPlayerName(String n) { this.playerName = n; }
    public void setReason(String r)     { this.reason = r; }
    public void setEndsAt(long e)       { this.endsAt = e; this.durationMs = e - startedAt; }

    public void markReleased(long when, String reason, String byUuid, String byName) {
        this.releasedAt = when;
        this.releaseReason = reason;
        this.releasedBy = byUuid;
        this.releasedByName = byName;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("playerUuid", playerUuid);
        o.addProperty("playerName", playerName);
        o.addProperty("reason", reason);
        o.addProperty("startedAt", startedAt);
        o.addProperty("durationMs", durationMs);
        o.addProperty("endsAt", endsAt);
        if (issuedBy != null)     o.addProperty("issuedBy", issuedBy);
        if (issuedByName != null) o.addProperty("issuedByName", issuedByName);
        if (releasedAt > 0L) {
            o.addProperty("releasedAt", releasedAt);
            if (releaseReason != null)   o.addProperty("releaseReason", releaseReason);
            if (releasedBy != null)      o.addProperty("releasedBy", releasedBy);
            if (releasedByName != null)  o.addProperty("releasedByName", releasedByName);
        }
        return o;
    }

    public static PrisonEntry fromJson(JsonObject o) {
        PrisonEntry e = new PrisonEntry();
        e.playerUuid    = o.has("playerUuid")    ? o.get("playerUuid").getAsString() : "";
        e.playerName    = o.has("playerName")    ? o.get("playerName").getAsString() : "";
        e.reason        = o.has("reason")        ? o.get("reason").getAsString() : "";
        e.startedAt     = o.has("startedAt")     ? o.get("startedAt").getAsLong() : 0L;
        e.durationMs    = o.has("durationMs")    ? o.get("durationMs").getAsLong() : 0L;
        e.endsAt        = o.has("endsAt")        ? o.get("endsAt").getAsLong()
                                                  : e.startedAt + e.durationMs;
        e.issuedBy      = o.has("issuedBy")      ? o.get("issuedBy").getAsString() : null;
        e.issuedByName  = o.has("issuedByName")  ? o.get("issuedByName").getAsString() : null;
        e.releasedAt    = o.has("releasedAt")    ? o.get("releasedAt").getAsLong() : 0L;
        e.releaseReason = o.has("releaseReason") ? o.get("releaseReason").getAsString() : null;
        e.releasedBy    = o.has("releasedBy")    ? o.get("releasedBy").getAsString() : null;
        e.releasedByName= o.has("releasedByName")? o.get("releasedByName").getAsString() : null;
        return e;
    }
}
