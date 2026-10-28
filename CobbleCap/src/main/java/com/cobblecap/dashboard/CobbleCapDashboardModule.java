package com.cobblecap.dashboard;

import com.cheesedash.DashboardModule;
import com.cheesedash.WebDashboard;
import com.cobblecap.CobbleCapMod;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;

public class CobbleCapDashboardModule implements DashboardModule {

    private MinecraftServer server;

    @Override public String getPrefix()        { return "cobblecap"; }
    @Override public String getDisplayName()    { return "CobbleCap"; }
    @Override public String getNavIcon()        { return "\uD83C\uDFC5"; }
    @Override public String getNavDescription() { return "Level cap progression"; }

    @Override
    public void onServerStart(MinecraftServer server) { this.server = server; }

    @Override
    public void onServerStop() { this.server = null; }

    @Override
    public void handleRequest(HttpExchange exchange, String subRoute) throws IOException {
        if (server == null || CobbleCapMod.INSTANCE == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"CobbleCap not ready\"}");
            return;
        }
        CobbleCapApi.handle(exchange, server, subRoute);
    }
}
