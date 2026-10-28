package com.cheesepatrol.dashboard;

import com.cheesedash.DashboardModule;
import com.cheesedash.WebDashboard;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;

public class CheesePatrolDashboardModule implements DashboardModule {

    private MinecraftServer server;

    @Override public String getPrefix()        { return "patrol"; }
    @Override public String getDisplayName()    { return "CheesePatrol"; }
    @Override public String getNavIcon()        { return "\uD83D\uDEB6"; } // 🚶
    @Override public String getNavDescription() { return "Patrouilles NPC"; }

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
            WebDashboard.respond(exchange, 503, "{\"error\":\"CheesePatrol not ready\"}");
            return;
        }
        CheesePatrolApi.handle(exchange, server, subRoute);
    }
}
