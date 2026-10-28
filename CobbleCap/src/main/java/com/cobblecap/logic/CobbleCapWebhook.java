package com.cobblecap.logic;

import com.cobblecap.data.LegendEntry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.StringJoiner;

/**
 * Sends Discord webhook notifications when a player defeats a champion or
 * becomes the Pokémon League Master (all challenges complete).
 */
public class CobbleCapWebhook {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");

    private static String webhookUrl = "";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public static void setUrl(String url) { webhookUrl = url != null ? url : ""; }
    public static String getUrl() { return webhookUrl; }
    public static boolean isConfigured() { return !webhookUrl.isBlank(); }

    /**
     * Send a Discord embed announcing a champion victory.
     * Routes through both CobbleCap's own webhook URL (if configured) AND the
     * CheeseDash DiscordWebhookManager (for subscribers of CHAMPION_VICTORY).
     * Runs on a dedicated thread — never blocks the server thread.
     */
    public static void sendChampionVictory(String playerName, String champName, int newCap) {
        JsonObject embed = buildChampionVictoryEmbed(playerName, champName, newCap);
        routeToCheeseDash("CHAMPION_VICTORY", embed);
        if (!isConfigured()) return;

        new Thread(() -> {
            try {
                JsonArray embeds = new JsonArray();
                embeds.add(embed);

                JsonObject body = new JsonObject();
                body.addProperty("username", "CobbleCap");
                body.add("embeds", embeds);

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(webhookUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .timeout(Duration.ofSeconds(10))
                        .build();

                HTTP.send(req, HttpResponse.BodyHandlers.ofString());

            } catch (Exception e) {
                LOGGER.warn("[CobbleCap] Discord webhook failed: {}", e.getMessage());
            }
        }, "cobblecap-webhook").start();
    }

    private static JsonObject buildChampionVictoryEmbed(String playerName, String champName, int newCap) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "Champion vaincu !");
        embed.addProperty("description",
                "**" + playerName + "** a vaincu **" + champName + "** — nouveau cap : **" + newCap + "**");
        embed.addProperty("color", 0xFFD700);
        embed.addProperty("timestamp", java.time.Instant.now().toString());
        JsonObject footer = new JsonObject();
        footer.addProperty("text", "CobbleCap");
        embed.add("footer", footer);
        JsonObject author = new JsonObject();
        author.addProperty("name", playerName);
        author.addProperty("icon_url", "https://mc-heads.net/avatar/" + playerName + "/64");
        embed.add("author", author);
        return embed;
    }

    /**
     * Route a pre-built embed to CheeseDash's DiscordWebhookManager if the
     * mod is loaded. Reflection-based to avoid hard-linking CheeseDash classes
     * (CobbleCap declares CheeseDash as a hard dependency, but going through
     * reflection keeps the failure mode silent if loading order ever changes).
     */
    private static void routeToCheeseDash(String eventName, JsonObject embed) {
        try {
            Class<?> modCls = Class.forName("com.cheesedash.CheeseDashMod");
            Object instance = modCls.getField("INSTANCE").get(null);
            if (instance == null) return;
            Object manager = modCls.getMethod("getDiscordWebhookManager").invoke(instance);
            if (manager == null) return;
            Class<?> mgrCls = manager.getClass();
            Class<?> eventEnum = Class.forName(
                    "com.cheesedash.DiscordWebhookManager$DiscordEventType");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object eventValue = Enum.valueOf((Class<Enum>) eventEnum, eventName);
            mgrCls.getMethod("sendRawEmbed", eventEnum, JsonObject.class)
                    .invoke(manager, eventValue, embed);
        } catch (NoSuchMethodException nsme) {
            LOGGER.debug("[CobbleCap] CheeseDash sendRawEmbed not available (pre-update build)");
        } catch (Exception e) {
            LOGGER.debug("[CobbleCap] CheeseDash routing skipped: {}", e.getMessage());
        }
    }

    /**
     * Send a concise embed when a player becomes the Pokémon League Master.
     * Shows player avatar + chrono + team species list (no sprites, no
     * levels, no balls). User explicitly asked for a short, dense format.
     */
    public static void sendLeagueMaster(LegendEntry entry) {
        // Build the rich embed FIRST so we can route it to both:
        //   (a) the CobbleCap-specific URL (if configured) — keeps @everyone ping
        //   (b) all CheeseDash webhooks subscribed to LEAGUE_MASTER
        // Routing to CheeseDash happens regardless of CobbleCap URL config so
        // that admins who only use the CheeseDash webhook UI also get notified.

        new Thread(() -> {
            try {
                String chronoStr = entry.bestTimeMs() > 0
                        ? com.cobblecap.logic.ChronoManager.formatDuration(entry.bestTimeMs())
                        : null;

                String title = entry.firstEver()
                        ? "🏆 PREMIER MAÎTRE DE LA LIGUE — " + entry.playerName()
                        : "🏆 Nouveau Maître de la Ligue — " + entry.playerName();

                StringBuilder desc = new StringBuilder();
                desc.append("**").append(entry.playerName()).append("** ");
                if (entry.firstEver()) {
                    desc.append("entre dans l'histoire en devenant le **tout premier Maître de la Ligue Pokémon** d'Arkensia ! Tous les champions du serveur ont été vaincus.");
                } else {
                    desc.append("vient de battre tous les champions et devient un nouveau Maître de la Ligue Pokémon !");
                }
                if (chronoStr != null) {
                    desc.append("\n\n⏱ **Temps : ").append(chronoStr).append("**");
                }
                String description = desc.toString();

                JsonObject embed = new JsonObject();
                embed.addProperty("title", title);
                embed.addProperty("description", description);
                embed.addProperty("color", entry.firstEver() ? 0xE91E63 : 0xFFD700);
                embed.addProperty("timestamp", java.time.Instant.ofEpochMilli(entry.completedAt()).toString());

                JsonObject author = new JsonObject();
                author.addProperty("name", entry.playerName());
                author.addProperty("icon_url", "https://mc-heads.net/avatar/" + entry.playerName() + "/64");
                embed.add("author", author);

                JsonObject thumb = new JsonObject();
                thumb.addProperty("url", "https://mc-heads.net/head/" + entry.playerName() + "/128");
                embed.add("thumbnail", thumb);

                // Concise team list — species names only, comma-separated,
                // shiny marker preserved. No sprites, no levels, no balls
                // (user explicitly removed those 2026-05-17 for density).
                if (entry.team() != null && !entry.team().isEmpty()) {
                    StringJoiner team = new StringJoiner(" · ");
                    for (var m : entry.team()) {
                        String name = (m.nickname() == null || m.nickname().isBlank())
                                ? m.displayName()
                                : m.nickname();
                        if (name == null || name.isBlank()) name = capitalize(m.species());
                        team.add((m.shiny() ? "✨ " : "") + name);
                    }
                    JsonArray fields = new JsonArray();
                    JsonObject field = new JsonObject();
                    field.addProperty("name", "Équipe");
                    field.addProperty("value", team.toString());
                    field.addProperty("inline", false);
                    fields.add(field);
                    embed.add("fields", fields);
                }

                JsonObject footer = new JsonObject();
                footer.addProperty("text", "CobbleCap · Maître de la Ligue Pokémon");
                embed.add("footer", footer);

                // (b) Route to CheeseDash subscribers — always, even if CobbleCap URL is empty
                routeToCheeseDash("LEAGUE_MASTER", embed);

                // (a) Direct CobbleCap webhook URL (only if configured)
                if (!isConfigured()) {
                    LOGGER.info("[CobbleCap] League Master: no direct URL set — routed via CheeseDash only");
                    return;
                }

                JsonArray embeds = new JsonArray();
                embeds.add(embed);

                JsonObject body = new JsonObject();
                body.addProperty("username", "CobbleCap");
                body.addProperty("content",
                        entry.firstEver()
                                ? "@everyone Un nouveau chapitre s'écrit ! 🥇"
                                : "Félicitations à **" + entry.playerName() + "** ! 🎉");
                JsonObject allowedMentions = new JsonObject();
                JsonArray parse = new JsonArray();
                if (entry.firstEver()) parse.add("everyone");
                allowedMentions.add("parse", parse);
                body.add("allowed_mentions", allowedMentions);
                body.add("embeds", embeds);

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(webhookUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .timeout(Duration.ofSeconds(10))
                        .build();

                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 300) {
                    LOGGER.warn("[CobbleCap] League Master webhook returned {} : {}",
                            resp.statusCode(), resp.body());
                }
            } catch (Exception e) {
                LOGGER.warn("[CobbleCap] League Master webhook failed: {}", e.getMessage());
            }
        }, "cobblecap-webhook-league").start();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
