package com.cheesedash;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dispatches all /api/v1/* HTTP requests.
 *
 * Core endpoints (ping, status, modules) are handled directly.
 * Module-specific endpoints are dispatched to registered DashboardModules
 * based on their prefix: /api/v1/<prefix>/<subRoute>
 *
 * Phase 5 additions:
 *  - /auth/login, /auth/logout, /auth/session — session management
 *  - /admin/accounts — multi-admin account management (SUPER_ADMIN only)
 *  - /admin/sessions — active session management (SUPER_ADMIN only)
 *  - /admin/audit — audit log access
 *  - Permission enforcement on write operations
 *  - Audit logging on all mutations
 */
public class DashboardApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseDash");

    public static void handle(HttpExchange exchange, WebDashboard dashboard) throws IOException {
        String method = exchange.getRequestMethod();
        String path   = exchange.getRequestURI().getPath();

        // Strip /api/v1 prefix for routing
        String route = path.replaceFirst("^/api/v1", "")
                           .replaceFirst("^/api",    "");
        if (route.isEmpty()) route = "/";

        try {
            // ══════════════════════════════════════════════════════════════
            //  AUTH ENDPOINTS (no auth required for login)
            // ══════════════════════════════════════════════════════════════

            if (route.equals("/auth/login") && method.equals("POST")) {
                handleLogin(exchange, dashboard);
                return;
            }

            if (route.equals("/auth/logout") && method.equals("POST")) {
                handleLogout(exchange, dashboard);
                return;
            }

            if (route.equals("/auth/session") && method.equals("GET")) {
                handleSessionInfo(exchange, dashboard);
                return;
            }

            // ══════════════════════════════════════════════════════════════
            //  CORE ENDPOINTS
            // ══════════════════════════════════════════════════════════════

            // ── Ping / Health ───────────────────────────────────────────
            if (route.equals("/ping") && method.equals("GET")) {
                WebDashboard.respond(exchange, 200, "{\"status\":\"ok\",\"mod\":\"cheesedash\"}");
                return;
            }

            // ── Server Status (basic) ───────────────────────────────────
            if (route.equals("/status") && method.equals("GET")) {
                handleStatus(exchange, dashboard);
                return;
            }

            // ── List registered modules ─────────────────────────────────
            if (route.equals("/modules") && method.equals("GET")) {
                handleModules(exchange);
                return;
            }

            // ══════════════════════════════════════════════════════════════
            //  ADMIN ENDPOINTS (account & session management)
            // ══════════════════════════════════════════════════════════════

            if (route.startsWith("/admin/")) {
                handleAdmin(exchange, dashboard, route, method);
                return;
            }

            // ══════════════════════════════════════════════════════════════
            //  DASHBOARD ENDPOINTS (token regen, admin player)
            // ══════════════════════════════════════════════════════════════

            if (route.equals("/dashboard/token/regenerate") && method.equals("POST")) {
                if (!WebDashboard.requireAdmin(exchange)) return;
                handleTokenRegenerate(exchange, dashboard);
                return;
            }

            if (route.equals("/dashboard/admin-player") && method.equals("GET")) {
                handleGetAdminPlayer(exchange, dashboard);
                return;
            }
            if (route.equals("/dashboard/admin-player") && method.equals("PUT")) {
                if (!WebDashboard.requireWrite(exchange)) return;
                handleSetAdminPlayer(exchange, dashboard);
                return;
            }
            if (route.equals("/dashboard/admin-player/position") && method.equals("GET")) {
                handleGetAdminPosition(exchange, dashboard);
                return;
            }

            // ── Zone Folders (visual organization, CheeseDash only) ───
            if (route.equals("/dashboard/zone-folders") && method.equals("GET")) {
                handleZoneFoldersGet(exchange, dashboard);
                return;
            }
            if (route.equals("/dashboard/zone-folders") && method.equals("PUT")) {
                if (!WebDashboard.requireWrite(exchange)) return;
                handleZoneFoldersSave(exchange, dashboard);
                return;
            }
            if (route.equals("/dashboard/zone-folders") && method.equals("POST")) {
                if (!WebDashboard.requireWrite(exchange)) return;
                handleZoneFolderCreate(exchange, dashboard);
                return;
            }
            if (route.startsWith("/dashboard/zone-folders/") && method.equals("DELETE")) {
                if (!WebDashboard.requireWrite(exchange)) return;
                String folderId = route.substring("/dashboard/zone-folders/".length());
                handleZoneFolderDelete(exchange, dashboard, folderId);
                return;
            }

            // ══════════════════════════════════════════════════════════════
            //  SERVER ENDPOINTS
            // ══════════════════════════════════════════════════════════════

            if (route.equals("/server/command") && method.equals("POST")) {
                if (!WebDashboard.requireAdmin(exchange)) return;
                handleServerCommand(exchange, dashboard);
                return;
            }

            if (route.equals("/server/logs") && method.equals("GET")) {
                handleServerLogs(exchange, dashboard);
                return;
            }

            if (route.equals("/server/stats") && method.equals("GET")) {
                handleServerStats(exchange, dashboard);
                return;
            }

            if (route.equals("/server/mods") && method.equals("GET")) {
                handleServerMods(exchange);
                return;
            }

            // ══════════════════════════════════════════════════════════════
            //  SCHEDULING ENDPOINTS (SUPER_ADMIN)
            // ══════════════════════════════════════════════════════════════

            if (route.equals("/server/schedule") && method.equals("GET")) {
                handleScheduleGet(exchange);
                return;
            }
            if (route.equals("/server/schedule") && method.equals("POST")) {
                if (!WebDashboard.requireAdmin(exchange)) return;
                handleScheduleCreate(exchange, dashboard);
                return;
            }
            if (route.equals("/server/schedule/active") && method.equals("DELETE")) {
                if (!WebDashboard.requireAdmin(exchange)) return;
                handleScheduleCancel(exchange, dashboard);
                return;
            }
            if (route.equals("/server/schedule/recurring") && method.equals("POST")) {
                if (!WebDashboard.requireAdmin(exchange)) return;
                handleScheduleAddRecurring(exchange, dashboard);
                return;
            }
            if (route.startsWith("/server/schedule/recurring/") && method.equals("PUT")) {
                if (!WebDashboard.requireAdmin(exchange)) return;
                String recId = route.substring("/server/schedule/recurring/".length());
                handleScheduleUpdateRecurring(exchange, dashboard, recId);
                return;
            }
            if (route.startsWith("/server/schedule/recurring/") && method.equals("DELETE")) {
                if (!WebDashboard.requireAdmin(exchange)) return;
                String recId = route.substring("/server/schedule/recurring/".length());
                handleScheduleDeleteRecurring(exchange, dashboard, recId);
                return;
            }

            // ══════════════════════════════════════════════════════════════
            //  WEBHOOKS ENDPOINTS (SUPER_ADMIN)
            // ══════════════════════════════════════════════════════════════

            if (route.equals("/webhooks/event-types") && method.equals("GET")) {
                handleWebhookEventTypes(exchange);
                return;
            }
            if (route.equals("/webhooks") && method.equals("GET")) {
                handleWebhookList(exchange);
                return;
            }
            if (route.equals("/webhooks") && method.equals("POST")) {
                if (!WebDashboard.requireAdmin(exchange)) return;
                handleWebhookCreate(exchange, dashboard);
                return;
            }
            if (route.startsWith("/webhooks/") && route.endsWith("/test") && method.equals("POST")) {
                if (!WebDashboard.requireAdmin(exchange)) return;
                String whId = route.substring("/webhooks/".length(), route.length() - "/test".length());
                handleWebhookTest(exchange, whId);
                return;
            }
            if (route.startsWith("/webhooks/") && method.equals("GET")) {
                String whId = route.substring("/webhooks/".length());
                handleWebhookGet(exchange, whId);
                return;
            }
            if (route.startsWith("/webhooks/") && method.equals("PUT")) {
                if (!WebDashboard.requireAdmin(exchange)) return;
                String whId = route.substring("/webhooks/".length());
                handleWebhookUpdate(exchange, dashboard, whId);
                return;
            }
            if (route.startsWith("/webhooks/") && method.equals("DELETE")) {
                if (!WebDashboard.requireAdmin(exchange)) return;
                String whId = route.substring("/webhooks/".length());
                handleWebhookDelete(exchange, dashboard, whId);
                return;
            }

            // ══════════════════════════════════════════════════════════════
            //  MODULE DISPATCH: /api/v1/<prefix>/...
            // ══════════════════════════════════════════════════════════════

            if (route.startsWith("/")) {
                String withoutSlash = route.substring(1);
                int nextSlash = withoutSlash.indexOf('/');
                String prefix = nextSlash > 0 ? withoutSlash.substring(0, nextSlash) : withoutSlash;
                String subRoute = nextSlash > 0 ? withoutSlash.substring(nextSlash) : "/";

                DashboardModule module = DashboardRegistry.getModule(prefix);
                if (module != null) {
                    // Enforce write permissions for non-GET requests on modules
                    if (!method.equals("GET") && !WebDashboard.requireWrite(exchange)) {
                        return;
                    }
                    module.handleRequest(exchange, subRoute);

                    // Audit non-GET module requests
                    if (!method.equals("GET")) {
                        SessionManager.Session session = WebDashboard.getSession(exchange);
                        if (session != null && dashboard.getAuditLogger() != null) {
                            dashboard.getAuditLogger().log(session,
                                prefix + "." + method.toLowerCase(),
                                method + " /api/v1/" + prefix + subRoute);
                        }
                    }
                    return;
                }
            }

            // ── Not found ───────────────────────────────────────────────
            WebDashboard.respond(exchange, 404, "{\"error\":\"Route not found: " + escapeJson(route) + "\"}");

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            LOGGER.error("[CheeseDash] API error on {}: {}", route, msg, e);
            WebDashboard.respond(exchange, 500,
                "{\"error\":\"Internal server error\",\"detail\":\"" + escapeJson(msg) + "\"}");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  AUTH HANDLERS
    // ══════════════════════════════════════════════════════════════════════

    private static void handleLogin(HttpExchange exchange, WebDashboard dashboard) throws IOException {
        String body = WebDashboard.readBody(exchange);
        JsonObject data;
        try {
            data = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid JSON\"}");
            return;
        }

        if (!data.has("token")) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"token is required\"}");
            return;
        }

        String token = data.get("token").getAsString().trim();
        String ip = WebDashboard.getClientIp(exchange);

        // Resolve token to account
        DashboardConfig.ResolvedAuth resolved = dashboard.getConfig().resolveToken(token);
        if (resolved == null) {
            WebDashboard.respond(exchange, 401, "{\"error\":\"Invalid token\"}");
            // Audit failed login
            if (dashboard.getAuditLogger() != null) {
                dashboard.getAuditLogger().log("unknown", "NONE", "auth.login_failed",
                    "Failed login attempt", ip);
            }
            return;
        }

        // Create session
        SessionManager.Session session = dashboard.getSessionManager()
            .createSession(resolved.username, resolved.role, ip);

        // Audit successful login
        if (dashboard.getAuditLogger() != null) {
            dashboard.getAuditLogger().log(session, "auth.login",
                "Logged in as " + resolved.username + " (" + resolved.role + ")");
        }

        // Response
        JsonObject resp = new JsonObject();
        resp.addProperty("sessionId", session.getId());
        resp.addProperty("username", session.getUsername());
        resp.addProperty("role", session.getRole().name());
        resp.addProperty("expiresIn", session.expiresIn());
        WebDashboard.respondJson(exchange, resp);
    }

    private static void handleLogout(HttpExchange exchange, WebDashboard dashboard) throws IOException {
        SessionManager.Session session = WebDashboard.getSession(exchange);
        if (session != null) {
            dashboard.getSessionManager().revoke(session.getId());
            if (dashboard.getAuditLogger() != null) {
                dashboard.getAuditLogger().log(session, "auth.logout", "Logged out");
            }
        }
        WebDashboard.respond(exchange, 200, "{\"ok\":true}");
    }

    private static void handleSessionInfo(HttpExchange exchange, WebDashboard dashboard) throws IOException {
        SessionManager.Session session = WebDashboard.getSession(exchange);
        if (session == null) {
            WebDashboard.respond(exchange, 401, "{\"error\":\"No active session\"}");
            return;
        }
        JsonObject resp = session.toJson();
        resp.addProperty("activeSessions", dashboard.getSessionManager().getActiveCount());
        WebDashboard.respondJson(exchange, resp);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ADMIN HANDLERS (accounts, sessions, audit)
    // ══════════════════════════════════════════════════════════════════════

    private static void handleAdmin(HttpExchange exchange, WebDashboard dashboard,
                                     String route, String method) throws IOException {
        // ── Accounts CRUD ────────────────────────────────────────────────
        if (route.equals("/admin/accounts") && method.equals("GET")) {
            if (!WebDashboard.requireAdmin(exchange)) return;
            handleListAccounts(exchange, dashboard);
            return;
        }
        if (route.equals("/admin/accounts") && method.equals("POST")) {
            if (!WebDashboard.requireAdmin(exchange)) return;
            handleCreateAccount(exchange, dashboard);
            return;
        }
        if (route.startsWith("/admin/accounts/") && method.equals("PUT")) {
            if (!WebDashboard.requireAdmin(exchange)) return;
            String username = route.substring("/admin/accounts/".length());
            handleUpdateAccount(exchange, dashboard, username);
            return;
        }
        if (route.startsWith("/admin/accounts/") && route.endsWith("/regenerate") && method.equals("POST")) {
            if (!WebDashboard.requireAdmin(exchange)) return;
            String username = route.substring("/admin/accounts/".length(),
                route.length() - "/regenerate".length());
            handleRegenerateAccountToken(exchange, dashboard, username);
            return;
        }
        if (route.startsWith("/admin/accounts/") && method.equals("DELETE")) {
            if (!WebDashboard.requireAdmin(exchange)) return;
            String username = route.substring("/admin/accounts/".length());
            handleDeleteAccount(exchange, dashboard, username);
            return;
        }

        // ── Sessions ─────────────────────────────────────────────────────
        if (route.equals("/admin/sessions") && method.equals("GET")) {
            if (!WebDashboard.requireAdmin(exchange)) return;
            WebDashboard.respondJson(exchange, dashboard.getSessionManager().listSessions());
            return;
        }
        if (route.startsWith("/admin/sessions/") && method.equals("DELETE")) {
            if (!WebDashboard.requireAdmin(exchange)) return;
            String sessionId = route.substring("/admin/sessions/".length());
            boolean revoked = dashboard.getSessionManager().revoke(sessionId);
            WebDashboard.respond(exchange, 200,
                "{\"revoked\":" + revoked + "}");
            return;
        }

        // ── Audit Log ────────────────────────────────────────────────────
        if (route.equals("/admin/audit") && method.equals("GET")) {
            // All authenticated users can view audit log
            handleAuditLog(exchange, dashboard);
            return;
        }

        WebDashboard.respond(exchange, 404, "{\"error\":\"Admin route not found: " + escapeJson(route) + "\"}");
    }

    private static void handleListAccounts(HttpExchange exchange, WebDashboard dashboard) throws IOException {
        JsonArray arr = new JsonArray();
        for (AdminAccount acc : dashboard.getConfig().getAccounts()) {
            arr.add(acc.toPublicJson());
        }
        WebDashboard.respondJson(exchange, arr);
    }

    private static void handleCreateAccount(HttpExchange exchange, WebDashboard dashboard) throws IOException {
        String body = WebDashboard.readBody(exchange);
        JsonObject data = JsonParser.parseString(body).getAsJsonObject();

        if (!data.has("username") || !data.has("role")) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"username and role are required\"}");
            return;
        }

        String username = data.get("username").getAsString().trim();
        if (username.isEmpty() || username.length() > 32 || !username.matches("[a-zA-Z0-9_-]+")) {
            WebDashboard.respond(exchange, 400,
                "{\"error\":\"Username must be 1-32 chars, alphanumeric/dash/underscore only\"}");
            return;
        }
        if ("admin".equals(username)) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"'admin' is reserved for the master token\"}");
            return;
        }
        if (dashboard.getConfig().getAccount(username) != null) {
            WebDashboard.respond(exchange, 409, "{\"error\":\"Account already exists: " + escapeJson(username) + "\"}");
            return;
        }

        AdminAccount.Role role;
        try {
            role = AdminAccount.Role.valueOf(data.get("role").getAsString().toUpperCase());
        } catch (IllegalArgumentException e) {
            WebDashboard.respond(exchange, 400,
                "{\"error\":\"Invalid role. Must be READ_ONLY, OPERATOR, or SUPER_ADMIN\"}");
            return;
        }

        AdminAccount account = dashboard.getConfig().createAccount(username, role);

        // Audit
        SessionManager.Session session = WebDashboard.getSession(exchange);
        if (session != null && dashboard.getAuditLogger() != null) {
            dashboard.getAuditLogger().log(session, "admin.account_create",
                "Created account '" + username + "' with role " + role);
        }

        // Return the account WITH token (only shown once at creation)
        WebDashboard.respondJson(exchange, account.toJson(true));
    }

    private static void handleUpdateAccount(HttpExchange exchange, WebDashboard dashboard,
                                             String username) throws IOException {
        String body = WebDashboard.readBody(exchange);
        JsonObject data = JsonParser.parseString(body).getAsJsonObject();

        if (!data.has("role")) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"role is required\"}");
            return;
        }

        AdminAccount.Role newRole;
        try {
            newRole = AdminAccount.Role.valueOf(data.get("role").getAsString().toUpperCase());
        } catch (IllegalArgumentException e) {
            WebDashboard.respond(exchange, 400,
                "{\"error\":\"Invalid role. Must be READ_ONLY, OPERATOR, or SUPER_ADMIN\"}");
            return;
        }

        if (!dashboard.getConfig().updateAccountRole(username, newRole)) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Account not found: " + escapeJson(username) + "\"}");
            return;
        }

        // Audit
        SessionManager.Session session = WebDashboard.getSession(exchange);
        if (session != null && dashboard.getAuditLogger() != null) {
            dashboard.getAuditLogger().log(session, "admin.account_update",
                "Changed role of '" + username + "' to " + newRole);
        }

        WebDashboard.respond(exchange, 200, "{\"updated\":true,\"username\":\"" + escapeJson(username) +
            "\",\"role\":\"" + newRole + "\"}");
    }

    private static void handleRegenerateAccountToken(HttpExchange exchange, WebDashboard dashboard,
                                                      String username) throws IOException {
        String newToken = dashboard.getConfig().regenerateAccountToken(username);
        if (newToken == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Account not found: " + escapeJson(username) + "\"}");
            return;
        }

        // Revoke all sessions for this user (token changed)
        dashboard.getSessionManager().revokeAllForUser(username);

        // Audit
        SessionManager.Session session = WebDashboard.getSession(exchange);
        if (session != null && dashboard.getAuditLogger() != null) {
            dashboard.getAuditLogger().log(session, "admin.account_regen_token",
                "Regenerated token for '" + username + "'");
        }

        WebDashboard.respond(exchange, 200,
            "{\"username\":\"" + escapeJson(username) + "\",\"token\":\"" + escapeJson(newToken) + "\"}");
    }

    private static void handleDeleteAccount(HttpExchange exchange, WebDashboard dashboard,
                                             String username) throws IOException {
        if (!dashboard.getConfig().deleteAccount(username)) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Account not found: " + escapeJson(username) + "\"}");
            return;
        }

        // Revoke all sessions for this user
        dashboard.getSessionManager().revokeAllForUser(username);

        // Audit
        SessionManager.Session session = WebDashboard.getSession(exchange);
        if (session != null && dashboard.getAuditLogger() != null) {
            dashboard.getAuditLogger().log(session, "admin.account_delete",
                "Deleted account '" + username + "'");
        }

        WebDashboard.respond(exchange, 200, "{\"deleted\":true}");
    }

    private static void handleAuditLog(HttpExchange exchange, WebDashboard dashboard) throws IOException {
        AuditLogger audit = dashboard.getAuditLogger();
        if (audit == null) {
            WebDashboard.respond(exchange, 200, "{\"entries\":[],\"total\":0}");
            return;
        }

        // Parse ?n=100&offset=0
        String query = exchange.getRequestURI().getQuery();
        int n = 100;
        int offset = 0;
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("n=")) {
                    try { n = Math.min(500, Math.max(1, Integer.parseInt(param.substring(2)))); }
                    catch (NumberFormatException ignored) {}
                }
                if (param.startsWith("offset=")) {
                    try { offset = Math.max(0, Integer.parseInt(param.substring(7))); }
                    catch (NumberFormatException ignored) {}
                }
            }
        }

        JsonObject result = new JsonObject();
        result.add("entries", audit.getEntries(offset, n));
        result.addProperty("total", audit.getTotal());
        result.addProperty("offset", offset);
        result.addProperty("limit", n);
        WebDashboard.respondJson(exchange, result);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  CORE ENDPOINTS
    // ══════════════════════════════════════════════════════════════════════

    private static void handleStatus(HttpExchange exchange, WebDashboard dashboard) throws IOException {
        MinecraftServer server = dashboard.getMinecraftServer();
        if (server == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"Server not ready\"}");
            return;
        }

        JsonObject obj = new JsonObject();
        obj.addProperty("online", true);
        obj.addProperty("playerCount",  server.getCurrentPlayerCount());
        obj.addProperty("maxPlayers",   server.getMaxPlayerCount());
        obj.addProperty("motd",         server.getServerMotd());
        obj.addProperty("tps", Math.round(CheeseDashMod.INSTANCE.getTps() * 10.0) / 10.0);
        obj.addProperty("uptime",       System.currentTimeMillis());
        obj.addProperty("version",      server.getVersion());
        obj.addProperty("moduleCount",  DashboardRegistry.getModules().size());
        obj.addProperty("wsPort", dashboard.getConfig().getPort() + 1);

        // Include session info
        SessionManager.Session session = WebDashboard.getSession(exchange);
        if (session != null) {
            obj.addProperty("username", session.getUsername());
            obj.addProperty("role", session.getRole().name());
        }

        WebDashboard.respondJson(exchange, obj);
    }

    private static void handleModules(HttpExchange exchange) throws IOException {
        JsonArray arr = new JsonArray();
        for (DashboardModule module : DashboardRegistry.getModules().values()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("prefix",      module.getPrefix());
            obj.addProperty("displayName", module.getDisplayName());
            obj.addProperty("icon",        module.getNavIcon());
            obj.addProperty("description", module.getNavDescription());
            arr.add(obj);
        }
        WebDashboard.respondJson(exchange, arr);
    }

    private static void handleTokenRegenerate(HttpExchange exchange, WebDashboard dashboard) throws IOException {
        String body = WebDashboard.readBody(exchange);
        if (!body.contains("\"confirm\":true") && !body.contains("\"confirm\": true")) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Missing confirm:true in body\"}");
            return;
        }
        String newToken = dashboard.getConfig().regenerateToken();
        LOGGER.warn("[CheeseDash] Token regenerated via API from {}", WebDashboard.getClientIp(exchange));

        // Audit
        SessionManager.Session session = WebDashboard.getSession(exchange);
        if (session != null && dashboard.getAuditLogger() != null) {
            dashboard.getAuditLogger().log(session, "admin.token_regenerate",
                "Master token regenerated");
        }

        WebDashboard.respond(exchange, 200, "{\"token\":\"" + escapeJson(newToken) + "\"}");
    }

    // ── Server Command ────────────────────────────────────────────────────

    private static void handleServerCommand(HttpExchange exchange, WebDashboard dashboard) throws IOException {
        MinecraftServer server = dashboard.getMinecraftServer();
        if (server == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"Server not ready\"}");
            return;
        }
        String body = WebDashboard.readBody(exchange);
        JsonObject data = JsonParser.parseString(body).getAsJsonObject();
        if (!data.has("command")) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"command is required\"}");
            return;
        }
        String command = data.get("command").getAsString().trim();
        if (command.isEmpty()) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"command cannot be empty\"}");
            return;
        }

        String clientIp = WebDashboard.getClientIp(exchange);
        LOGGER.info("[CheeseDash] Command executed from {}: {}", clientIp, command);

        // Audit
        SessionManager.Session session = WebDashboard.getSession(exchange);
        if (session != null && dashboard.getAuditLogger() != null) {
            dashboard.getAuditLogger().log(session, "server.command", command);
        }

        try {
            WebDashboard.executeOnServerThread(server, () -> {
                server.getCommandManager().executeWithPrefix(
                    server.getCommandSource(), command);
            });
        } catch (Exception e) {
            WebDashboard.respond(exchange, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            return;
        }
        WebDashboard.respond(exchange, 200, "{\"executed\":true,\"command\":\"" + escapeJson(command) + "\"}");
    }

    // ── Server Logs ───────────────────────────────────────────────────────

    private static void handleServerLogs(HttpExchange exchange, WebDashboard dashboard) throws IOException {
        MinecraftServer server = dashboard.getMinecraftServer();
        if (server == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"Server not ready\"}");
            return;
        }

        String query = exchange.getRequestURI().getQuery();
        int n = 100;
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("n=")) {
                    try { n = Math.min(500, Math.max(1, Integer.parseInt(param.substring(2)))); }
                    catch (NumberFormatException ignored) {}
                }
            }
        }

        Path logFile = server.getRunDirectory().resolve("logs").resolve("latest.log");
        JsonObject result = new JsonObject();
        if (Files.exists(logFile)) {
            try {
                List<String> allLines = Files.readAllLines(logFile);
                int start = Math.max(0, allLines.size() - n);
                JsonArray lines = new JsonArray();
                for (int i = start; i < allLines.size(); i++) {
                    lines.add(allLines.get(i));
                }
                result.add("lines", lines);
                result.addProperty("totalLines", allLines.size());
                result.addProperty("returned", lines.size());
            } catch (Exception e) {
                WebDashboard.respond(exchange, 500, "{\"error\":\"Failed to read logs: " + escapeJson(e.getMessage()) + "\"}");
                return;
            }
        } else {
            result.add("lines", new JsonArray());
            result.addProperty("totalLines", 0);
            result.addProperty("returned", 0);
        }
        WebDashboard.respondJson(exchange, result);
    }

    // ── Server Stats ────────────────────────────────────────────────────

    private static void handleServerStats(HttpExchange exchange, WebDashboard dashboard) throws IOException {
        MinecraftServer server = dashboard.getMinecraftServer();
        if (server == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"Server not ready\"}");
            return;
        }

        Runtime rt = Runtime.getRuntime();
        JsonObject obj = new JsonObject();
        obj.addProperty("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        obj.addProperty("heapMaxMb", rt.maxMemory() / (1024 * 1024));
        obj.addProperty("heapTotalMb", rt.totalMemory() / (1024 * 1024));
        obj.addProperty("tps", Math.round(CheeseDashMod.INSTANCE.getTps() * 10.0) / 10.0);
        obj.addProperty("playerCount", server.getCurrentPlayerCount());
        obj.addProperty("maxPlayers", server.getMaxPlayerCount());
        obj.addProperty("uptime", System.currentTimeMillis() - CheeseDashMod.INSTANCE.getStartTime());
        obj.addProperty("version", server.getVersion());
        WebDashboard.respondJson(exchange, obj);
    }

    // ── Server Mods ──────────────────────────────────────────────────────

    private static void handleServerMods(HttpExchange exchange) throws IOException {
        JsonArray arr = new JsonArray();
        FabricLoader.getInstance().getAllMods().forEach(mod -> {
            JsonObject o = new JsonObject();
            o.addProperty("id", mod.getMetadata().getId());
            o.addProperty("name", mod.getMetadata().getName());
            o.addProperty("version", mod.getMetadata().getVersion().getFriendlyString());
            arr.add(o);
        });
        WebDashboard.respondJson(exchange, arr);
    }

    // ── Admin Player Identity ───────────────────────────────────────────

    private static void handleGetAdminPlayer(HttpExchange exchange, WebDashboard dashboard) throws IOException {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", dashboard.getConfig().getAdminPlayerName());
        WebDashboard.respondJson(exchange, obj);
    }

    private static void handleSetAdminPlayer(HttpExchange exchange, WebDashboard dashboard) throws IOException {
        String body = WebDashboard.readBody(exchange);
        JsonObject data = JsonParser.parseString(body).getAsJsonObject();
        if (!data.has("name")) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"name is required\"}");
            return;
        }
        String name = data.get("name").getAsString().trim();
        dashboard.getConfig().setAdminPlayerName(name);
        dashboard.getConfig().save();
        JsonObject obj = new JsonObject();
        obj.addProperty("name", name);
        WebDashboard.respondJson(exchange, obj);
    }

    private static void handleGetAdminPosition(HttpExchange exchange, WebDashboard dashboard) throws IOException {
        MinecraftServer server = dashboard.getMinecraftServer();
        String adminName = dashboard.getConfig().getAdminPlayerName();
        if (server == null || adminName.isEmpty()) {
            WebDashboard.respond(exchange, 200, "{\"online\":false}");
            return;
        }
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(adminName);
        if (player == null) {
            WebDashboard.respond(exchange, 200, "{\"online\":false}");
            return;
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("online", true);
        obj.addProperty("x", Math.round(player.getX() * 10.0) / 10.0);
        obj.addProperty("y", Math.round(player.getY() * 10.0) / 10.0);
        obj.addProperty("z", Math.round(player.getZ() * 10.0) / 10.0);
        obj.addProperty("world", player.getWorld().getRegistryKey().getValue().toString());
        WebDashboard.respondJson(exchange, obj);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SCHEDULING HANDLERS
    // ══════════════════════════════════════════════════════════════════════

    private static void handleScheduleGet(HttpExchange exchange) throws IOException {
        ServerScheduler scheduler = CheeseDashMod.INSTANCE.getScheduler();
        JsonObject result = new JsonObject();

        // Active action
        ServerScheduler.ScheduledAction active = scheduler.getActiveAction();
        if (active != null) {
            result.add("active", active.toJson());
        } else {
            result.add("active", JsonNull.INSTANCE);
        }

        // Recurring schedules
        JsonArray recArr = new JsonArray();
        scheduler.getRecurringSchedules().forEach(s -> recArr.add(s.toJson()));
        result.add("recurring", recArr);

        WebDashboard.respondJson(exchange, result);
    }

    private static void handleScheduleCreate(HttpExchange exchange, WebDashboard dashboard) throws IOException {
        String body = WebDashboard.readBody(exchange);
        JsonObject data;
        try {
            data = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid JSON\"}");
            return;
        }

        if (!data.has("type") || !data.has("delaySeconds")) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"type and delaySeconds are required\"}");
            return;
        }

        ServerScheduler.ActionType type;
        try {
            type = ServerScheduler.ActionType.valueOf(data.get("type").getAsString().toUpperCase());
        } catch (IllegalArgumentException e) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid type. Must be RESTART, STOP, or MAINTENANCE\"}");
            return;
        }

        long delaySeconds = data.get("delaySeconds").getAsLong();
        if (delaySeconds < 5 || delaySeconds > 86400) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"delaySeconds must be between 5 and 86400\"}");
            return;
        }

        String message = data.has("message") ? data.get("message").getAsString() : "";
        SessionManager.Session session = WebDashboard.getSession(exchange);
        String username = session != null ? session.getUsername() : "unknown";

        ServerScheduler scheduler = CheeseDashMod.INSTANCE.getScheduler();
        ServerScheduler.ScheduledAction action = scheduler.scheduleAction(type, delaySeconds, message, username);

        // Audit
        if (session != null && dashboard.getAuditLogger() != null) {
            dashboard.getAuditLogger().log(session, "schedule.create",
                type + " in " + delaySeconds + "s: " + message);
        }

        // Discord notification
        DiscordWebhookManager discord = CheeseDashMod.INSTANCE.getDiscordWebhookManager();
        if (discord != null) {
            java.util.Map<String, String> discordData = new java.util.HashMap<>();
            discordData.put("delay", ServerScheduler.formatTime(delaySeconds));
            discordData.put("message", message);
            discord.sendEvent(DiscordWebhookManager.DiscordEventType.RESTART_SCHEDULED, discordData);
        }

        WebDashboard.respondJson(exchange, action.toJson());
    }

    private static void handleScheduleCancel(HttpExchange exchange, WebDashboard dashboard) throws IOException {
        ServerScheduler scheduler = CheeseDashMod.INSTANCE.getScheduler();
        boolean cancelled = scheduler.cancelAction();

        if (!cancelled) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"No active scheduled action\"}");
            return;
        }

        // Audit
        SessionManager.Session session = WebDashboard.getSession(exchange);
        if (session != null && dashboard.getAuditLogger() != null) {
            dashboard.getAuditLogger().log(session, "schedule.cancel", "Cancelled active schedule");
        }

        // Discord notification
        DiscordWebhookManager discord = CheeseDashMod.INSTANCE.getDiscordWebhookManager();
        if (discord != null) {
            discord.sendEvent(DiscordWebhookManager.DiscordEventType.RESTART_CANCELLED, null);
        }

        WebDashboard.respond(exchange, 200, "{\"cancelled\":true}");
    }

    private static void handleScheduleAddRecurring(HttpExchange exchange, WebDashboard dashboard) throws IOException {
        String body = WebDashboard.readBody(exchange);
        JsonObject data;
        try {
            data = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid JSON\"}");
            return;
        }

        if (!data.has("type") || !data.has("hour") || !data.has("minute")) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"type, hour, and minute are required\"}");
            return;
        }

        ServerScheduler.ActionType type;
        try {
            type = ServerScheduler.ActionType.valueOf(data.get("type").getAsString().toUpperCase());
        } catch (IllegalArgumentException e) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid type\"}");
            return;
        }

        int hour = data.get("hour").getAsInt();
        int minute = data.get("minute").getAsInt();
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"hour must be 0-23, minute must be 0-59\"}");
            return;
        }

        java.util.Set<Integer> days = new java.util.HashSet<>();
        if (data.has("daysOfWeek")) {
            data.getAsJsonArray("daysOfWeek").forEach(e -> days.add(e.getAsInt()));
        } else {
            // Default: all days
            for (int i = 1; i <= 7; i++) days.add(i);
        }

        String message = data.has("message") ? data.get("message").getAsString() : "";

        ServerScheduler scheduler = CheeseDashMod.INSTANCE.getScheduler();
        ServerScheduler.RecurringSchedule rec = scheduler.addRecurring(type, hour, minute, days, message);

        // Audit
        SessionManager.Session session = WebDashboard.getSession(exchange);
        if (session != null && dashboard.getAuditLogger() != null) {
            dashboard.getAuditLogger().log(session, "schedule.recurring_add",
                type + " at " + String.format("%02d:%02d", hour, minute));
        }

        WebDashboard.respondJson(exchange, rec.toJson());
    }

    private static void handleScheduleUpdateRecurring(HttpExchange exchange, WebDashboard dashboard,
                                                        String id) throws IOException {
        String body = WebDashboard.readBody(exchange);
        JsonObject data;
        try {
            data = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid JSON\"}");
            return;
        }

        ServerScheduler scheduler = CheeseDashMod.INSTANCE.getScheduler();
        if (!scheduler.updateRecurring(id, data)) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Recurring schedule not found: " + escapeJson(id) + "\"}");
            return;
        }

        // Audit
        SessionManager.Session session = WebDashboard.getSession(exchange);
        if (session != null && dashboard.getAuditLogger() != null) {
            dashboard.getAuditLogger().log(session, "schedule.recurring_update", "Updated " + id);
        }

        ServerScheduler.RecurringSchedule updated = scheduler.getRecurring(id);
        WebDashboard.respondJson(exchange, updated != null ? updated.toJson() : new JsonObject());
    }

    private static void handleScheduleDeleteRecurring(HttpExchange exchange, WebDashboard dashboard,
                                                        String id) throws IOException {
        ServerScheduler scheduler = CheeseDashMod.INSTANCE.getScheduler();
        if (!scheduler.removeRecurring(id)) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Recurring schedule not found: " + escapeJson(id) + "\"}");
            return;
        }

        // Audit
        SessionManager.Session session = WebDashboard.getSession(exchange);
        if (session != null && dashboard.getAuditLogger() != null) {
            dashboard.getAuditLogger().log(session, "schedule.recurring_delete", "Deleted " + id);
        }

        WebDashboard.respond(exchange, 200, "{\"deleted\":true}");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  WEBHOOK HANDLERS
    // ══════════════════════════════════════════════════════════════════════

    private static void handleWebhookEventTypes(HttpExchange exchange) throws IOException {
        JsonArray arr = new JsonArray();
        for (DiscordWebhookManager.DiscordEventType et : DiscordWebhookManager.DiscordEventType.values()) {
            arr.add(et.toJson());
        }
        WebDashboard.respondJson(exchange, arr);
    }

    private static void handleWebhookList(HttpExchange exchange) throws IOException {
        DiscordWebhookManager discord = CheeseDashMod.INSTANCE.getDiscordWebhookManager();
        JsonArray arr = new JsonArray();
        for (DiscordWebhookManager.WebhookConfig w : discord.getWebhooks()) {
            arr.add(w.toJson(true)); // masked URL
        }
        WebDashboard.respondJson(exchange, arr);
    }

    private static void handleWebhookGet(HttpExchange exchange, String id) throws IOException {
        DiscordWebhookManager discord = CheeseDashMod.INSTANCE.getDiscordWebhookManager();
        DiscordWebhookManager.WebhookConfig w = discord.getWebhook(id);
        if (w == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Webhook not found\"}");
            return;
        }
        WebDashboard.respondJson(exchange, w.toJson(false)); // full URL for detail view
    }

    private static void handleWebhookCreate(HttpExchange exchange, WebDashboard dashboard) throws IOException {
        String body = WebDashboard.readBody(exchange);
        JsonObject data;
        try {
            data = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid JSON\"}");
            return;
        }

        if (!data.has("name") || !data.has("url")) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"name and url are required\"}");
            return;
        }

        String name = data.get("name").getAsString().trim();
        String url = data.get("url").getAsString().trim();

        if (name.isEmpty() || name.length() > 64) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"name must be 1-64 characters\"}");
            return;
        }
        if (!url.startsWith("https://discord.com/api/webhooks/") && !url.startsWith("https://discordapp.com/api/webhooks/")) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"URL must be a valid Discord webhook URL\"}");
            return;
        }

        DiscordWebhookManager discord = CheeseDashMod.INSTANCE.getDiscordWebhookManager();
        DiscordWebhookManager.WebhookConfig w = discord.addWebhook(name, url);

        // Audit
        SessionManager.Session session = WebDashboard.getSession(exchange);
        if (session != null && dashboard.getAuditLogger() != null) {
            dashboard.getAuditLogger().log(session, "webhook.create", "Created webhook '" + name + "'");
        }

        WebDashboard.respondJson(exchange, w.toJson(true));
    }

    private static void handleWebhookUpdate(HttpExchange exchange, WebDashboard dashboard,
                                              String id) throws IOException {
        String body = WebDashboard.readBody(exchange);
        JsonObject data;
        try {
            data = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid JSON\"}");
            return;
        }

        // Validate URL if being updated
        if (data.has("url")) {
            String url = data.get("url").getAsString().trim();
            if (!url.startsWith("https://discord.com/api/webhooks/") && !url.startsWith("https://discordapp.com/api/webhooks/")) {
                WebDashboard.respond(exchange, 400, "{\"error\":\"URL must be a valid Discord webhook URL\"}");
                return;
            }
        }

        DiscordWebhookManager discord = CheeseDashMod.INSTANCE.getDiscordWebhookManager();
        if (!discord.updateWebhook(id, data)) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Webhook not found\"}");
            return;
        }

        // Audit
        SessionManager.Session session = WebDashboard.getSession(exchange);
        if (session != null && dashboard.getAuditLogger() != null) {
            dashboard.getAuditLogger().log(session, "webhook.update", "Updated webhook " + id);
        }

        DiscordWebhookManager.WebhookConfig updated = discord.getWebhook(id);
        WebDashboard.respondJson(exchange, updated != null ? updated.toJson(true) : new JsonObject());
    }

    private static void handleWebhookDelete(HttpExchange exchange, WebDashboard dashboard,
                                              String id) throws IOException {
        DiscordWebhookManager discord = CheeseDashMod.INSTANCE.getDiscordWebhookManager();
        if (!discord.removeWebhook(id)) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Webhook not found\"}");
            return;
        }

        // Audit
        SessionManager.Session session = WebDashboard.getSession(exchange);
        if (session != null && dashboard.getAuditLogger() != null) {
            dashboard.getAuditLogger().log(session, "webhook.delete", "Deleted webhook " + id);
        }

        WebDashboard.respond(exchange, 200, "{\"deleted\":true}");
    }

    private static void handleWebhookTest(HttpExchange exchange, String id) throws IOException {
        DiscordWebhookManager discord = CheeseDashMod.INSTANCE.getDiscordWebhookManager();
        DiscordWebhookManager.WebhookConfig w = discord.getWebhook(id);
        if (w == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Webhook not found\"}");
            return;
        }

        try {
            String result = discord.testWebhook(id).get(10, java.util.concurrent.TimeUnit.SECONDS);
            if (result == null) {
                WebDashboard.respond(exchange, 200, "{\"success\":true}");
            } else {
                WebDashboard.respond(exchange, 200, "{\"success\":false,\"error\":\"" + escapeJson(result) + "\"}");
            }
        } catch (Exception e) {
            WebDashboard.respond(exchange, 500, "{\"success\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ZONE FOLDERS (visual organization — CheeseDash only)
    // ══════════════════════════════════════════════════════════════════════

    private static void handleZoneFoldersGet(HttpExchange exchange, WebDashboard dashboard) throws IOException {
        JsonArray arr = new JsonArray();
        for (JsonObject f : dashboard.getConfig().getZoneFolders()) {
            arr.add(f.deepCopy());
        }
        WebDashboard.respond(exchange, 200, arr.toString());
    }

    private static void handleZoneFoldersSave(HttpExchange exchange, WebDashboard dashboard) throws IOException {
        String body = WebDashboard.readBody(exchange);
        JsonArray arr;
        try {
            arr = JsonParser.parseString(body).getAsJsonArray();
        } catch (Exception e) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Expected JSON array\"}");
            return;
        }
        List<JsonObject> folders = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            folders.add(arr.get(i).getAsJsonObject());
        }
        dashboard.getConfig().setZoneFolders(folders);
        WebDashboard.respond(exchange, 200, "{\"ok\":true}");
    }

    private static void handleZoneFolderCreate(HttpExchange exchange, WebDashboard dashboard) throws IOException {
        String body = WebDashboard.readBody(exchange);
        JsonObject data;
        try {
            data = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid JSON\"}");
            return;
        }
        String name = data.has("name") ? data.get("name").getAsString().trim() : "";
        if (name.isEmpty()) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"name is required\"}");
            return;
        }
        String id = "f_" + Long.toHexString(System.currentTimeMillis());
        List<JsonObject> folders = dashboard.getConfig().getZoneFolders();
        JsonObject folder = new JsonObject();
        folder.addProperty("id", id);
        folder.addProperty("name", name);
        folder.add("zoneIds", new JsonArray());
        folder.addProperty("order", folders.size());
        folders.add(folder);
        dashboard.getConfig().save();
        WebDashboard.respond(exchange, 200, folder.toString());
    }

    private static void handleZoneFolderDelete(HttpExchange exchange, WebDashboard dashboard,
                                                String folderId) throws IOException {
        if (folderId == null || folderId.isEmpty() || folderId.contains("..") || folderId.contains("/")) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid folder ID\"}");
            return;
        }
        List<JsonObject> folders = dashboard.getConfig().getZoneFolders();
        boolean removed = folders.removeIf(f -> f.has("id") && f.get("id").getAsString().equals(folderId));
        if (!removed) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Folder not found\"}");
            return;
        }
        dashboard.getConfig().save();
        WebDashboard.respond(exchange, 200, "{\"ok\":true}");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
