package com.cheeseraids.dashboard;

import com.cheesedash.DashboardModule;
import com.cheesedash.DashboardRegistry;
import com.cheesedash.WebDashboard;
import com.cheeseraids.CheeseRaidsMod;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;

/** CheeseDash integration: /api/v1/raids/* + live "raids" WebSocket channel. */
public class CheeseRaidsDashboardModule implements DashboardModule {

    private MinecraftServer server;

    @Override public String getPrefix()        { return "raids"; }
    @Override public String getDisplayName()    { return "CheeseRaids"; }
    @Override public String getNavIcon()        { return "👻"; } // 👻
    @Override public String getNavDescription() { return "Raids serveur (invasions)"; }

    @Override
    public void onServerStart(MinecraftServer server) {
        this.server = server;
        // Broadcast instance updates over WebSocket on phase changes / progress.
        CheeseRaidsMod.DASHBOARD_BROADCAST = inst -> {
            try {
                JsonObject msg = new JsonObject();
                msg.addProperty("type", "raid_update");
                msg.addProperty("raidId", inst.raidId);
                msg.addProperty("phase", inst.phase.name());
                msg.addProperty("participants", inst.participantCount());
                DashboardRegistry.broadcast("raids", msg.toString());
            } catch (Exception ignored) {}
        };
    }

    @Override
    public void onServerStop() {
        this.server = null;
        CheeseRaidsMod.DASHBOARD_BROADCAST = null;
    }

    @Override
    public void handleRequest(HttpExchange exchange, String subRoute) throws IOException {
        if (server == null || CheeseRaidsMod.INSTANCE == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"CheeseRaids not ready\"}");
            return;
        }
        RaidsApi.handle(exchange, server, subRoute);
    }
}
