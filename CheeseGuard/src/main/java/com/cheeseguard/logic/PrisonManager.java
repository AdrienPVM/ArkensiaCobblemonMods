package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.PrisonEntry;
import com.google.gson.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns prison state: active prisoners, history, and config (zone id, welcome message).
 * Thread-safe for read access; writes go through synchronized save().
 */
public class PrisonManager {

    private static final Path CONFIG_DIR = Paths.get("config", "cheeseguard");
    private static final Path FILE = CONFIG_DIR.resolve("prison.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int HISTORY_CAP = 500;

    /** uuid -> active PrisonEntry */
    private final Map<String, PrisonEntry> active = new ConcurrentHashMap<>();
    /** Most recent first. */
    private final List<PrisonEntry> history = Collections.synchronizedList(new ArrayList<>());

    private String prisonZoneId;
    private String welcomeMessage = "§c⛓ Vous êtes en prison.\n§7Raison: §f{reason}\n§7Temps restant: §f{time}";

    // Release point (where prisoners are TP'd on release). null = fall back to world spawn.
    private String releaseWorld;       // e.g. "minecraft:overworld"
    private Double releaseX, releaseY, releaseZ;
    private Float releaseYaw, releasePitch;

    public PrisonManager() {
        try { Files.createDirectories(CONFIG_DIR); } catch (IOException ignored) {}
        load();
    }

    // ── Config ───────────────────────────────────────────────────────────────

    public String getPrisonZoneId()      { return prisonZoneId; }
    public void   setPrisonZoneId(String id) { this.prisonZoneId = id; save(); }

    public String getWelcomeMessage()    { return welcomeMessage; }
    public void   setWelcomeMessage(String msg) {
        this.welcomeMessage = (msg == null || msg.isEmpty()) ? welcomeMessage : msg;
        save();
    }

    public boolean hasReleasePoint() {
        return releaseWorld != null && releaseX != null && releaseY != null && releaseZ != null;
    }
    public String getReleaseWorld() { return releaseWorld; }
    public Double getReleaseX()     { return releaseX; }
    public Double getReleaseY()     { return releaseY; }
    public Double getReleaseZ()     { return releaseZ; }
    public Float  getReleaseYaw()   { return releaseYaw; }
    public Float  getReleasePitch() { return releasePitch; }

    /** Set the release point. Pass null to clear and fall back to world spawn. */
    public void setReleasePoint(String world, Double x, Double y, Double z, Float yaw, Float pitch) {
        this.releaseWorld = world;
        this.releaseX = x;
        this.releaseY = y;
        this.releaseZ = z;
        this.releaseYaw = yaw;
        this.releasePitch = pitch;
        save();
    }

    public void clearReleasePoint() {
        setReleasePoint(null, null, null, null, null, null);
    }

    // ── Active queries ───────────────────────────────────────────────────────

    public PrisonEntry get(String uuid)    { return uuid == null ? null : active.get(uuid); }
    public boolean isImprisoned(String uuid){ return uuid != null && active.containsKey(uuid); }
    public Collection<PrisonEntry> getActive() { return active.values(); }
    public List<PrisonEntry> getHistory() {
        synchronized (history) { return new ArrayList<>(history); }
    }

    // ── Mutations ────────────────────────────────────────────────────────────

    /** Add or replace an active sentence. Returns the new entry. */
    public PrisonEntry imprison(String uuid, String name, String reason,
                                long durationMs, String issuedBy, String issuedByName) {
        long now = System.currentTimeMillis();
        PrisonEntry e = new PrisonEntry(uuid, name, reason, now, durationMs, issuedBy, issuedByName);
        active.put(uuid, e);
        save();
        return e;
    }

    /**
     * Release the player. Moves the entry to history with the given reason.
     * Returns the released entry, or null if the player wasn't imprisoned.
     */
    public PrisonEntry release(String uuid, String reason, String byUuid, String byName) {
        PrisonEntry e = active.remove(uuid);
        if (e == null) return null;
        e.markReleased(System.currentTimeMillis(), reason, byUuid, byName);
        synchronized (history) {
            history.add(0, e);
            while (history.size() > HISTORY_CAP) history.remove(history.size() - 1);
        }
        save();
        // Despawn doppelgänger if one exists (case: offline player released by admin or
        // by expiry tick — without this, the doppel would stay forever in the prison zone).
        try {
            java.util.UUID parsed = java.util.UUID.fromString(uuid);
            var mod = CheeseGuardMod.getInstance();
            if (mod != null && mod.getPrisonDoppelgangerManager() != null) {
                mod.getPrisonDoppelgangerManager().removeFor(parsed);
            }
        } catch (IllegalArgumentException ignored) {}
        return e;
    }

    /** Tick: release any expired sentences. Returns the list of released entries (for broadcasts). */
    public List<PrisonEntry> tickExpiry() {
        long now = System.currentTimeMillis();
        List<PrisonEntry> released = null;
        for (Map.Entry<String, PrisonEntry> e : active.entrySet()) {
            if (e.getValue().hasExpired(now)) {
                if (released == null) released = new ArrayList<>();
                released.add(e.getValue());
            }
        }
        if (released != null) {
            for (PrisonEntry e : released) {
                release(e.getPlayerUuid(), PrisonEntry.RELEASE_EXPIRED, null, null);
            }
        }
        return released == null ? Collections.emptyList() : released;
    }

    /** Extend or shorten the active sentence. Returns the updated entry, or null if not imprisoned. */
    public PrisonEntry adjustEnd(String uuid, long newEndsAt) {
        PrisonEntry e = active.get(uuid);
        if (e == null) return null;
        e.setEndsAt(newEndsAt);
        save();
        return e;
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    public synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            if (prisonZoneId != null) root.addProperty("prisonZoneId", prisonZoneId);
            root.addProperty("welcomeMessage", welcomeMessage);
            if (hasReleasePoint()) {
                JsonObject rp = new JsonObject();
                rp.addProperty("world", releaseWorld);
                rp.addProperty("x", releaseX);
                rp.addProperty("y", releaseY);
                rp.addProperty("z", releaseZ);
                if (releaseYaw   != null) rp.addProperty("yaw",   releaseYaw);
                if (releasePitch != null) rp.addProperty("pitch", releasePitch);
                root.add("releasePoint", rp);
            }

            JsonArray act = new JsonArray();
            for (PrisonEntry e : active.values()) act.add(e.toJson());
            root.add("active", act);

            JsonArray hist = new JsonArray();
            synchronized (history) {
                for (PrisonEntry e : history) hist.add(e.toJson());
            }
            root.add("history", hist);

            Files.writeString(FILE, GSON.toJson(root));
        } catch (IOException ex) {
            CheeseGuardMod.LOGGER.error("[CG/Prison] Failed to save prison state: {}", ex.getMessage());
        }
    }

    private void load() {
        if (!Files.exists(FILE)) return;
        try {
            String json = Files.readString(FILE);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            if (root.has("prisonZoneId") && !root.get("prisonZoneId").isJsonNull()) {
                prisonZoneId = root.get("prisonZoneId").getAsString();
            }
            if (root.has("welcomeMessage")) {
                welcomeMessage = root.get("welcomeMessage").getAsString();
            }
            if (root.has("releasePoint") && root.get("releasePoint").isJsonObject()) {
                JsonObject rp = root.getAsJsonObject("releasePoint");
                if (rp.has("world") && rp.has("x") && rp.has("y") && rp.has("z")) {
                    releaseWorld = rp.get("world").getAsString();
                    releaseX = rp.get("x").getAsDouble();
                    releaseY = rp.get("y").getAsDouble();
                    releaseZ = rp.get("z").getAsDouble();
                    releaseYaw   = rp.has("yaw")   ? rp.get("yaw").getAsFloat()   : null;
                    releasePitch = rp.has("pitch") ? rp.get("pitch").getAsFloat() : null;
                }
            }
            if (root.has("active")) {
                for (var el : root.getAsJsonArray("active")) {
                    PrisonEntry e = PrisonEntry.fromJson(el.getAsJsonObject());
                    if (e.getPlayerUuid() != null && !e.getPlayerUuid().isEmpty()) {
                        active.put(e.getPlayerUuid(), e);
                    }
                }
            }
            if (root.has("history")) {
                synchronized (history) {
                    for (var el : root.getAsJsonArray("history")) {
                        history.add(PrisonEntry.fromJson(el.getAsJsonObject()));
                    }
                }
            }
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.error("[CG/Prison] Failed to load prison state: {}", e.getMessage());
        }
    }

    // ── Duration parser (used by command + API) ──────────────────────────────

    /**
     * Parse a duration string like "30m", "2h", "7d", "90s", "1d12h".
     * Returns ms, or -1 on parse failure / non-positive result.
     */
    public static long parseDuration(String s) {
        if (s == null) return -1;
        s = s.trim().toLowerCase();
        if (s.isEmpty()) return -1;
        long total = 0L;
        long current = 0L;
        boolean sawDigit = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                current = current * 10 + (c - '0');
                sawDigit = true;
            } else {
                if (!sawDigit) return -1;
                long mult = switch (c) {
                    case 's' -> 1_000L;
                    case 'm' -> 60_000L;
                    case 'h' -> 3_600_000L;
                    case 'd' -> 86_400_000L;
                    default  -> -1L;
                };
                if (mult < 0) return -1;
                total += current * mult;
                current = 0L;
                sawDigit = false;
            }
        }
        if (sawDigit) total += current * 60_000L; // bare number = minutes
        return total > 0 ? total : -1;
    }

    public static String formatDuration(long ms) {
        if (ms <= 0) return "0s";
        long s = ms / 1000;
        long d = s / 86400; s %= 86400;
        long h = s / 3600;  s %= 3600;
        long m = s / 60;    s %= 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (sb.length() == 0) sb.append(s).append("s");
        return sb.toString().trim();
    }
}
