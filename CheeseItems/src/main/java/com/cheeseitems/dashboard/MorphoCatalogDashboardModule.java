package com.cheeseitems.dashboard;

import com.cheesedash.DashboardModule;
import com.cheesedash.WebDashboard;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;

/**
 * Dashboard module that exposes the central morpho catalog.
 *
 * Admin routes (auth):    /api/v1/morphos/*    — full CRUD + image + grant
 * Public routes (no auth): /morphos/api/image/:id  — used by the website + the
 *                          Minecraft client to download catalog images
 */
public class MorphoCatalogDashboardModule implements DashboardModule {

    private MinecraftServer server;

    @Override public String getPrefix()         { return "morphos"; }
    @Override public String getDisplayName()    { return "Morphos"; }
    @Override public String getNavIcon()        { return "🧬"; } // 🧬
    @Override public String getNavDescription() { return "Catalogue de morphos"; }

    @Override public String getPublicPrefix()   { return "morphos"; }

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
            WebDashboard.respond(exchange, 503, "{\"error\":\"Server not ready\"}");
            return;
        }
        MorphoCatalogApi.handle(exchange, server, subRoute);
    }

    @Override
    public void handlePublicRequest(HttpExchange exchange, String path) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            // GET /morphos/api/image/<id> — full resolution (used by web lightbox).
            if (path.startsWith("/image/")) {
                String id = path.substring("/image/".length());
                if (id.endsWith(".png")) id = id.substring(0, id.length() - 4);
                MorphoCatalogApi.handlePublicImage(exchange, id);
                return;
            }
            // GET /morphos/api/thumb/<id> — ≤480px downscale (in-game GUI + admin table).
            if (path.startsWith("/thumb/")) {
                String id = path.substring("/thumb/".length());
                if (id.endsWith(".png")) id = id.substring(0, id.length() - 4);
                MorphoCatalogApi.handlePublicThumb(exchange, id);
                return;
            }
        }
        WebDashboard.respond(exchange, 404, "{\"error\":\"Not found\"}");
    }
}
