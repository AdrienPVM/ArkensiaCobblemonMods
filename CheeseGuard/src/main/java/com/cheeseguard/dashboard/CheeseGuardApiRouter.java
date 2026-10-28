package com.cheeseguard.dashboard;

import com.cheesedash.WebDashboard;
import com.cheeseguard.CheeseGuardMod;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;

/**
 * Internal router for CheeseGuard dashboard API.
 * Dispatches /api/v1/cheeseguard/<subRoute> to the appropriate handler.
 */
public class CheeseGuardApiRouter {

    public static void handle(HttpExchange exchange, String subRoute, CheeseGuardMod mod, MinecraftServer server) throws IOException {
        String method = exchange.getRequestMethod();

        try {
            // ── Status (CheeseGuard-specific) ───────────────────────────
            if (subRoute.equals("/") || subRoute.equals("/status")) {
                if (method.equals("GET")) {
                    handleStatus(exchange, mod);
                    return;
                }
            }

            // ── Zones ───────────────────────────────────────────────────
            if (subRoute.startsWith("/zones")) {
                ZonesApi.handle(exchange, mod, subRoute);
                return;
            }

            // ── Groups ──────────────────────────────────────────────────
            if (subRoute.startsWith("/groups")) {
                GroupsApi.handle(exchange, mod, subRoute);
                return;
            }

            // ── Dimensions ──────────────────────────────────────────────
            if (subRoute.startsWith("/dimensions")) {
                DimensionsApi.handle(exchange, mod, subRoute);
                return;
            }

            // ── Events ──────────────────────────────────────────────────
            if (subRoute.startsWith("/events")) {
                EventsApi.handle(exchange, mod, subRoute);
                return;
            }

            // ── Players ─────────────────────────────────────────────────
            if (subRoute.startsWith("/players")) {
                PlayersApi.handle(exchange, mod, subRoute);
                return;
            }

            // ── Chunks ──────────────────────────────────────────────────
            if (subRoute.startsWith("/chunks")) {
                ChunksApi.handle(exchange, mod, subRoute);
                return;
            }

            // ── Races ──────────────────────────────────────────────────
            if (subRoute.startsWith("/races")) {
                RacesApi.handle(exchange, mod, subRoute);
                return;
            }

            // ── Prison ─────────────────────────────────────────────────
            if (subRoute.startsWith("/prison")) {
                PrisonApi.handle(exchange, mod, subRoute);
                return;
            }

            // ── Not found ───────────────────────────────────────────────
            WebDashboard.respond(exchange, 404, "{\"error\":\"CheeseGuard route not found: " + subRoute + "\"}");

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            WebDashboard.respond(exchange, 500,
                "{\"error\":\"Internal server error\",\"detail\":\"" +
                msg.replace("\"", "'") + "\"}");
        }
    }

    private static void handleStatus(HttpExchange exchange, CheeseGuardMod mod) throws IOException {
        JsonObject obj = new JsonObject();

        if (mod.getRegionManager() != null) {
            obj.addProperty("zoneCount", mod.getRegionManager().getStorage().getRegions().size());
        }
        if (mod.getDimensionManager() != null) {
            obj.addProperty("dimensionCount", mod.getDimensionManager().getCustomDimensions().size());
        }
        if (mod.getEventManager() != null) {
            long activeEvents = mod.getEventManager().getEvents().stream()
                .filter(e -> e.isRunning()).count();
            obj.addProperty("activeEvents", activeEvents);
            obj.addProperty("eventCount", mod.getEventManager().getEvents().size());
        }

        WebDashboard.respondJson(exchange, obj);
    }
}
