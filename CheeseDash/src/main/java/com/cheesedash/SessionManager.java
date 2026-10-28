package com.cheesedash;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory session manager for CheeseDash.
 * Sessions expire after 24h of inactivity (sliding window).
 * Sessions are lost on server restart (by design — login is cheap).
 */
public class SessionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseDash");
    private static final long SESSION_DURATION_MS = 24 * 60 * 60 * 1000L; // 24 hours

    // ── Session record ───────────────────────────────────────────────────

    public static class Session {
        private final String id;
        private final String username;
        private final AdminAccount.Role role;
        private final String ip;
        private final long createdAt;
        private volatile long lastActivity;

        Session(String id, String username, AdminAccount.Role role, String ip) {
            this.id = id;
            this.username = username;
            this.role = role;
            this.ip = ip;
            this.createdAt = System.currentTimeMillis();
            this.lastActivity = this.createdAt;
        }

        public String getId()                 { return id; }
        public String getUsername()            { return username; }
        public AdminAccount.Role getRole()     { return role; }
        public String getIp()                  { return ip; }
        public long getCreatedAt()             { return createdAt; }
        public long getLastActivity()          { return lastActivity; }

        public void touch() { this.lastActivity = System.currentTimeMillis(); }

        public boolean isExpired() {
            return System.currentTimeMillis() - lastActivity > SESSION_DURATION_MS;
        }

        public long expiresIn() {
            long remaining = SESSION_DURATION_MS - (System.currentTimeMillis() - lastActivity);
            return Math.max(0, remaining);
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("sessionId", id);
            obj.addProperty("username", username);
            obj.addProperty("role", role.name());
            obj.addProperty("ip", ip);
            obj.addProperty("createdAt", createdAt);
            obj.addProperty("lastActivity", lastActivity);
            obj.addProperty("expiresIn", expiresIn());
            return obj;
        }
    }

    // ── State ────────────────────────────────────────────────────────────

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public SessionManager() {
        // Periodic cleanup thread
        Thread cleaner = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(5 * 60 * 1000L); // every 5 min
                    int removed = cleanup();
                    if (removed > 0) {
                        LOGGER.debug("[CheeseDash] Cleaned up {} expired session(s)", removed);
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "CheeseDash-Session-Cleanup");
        cleaner.setDaemon(true);
        cleaner.start();
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Create a new session for the given user.
     */
    public Session createSession(String username, AdminAccount.Role role, String ip) {
        String id = generateSessionId();
        Session session = new Session(id, username, role, ip);
        sessions.put(id, session);
        LOGGER.info("[CheeseDash] Session created for '{}' ({}) from {}", username, role, ip);
        return session;
    }

    /**
     * Validate a session ID. Returns the session if valid and not expired, null otherwise.
     * Touches the session (sliding window) on successful validation.
     */
    public Session validate(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return null;
        Session session = sessions.get(sessionId);
        if (session == null) return null;
        if (session.isExpired()) {
            sessions.remove(sessionId);
            return null;
        }
        session.touch();
        return session;
    }

    /**
     * Revoke a specific session.
     */
    public boolean revoke(String sessionId) {
        Session removed = sessions.remove(sessionId);
        if (removed != null) {
            LOGGER.info("[CheeseDash] Session revoked for '{}'", removed.getUsername());
        }
        return removed != null;
    }

    /**
     * Revoke all sessions for a specific username.
     */
    public int revokeAllForUser(String username) {
        int count = 0;
        var it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().getUsername().equals(username)) {
                it.remove();
                count++;
            }
        }
        if (count > 0) {
            LOGGER.info("[CheeseDash] Revoked {} session(s) for '{}'", count, username);
        }
        return count;
    }

    /**
     * List all active (non-expired) sessions.
     */
    public JsonArray listSessions() {
        JsonArray arr = new JsonArray();
        for (Session s : sessions.values()) {
            if (!s.isExpired()) {
                arr.add(s.toJson());
            }
        }
        return arr;
    }

    /**
     * Get the count of active sessions.
     */
    public int getActiveCount() {
        return (int) sessions.values().stream().filter(s -> !s.isExpired()).count();
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private int cleanup() {
        int before = sessions.size();
        sessions.entrySet().removeIf(e -> e.getValue().isExpired());
        return before - sessions.size();
    }

    private static String generateSessionId() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return "ses_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
