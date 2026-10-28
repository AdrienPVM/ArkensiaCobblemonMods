package com.cobblecheesescore.dashboard;

import com.cheesedash.DashboardModule;
import com.cheesedash.WebDashboard;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;

/**
 * CobbleCheeseScore's integration module for CheeseDash.
 * Handles all /api/v1/scores/* routes.
 */
public class CobbleCheeseScoreDashboardModule implements DashboardModule {

    private MinecraftServer server;

    @Override public String getPrefix()          { return "scores"; }
    @Override public String getDisplayName()      { return "CobbleCheeseScore"; }
    @Override public String getNavIcon()          { return "\uD83C\uDFC6"; }
    @Override public String getNavDescription()   { return "Classements et scoreboards"; }
    @Override public String getPublicPrefix()     { return "scores"; }

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
        ScoresApi.handle(exchange, server, subRoute);
    }

    @Override
    public void handlePublicRequest(HttpExchange exchange, String path) throws IOException {
        // Public image route: /images/{name}/{id}.png — no auth required (for WaterFrames etc.)
        if ("GET".equals(exchange.getRequestMethod()) && path.matches("/images/[^/]+/[^/]+\\.png")) {
            String[] parts = path.split("/");
            String idPng = parts[3]; // "{id}.png"
            String id = java.net.URLDecoder.decode(idPng.substring(0, idPng.length() - 4), "UTF-8");
            ScoresApi.handlePublicImage(exchange, id);
            return;
        }
        WebDashboard.respond(exchange, 404, "{\"error\":\"Not found\"}");
    }
}
