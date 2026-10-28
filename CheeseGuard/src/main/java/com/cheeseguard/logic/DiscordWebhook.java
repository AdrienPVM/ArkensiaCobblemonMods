package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Sends Discord webhook messages for server events.
 */
public class DiscordWebhook {

    private static String webhookUrl = "";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public static void setUrl(String url) { webhookUrl = url != null ? url : ""; }
    public static String getUrl() { return webhookUrl; }
    public static boolean isConfigured() { return !webhookUrl.isBlank(); }

    /**
     * Send a simple embed message to Discord.
     */
    public static void send(String title, String description, int color) {
        if (!isConfigured()) return;
        new Thread(() -> {
            try {
                JsonObject embed = new JsonObject();
                embed.addProperty("title", title);
                embed.addProperty("description", description);
                embed.addProperty("color", color);
                embed.addProperty("timestamp", java.time.Instant.now().toString());

                JsonObject footer = new JsonObject();
                footer.addProperty("text", "CheeseGuard");
                embed.add("footer", footer);

                com.google.gson.JsonArray embeds = new com.google.gson.JsonArray();
                embeds.add(embed);

                JsonObject body = new JsonObject();
                body.addProperty("username", "CheeseGuard");
                body.add("embeds", embeds);

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(webhookUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .timeout(Duration.ofSeconds(10))
                        .build();

                HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                CheeseGuardMod.LOGGER.warn("[CG] Discord webhook failed: {}", e.getMessage());
            }
        }, "cg-webhook").start();
    }

    // ── Preset messages ─────────────────────────────────────────────────────

    public static void serverStarting() {
        send("🟢 Serveur démarré", "Le serveur est maintenant en ligne !", 0x2ECC71);
    }

    public static void serverStopping() {
        send("🔴 Serveur arrêté", "Le serveur s'est arrêté.", 0xE74C3C);
    }

    public static void serverRestart(int minutes) {
        send("🔄 Redémarrage planifié", "Le serveur va redémarrer dans **" + minutes + " minute(s)**.", 0x3498DB);
    }

    public static void serverStop(int minutes) {
        send("⏹ Arrêt planifié", "Le serveur va s'arrêter dans **" + minutes + " minute(s)**.", 0xE67E22);
    }

    public static void serverMaintenance(int minutes) {
        send("🔧 Maintenance planifiée", "Le serveur entre en maintenance dans **" + minutes + " minute(s)**.", 0x9B59B6);
    }

    public static void serverCrash(String reason) {
        send("💥 Crash serveur", "Le serveur a crashé !\n" + (reason != null ? reason : "Raison inconnue"), 0xFF0000);
    }
}
