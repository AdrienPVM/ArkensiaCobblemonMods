package com.cheesestore.auth;

import com.cheesedash.WebDashboard;
import com.cheesestore.CheeseStoreMod;
import com.cheesestore.WalletManager;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Anonymous browser session + in-game pairing flow (no third-party identity provider).
 *
 * On first visit, every browser receives a long-lived random {@code session_id}
 * cookie. The visitor clicks "Identify me" → server issues a short pairing code
 * tied to that session → player types {@code /store link <code>} in-game →
 * the session becomes permanently bound to their Minecraft UUID.
 *
 * Public endpoints (under the {@code /store/api/oauth/} prefix — the path is kept
 * for compatibility with the existing frontend, even though Google is no longer
 * involved):
 *   GET  /oauth/me          — return the current session + linked profile (creates a session if absent)
 *   GET  /oauth/link-code   — issue a fresh pairing code for the current session
 *   POST /oauth/logout      — unlink + clear the cookie
 *
 * The legacy {@code /oauth/start} and {@code /oauth/callback} endpoints from the
 * Google flow are still answered (with a redirect home) so any cached link still
 * lands on a working page.
 */
public final class GoogleOAuth {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseStore");
    public static final String COOKIE_NAME = "store_session";
    /** Session lifetime — 14 days sliding. Trade-off between forcing re-login often
     *  (annoying) and keeping a stolen cookie usable for too long. */
    private static final long SESSION_TTL_SECONDS = 14L * 24 * 3600;
    /** Cookie attributes sent on every Set-Cookie. We deploy the storefront
     *  over plain HTTP (direct IP:port — no HTTPS reverse proxy), so {@code Secure}
     *  must NOT be set — browsers refuse to return Secure cookies over HTTP
     *  (localhost gets a pass, but a real public IP does not), which silently
     *  rotates session IDs on every request and breaks the link-code flow.
     *  {@code SameSite=Lax} still blocks the third-party POST CSRF surface,
     *  which is the only attack that matters here. */
    private static final String COOKIE_FLAGS = "; HttpOnly; Path=/; SameSite=Lax";

    private GoogleOAuth() {}

    public static boolean dispatch(HttpExchange exchange, String path) throws IOException {
        switch (path) {
            case "/oauth/me":        handleMe(exchange);        return true;
            case "/oauth/link-code": handleLinkCode(exchange);  return true;
            case "/oauth/logout":    handleLogout(exchange);    return true;
            // Legacy endpoints — just bounce back to the store homepage.
            case "/oauth/start":
            case "/oauth/callback":
                redirectHome(exchange);
                return true;
            default:
                return false;
        }
    }

    // ── Endpoints ────────────────────────────────────────────────────────────

    private static void handleMe(HttpExchange exchange) throws IOException {
        String sessionId = ensureSessionCookie(exchange);
        AccountLinker.Link link = AccountLinker.getInstance().findBySession(sessionId);
        JsonObject out = new JsonObject();
        out.addProperty("sessionId", sessionId);
        if (link != null) {
            out.addProperty("linkedUuid", link.minecraftUuid.toString());
            out.addProperty("playerName", link.minecraftName);
            try {
                long balance = WalletManager.getInstance().getBalance(link.minecraftUuid);
                out.addProperty("credits", balance);
            } catch (Exception ignored) {
                out.addProperty("credits", 0L);
            }
        }
        WebDashboard.respond(exchange, 200, out.toString());
    }

    private static void handleLinkCode(HttpExchange exchange) throws IOException {
        // Cap how often a single IP can ask for fresh codes — would otherwise
        // be a nice path to brute-force the 8-char pairing space at scale.
        if (!com.cheesestore.dashboard.StoreApi.rateLimitOk(
                "linkcode:" + com.cheesestore.dashboard.StoreApi.publicClientIp(exchange),
                12, 60_000L)) {
            WebDashboard.respond(exchange, 429, "{\"error\":\"too_many_requests\"}");
            return;
        }
        String sessionId = ensureSessionCookie(exchange);
        AccountLinker linker = AccountLinker.getInstance();
        AccountLinker.Link existing = linker.findBySession(sessionId);
        if (existing != null) {
            JsonObject out = new JsonObject();
            out.addProperty("alreadyLinked", true);
            out.addProperty("playerName", existing.minecraftName);
            WebDashboard.respond(exchange, 200, out.toString());
            return;
        }
        AccountLinker.PendingLink p = linker.issueCode(sessionId);
        JsonObject out = new JsonObject();
        out.addProperty("code", p.code);
        out.addProperty("expiresAt", p.expiresAt);
        WebDashboard.respond(exchange, 200, out.toString());
    }

    private static void handleLogout(HttpExchange exchange) throws IOException {
        String sessionId = readSessionCookie(exchange);
        if (sessionId != null) AccountLinker.getInstance().unlinkSession(sessionId);
        // Clear the cookie too so the next visit starts a fresh anonymous session.
        exchange.getResponseHeaders().add("Set-Cookie",
            COOKIE_NAME + "=" + COOKIE_FLAGS + "; Max-Age=0");
        WebDashboard.respond(exchange, 200, "{\"loggedOut\":true}");
    }

    // ── Public helpers ───────────────────────────────────────────────────────

    /** Resolve the linked Minecraft account for the current request, or null. */
    public static Session currentSession(HttpExchange exchange) {
        String sessionId = readSessionCookie(exchange);
        if (sessionId == null) return null;
        AccountLinker.Link link = AccountLinker.getInstance().findBySession(sessionId);
        if (link == null) return null;
        // For backward compat with the morpho/buy code path, the Session object
        // exposes `email` (now repurposed to "session id" for diagnostics) and
        // `googleId` (unused, kept blank).
        return new Session(sessionId, "");
    }

    public static final class Session {
        /** @deprecated semantically a session id now, kept for binary compat. */
        public final String email;
        public final String googleId;
        public Session(String email, String googleId) {
            this.email = email;
            this.googleId = googleId;
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private static String readSessionCookie(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Cookie");
        if (header == null) return null;
        for (String part : header.split(";")) {
            String s = part.trim();
            if (s.startsWith(COOKIE_NAME + "=")) {
                String v = s.substring(COOKIE_NAME.length() + 1);
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    private static String ensureSessionCookie(HttpExchange exchange) {
        String existing = readSessionCookie(exchange);
        if (existing != null) return existing;
        String fresh = AccountLinker.randomSessionId();
        exchange.getResponseHeaders().add("Set-Cookie",
            COOKIE_NAME + "=" + fresh + COOKIE_FLAGS + "; Max-Age=" + SESSION_TTL_SECONDS);
        return fresh;
    }

    private static void redirectHome(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Location", "/store/");
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }
}
