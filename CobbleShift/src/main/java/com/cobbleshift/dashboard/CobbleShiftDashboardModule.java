package com.cobbleshift.dashboard;

import com.cheesedash.DashboardModule;
import com.cheesedash.WebDashboard;
import com.cobbleshift.CobbleShiftMod;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;

/**
 * CobbleShift's integration module for CheeseDash.
 * Handles all /api/v1/shift/* routes.
 */
public class CobbleShiftDashboardModule implements DashboardModule {

    private MinecraftServer server;

    @Override public String getPrefix()          { return "shift"; }
    @Override public String getDisplayName()      { return "CobbleShift"; }
    @Override public String getNavIcon()          { return "\uD83C\uDFAE"; } // 🎮
    @Override public String getNavDescription()   { return "Transformations et mini-jeux"; }

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
        if (server == null || CobbleShiftMod.INSTANCE == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"CobbleShift not ready\"}");
            return;
        }
        ShiftApi.handle(exchange, server, subRoute);
    }
}
