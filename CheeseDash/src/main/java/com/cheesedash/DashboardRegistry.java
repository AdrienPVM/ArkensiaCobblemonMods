package com.cheesedash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry of all dashboard modules discovered via Fabric entrypoints.
 * Modules register here and are dispatched to by the API layer.
 */
public class DashboardRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseDash");
    private static final Map<String, DashboardModule> modules = new LinkedHashMap<>();

    public static void register(DashboardModule module) {
        String prefix = module.getPrefix();
        if (modules.containsKey(prefix)) {
            LOGGER.warn("[CheeseDash] Duplicate module prefix '{}' — skipping {}", prefix, module.getDisplayName());
            return;
        }
        modules.put(prefix, module);
        LOGGER.info("[CheeseDash] Module registered: {} (prefix: /api/v1/{})", module.getDisplayName(), prefix);
    }

    public static DashboardModule getModule(String prefix) {
        return modules.get(prefix);
    }

    public static Map<String, DashboardModule> getModules() {
        return Collections.unmodifiableMap(modules);
    }

    public static void clear() {
        modules.clear();
    }

    // ── WS Broadcast ─────────────────────────────────────────────────────────
    private static WebSocketServer wsServer;
    private static DiscordWebhookManager discordManager;

    public static void setWsServer(WebSocketServer ws) {
        wsServer = ws;
    }

    public static void setDiscordManager(DiscordWebhookManager dm) {
        discordManager = dm;
    }

    /**
     * Broadcast a JSON message to all WS clients subscribed to the given channel.
     * Also routes to Discord webhook manager for automatic event mapping.
     * Safe to call from any mod — does nothing if CheeseDash WS/Discord is not available.
     */
    public static void broadcast(String channel, String jsonMessage) {
        if (wsServer != null) wsServer.broadcast(channel, jsonMessage);
        if (discordManager != null) discordManager.onBroadcast(channel, jsonMessage);
    }
}
