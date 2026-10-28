package com.cheesedash;

import com.google.gson.JsonObject;

/**
 * Represents an admin account for CheeseDash multi-admin support.
 * Each account has a unique username, a secret token, and a role.
 *
 * Roles:
 *  - READ_ONLY:   Can view everything, cannot modify anything (GET only)
 *  - OPERATOR:    Can modify zones, events, dims, players, market, eggs — no server commands, no account management
 *  - SUPER_ADMIN: Full access including server commands, token regen, account management
 */
public class AdminAccount {

    public enum Role {
        READ_ONLY, OPERATOR, SUPER_ADMIN;

        public boolean canWrite() { return this != READ_ONLY; }
        public boolean canAdmin() { return this == SUPER_ADMIN; }
    }

    private String username;
    private String token;
    private Role role;
    private long createdAt;

    public AdminAccount(String username, String token, Role role) {
        this.username = username;
        this.token = token;
        this.role = role;
        this.createdAt = System.currentTimeMillis();
    }

    // ── Getters/Setters ──────────────────────────────────────────────────

    public String getUsername() { return username; }
    public String getToken()   { return token; }
    public Role   getRole()    { return role; }
    public long   getCreatedAt() { return createdAt; }

    public void setRole(Role role) { this.role = role; }
    public void setCreatedAt(long ts) { this.createdAt = ts; }

    // ── JSON ─────────────────────────────────────────────────────────────

    public JsonObject toJson(boolean includeToken) {
        JsonObject obj = new JsonObject();
        obj.addProperty("username", username);
        obj.addProperty("role", role.name());
        obj.addProperty("createdAt", createdAt);
        if (includeToken) obj.addProperty("token", token);
        return obj;
    }

    public JsonObject toPublicJson() {
        return toJson(false);
    }
}
