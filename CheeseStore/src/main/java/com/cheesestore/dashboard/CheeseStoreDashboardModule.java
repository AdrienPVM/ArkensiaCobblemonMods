package com.cheesestore.dashboard;

import com.cheesedash.DashboardModule;
import com.cheesedash.WebDashboard;
import com.cheesestore.CheeseStoreMod;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;

public class CheeseStoreDashboardModule implements DashboardModule {

    private MinecraftServer server;

    @Override public String getPrefix()         { return "store"; }
    @Override public String getDisplayName()     { return "CheeseStore"; }
    @Override public String getNavIcon()         { return "\uD83D\uDC8E"; } // diamond emoji
    @Override public String getNavDescription()  { return "Boutique premium et paiements"; }

    // Public storefront served at /store/
    @Override public String getPublicPrefix()    { return "store"; }

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
        if (server == null || CheeseStoreMod.INSTANCE == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"CheeseStore not ready\"}");
            return;
        }
        StoreApi.handle(exchange, server, subRoute);
    }

    @Override
    public void handlePublicRequest(HttpExchange exchange, String path) throws IOException {
        if (CheeseStoreMod.INSTANCE == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"CheeseStore not ready\"}");
            return;
        }
        StoreApi.handlePublic(exchange, path);
    }
}
