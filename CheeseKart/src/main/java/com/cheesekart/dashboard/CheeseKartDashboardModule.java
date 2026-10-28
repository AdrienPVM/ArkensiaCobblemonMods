package com.cheesekart.dashboard;

import com.cheesedash.DashboardModule;
import com.cheesedash.WebDashboard;
import com.cheesekart.CheeseKartMod;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;

public class CheeseKartDashboardModule implements DashboardModule {

    private MinecraftServer server;

    @Override public String getPrefix()         { return "kart"; }
    @Override public String getDisplayName()     { return "CheeseKart"; }
    @Override public String getNavIcon()         { return "\uD83C\uDFCE"; } // 🏎
    @Override public String getNavDescription()  { return "Mode kart et courses"; }

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
        if (server == null || CheeseKartMod.INSTANCE == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"CheeseKart not ready\"}");
            return;
        }
        KartApi.handle(exchange, server, subRoute);
    }
}
