package com.roicheese.eggandcheese.dashboard;

import com.cheesedash.DashboardModule;
import com.cheesedash.WebDashboard;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;

/**
 * EggAndCheese's integration module for CheeseDash.
 * Handles all /api/v1/eggs/* routes.
 */
public class EggAndCheeseDashboardModule implements DashboardModule {

    private MinecraftServer server;

    @Override public String getPrefix()          { return "eggs"; }
    @Override public String getDisplayName()      { return "EggAndCheese"; }
    @Override public String getNavIcon()          { return "🥚"; }
    @Override public String getNavDescription()   { return "Collectibles et œufs"; }

    @Override
    public void onServerStart(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public void onServerStop() {
        this.server = null;
    }

    @Override
    public void handleRequest(HttpExchange exchange, String subRoute) throws IOException {
        if (server == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"Server not available\"}");
            return;
        }
        EggsApi.handle(exchange, server, subRoute);
    }
}
