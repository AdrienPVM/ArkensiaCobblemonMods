package com.sebastian.cobblemondungeons.dashboard;

import com.cheesedash.DashboardModule;
import com.cheesedash.WebDashboard;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;

public class CobblemonDungeonsDashboardModule implements DashboardModule {

    private MinecraftServer server;

    @Override public String getPrefix()          { return "dungeons"; }
    @Override public String getDisplayName()     { return "CobblemonDungeons"; }
    @Override public String getNavIcon()         { return "\uD83C\uDFF0"; }
    @Override public String getNavDescription()  { return "Donjons Cobblemon"; }

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
            WebDashboard.respond(exchange, 503, "{\"error\":\"CobblemonDungeons not ready\"}");
            return;
        }
        DungeonsApi.handle(exchange, server, subRoute);
    }
}
