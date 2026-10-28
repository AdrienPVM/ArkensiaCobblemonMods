package com.cheesedash;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the dashboard configuration: port, master token, accounts, enabled state.
 * On first run, generates a secure random master token and saves it to disk.
 *
 * Multi-admin:
 *  - The master token always maps to a SUPER_ADMIN account ("admin")
 *  - Additional accounts can be created with their own tokens and roles
 */
public class DashboardConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseDash");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final int DEFAULT_PORT = 44311;

    private final Path configFile;
    private int port = DEFAULT_PORT;
    private String token;     // master token (SUPER_ADMIN)
    private boolean enabled = true;
    private String adminPlayerName = "";

    // Additional admin accounts (username → account)
    private final Map<String, AdminAccount> accounts = new ConcurrentHashMap<>();

    // Visual zone folders (cosmetic only — no impact on CheeseGuard logic)
    private final List<JsonObject> zoneFolders = new ArrayList<>();

    public DashboardConfig(Path configDir) {
        this.configFile = configDir.resolve("cheesedash.json");
    }

    /** Load config from disk, or generate defaults (including a fresh token) on first run. */
    public void load() {
        if (Files.exists(configFile)) {
            try {
                String raw = Files.readString(configFile);
                JsonObject obj = GSON.fromJson(raw, JsonObject.class);
                if (obj.has("port"))    this.port    = obj.get("port").getAsInt();
                if (obj.has("token"))   this.token   = obj.get("token").getAsString();
                if (obj.has("enabled")) this.enabled = obj.get("enabled").getAsBoolean();
                if (obj.has("adminPlayerName")) this.adminPlayerName = obj.get("adminPlayerName").getAsString();

                // Load zone folders
                if (obj.has("zoneFolders") && obj.get("zoneFolders").isJsonArray()) {
                    zoneFolders.clear();
                    for (JsonElement el : obj.getAsJsonArray("zoneFolders")) {
                        zoneFolders.add(el.getAsJsonObject().deepCopy());
                    }
                }

                // Load accounts
                if (obj.has("accounts") && obj.get("accounts").isJsonArray()) {
                    for (JsonElement el : obj.getAsJsonArray("accounts")) {
                        JsonObject acc = el.getAsJsonObject();
                        String username = acc.get("username").getAsString();
                        String accToken = acc.get("token").getAsString();
                        AdminAccount.Role role = AdminAccount.Role.valueOf(
                            acc.has("role") ? acc.get("role").getAsString() : "READ_ONLY"
                        );
                        AdminAccount account = new AdminAccount(username, accToken, role);
                        if (acc.has("createdAt")) account.setCreatedAt(acc.get("createdAt").getAsLong());
                        accounts.put(username, account);
                    }
                }

                LOGGER.info("[CheeseDash] Config loaded — port: {}, enabled: {}, accounts: {}",
                    port, enabled, accounts.size());
                if (this.token == null || this.token.isEmpty()) {
                    this.token = generateToken();
                    save();
                    logTokenBanner("TOKEN MISSING — new master token generated");
                }
                return;
            } catch (Exception e) {
                LOGGER.warn("[CheeseDash] Failed to read config, regenerating: {}", e.getMessage());
            }
        }
        // First run — generate token
        this.token = generateToken();
        save();
        logTokenBanner("FIRST RUN — Dashboard token generated");
    }

    private void logTokenBanner(String title) {
        LOGGER.info("[CheeseDash] ╔═══════════════════════════════════════════════════╗");
        LOGGER.info("[CheeseDash] ║ {} ║", centerPad(title, 49));
        LOGGER.info("[CheeseDash] ║  Save this token, you'll need it to log in:       ║");
        LOGGER.info("[CheeseDash] ║  {}  ║", centerPad(token, 49));
        LOGGER.info("[CheeseDash] ║  Port: {}                                         ║", port);
        LOGGER.info("[CheeseDash] ╚═══════════════════════════════════════════════════╝");
    }

    /** Save config to disk. */
    public void save() {
        try {
            Files.createDirectories(configFile.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("port",    port);
            obj.addProperty("token",   token);
            obj.addProperty("enabled", enabled);
            if (adminPlayerName != null && !adminPlayerName.isEmpty()) {
                obj.addProperty("adminPlayerName", adminPlayerName);
            }

            // Save zone folders
            JsonArray foldersArr = new JsonArray();
            for (JsonObject folder : zoneFolders) {
                foldersArr.add(folder.deepCopy());
            }
            obj.add("zoneFolders", foldersArr);

            // Save accounts
            JsonArray accArr = new JsonArray();
            for (AdminAccount acc : accounts.values()) {
                JsonObject a = new JsonObject();
                a.addProperty("username", acc.getUsername());
                a.addProperty("token", acc.getToken());
                a.addProperty("role", acc.getRole().name());
                a.addProperty("createdAt", acc.getCreatedAt());
                accArr.add(a);
            }
            obj.add("accounts", accArr);

            obj.addProperty("_note", "Token is your dashboard password. Delete this file to regenerate.");
            Files.writeString(configFile, GSON.toJson(obj));
        } catch (IOException e) {
            LOGGER.error("[CheeseDash] Failed to save config: {}", e.getMessage());
        }
    }

    /** Regenerate the master token and save. Returns the new token. */
    public String regenerateToken() {
        this.token = generateToken();
        save();
        LOGGER.info("[CheeseDash] Master token regenerated.");
        return this.token;
    }

    // ── Account Management ───────────────────────────────────────────────

    /**
     * Resolve a token to its owner. Checks master token first, then accounts.
     * Returns null if no match.
     */
    public ResolvedAuth resolveToken(String candidateToken) {
        if (candidateToken == null) return null;

        // Check master token first
        if (WebDashboard.constantTimeEquals(candidateToken, this.token)) {
            return new ResolvedAuth("admin", AdminAccount.Role.SUPER_ADMIN);
        }

        // Check additional accounts (constant-time on each)
        for (AdminAccount acc : accounts.values()) {
            if (WebDashboard.constantTimeEquals(candidateToken, acc.getToken())) {
                return new ResolvedAuth(acc.getUsername(), acc.getRole());
            }
        }

        return null;
    }

    /**
     * Result of token resolution — who owns this token and what role do they have.
     */
    public static class ResolvedAuth {
        public final String username;
        public final AdminAccount.Role role;

        public ResolvedAuth(String username, AdminAccount.Role role) {
            this.username = username;
            this.role = role;
        }
    }

    /**
     * Create a new admin account. Returns the generated token.
     */
    public AdminAccount createAccount(String username, AdminAccount.Role role) {
        String accToken = generateToken();
        AdminAccount account = new AdminAccount(username, accToken, role);
        accounts.put(username, account);
        save();
        LOGGER.info("[CheeseDash] Account created: {} ({})", username, role);
        return account;
    }

    /**
     * Update an account's role.
     */
    public boolean updateAccountRole(String username, AdminAccount.Role newRole) {
        AdminAccount acc = accounts.get(username);
        if (acc == null) return false;
        acc.setRole(newRole);
        save();
        LOGGER.info("[CheeseDash] Account '{}' role changed to {}", username, newRole);
        return true;
    }

    /**
     * Delete an account.
     */
    public boolean deleteAccount(String username) {
        if (accounts.remove(username) != null) {
            save();
            LOGGER.info("[CheeseDash] Account '{}' deleted", username);
            return true;
        }
        return false;
    }

    /**
     * Get all accounts (read-only view).
     */
    public Collection<AdminAccount> getAccounts() {
        return Collections.unmodifiableCollection(accounts.values());
    }

    /**
     * Get a specific account by username.
     */
    public AdminAccount getAccount(String username) {
        return accounts.get(username);
    }

    /**
     * Regenerate the token for a specific account. Returns new token.
     */
    public String regenerateAccountToken(String username) {
        AdminAccount acc = accounts.get(username);
        if (acc == null) return null;
        // Create a new account with a new token but same role/createdAt
        String newToken = generateToken();
        AdminAccount updated = new AdminAccount(username, newToken, acc.getRole());
        updated.setCreatedAt(acc.getCreatedAt());
        accounts.put(username, updated);
        save();
        LOGGER.info("[CheeseDash] Token regenerated for account '{}'", username);
        return newToken;
    }

    // ── Getters ─────────────────────────────────────────────────────────

    public int     getPort()    { return port;    }
    public String  getToken()   { return token;   }
    public boolean isEnabled()  { return enabled; }
    public String  getAdminPlayerName() { return adminPlayerName != null ? adminPlayerName : ""; }
    public void    setAdminPlayerName(String name) { this.adminPlayerName = name != null ? name : ""; }

    public List<JsonObject> getZoneFolders() { return zoneFolders; }
    public void setZoneFolders(List<JsonObject> folders) {
        zoneFolders.clear();
        if (folders != null) {
            for (JsonObject f : folders) zoneFolders.add(f.deepCopy());
        }
        save();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String centerPad(String s, int width) {
        if (s.length() >= width) return s.substring(0, width);
        int pad = (width - s.length()) / 2;
        return " ".repeat(pad) + s + " ".repeat(width - s.length() - pad);
    }
}
