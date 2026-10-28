package com.cheesedash;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Embedded HTTP server for the CheeseDash web dashboard.
 * Uses only JDK built-ins (com.sun.net.httpserver) — zero extra dependencies.
 *
 * Security:
 *  - All /api/* routes require "Authorization: Bearer <token>" header
 *  - Auth accepts both session IDs and raw tokens (creates implicit session)
 *  - Rate limiting: max 20 bad-auth attempts per 60s per IP → 429
 *  - Static files served from resources/dashboard/
 *  - CORS locked down, security headers on every response
 *
 * Phase 5 additions:
 *  - SessionManager for 24h session expiry
 *  - AuditLogger for persistent action logging
 *  - Exchange attributes for passing session context to modules
 */
public class WebDashboard {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseDash");
    static final Gson GSON = new Gson();

    private final DashboardConfig config;
    private HttpServer server;
    private MinecraftServer mcServer;

    // Session & Audit
    private final SessionManager sessionManager = new SessionManager();
    private final AuditLogger auditLogger;

    // Rate limiter: IP → [attempt count, window start ms]
    private final Map<String, long[]> rateLimitMap = new ConcurrentHashMap<>();
    private static final int RATE_LIMIT_MAX  = 20;
    private static final long RATE_LIMIT_WINDOW = 60_000L;

    public WebDashboard(DashboardConfig config, AuditLogger auditLogger) {
        this.config = config;
        this.auditLogger = auditLogger;
    }

    public void setMinecraftServer(MinecraftServer server) {
        this.mcServer = server;
    }

    public MinecraftServer getMinecraftServer() {
        return mcServer;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public AuditLogger getAuditLogger() {
        return auditLogger;
    }

    /** Start the HTTP server. Called on server start. */
    public void start() {
        if (!config.isEnabled()) {
            LOGGER.info("[CheeseDash] Disabled in config, skipping.");
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(config.getPort()), 64);
            server.setExecutor(Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "CheeseDash-HTTP");
                t.setDaemon(true);
                return t;
            }));

            // ── Routes ──────────────────────────────────────────────────────
            // Static files (login page, dashboard shell, assets)
            server.createContext("/", new StaticHandler());

            // Register public module contexts (no auth) — BEFORE /api/ so they match first
            for (DashboardModule module : DashboardRegistry.getModules().values()) {
                if (module.getPublicPrefix() != null) {
                    registerPublicContext(module);
                }
            }

            // All API routes go through auth middleware
            server.createContext("/api/", exchange -> {
                addSecurityHeaders(exchange);

                // Handle CORS preflight
                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                    exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }

                String ip = getClientIp(exchange);
                String path = exchange.getRequestURI().getPath();
                String route = path.replaceFirst("^/api/v1", "").replaceFirst("^/api", "");

                // Auth-free endpoints: login
                if (route.equals("/auth/login")) {
                    DashboardApi.handle(exchange, this);
                    return;
                }

                // Check rate limit first
                if (isRateLimited(ip)) {
                    respond(exchange, 429, "{\"error\":\"Too many requests\"}");
                    return;
                }

                // Auth check — session or raw token
                SessionManager.Session session = authenticate(exchange);
                if (session == null) {
                    recordFailedAuth(ip);
                    respond(exchange, 401, "{\"error\":\"Unauthorized\"}");
                    return;
                }

                // Store session in exchange attributes for downstream use
                exchange.setAttribute("cheesedash.session", session);
                exchange.setAttribute("cheesedash.username", session.getUsername());
                exchange.setAttribute("cheesedash.role", session.getRole().name());

                // Route dispatch
                DashboardApi.handle(exchange, this);
            });

            server.start();
            LOGGER.info("[CheeseDash] ✅ Started on port {} — http://localhost:{}/",
                config.getPort(), config.getPort());
        } catch (Exception e) {
            LOGGER.error("[CheeseDash] ❌ Failed to start: {}", e.getMessage(), e);
        }
    }

    /** Stop the HTTP server. Called on server stop. */
    public void stop() {
        if (server != null) {
            server.stop(1);
            LOGGER.info("[CheeseDash] Stopped.");
        }
    }

    // ── Auth ────────────────────────────────────────────────────────────────

    /**
     * Authenticate an exchange. Checks Bearer token as:
     * 1. A valid session ID → return that session
     * 2. A raw master/account token → create implicit session and return it
     * 3. Neither → return null (unauthorized)
     */
    private SessionManager.Session authenticate(HttpExchange exchange) {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        String bearer = authHeader.substring(7).trim();
        String ip = getClientIp(exchange);

        // 1. Try as session ID
        SessionManager.Session session = sessionManager.validate(bearer);
        if (session != null) return session;

        // 2. Try as raw token (master or account) — create implicit session
        DashboardConfig.ResolvedAuth resolved = config.resolveToken(bearer);
        if (resolved != null) {
            return sessionManager.createSession(resolved.username, resolved.role, ip);
        }

        return null;
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (ba.length != bb.length) return false;
        int diff = 0;
        for (int i = 0; i < ba.length; i++) diff |= (ba[i] ^ bb[i]);
        return diff == 0;
    }

    // ── Rate Limiting ───────────────────────────────────────────────────────

    private boolean isRateLimited(String ip) {
        long now = System.currentTimeMillis();
        long[] entry = rateLimitMap.get(ip);
        if (entry == null) return false;
        if (now - entry[1] > RATE_LIMIT_WINDOW) {
            rateLimitMap.remove(ip);
            return false;
        }
        return entry[0] >= RATE_LIMIT_MAX;
    }

    private void recordFailedAuth(String ip) {
        long now = System.currentTimeMillis();
        rateLimitMap.compute(ip, (k, v) -> {
            if (v == null || now - v[1] > RATE_LIMIT_WINDOW) return new long[]{1, now};
            v[0]++;
            return v;
        });
    }

    // ── Security Headers ────────────────────────────────────────────────────

    static void addSecurityHeaders(HttpExchange exchange) {
        var h = exchange.getResponseHeaders();
        h.set("X-Frame-Options",       "DENY");
        h.set("X-Content-Type-Options","nosniff");
        h.set("X-XSS-Protection",      "1; mode=block");
        h.set("Referrer-Policy",        "no-referrer");
        h.set("Content-Security-Policy",
            "default-src 'self'; " +
            "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://cdn.tailwindcss.com https://cdn.jsdelivr.net https://unpkg.com; " +
            "style-src 'self' 'unsafe-inline' https://cdn.tailwindcss.com https://cdn.jsdelivr.net https://fonts.googleapis.com; " +
            "font-src 'self' https://fonts.gstatic.com; " +
            "img-src 'self' data: blob: https://mc-heads.net https://minecraft.wiki https://img.pokemondb.net; " +
            "connect-src 'self' ws: wss:;");
        h.set("Access-Control-Allow-Origin", "");
    }

    // ── Public Response Helpers (used by modules) ───────────────────────────

    public static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    public static void respondJson(HttpExchange exchange, Object obj) throws IOException {
        respond(exchange, 200, GSON.toJson(obj));
    }

    public static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Serve a PNG file with mtime-based revalidation so re-uploaded images appear
     * immediately on clients. Sets {@code Last-Modified} + {@code ETag} from the
     * file mtime and asks the client to revalidate every time. Unchanged files
     * return {@code 304 Not Modified} (just headers, no body).
     */
    public static void servePngWithRevalidation(HttpExchange exchange, java.nio.file.Path path) throws IOException {
        if (!java.nio.file.Files.exists(path)) {
            respond(exchange, 404, "{\"error\":\"Not found\"}");
            return;
        }
        long mtime;
        try { mtime = java.nio.file.Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException e) { mtime = 0L; }
        long mtimeSec = mtime / 1000L * 1000L;
        String etag = "\"" + Long.toHexString(mtimeSec) + "\"";

        String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
        String ifModifiedSince = exchange.getRequestHeaders().getFirst("If-Modified-Since");
        boolean notModified = etag.equals(ifNoneMatch);
        if (!notModified && ifModifiedSince != null) {
            try {
                long since = java.time.ZonedDateTime
                    .parse(ifModifiedSince, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
                notModified = since >= mtimeSec;
            } catch (Exception ignored) {}
        }

        java.time.format.DateTimeFormatter httpDate = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .withZone(java.time.ZoneId.of("GMT"));
        exchange.getResponseHeaders().set("Last-Modified",
                httpDate.format(java.time.Instant.ofEpochMilli(mtimeSec)));
        exchange.getResponseHeaders().set("ETag", etag);
        // 5 min hard cache + 1 hour stale-while-revalidate: visitors see updates
        // within minutes without paying a 304 round-trip on every image load.
        // Combined with the ETag/Last-Modified above, browsers still fall back
        // to revalidation when the cache window expires.
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=300, stale-while-revalidate=3600");
        exchange.getResponseHeaders().set("Content-Type", "image/png");

        if (notModified) {
            exchange.sendResponseHeaders(304, -1);
            exchange.getResponseBody().close();
            return;
        }
        byte[] data = java.nio.file.Files.readAllBytes(path);
        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(data); }
    }

    /** Execute a Runnable on the MC server tick thread and wait for result (max 5s). */
    public static void executeOnServerThread(MinecraftServer server, Runnable task) throws Exception {
        if (server == null) throw new IllegalStateException("Server not available");
        CompletableFuture<Void> future = new CompletableFuture<>();
        server.execute(() -> {
            try { task.run(); future.complete(null); }
            catch (Exception e) { future.completeExceptionally(e); }
        });
        future.get(5, TimeUnit.SECONDS);
    }

    static String getClientIp(HttpExchange exchange) {
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    DashboardConfig getConfig() {
        return config;
    }

    // ── Session helpers for modules ──────────────────────────────────────────

    /**
     * Get the session from an exchange (set during auth).
     * Returns null if somehow called without auth (should not happen for /api/ routes).
     */
    public static SessionManager.Session getSession(HttpExchange exchange) {
        Object s = exchange.getAttribute("cheesedash.session");
        return s instanceof SessionManager.Session ? (SessionManager.Session) s : null;
    }

    /**
     * Check if the current session has at least the given role level.
     * SUPER_ADMIN > OPERATOR > READ_ONLY
     */
    public static boolean hasRole(HttpExchange exchange, AdminAccount.Role minRole) {
        SessionManager.Session session = getSession(exchange);
        if (session == null) return false;
        return session.getRole().ordinal() >= minRole.ordinal();
    }

    /**
     * Require a minimum role. If not met, sends 403 and returns false.
     */
    public static boolean requireRole(HttpExchange exchange, AdminAccount.Role minRole) throws IOException {
        if (!hasRole(exchange, minRole)) {
            respond(exchange, 403, "{\"error\":\"Insufficient permissions — requires " + minRole.name() + "\"}");
            return false;
        }
        return true;
    }

    /**
     * Require write access (OPERATOR or higher). If not met, sends 403 and returns false.
     */
    public static boolean requireWrite(HttpExchange exchange) throws IOException {
        return requireRole(exchange, AdminAccount.Role.OPERATOR);
    }

    /**
     * Require admin access (SUPER_ADMIN only). If not met, sends 403 and returns false.
     */
    public static boolean requireAdmin(HttpExchange exchange) throws IOException {
        return requireRole(exchange, AdminAccount.Role.SUPER_ADMIN);
    }

    // ── Public Module Context ─────────────────────────────────────────────────

    private void registerPublicContext(DashboardModule module) {
        String prefix = "/" + module.getPublicPrefix();
        com.sun.net.httpserver.HttpHandler handler = exchange -> {
            addPublicSecurityHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String subPath = path.substring(prefix.length());
            if (subPath.isEmpty() || subPath.equals("/")) subPath = "/index.html";

            // API routes: /<prefix>/api/*
            if (subPath.startsWith("/api/")) {
                try {
                    module.handlePublicRequest(exchange, subPath.substring(4));
                } catch (Exception e) {
                    LOGGER.error("[CheeseDash] Public API error: {}", e.getMessage(), e);
                    respond(exchange, 500, "{\"error\":\"Internal server error\"}");
                }
                return;
            }

            // Static files from classpath /<publicPrefix>/
            servePublicResource(exchange, module.getPublicPrefix(), subPath);
        };

        server.createContext(prefix + "/", handler);
        // Redirect /<prefix> (no trailing slash) → /<prefix>/
        server.createContext(prefix, exchange -> {
            String reqPath = exchange.getRequestURI().getPath();
            if (reqPath.equals(prefix)) {
                exchange.getResponseHeaders().set("Location", prefix + "/");
                exchange.sendResponseHeaders(301, -1);
                exchange.getResponseBody().close();
            } else {
                handler.handle(exchange);
            }
        });
        LOGGER.info("[CheeseDash] Public context registered: {}/", prefix);
    }

    private void servePublicResource(HttpExchange exchange, String prefix, String path) throws IOException {
        if (path.contains("..")) { respond(exchange, 403, "{\"error\":\"Forbidden\"}"); return; }
        String resource = "/" + prefix + path;
        try (InputStream is = WebDashboard.class.getResourceAsStream(resource)) {
            if (is == null) {
                // SPA fallback
                try (InputStream fallback = WebDashboard.class.getResourceAsStream("/" + prefix + "/index.html")) {
                    if (fallback == null) { respond(exchange, 404, "{\"error\":\"Not found\"}"); return; }
                    serveBytes(exchange, fallback.readAllBytes(), "text/html; charset=utf-8", true);
                }
                return;
            }
            serveBytes(exchange, is.readAllBytes(), detectContentType(path), false);
        }
    }

    private void serveBytes(HttpExchange exchange, byte[] data, String contentType, boolean noCache) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (!noCache && !contentType.startsWith("text/html")) {
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
        }
        // Transparent gzip for compressible payloads (HTML/JS/CSS/JSON/SVG/text).
        // Skip for already-compressed formats (PNG/WebP/AVIF/woff2 are LZ-compressed
        // internally — gzipping them costs CPU for no gain).
        if (shouldGzip(contentType, data.length)
                && acceptsGzip(exchange.getRequestHeaders().getFirst("Accept-Encoding"))) {
            byte[] gz = gzip(data);
            // Only use gzip if it actually shrinks the payload. Tiny strings can grow.
            if (gz.length < data.length) {
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                // Vary on Accept-Encoding so caches don't cross-serve gzip to non-gzip clients.
                exchange.getResponseHeaders().add("Vary", "Accept-Encoding");
                exchange.sendResponseHeaders(200, gz.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(gz); }
                return;
            }
        }
        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(data); }
    }

    private static boolean shouldGzip(String contentType, int size) {
        if (size < 256) return false; // overhead > gain for tiny payloads
        if (contentType == null) return false;
        return contentType.startsWith("text/")
            || contentType.startsWith("application/javascript")
            || contentType.startsWith("application/json")
            || contentType.startsWith("image/svg+xml");
    }

    private static boolean acceptsGzip(String header) {
        return header != null && header.toLowerCase().contains("gzip");
    }

    private static byte[] gzip(byte[] in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(in.length);
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bos)) {
            gz.write(in);
        }
        return bos.toByteArray();
    }

    private static void addPublicSecurityHeaders(HttpExchange exchange) {
        var h = exchange.getResponseHeaders();
        h.set("X-Frame-Options",        "DENY");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-XSS-Protection",       "1; mode=block");
        h.set("Referrer-Policy",         "strict-origin-when-cross-origin");
        // HSTS: tell browsers to always use HTTPS for this origin (1 year, include subdomains).
        // Safe even over HTTP — browsers ignore it; only takes effect on first HTTPS hit.
        h.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        // Restrict powerful features the storefront doesn't need (defence in depth).
        h.set("Permissions-Policy",
            "camera=(), microphone=(), geolocation=(), payment=(self \"https://js.stripe.com\")");
        h.set("Content-Security-Policy",
            "default-src 'self'; " +
            // Self-hosted JS bundle + a few CDNs for Alpine/Lenis (SRI-pinned in HTML),
            // plus Stripe.js for the hosted checkout redirect.
            "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://cdn.jsdelivr.net https://unpkg.com https://js.stripe.com; " +
            // Self-hosted CSS only (Tailwind CDN dropped, Google Fonts self-hosted).
            "style-src 'self' 'unsafe-inline'; " +
            // Self-hosted woff2 only.
            "font-src 'self'; " +
            "img-src 'self' data: blob: https://mc-heads.net; " +
            "frame-src https://js.stripe.com https://hooks.stripe.com; " +
            "frame-ancestors 'none'; " +
            "connect-src 'self' https://api.stripe.com;");
    }

    static String detectContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js"))   return "application/javascript; charset=utf-8";
        if (path.endsWith(".css"))  return "text/css; charset=utf-8";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".png"))  return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".svg"))  return "image/svg+xml";
        if (path.endsWith(".ico"))  return "image/x-icon";
        if (path.endsWith(".webp")) return "image/webp";
        if (path.endsWith(".avif")) return "image/avif";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".woff"))  return "font/woff";
        if (path.endsWith(".ttf"))   return "font/ttf";
        if (path.endsWith(".otf"))   return "font/otf";
        return "application/octet-stream";
    }

    /** Get the HttpServer instance (for modules needing direct context registration). */
    public HttpServer getHttpServer() { return server; }

    // ── Static File Handler ─────────────────────────────────────────────────

    private class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addSecurityHeaders(exchange);
            String path = exchange.getRequestURI().getPath();

            // Normalize: root → index.html, block traversal
            if (path.equals("/") || path.isEmpty()) path = "/index.html";
            if (path.contains("..")) { respond(exchange, 403, "{\"error\":\"Forbidden\"}"); return; }

            // Load from classpath resources/dashboard/
            String resource = "/dashboard" + path;
            try (InputStream is = WebDashboard.class.getResourceAsStream(resource)) {
                if (is == null) {
                    // SPA fallback: return index.html for unknown paths (client-side routing)
                    try (InputStream fallback = WebDashboard.class.getResourceAsStream("/dashboard/index.html")) {
                        if (fallback == null) { respond(exchange, 404, "{\"error\":\"Not found\"}"); return; }
                        serveBytes(exchange, fallback.readAllBytes(), "text/html; charset=utf-8", true);
                    }
                    return;
                }
                String contentType = WebDashboard.detectContentType(path);
                serveBytes(exchange, is.readAllBytes(), contentType, false);
            }
        }
    }
}
