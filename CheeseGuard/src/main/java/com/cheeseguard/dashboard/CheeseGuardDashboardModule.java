package com.cheeseguard.dashboard;

import com.cheesedash.DashboardModule;
import com.cheesedash.WebDashboard;
import com.cheeseguard.CheeseGuardMod;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;

/**
 * CheeseGuard's integration module for CheeseDash.
 * Handles all /api/v1/cheeseguard/* routes.
 */
public class CheeseGuardDashboardModule implements DashboardModule {

    private MinecraftServer server;
    private CheeseGuardMod mod;

    @Override public String getPrefix()          { return "cheeseguard"; }
    @Override public String getDisplayName()      { return "CheeseGuard"; }
    @Override public String getNavIcon()          { return "🛡️"; }
    @Override public String getNavDescription()   { return "Zones, dimensions et événements"; }

    @Override
    public void onServerStart(MinecraftServer server) {
        this.server = server;
        this.mod = CheeseGuardMod.INSTANCE;
    }

    @Override
    public void onServerStop() {
        this.server = null;
    }

    @Override
    public void handleRequest(HttpExchange exchange, String subRoute) throws IOException {
        if (mod == null || server == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"CheeseGuard not ready\"}");
            return;
        }
        CheeseGuardApiRouter.handle(exchange, subRoute, mod, server);
    }
}
