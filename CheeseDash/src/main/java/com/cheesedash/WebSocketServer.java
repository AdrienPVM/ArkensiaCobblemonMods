package com.cheesedash;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket server for real-time dashboard updates.
 *
 * Protocol:
 *  1. Client connects to ws://host:port/
 *  2. Client MUST send auth message first: {"type":"auth","token":"..."}
 *  3. Server responds {"type":"auth_ok"} or closes with code 4001
 *  4. Client subscribes: {"type":"subscribe","channels":["tps","players","logs",...]}
 *  5. Server pushes events on subscribed channels
 *
 * Channels: tps, players, logs, events, dimensions
 */
public class WebSocketServer extends org.java_websocket.server.WebSocketServer {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseDash");

    private final DashboardConfig config;

    // Authenticated connections with their subscribed channels
    private final Map<WebSocket, Set<String>> authedConnections = new ConcurrentHashMap<>();

    // Connections awaiting auth (must auth within 5 seconds)
    private final Map<WebSocket, Long> pendingAuth = new ConcurrentHashMap<>();

    public WebSocketServer(DashboardConfig config, int port) {
        super(new InetSocketAddress(port));
        this.config = config;
        this.setReuseAddr(true);
        this.setDaemon(true);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onStart() {
        LOGGER.info("[CheeseDash] WebSocket started on port {}", getPort());
        // Periodic cleanup of stale pending connections
        Thread cleanup = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(5000);
                    long now = System.currentTimeMillis();
                    pendingAuth.entrySet().removeIf(e -> {
                        if (now - e.getValue() > 5000) {
                            try { e.getKey().close(4001, "Auth timeout"); } catch (Exception ignored) {}
                            return true;
                        }
                        return false;
                    });
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }, "CheeseDash-WS-Cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        pendingAuth.put(conn, System.currentTimeMillis());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        authedConnections.remove(conn);
        pendingAuth.remove(conn);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            var parsed = JsonParser.parseString(message);
            if (!parsed.isJsonObject()) return;
            JsonObject msg = parsed.getAsJsonObject();
            var typeEl = msg.get("type");
            if (typeEl == null || typeEl.isJsonNull()) return;
            String type = typeEl.getAsString();

            // ── Auth handshake ────────────────────────────────────────────
            if ("auth".equals(type)) {
                String token = msg.has("token") && !msg.get("token").isJsonNull() ? msg.get("token").getAsString() : "";
                if (WebDashboard.constantTimeEquals(token, config.getToken())) {
                    pendingAuth.remove(conn);
                    authedConnections.put(conn, ConcurrentHashMap.newKeySet());
                    conn.send("{\"type\":\"auth_ok\"}");
                } else {
                    conn.close(4001, "Invalid token");
                }
                return;
            }

            // All other messages require auth
            if (!authedConnections.containsKey(conn)) {
                conn.close(4001, "Not authenticated");
                return;
            }

            // ── Subscribe ─────────────────────────────────────────────────
            if ("subscribe".equals(type) && msg.has("channels")) {
                Set<String> channels = authedConnections.get(conn);
                if (channels != null) {
                    msg.getAsJsonArray("channels").forEach(ch -> channels.add(ch.getAsString()));
                }
                return;
            }

            // ── Unsubscribe ───────────────────────────────────────────────
            if ("unsubscribe".equals(type) && msg.has("channels")) {
                Set<String> channels = authedConnections.get(conn);
                if (channels != null) {
                    msg.getAsJsonArray("channels").forEach(ch -> channels.remove(ch.getAsString()));
                }
                return;
            }

            // ── Ping / Pong ───────────────────────────────────────────────
            if ("ping".equals(type)) {
                conn.send("{\"type\":\"pong\"}");
            }

        } catch (Exception e) {
            LOGGER.warn("[CheeseDash] WS message error: {}", e.getMessage());
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn != null) {
            authedConnections.remove(conn);
            pendingAuth.remove(conn);
        }
        // Don't log common disconnection errors
        if (!(ex instanceof java.io.IOException)) {
            LOGGER.warn("[CheeseDash] WS error: {}", ex.getMessage());
        }
    }

    // ── Broadcast ─────────────────────────────────────────────────────────────

    /**
     * Broadcast a JSON message to all authenticated clients subscribed to the given channel.
     */
    public void broadcast(String channel, String jsonMessage) {
        for (var entry : authedConnections.entrySet()) {
            WebSocket conn = entry.getKey();
            Set<String> channels = entry.getValue();
            if (channels.contains(channel) && conn.isOpen()) {
                try {
                    conn.send(jsonMessage);
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Broadcast a JsonObject to a channel (convenience).
     */
    public void broadcast(String channel, JsonObject message) {
        broadcast(channel, message.toString());
    }

    /**
     * Get the number of authenticated connections.
     */
    public int getConnectionCount() {
        return authedConnections.size();
    }

    /**
     * Stop the WebSocket server gracefully.
     */
    public void shutdown() {
        try {
            this.stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
