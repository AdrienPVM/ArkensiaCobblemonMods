package com.cheesestore;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Lightweight Stripe API client using JDK HttpClient (no SDK dependency).
 * Supports Checkout Sessions creation and webhook signature verification.
 */
public class StripeClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseStore");
    private static final String STRIPE_API = "https://api.stripe.com/v1";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final String secretKey;

    public StripeClient(String secretKey) {
        this.secretKey = secretKey;
    }

    // ── Checkout Session ─────────────────────────────────────────────────────

    /**
     * Create a Stripe Hosted Checkout Session.
     * Returns a JsonObject with {checkoutUrl} on success, or {error} on failure.
     * Uses hosted mode (Stripe-hosted page) so HTTPS is not required on our server.
     */
    public JsonObject createCheckoutSession(StoreProduct product, String playerName,
                                             String playerUUID, String returnUrl) {
        try {
            String body = buildFormBody(
                "mode", "payment",
                "success_url", returnUrl + "?session_id={CHECKOUT_SESSION_ID}",
                "cancel_url", returnUrl,
                "line_items[0][price_data][currency]", product.getCurrency(),
                "line_items[0][price_data][unit_amount]", String.valueOf(product.getPriceCents()),
                "line_items[0][price_data][product_data][name]", product.getName(),
                "line_items[0][quantity]", "1",
                "metadata[player_uuid]", playerUUID,
                "metadata[player_name]", playerName,
                "metadata[product_id]", product.getId()
            );

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(STRIPE_API + "/checkout/sessions"))
                .header("Authorization", "Basic " + base64Auth())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();

            if (response.statusCode() >= 400) {
                String errMsg = result.has("error")
                    ? result.getAsJsonObject("error").get("message").getAsString()
                    : "Stripe error " + response.statusCode();
                LOGGER.error("[CheeseStore] Stripe error: {}", errMsg);
                JsonObject err = new JsonObject();
                err.addProperty("error", errMsg);
                return err;
            }

            JsonObject ok = new JsonObject();
            ok.addProperty("checkoutUrl", result.get("url").getAsString());
            return ok;

        } catch (Exception e) {
            LOGGER.error("[CheeseStore] Stripe request failed: {}", e.getMessage(), e);
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getMessage());
            return err;
        }
    }

    /**
     * Retrieve a Checkout Session by ID to verify payment status.
     * Returns the full session object from Stripe.
     */
    public JsonObject retrieveCheckoutSession(String sessionId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(STRIPE_API + "/checkout/sessions/" +
                    URLEncoder.encode(sessionId, StandardCharsets.UTF_8)))
                .header("Authorization", "Basic " + base64Auth())
                .GET()
                .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (Exception e) {
            LOGGER.error("[CheeseStore] Failed to retrieve session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    // ── Webhook Verification ─────────────────────────────────────────────────

    /**
     * Verify Stripe webhook signature (HMAC-SHA256).
     * @param payload  raw request body
     * @param sigHeader  Stripe-Signature header value
     * @param webhookSecret  endpoint secret from Stripe dashboard
     */
    public static boolean verifyWebhookSignature(String payload, String sigHeader, String webhookSecret) {
        if (sigHeader == null || webhookSecret == null || webhookSecret.isEmpty()) return false;

        String timestamp = null, signature = null;
        for (String part : sigHeader.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            if ("t".equals(kv[0])) timestamp = kv[1];
            if ("v1".equals(kv[0])) signature = kv[1];
        }
        if (timestamp == null || signature == null) return false;

        // Check timestamp tolerance (5 minutes)
        try {
            long ts = Long.parseLong(timestamp);
            long now = System.currentTimeMillis() / 1000;
            if (Math.abs(now - ts) > 300) {
                LOGGER.warn("[CheeseStore] Webhook timestamp too old: {} vs {}", ts, now);
                return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }

        String signedPayload = timestamp + "." + payload;
        String expected = hmacSha256(webhookSecret, signedPayload);
        return constantTimeEquals(expected, signature);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String base64Auth() {
        return Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
    }

    private static String buildFormBody(String... pairs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(pairs[i], StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String hmacSha256(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (ba.length != bb.length) return false;
        int diff = 0;
        for (int i = 0; i < ba.length; i++) diff |= (ba[i] ^ bb[i]);
        return diff == 0;
    }
}
