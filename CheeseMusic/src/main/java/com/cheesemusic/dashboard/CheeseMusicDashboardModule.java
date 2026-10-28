package com.cheesemusic.dashboard;

import com.cheesedash.DashboardModule;
import com.cheesedash.WebDashboard;
import com.cheesemusic.CheeseMusicMod;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;

public class CheeseMusicDashboardModule implements DashboardModule {

    private MinecraftServer server;

    @Override public String getPrefix()         { return "music"; }
    @Override public String getDisplayName()     { return "CheeseMusic"; }
    @Override public String getNavIcon()         { return "\uD83C\uDFB5"; } // 🎵
    @Override public String getNavDescription()  { return "Musique de zones"; }

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
        if (server == null || CheeseMusicMod.INSTANCE == null || CheeseMusicMod.INSTANCE.getConfig() == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"CheeseMusic not ready\"}");
            return;
        }
        MusicApi.handle(exchange, server, subRoute);
    }
}
