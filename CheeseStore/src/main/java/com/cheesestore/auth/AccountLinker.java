package com.cheesestore.auth;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps a browser session ID ↔ a Minecraft UUID. No third-party identity provider —
 * the visitor proves they own the Minecraft account by typing
 * {@code /store link <code>} in-game while the website is open.
 *
 * Each browser gets a long-lived random {@code sessionId} cookie. When the visitor
 * clicks "Identify me", the server issues a short pairing code tied to that
 * session. After the in-game command runs, the session is permanently linked and
 * subsequent requests are recognised by the same cookie.
 *
 * Persistence: config/cheesestore_oauth_links.json (kept this filename for
 * forward/backward compatibility with the old Google-based flow).
 */
public class AccountLinker {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseStore");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long PENDING_TTL_MS = 5 * 60 * 1000L;
    private static final SecureRandom RNG = new SecureRandom();

    private static AccountLinker INSTANCE;

    private final Path file;
    /** sessionId → Link */
    private final Map<String, Link> bySession = new ConcurrentHashMap<>();
    /** Minecraft UUID → Link (for reverse lookup) */
    private final Map<UUID, Link> byUuid = new ConcurrentHashMap<>();
    /** code → PendingLink (created when a visitor clicks "Identify me") */
    private final Map<String, PendingLink> pending = new ConcurrentHashMap<>();

    public AccountLinker() {
        this.file = FabricLoader.getInstance().getConfigDir().resolve("cheesestore_oauth_links.json");
    }

    public static synchronized AccountLinker getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new AccountLinker();
            INSTANCE.load();
        }
        return INSTANCE;
    }

    public synchronized void load() {
        if (!Files.exists(file)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            bySession.clear();
            byUuid.clear();
            if (root.has("links")) {
                for (JsonElement el : root.getAsJsonArray("links")) {
                    JsonObject o = el.getAsJsonObject();
                    Link l = Link.fromJson(o);
                    if (l != null) {
                        bySession.put(l.sessionId, l);
                        byUuid.put(l.minecraftUuid, l);
                    }
                }
            }
            LOGGER.info("[CheeseStore] Account links loaded ({} entries)", bySession.size());
        } catch (Exception e) {
            LOGGER.error("[CheeseStore] Failed to load account links: {}", e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (Link l : bySession.values()) arr.add(l.toJson());
            root.add("links", arr);
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root));
        } catch (IOException e) {
            LOGGER.error("[CheeseStore] Failed to save account links: {}", e.getMessage());
        }
    }

    public Link findBySession(String sessionId) {
        if (sessionId == null) return null;
        return bySession.get(sessionId);
    }

    public Link findByUuid(UUID uuid) {
        return uuid == null ? null : byUuid.get(uuid);
    }

    /**
     * Generate (or refresh) a pairing code for a given browser session. Replaces
     * any previously issued (still pending) code for the same session.
     */
    public synchronized PendingLink issueCode(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return null;
        // Drop any previous pending code for this session, plus any expired entries.
        long now = System.currentTimeMillis();
        pending.values().removeIf(p -> p.sessionId.equals(sessionId) || now > p.expiresAt);
        String code = randomCode();
        PendingLink p = new PendingLink(code, sessionId, now + PENDING_TTL_MS);
        pending.put(code, p);
        return p;
    }

    /** Per-player attempt window for {@code /store link} brute-force protection. */
    private static final int LINK_MAX_ATTEMPTS = 5;
    private static final long LINK_WINDOW_MS = 60_000L;
    /** uuid → [windowStartMs, attemptCount] */
    private final Map<UUID, long[]> linkAttempts = new ConcurrentHashMap<>();

    /** Outcome flags returned by {@link #completeLink}. */
    public enum LinkResult { OK, INVALID_OR_EXPIRED, RATE_LIMITED }

    /**
     * Player typed {@code /store link <code>} in-game. Looks up the pending pairing
     * and persists the link.
     *
     * <p>Rate-limited per Minecraft UUID: 5 attempts per 60 seconds. Both wrong
     * codes and expired codes count. After 3 consecutive failed attempts inside
     * a single window, every still-pending pairing for the abuser's *currently
     * active* session is invalidated to short-circuit any further guessing.
     *
     * @return {@code OK} + the new Link, or a non-null LinkResult with {@code link=null}.
     */
    public synchronized LinkAttempt completeLink(String code, UUID playerUuid, String playerName) {
        if (code == null) return new LinkAttempt(LinkResult.INVALID_OR_EXPIRED, null);

        // Rate-limit window per player UUID.
        long now = System.currentTimeMillis();
        long[] bucket = linkAttempts.compute(playerUuid, (k, v) -> {
            if (v == null || now - v[0] > LINK_WINDOW_MS) return new long[]{ now, 0 };
            return v;
        });
        if (bucket[1] >= LINK_MAX_ATTEMPTS) {
            return new LinkAttempt(LinkResult.RATE_LIMITED, null);
        }

        PendingLink p = pending.remove(code.trim().toUpperCase(Locale.ROOT));
        if (p == null || now > p.expiresAt) {
            bucket[1]++;
            // After 3 misses, scrap any pending codes already linked to this
            // player (defence against pre-issued codes harvested by an attacker).
            if (bucket[1] >= 3) {
                Link existingForUuid = byUuid.get(playerUuid);
                if (existingForUuid != null) {
                    pending.values().removeIf(pl -> pl.sessionId.equals(existingForUuid.sessionId));
                }
            }
            return new LinkAttempt(LinkResult.INVALID_OR_EXPIRED, null);
        }

        // Successful pairing: reset the attempt counter.
        linkAttempts.remove(playerUuid);

        // If this session was linked previously, drop the old binding.
        Link existing = bySession.get(p.sessionId);
        if (existing != null) byUuid.remove(existing.minecraftUuid);
        // If this UUID was linked to another session, drop that one too.
        Link prevByUuid = byUuid.get(playerUuid);
        if (prevByUuid != null) bySession.remove(prevByUuid.sessionId);
        Link l = new Link(p.sessionId, playerUuid, playerName, System.currentTimeMillis());
        bySession.put(p.sessionId, l);
        byUuid.put(playerUuid, l);
        save();
        return new LinkAttempt(LinkResult.OK, l);
    }

    /** Result tuple returned by {@link #completeLink}. */
    public static final class LinkAttempt {
        public final LinkResult result;
        public final Link link;
        public LinkAttempt(LinkResult result, Link link) {
            this.result = result;
            this.link = link;
        }
    }

    /** Remove the link associated with a given session (logout). */
    public synchronized void unlinkSession(String sessionId) {
        Link l = bySession.remove(sessionId);
        if (l != null) {
            byUuid.remove(l.minecraftUuid);
            save();
        }
    }

    private static String randomCode() {
        // Collision-resistant 8-char alphanumeric (ambiguous chars omitted).
        char[] chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) sb.append(chars[RNG.nextInt(chars.length)]);
        return sb.toString();
    }

    public static String randomSessionId() {
        byte[] bytes = new byte[24];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static final class Link {
        public final String sessionId;
        public final UUID minecraftUuid;
        public final String minecraftName;
        public final long createdAt;

        public Link(String sessionId, UUID uuid, String name, long createdAt) {
            this.sessionId = sessionId;
            this.minecraftUuid = uuid;
            this.minecraftName = name == null ? "" : name;
            this.createdAt = createdAt;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("sessionId", sessionId);
            o.addProperty("minecraftUuid", minecraftUuid.toString());
            o.addProperty("minecraftName", minecraftName);
            o.addProperty("createdAt", createdAt);
            return o;
        }

        static Link fromJson(JsonObject o) {
            try {
                return new Link(
                    o.get("sessionId").getAsString(),
                    UUID.fromString(o.get("minecraftUuid").getAsString()),
                    o.has("minecraftName") ? o.get("minecraftName").getAsString() : "",
                    o.has("createdAt") ? o.get("createdAt").getAsLong() : System.currentTimeMillis()
                );
            } catch (Exception e) { return null; }
        }
    }

    public static final class PendingLink {
        public final String code;
        public final String sessionId;
        public final long expiresAt;

        public PendingLink(String code, String sessionId, long expiresAt) {
            this.code = code;
            this.sessionId = sessionId;
            this.expiresAt = expiresAt;
        }
    }
}
