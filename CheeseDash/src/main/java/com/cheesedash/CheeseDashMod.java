package com.cheesedash;

import com.cheesedash.network.QuickMenuPayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * CheeseDash — Admin dashboard web pour les mods Arkensia.
 *
 * Entry point: loads all DashboardModule implementations via Fabric's
 * custom entrypoint system ("cheesedash"), starts the HTTP server + WebSocket,
 * and dispatches API requests to registered modules.
 */
public class CheeseDashMod implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseDash");
    public static CheeseDashMod INSTANCE;

    private DashboardConfig config;
    private WebDashboard webDashboard;
    private WebSocketServer webSocketServer;
    private AuditLogger auditLogger;
    private ServerScheduler scheduler;
    private DiscordWebhookManager discordWebhookManager;

    // TPS tracker — updated every second (20 ticks)
    private long lastTpsCheckTime = System.nanoTime();
    private int ticksSinceLastCheck = 0;
    private volatile double currentTps = 20.0;
    private long startTime = System.currentTimeMillis();

    @Override
    public void onInitialize() {
        INSTANCE = this;
        LOGGER.info("[CheeseDash] Initializing...");

        // ── Load all modules via Fabric entrypoints ─────────────────────
        FabricLoader.getInstance()
            .getEntrypoints("cheesedash", DashboardModule.class)
            .forEach(module -> {
                DashboardRegistry.register(module);
                LOGGER.info("[CheeseDash] Module loaded: {} ({})",
                    module.getDisplayName(), module.getPrefix());
            });

        int moduleCount = DashboardRegistry.getModules().size();
        LOGGER.info("[CheeseDash] {} module(s) discovered.", moduleCount);

        // ── Quick Menu network packet (C2S) ──────────────────────────
        PayloadTypeRegistry.playC2S().register(QuickMenuPayload.ID, QuickMenuPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(QuickMenuPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            String command = payload.command();
            if (!player.hasPermissionLevel(2)) {
                LOGGER.warn("[CheeseDash] QuickMenu: {} tried to execute '{}' without permission", player.getName().getString(), command);
                return;
            }
            context.player().server.execute(() -> {
                LOGGER.info("[CheeseDash] QuickMenu: {} executing '{}'", player.getName().getString(), command);
                player.server.getCommandManager().executeWithPrefix(player.getCommandSource(), command);
            });
        });

        // ── Config ──────────────────────────────────────────────────────
        Path configDir = FabricLoader.getInstance().getConfigDir();
        config = new DashboardConfig(configDir);
        config.load();

        // ── Audit Logger ─────────────────────────────────────────────────
        auditLogger = new AuditLogger(configDir);

        // ── Server Scheduler ────────────────────────────────────────────
        scheduler = new ServerScheduler(configDir);

        // ── Discord Webhooks ────────────────────────────────────────────
        discordWebhookManager = new DiscordWebhookManager(configDir);

        // ── HTTP Dashboard + WebSocket ──────────────────────────────────
        webDashboard = new WebDashboard(config, auditLogger);
        int wsPort = config.getPort() + 1; // HTTP port + 1
        webSocketServer = new WebSocketServer(config, wsPort);
        DashboardRegistry.setWsServer(webSocketServer);
        DashboardRegistry.setDiscordManager(discordWebhookManager);

        // ── TPS tracker + WS broadcast ──────────────────────────────────
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ticksSinceLastCheck++;
            if (ticksSinceLastCheck >= 20) {
                long now = System.nanoTime();
                double elapsed = (now - lastTpsCheckTime) / 1_000_000_000.0;
                currentTps = Math.min(20.0, ticksSinceLastCheck / elapsed);
                lastTpsCheckTime = now;
                ticksSinceLastCheck = 0;

                // Broadcast TPS to WS clients
                if (webSocketServer.getConnectionCount() > 0) {
                    JsonObject msg = new JsonObject();
                    msg.addProperty("type", "tps");
                    msg.addProperty("value", Math.round(currentTps * 10.0) / 10.0);
                    webSocketServer.broadcast("tps", msg);

                    // Also broadcast player count
                    JsonObject plMsg = new JsonObject();
                    plMsg.addProperty("type", "player_count");
                    plMsg.addProperty("count", server.getCurrentPlayerCount());
                    plMsg.addProperty("max", server.getMaxPlayerCount());
                    webSocketServer.broadcast("players", plMsg);
                }
            }
        });

        // ── Player join/leave events → WS broadcast ─────────────────────
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            String playerName = player.getName().getString();
            String uuid = player.getUuidAsString();
            String world = player.getWorld().getRegistryKey().getValue().toString();

            JsonObject msg = new JsonObject();
            msg.addProperty("type", "player_join");
            msg.addProperty("name", playerName);
            msg.addProperty("uuid", uuid);
            msg.addProperty("world", world);
            webSocketServer.broadcast("players", msg);

            // Discord notification
            if (discordWebhookManager != null) {
                java.util.Map<String, String> data = new java.util.HashMap<>();
                data.put("name", playerName);
                data.put("playerName", playerName);
                data.put("uuid", uuid);
                data.put("world", world);
                discordWebhookManager.sendEvent(DiscordWebhookManager.DiscordEventType.PLAYER_JOIN, data);
            }

            // Also send full player list
            broadcastPlayerList(server);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            String playerName = player.getName().getString();
            String uuid = player.getUuidAsString();

            JsonObject msg = new JsonObject();
            msg.addProperty("type", "player_leave");
            msg.addProperty("name", playerName);
            msg.addProperty("uuid", uuid);
            webSocketServer.broadcast("players", msg);

            // Discord notification
            if (discordWebhookManager != null) {
                java.util.Map<String, String> data = new java.util.HashMap<>();
                data.put("name", playerName);
                data.put("playerName", playerName);
                data.put("uuid", uuid);
                discordWebhookManager.sendEvent(DiscordWebhookManager.DiscordEventType.PLAYER_LEAVE, data);
            }

            // Delay player list broadcast slightly (player still in list during disconnect)
            server.execute(() -> broadcastPlayerList(server));
        });

        // ── Server lifecycle hooks ──────────────────────────────────────
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("[CheeseDash] Server started — notifying {} module(s)...", moduleCount);
            webDashboard.setMinecraftServer(server);

            // Notify all modules
            DashboardRegistry.getModules().values()
                .forEach(m -> m.onServerStart(server));

            // Reset TPS tracker
            lastTpsCheckTime = System.nanoTime();
            ticksSinceLastCheck = 0;
            currentTps = 20.0;

            // Initialize scheduler with server reference
            scheduler.setServer(server);

            // Start HTTP server
            webDashboard.start();

            // Start WebSocket server
            webSocketServer.start();

            // Discord: server started notification
            if (discordWebhookManager != null) {
                java.util.Map<String, String> data = new java.util.HashMap<>();
                data.put("playerCount", String.valueOf(server.getCurrentPlayerCount()));
                data.put("tps", "20.0");
                discordWebhookManager.sendEvent(
                    DiscordWebhookManager.DiscordEventType.SERVER_START, data);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("[CheeseDash] Server stopping — shutting down dashboard...");

            // Discord: server stop notification (before shutdown)
            if (discordWebhookManager != null) {
                discordWebhookManager.sendEvent(
                    DiscordWebhookManager.DiscordEventType.SERVER_STOP, null);
            }

            DashboardRegistry.getModules().values()
                .forEach(m -> m.onServerStop());
            webDashboard.stop();
            webSocketServer.shutdown();
            scheduler.shutdown();
            if (discordWebhookManager != null) discordWebhookManager.shutdown();
        });

        LOGGER.info("[CheeseDash] Initialization complete.");
    }

    // ── Player list broadcast helper ─────────────────────────────────────────

    private void broadcastPlayerList(MinecraftServer server) {
        if (webSocketServer.getConnectionCount() == 0) return;
        JsonArray players = new JsonArray();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            JsonObject po = new JsonObject();
            po.addProperty("name", p.getName().getString());
            po.addProperty("uuid", p.getUuidAsString());
            po.addProperty("world", p.getWorld().getRegistryKey().getValue().toString());
            players.add(po);
        }
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "player_list");
        msg.add("players", players);
        webSocketServer.broadcast("players", msg);
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public double getTps() { return currentTps; }
    public long getStartTime() { return startTime; }
    public DashboardConfig getConfig() { return config; }
    public WebDashboard getWebDashboard() { return webDashboard; }
    public WebSocketServer getWebSocketServer() { return webSocketServer; }
    public AuditLogger getAuditLogger() { return auditLogger; }
    public ServerScheduler getScheduler() { return scheduler; }
    public DiscordWebhookManager getDiscordWebhookManager() { return discordWebhookManager; }
}
