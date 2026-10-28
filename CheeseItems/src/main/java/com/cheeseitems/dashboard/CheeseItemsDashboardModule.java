package com.cheeseitems.dashboard;

import com.cheesedash.DashboardModule;
import com.cheesedash.WebDashboard;
import com.cheeseitems.CheeseItemsMod;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.OutputStream;

/**
 * CheeseDash integration module for CheeseItems.
 * Handles all /api/v1/items/* routes (authenticated)
 * and /cheeseitems/api/pack.zip (public, for resource pack serving).
 */
public class CheeseItemsDashboardModule implements DashboardModule {

    private MinecraftServer server;

    @Override public String getPrefix()          { return "items"; }
    @Override public String getDisplayName()      { return "CheeseItems"; }
    @Override public String getNavIcon()          { return "\uD83C\uDF81"; } // 🎁
    @Override public String getNavDescription()   { return "Items custom de qu\u00eate"; }

    @Override public String getPublicPrefix()     { return "cheeseitems"; }

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
        if (server == null || CheeseItemsMod.INSTANCE == null) {
            WebDashboard.respond(exchange, 503, "{\"error\":\"CheeseItems not ready\"}");
            return;
        }
        ItemsApi.handle(exchange, server, subRoute);
    }

    @Override
    public void handlePublicRequest(HttpExchange exchange, String path) throws IOException {
        // GET /cheeseitems/api/pack.zip — serve the generated resource pack
        if ("/pack.zip".equals(path) && "GET".equals(exchange.getRequestMethod())) {
            if (CheeseItemsMod.INSTANCE == null || !CheeseItemsMod.INSTANCE.getResourcePackGenerator().hasPack()) {
                WebDashboard.respond(exchange, 404, "{\"error\":\"No resource pack available\"}");
                return;
            }
            byte[] pack = CheeseItemsMod.INSTANCE.getResourcePackGenerator().getPack();
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"cheeseitems-pack.zip\"");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, pack.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(pack);
            }
            return;
        }
        WebDashboard.respond(exchange, 404, "{\"error\":\"Not found\"}");
    }
}
