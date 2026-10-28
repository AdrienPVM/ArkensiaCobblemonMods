package com.cheesemarket.dashboard;

import com.cheesedash.DashboardModule;
import com.cheesedash.WebDashboard;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;

/**
 * CheeseMarket's integration module for CheeseDash.
 * Handles all /api/v1/market/* routes.
 */
public class CheeseMarketDashboardModule implements DashboardModule {

    private MinecraftServer server;

    @Override public String getPrefix()          { return "market"; }
    @Override public String getDisplayName()      { return "CheeseMarket"; }
    @Override public String getNavIcon()          { return "🛒"; }
    @Override public String getNavDescription()   { return "Gestion des boutiques"; }

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
        MarketApi.handle(exchange, server, subRoute);
    }
}
