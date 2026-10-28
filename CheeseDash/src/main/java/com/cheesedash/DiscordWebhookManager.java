package com.cheesedash;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Manages Discord webhook notifications for CheeseDash.
 *
 * Features:
 *  - Multiple webhooks with per-webhook event filtering
 *  - Async HTTP POST to Discord webhook URLs
 *  - Rate limiting (max 1 send / 2s per webhook)
 *  - Rich embeds with color-coded event types
 *  - Persistence to config/cheesedash_webhooks.json
 *  - Auto-mapping of WS broadcast channels to Discord events
 */
public class DiscordWebhookManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseDash");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Rate limit: min interval between sends per webhook (ms)
    private static final long RATE_LIMIT_INTERVAL_MS = 2000;

    private final ExecutorService httpExecutor;
    private final Path configPath;

    private final List<WebhookConfig> webhooks = new CopyOnWriteArrayList<>();
    private final Map<String, Long> rateLimitTracker = new ConcurrentHashMap<>();

    // ── Event Types ─────────────────────────────────────────────────────────

    public enum DiscordEventType {
        SERVER_START       ("D\u00E9marrage serveur",     "Le serveur Minecraft a d\u00E9marr\u00E9",       0x22c55e),
        SERVER_STOP        ("Arr\u00EAt serveur",         "Le serveur Minecraft s'arr\u00EAte",              0xef4444),
        RESTART_SCHEDULED  ("Restart planifi\u00E9",      "Un red\u00E9marrage a \u00E9t\u00E9 planifi\u00E9",                0xf5a623),
        RESTART_CANCELLED  ("Restart annul\u00E9",        "Le red\u00E9marrage planifi\u00E9 a \u00E9t\u00E9 annul\u00E9",    0x22d3ee),
        MAINTENANCE        ("Maintenance",                "Le serveur entre en maintenance",                 0xef4444),
        PLAYER_JOIN        ("Connexion joueur",           "Un joueur a rejoint le serveur",                  0x22d3ee),
        PLAYER_LEAVE       ("D\u00E9connexion joueur",    "Un joueur a quitt\u00E9 le serveur",             0x6b7280),
        EVENT_ANNOUNCE     ("Event annonc\u00E9",         "Un \u00E9v\u00E9nement a \u00E9t\u00E9 annonc\u00E9",              0xf5a623),
        EVENT_START        ("Event d\u00E9marr\u00E9",    "Un \u00E9v\u00E9nement a commenc\u00E9",                    0x22c55e),
        EVENT_END          ("Event termin\u00E9",         "Un \u00E9v\u00E9nement est termin\u00E9",                   0x6b7280),
        SCORE_LEADER_CHANGE("Changement de leader",       "Le classement d'un scoreboard a chang\u00E9",    0xffd700),
        MARKET_PURCHASE    ("Achat march\u00E9",          "Un joueur a achet\u00E9 un article",             0x22c55e),
        EGG_DISCOVERY      ("D\u00E9couverte d'\u0153uf", "Un joueur a trouv\u00E9 un collectible",        0x9b59b6),
        ADMIN_COMMAND      ("Commande admin",             "Une commande a \u00E9t\u00E9 ex\u00E9cut\u00E9e depuis le dashboard", 0xef4444),
        DIMENSION_CREATED  ("Dimension cr\u00E9\u00E9e",  "Une nouvelle dimension a \u00E9t\u00E9 cr\u00E9\u00E9e",   0x7b68ee),
        DIMENSION_DELETED  ("Dimension supprim\u00E9e",   "Une dimension a \u00E9t\u00E9 supprim\u00E9e",             0xef4444),
        SHIFT_TRANSFORM    ("Transformation",             "Un joueur a \u00E9t\u00E9 transform\u00E9",     0xff6b9d),
        PRISON_IMPRISONED  ("Joueur emprisonn\u00E9",     "Un joueur a \u00E9t\u00E9 envoy\u00E9 en prison",  0xef4444),
        PRISON_RELEASED    ("Joueur lib\u00E9r\u00E9",    "Un joueur a \u00E9t\u00E9 lib\u00E9r\u00E9 de prison", 0x22c55e),
        SCHEDULED_TASK_FIRED("T\u00E2che planifi\u00E9e", "Une action planifi\u00E9e s'est d\u00E9clench\u00E9e",     0xf5a623),
        LEAGUE_MASTER      ("Ma\u00EEtre de la Ligue",    "Un joueur a vaincu tous les champions",         0xffd700),
        CHAMPION_VICTORY   ("Victoire champion",          "Un joueur a vaincu un champion",                 0xf5a623),
        CUSTOM             ("Personnalis\u00E9",          "\u00C9v\u00E9nement personnalis\u00E9",                     0x9ca3af);

        public final String label;
        public final String description;
        public final int color;

        DiscordEventType(String label, String description, int color) {
            this.label = label;
            this.description = description;
            this.color = color;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", name());
            obj.addProperty("label", label);
            obj.addProperty("description", description);
            obj.addProperty("color", String.format("#%06x", color));
            // Categorize
            obj.addProperty("category", getCategory());
            return obj;
        }

        public String getCategory() {
            switch (this) {
                case SERVER_START: case SERVER_STOP: case RESTART_SCHEDULED:
                case RESTART_CANCELLED: case MAINTENANCE: case SCHEDULED_TASK_FIRED:
                    return "Serveur";
                case PLAYER_JOIN: case PLAYER_LEAVE:
                    return "Joueurs";
                case EVENT_ANNOUNCE: case EVENT_START: case EVENT_END:
                    return "Ev\u00E9nements";
                case SCORE_LEADER_CHANGE:
                    return "Scores";
                case MARKET_PURCHASE:
                    return "March\u00E9";
                case EGG_DISCOVERY:
                    return "Collectibles";
                case ADMIN_COMMAND:
                    return "Administration";
                case DIMENSION_CREATED: case DIMENSION_DELETED:
                    return "Dimensions";
                case SHIFT_TRANSFORM:
                    return "CobbleShift";
                case PRISON_IMPRISONED: case PRISON_RELEASED:
                    return "Prison";
                case LEAGUE_MASTER: case CHAMPION_VICTORY:
                    return "CobbleCap";
                default:
                    return "Autre";
            }
        }
    }

    // ── Webhook Config ──────────────────────────────────────────────────────

    public static class WebhookConfig {
        public String id;
        public String name;
        public String url;
        public String avatarUrl;
        public boolean enabled;
        public Set<DiscordEventType> events;
        public Set<String> scoreboardFilter; // for SCORE_LEADER_CHANGE
        public long createdAt;

        public WebhookConfig(String name, String url) {
            this.id = "wh_" + Integer.toHexString(new Random().nextInt(0xFFFFFF));
            this.name = name;
            this.url = url;
            this.avatarUrl = "";
            this.enabled = true;
            this.events = EnumSet.noneOf(DiscordEventType.class);
            this.scoreboardFilter = new HashSet<>();
            this.createdAt = System.currentTimeMillis();
        }

        private WebhookConfig() {} // for deserialization

        public JsonObject toJson(boolean maskUrl) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", id);
            obj.addProperty("name", name);
            if (maskUrl && url != null && url.length() > 20) {
                obj.addProperty("url", url.substring(0, 40) + "..." + url.substring(url.length() - 10));
            } else {
                obj.addProperty("url", url);
            }
            obj.addProperty("avatarUrl", avatarUrl != null ? avatarUrl : "");
            obj.addProperty("enabled", enabled);
            JsonArray evArr = new JsonArray();
            events.forEach(e -> evArr.add(e.name()));
            obj.add("events", evArr);
            JsonArray sbArr = new JsonArray();
            scoreboardFilter.forEach(sbArr::add);
            obj.add("scoreboardFilter", sbArr);
            obj.addProperty("createdAt", createdAt);
            obj.addProperty("eventCount", events.size());
            return obj;
        }

        public static WebhookConfig fromJson(JsonObject obj) {
            WebhookConfig w = new WebhookConfig();
            w.id = obj.get("id").getAsString();
            w.name = obj.get("name").getAsString();
            w.url = obj.get("url").getAsString();
            w.avatarUrl = obj.has("avatarUrl") ? obj.get("avatarUrl").getAsString() : "";
            w.enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
            w.events = EnumSet.noneOf(DiscordEventType.class);
            if (obj.has("events")) {
                obj.getAsJsonArray("events").forEach(e -> {
                    try { w.events.add(DiscordEventType.valueOf(e.getAsString())); }
                    catch (IllegalArgumentException ignored) {}
                });
            }
            w.scoreboardFilter = new HashSet<>();
            if (obj.has("scoreboardFilter")) {
                obj.getAsJsonArray("scoreboardFilter").forEach(e -> w.scoreboardFilter.add(e.getAsString()));
            }
            w.createdAt = obj.has("createdAt") ? obj.get("createdAt").getAsLong() : System.currentTimeMillis();
            return w;
        }
    }

    // ── Constructor & lifecycle ──────────────────────────────────────────────

    public DiscordWebhookManager(Path configDir) {
        this.configPath = configDir.resolve("cheesedash_webhooks.json");
        this.httpExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "CheeseDash-Discord");
            t.setDaemon(true);
            return t;
        });
        loadFromDisk();
        LOGGER.info("[CheeseDash] DiscordWebhookManager loaded — {} webhook(s)", webhooks.size());
    }

    public void shutdown() {
        httpExecutor.shutdownNow();
    }

    // ── Sending events ──────────────────────────────────────────────────────

    /**
     * Send a pre-built rich embed to every enabled webhook subscribed to
     * {@code eventType}. Used by mods that build their own elaborate embed
     * (e.g. CobbleCap's League Master notification with sprites + team detail).
     * Bypasses the internal {@code buildEmbed} template entirely.
     */
    public void sendRawEmbed(DiscordEventType eventType, JsonObject embed) {
        for (WebhookConfig webhook : webhooks) {
            if (!webhook.enabled) continue;
            if (!webhook.events.contains(eventType)) continue;
            Long lastSend = rateLimitTracker.get(webhook.id);
            if (lastSend != null && System.currentTimeMillis() - lastSend < RATE_LIMIT_INTERVAL_MS) continue;
            rateLimitTracker.put(webhook.id, System.currentTimeMillis());
            httpExecutor.submit(() -> postToWebhook(webhook, embed));
        }
    }

    /**
     * Send an event to all matching webhooks (async).
     * Iterates all enabled webhooks that have this event type in their set.
     */
    public void sendEvent(DiscordEventType eventType, Map<String, String> data) {
        for (WebhookConfig webhook : webhooks) {
            if (!webhook.enabled) continue;
            if (!webhook.events.contains(eventType)) continue;

            // SCORE_LEADER_CHANGE: check scoreboard filter
            if (eventType == DiscordEventType.SCORE_LEADER_CHANGE && !webhook.scoreboardFilter.isEmpty()) {
                String scoreboardId = data != null ? data.get("scoreboardId") : null;
                if (scoreboardId != null && !webhook.scoreboardFilter.contains(scoreboardId)) continue;
            }

            // Rate limit check
            Long lastSend = rateLimitTracker.get(webhook.id);
            if (lastSend != null && System.currentTimeMillis() - lastSend < RATE_LIMIT_INTERVAL_MS) {
                continue;
            }
            rateLimitTracker.put(webhook.id, System.currentTimeMillis());

            // Build embed and send async
            JsonObject embed = buildEmbed(eventType, data);
            httpExecutor.submit(() -> postToWebhook(webhook, embed));
        }
    }

    /**
     * Called by DashboardRegistry.broadcast() to automatically capture WS events
     * and route them to Discord without modifying external mods.
     */
    public void onBroadcast(String channel, String jsonMessage) {
        try {
            JsonObject msg = JsonParser.parseString(jsonMessage).getAsJsonObject();
            String type = msg.has("type") ? msg.get("type").getAsString() : "";

            DiscordEventType eventType = mapBroadcastToEvent(channel, type);
            if (eventType == null) return;

            Map<String, String> data = extractBroadcastData(msg, eventType);
            sendEvent(eventType, data);
        } catch (Exception e) {
            // Silently ignore parsing errors — not all broadcasts are for Discord
        }
    }

    // ── Webhook CRUD ────────────────────────────────────────────────────────

    public WebhookConfig addWebhook(String name, String url) {
        WebhookConfig w = new WebhookConfig(name, url);
        webhooks.add(w);
        saveToDisk();
        return w;
    }

    public boolean updateWebhook(String id, JsonObject updates) {
        WebhookConfig w = getWebhook(id);
        if (w == null) return false;
        if (updates.has("name")) w.name = updates.get("name").getAsString();
        if (updates.has("url")) w.url = updates.get("url").getAsString();
        if (updates.has("avatarUrl")) w.avatarUrl = updates.get("avatarUrl").getAsString();
        if (updates.has("enabled")) w.enabled = updates.get("enabled").getAsBoolean();
        if (updates.has("events")) {
            w.events = EnumSet.noneOf(DiscordEventType.class);
            updates.getAsJsonArray("events").forEach(e -> {
                try { w.events.add(DiscordEventType.valueOf(e.getAsString())); }
                catch (IllegalArgumentException ignored) {}
            });
        }
        if (updates.has("scoreboardFilter")) {
            w.scoreboardFilter.clear();
            updates.getAsJsonArray("scoreboardFilter").forEach(e -> w.scoreboardFilter.add(e.getAsString()));
        }
        saveToDisk();
        return true;
    }

    public boolean removeWebhook(String id) {
        boolean removed = webhooks.removeIf(w -> w.id.equals(id));
        if (removed) saveToDisk();
        return removed;
    }

    public WebhookConfig getWebhook(String id) {
        for (WebhookConfig w : webhooks) {
            if (w.id.equals(id)) return w;
        }
        return null;
    }

    public List<WebhookConfig> getWebhooks() {
        return Collections.unmodifiableList(webhooks);
    }

    /**
     * Send a test embed to a specific webhook. Returns success/error.
     */
    public CompletableFuture<String> testWebhook(String id) {
        WebhookConfig w = getWebhook(id);
        if (w == null) return CompletableFuture.completedFuture("Webhook not found");

        JsonObject embed = new JsonObject();
        embed.addProperty("title", "\u2705 Test CheeseDash");
        embed.addProperty("description", "Ce webhook fonctionne correctement !\n\n"
            + "**Webhook:** " + w.name + "\n"
            + "**Events configur\u00E9s:** " + w.events.size());
        embed.addProperty("color", 0x22c55e);
        JsonObject footer = new JsonObject();
        footer.addProperty("text", "CheeseDash \u2022 Test");
        embed.add("footer", footer);
        embed.addProperty("timestamp", Instant.now().toString());

        CompletableFuture<String> future = new CompletableFuture<>();
        httpExecutor.submit(() -> {
            String result = postToWebhook(w, embed);
            future.complete(result);
        });
        return future;
    }

    // ── Embed builder ───────────────────────────────────────────────────────

    private JsonObject buildEmbed(DiscordEventType eventType, Map<String, String> data) {
        JsonObject embed = new JsonObject();
        embed.addProperty("color", eventType.color);
        embed.addProperty("timestamp", Instant.now().toString());

        JsonObject footer = new JsonObject();
        footer.addProperty("text", "CheeseDash \u2022 Arkensia");
        embed.add("footer", footer);

        String title;
        String description;

        switch (eventType) {
            case SERVER_START:
                title = "\uD83D\uDFE2 Serveur d\u00E9marr\u00E9";
                description = "Le serveur Arkensia est en ligne.";
                break;
            case SERVER_STOP:
                title = "\uD83D\uDD34 Serveur arr\u00EAt\u00E9";
                description = "Le serveur Arkensia s'est arr\u00EAt\u00E9.";
                break;
            case RESTART_SCHEDULED:
                title = "\u23F0 Red\u00E9marrage planifi\u00E9";
                description = data != null && data.containsKey("delay")
                    ? "Red\u00E9marrage pr\u00E9vu dans **" + data.get("delay") + "**"
                    : "Un red\u00E9marrage a \u00E9t\u00E9 planifi\u00E9.";
                if (data != null && data.containsKey("message") && !data.get("message").isEmpty()) {
                    description += "\n> " + data.get("message");
                }
                break;
            case RESTART_CANCELLED:
                title = "\u274C Red\u00E9marrage annul\u00E9";
                description = "Le red\u00E9marrage planifi\u00E9 a \u00E9t\u00E9 annul\u00E9.";
                break;
            case MAINTENANCE:
                title = "\uD83D\uDD27 Maintenance";
                description = "Le serveur entre en maintenance.";
                if (data != null && data.containsKey("message") && !data.get("message").isEmpty()) {
                    description += "\n> " + data.get("message");
                }
                break;
            case PLAYER_JOIN:
                String joinName = data != null ? data.getOrDefault("name", "?") : "?";
                title = "\u27A1\uFE0F " + joinName + " a rejoint";
                description = "**" + joinName + "** a rejoint le serveur.";
                break;
            case PLAYER_LEAVE:
                String leaveName = data != null ? data.getOrDefault("name", "?") : "?";
                title = "\u2B05\uFE0F " + leaveName + " a quitt\u00E9";
                description = "**" + leaveName + "** a quitt\u00E9 le serveur.";
                break;
            case EVENT_ANNOUNCE:
                title = "\uD83D\uDCE2 Event annonc\u00E9";
                description = data != null && data.containsKey("eventName")
                    ? "**" + data.get("eventName") + "** a \u00E9t\u00E9 annonc\u00E9 !"
                    : "Un \u00E9v\u00E9nement a \u00E9t\u00E9 annonc\u00E9.";
                break;
            case EVENT_START:
                title = "\uD83C\uDFAE Event d\u00E9marr\u00E9";
                description = data != null && data.containsKey("eventName")
                    ? "**" + data.get("eventName") + "** a commenc\u00E9 !"
                    : "Un \u00E9v\u00E9nement a commenc\u00E9.";
                break;
            case EVENT_END:
                title = "\uD83C\uDFC1 Event termin\u00E9";
                description = data != null && data.containsKey("eventName")
                    ? "**" + data.get("eventName") + "** est termin\u00E9."
                    : "Un \u00E9v\u00E9nement est termin\u00E9.";
                break;
            case SCORE_LEADER_CHANGE:
                title = "\uD83C\uDFC6 Nouveau leader";
                description = data != null && data.containsKey("playerName") && data.containsKey("scoreboardName")
                    ? "**" + data.get("playerName") + "** est en t\u00EAte de **" + data.get("scoreboardName") + "** !"
                    : "Le classement a chang\u00E9.";
                break;
            case MARKET_PURCHASE:
                title = "\uD83D\uDED2 Achat";
                description = data != null && data.containsKey("playerName") && data.containsKey("itemName")
                    ? "**" + data.get("playerName") + "** a achet\u00E9 **" + data.get("itemName") + "**"
                    : "Un achat a \u00E9t\u00E9 effectu\u00E9.";
                break;
            case EGG_DISCOVERY:
                title = "\uD83E\uDD5A D\u00E9couverte";
                description = data != null && data.containsKey("playerName") && data.containsKey("eggName")
                    ? "**" + data.get("playerName") + "** a trouv\u00E9 **" + data.get("eggName") + "** !"
                    : "Un collectible a \u00E9t\u00E9 trouv\u00E9.";
                break;
            case ADMIN_COMMAND:
                title = "\u26A1 Commande admin";
                description = data != null && data.containsKey("command")
                    ? "`" + data.get("command") + "`"
                    : "Une commande a \u00E9t\u00E9 ex\u00E9cut\u00E9e.";
                if (data != null && data.containsKey("username")) {
                    description += "\nPar: **" + data.get("username") + "**";
                }
                break;
            case DIMENSION_CREATED:
                title = "\uD83C\uDF00 Dimension cr\u00E9\u00E9e";
                description = data != null && data.containsKey("dimName")
                    ? "Nouvelle dimension : **" + data.get("dimName") + "**"
                    : "Une dimension a \u00E9t\u00E9 cr\u00E9\u00E9e.";
                break;
            case DIMENSION_DELETED:
                title = "\uD83D\uDDD1\uFE0F Dimension supprim\u00E9e";
                description = data != null && data.containsKey("dimName")
                    ? "Dimension supprim\u00E9e : **" + data.get("dimName") + "**"
                    : "Une dimension a \u00E9t\u00E9 supprim\u00E9e.";
                break;
            case SHIFT_TRANSFORM:
                title = "\u2728 Transformation";
                description = data != null && data.containsKey("playerName") && data.containsKey("species")
                    ? "**" + data.get("playerName") + "** transform\u00E9 en **" + data.get("species") + "**"
                    : "Un joueur a \u00E9t\u00E9 transform\u00E9.";
                break;
            case PRISON_IMPRISONED: {
                String pname = data != null ? data.getOrDefault("playerName", "?") : "?";
                String dur   = data != null ? data.getOrDefault("duration", "?") : "?";
                String rsn   = data != null ? data.getOrDefault("reason", "\u2014") : "\u2014";
                String by    = data != null ? data.getOrDefault("byName", "Console") : "Console";
                title = "\u26D3 Joueur emprisonn\u00E9";
                description = "**" + pname + "** a \u00E9t\u00E9 emprisonn\u00E9 par **" + by + "**\n"
                            + "**Dur\u00E9e:** " + dur + "\n"
                            + "**Raison:** " + rsn;
                break;
            }
            case PRISON_RELEASED: {
                String pname = data != null ? data.getOrDefault("playerName", "?") : "?";
                String relReason = data != null ? data.getOrDefault("releaseReason", "PARDONED") : "PARDONED";
                String relBy = data != null ? data.get("releasedByName") : null;
                title = "\u2728 Joueur lib\u00E9r\u00E9";
                if ("EXPIRED".equals(relReason)) {
                    description = "**" + pname + "** a purg\u00E9 sa peine et a \u00E9t\u00E9 lib\u00E9r\u00E9.";
                } else {
                    description = "**" + pname + "** a \u00E9t\u00E9 graci\u00E9"
                                + (relBy != null ? " par **" + relBy + "**" : "") + ".";
                }
                break;
            }
            case SCHEDULED_TASK_FIRED:
                title = "\u23F0 T\u00E2che planifi\u00E9e";
                description = data != null && data.containsKey("message")
                    ? data.get("message")
                    : "Une action planifi\u00E9e s'est d\u00E9clench\u00E9e.";
                break;
            default:
                title = eventType.label;
                description = data != null && data.containsKey("message")
                    ? data.get("message")
                    : eventType.description;
        }

        embed.addProperty("title", title);
        embed.addProperty("description", description);

        // Add fields from data if present
        JsonArray fields = new JsonArray();
        if (data != null) {
            if (data.containsKey("playerCount")) {
                JsonObject f = new JsonObject();
                f.addProperty("name", "Joueurs en ligne");
                f.addProperty("value", data.get("playerCount"));
                f.addProperty("inline", true);
                fields.add(f);
            }
            if (data.containsKey("tps")) {
                JsonObject f = new JsonObject();
                f.addProperty("name", "TPS");
                f.addProperty("value", data.get("tps"));
                f.addProperty("inline", true);
                fields.add(f);
            }
        }
        if (fields.size() > 0) {
            embed.add("fields", fields);
        }

        return embed;
    }

    // ── HTTP POST to Discord ────────────────────────────────────────────────

    /**
     * POST an embed to a Discord webhook URL.
     * Returns null on success, or error message on failure.
     */
    private String postToWebhook(WebhookConfig webhook, JsonObject embed) {
        try {
            // Build Discord webhook payload
            JsonObject payload = new JsonObject();
            if (webhook.avatarUrl != null && !webhook.avatarUrl.isEmpty()) {
                payload.addProperty("avatar_url", webhook.avatarUrl);
            }
            payload.addProperty("username", "CheeseDash");
            JsonArray embeds = new JsonArray();
            embeds.add(embed);
            payload.add("embeds", embeds);

            byte[] bodyBytes = payload.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL(webhook.url);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "CheeseDash/1.0");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int code = conn.getResponseCode();
            if (code == 204 || code == 200) {
                return null; // success
            } else if (code == 429) {
                LOGGER.warn("[CheeseDash] Discord rate limited for webhook '{}'", webhook.name);
                return "Rate limited (429)";
            } else {
                // Read error body
                String errorBody = "";
                try (InputStream es = conn.getErrorStream()) {
                    if (es != null) errorBody = new String(es.readAllBytes(), StandardCharsets.UTF_8);
                }
                LOGGER.warn("[CheeseDash] Discord webhook '{}' returned {}: {}",
                    webhook.name, code, errorBody);
                return "HTTP " + code + ": " + errorBody;
            }
        } catch (Exception e) {
            LOGGER.warn("[CheeseDash] Discord webhook '{}' failed: {}", webhook.name, e.getMessage());
            return e.getMessage();
        }
    }

    // ── Broadcast → Discord event mapping ───────────────────────────────────

    private DiscordEventType mapBroadcastToEvent(String channel, String type) {
        switch (channel) {
            case "players":
                if ("player_join".equals(type)) return DiscordEventType.PLAYER_JOIN;
                if ("player_leave".equals(type)) return DiscordEventType.PLAYER_LEAVE;
                break;
            case "events":
                if (type.contains("ANNOUNCED")) return DiscordEventType.EVENT_ANNOUNCE;
                if (type.contains("ACTIVE") || type.contains("COUNTDOWN")) return DiscordEventType.EVENT_START;
                if (type.contains("CLEANUP") || type.contains("IDLE")) return DiscordEventType.EVENT_END;
                break;
            case "market":
                if ("market_purchase".equals(type)) return DiscordEventType.MARKET_PURCHASE;
                break;
            case "eggs":
                if ("egg_discovery".equals(type)) return DiscordEventType.EGG_DISCOVERY;
                break;
            case "dimensions":
                if ("dim_created".equals(type)) return DiscordEventType.DIMENSION_CREATED;
                if ("dim_deleted".equals(type)) return DiscordEventType.DIMENSION_DELETED;
                break;
            case "shift":
                if ("transform".equals(type)) return DiscordEventType.SHIFT_TRANSFORM;
                break;
            case "schedule":
                if ("schedule_created".equals(type)) return DiscordEventType.RESTART_SCHEDULED;
                if ("schedule_cancelled".equals(type)) return DiscordEventType.RESTART_CANCELLED;
                if ("schedule_fired".equals(type)) return DiscordEventType.SCHEDULED_TASK_FIRED;
                break;
            case "prison":
                if ("prison_added".equals(type)) return DiscordEventType.PRISON_IMPRISONED;
                if ("prison_released".equals(type)) return DiscordEventType.PRISON_RELEASED;
                break;
        }
        return null;
    }

    private Map<String, String> extractBroadcastData(JsonObject msg, DiscordEventType eventType) {
        Map<String, String> data = new HashMap<>();
        // Extract common fields
        if (msg.has("name")) data.put("name", msg.get("name").getAsString());
        if (msg.has("playerName")) data.put("playerName", msg.get("playerName").getAsString());
        if (msg.has("eventName")) data.put("eventName", msg.get("eventName").getAsString());
        if (msg.has("itemName")) data.put("itemName", msg.get("itemName").getAsString());
        if (msg.has("eggName")) data.put("eggName", msg.get("eggName").getAsString());
        if (msg.has("dimName")) data.put("dimName", msg.get("dimName").getAsString());
        if (msg.has("species")) data.put("species", msg.get("species").getAsString());
        if (msg.has("message")) data.put("message", msg.get("message").getAsString());
        if (msg.has("scoreboardId")) data.put("scoreboardId", msg.get("scoreboardId").getAsString());
        if (msg.has("scoreboardName")) data.put("scoreboardName", msg.get("scoreboardName").getAsString());
        if (msg.has("reason")) data.put("reason", msg.get("reason").getAsString());
        if (msg.has("duration")) data.put("duration", msg.get("duration").getAsString());
        if (msg.has("byName")) data.put("byName", msg.get("byName").getAsString());
        if (msg.has("releaseReason")) data.put("releaseReason", msg.get("releaseReason").getAsString());
        if (msg.has("releasedByName")) data.put("releasedByName", msg.get("releasedByName").getAsString());

        // Extract from nested action object (schedule events)
        if (msg.has("action") && msg.get("action").isJsonObject()) {
            JsonObject action = msg.getAsJsonObject("action");
            if (action.has("message")) data.put("message", action.get("message").getAsString());
            if (action.has("remainingSeconds")) {
                data.put("delay", ServerScheduler.formatTime(action.get("remainingSeconds").getAsLong()));
            }
        }

        return data;
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    private void loadFromDisk() {
        if (!Files.exists(configPath)) return;
        try {
            String raw = Files.readString(configPath);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            if (root.has("webhooks")) {
                root.getAsJsonArray("webhooks").forEach(el -> {
                    try {
                        webhooks.add(WebhookConfig.fromJson(el.getAsJsonObject()));
                    } catch (Exception e) {
                        LOGGER.warn("[CheeseDash] Skipping invalid webhook config: {}", e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            LOGGER.warn("[CheeseDash] Failed to load webhooks: {}", e.getMessage());
        }
    }

    private void saveToDisk() {
        Thread writer = new Thread(() -> {
            try {
                JsonObject root = new JsonObject();
                JsonArray arr = new JsonArray();
                for (WebhookConfig w : webhooks) {
                    arr.add(w.toJson(false));
                }
                root.add("webhooks", arr);
                Files.createDirectories(configPath.getParent());
                Files.writeString(configPath, GSON.toJson(root));
            } catch (IOException e) {
                LOGGER.warn("[CheeseDash] Failed to save webhooks: {}", e.getMessage());
            }
        }, "CheeseDash-WebhookSave");
        writer.setDaemon(true);
        writer.start();
    }
}
