package com.cheesepatrol.dashboard;

import com.cheesedash.WebDashboard;
import com.cheesepatrol.PatrolData;
import com.cheesepatrol.PatrolManager;
import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

public class CheesePatrolApi {

    public static void handle(HttpExchange exchange, MinecraftServer server, String subRoute) throws IOException {
        String method = exchange.getRequestMethod();
        String path = subRoute.startsWith("/") ? subRoute : "/" + subRoute;

        try {
            if (path.equals("/list") && method.equals("GET")) {
                handleList(exchange, server);
            } else if (path.equals("/tp") && method.equals("POST")) {
                if (!WebDashboard.requireWrite(exchange)) return;
                handleTp(exchange, server);
            } else {
                WebDashboard.respond(exchange, 404, "{\"error\":\"Unknown patrol route: " + path + "\"}");
            }
        } catch (Exception e) {
            WebDashboard.respond(exchange, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private static void handleList(HttpExchange exchange, MinecraftServer server) throws IOException {
        JsonArray arr = new JsonArray();
        for (PatrolData patrol : PatrolManager.getAll()) {
            JsonObject o = new JsonObject();
            o.addProperty("npcUuid", patrol.getNpcUuid().toString());
            o.addProperty("npcName", resolveNpcName(server, patrol));
            o.addProperty("dimension", patrol.getDimension());
            o.addProperty("active", patrol.isActive());
            o.addProperty("waypointCount", patrol.getWaypoints().size());
            o.addProperty("currentWaypointIndex", patrol.getCurrentWaypointIndex());
            o.addProperty("speed", patrol.getSpeed());
            o.addProperty("pausedBySystem", patrol.isPausedBySystem());
            arr.add(o);
        }
        WebDashboard.respond(exchange, 200, arr.toString());
    }

    private static void handleTp(HttpExchange exchange, MinecraftServer server) throws Exception {
        JsonObject body = readBody(exchange);
        if (!body.has("npcUuid") || !body.has("playerName")) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Missing npcUuid or playerName\"}");
            return;
        }

        UUID npcUuid;
        try {
            npcUuid = UUID.fromString(body.get("npcUuid").getAsString());
        } catch (IllegalArgumentException e) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid UUID\"}");
            return;
        }

        PatrolData patrol = PatrolManager.get(npcUuid);
        if (patrol == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Patrol not found\"}");
            return;
        }
        if (patrol.getWaypoints().isEmpty()) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Patrol has no waypoints\"}");
            return;
        }

        String playerName = body.get("playerName").getAsString();
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Player not found: " + escapeJson(playerName) + "\"}");
            return;
        }

        // Calculate next waypoint (where the NPC is heading)
        int nextIdx = (patrol.getCurrentWaypointIndex() + 1) % patrol.getWaypoints().size();
        BlockPos nextWp = patrol.getWaypoints().get(nextIdx);

        // Find target dimension
        ServerWorld targetWorld = getWorld(server, patrol.getDimension());
        if (targetWorld == null) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Dimension not found: " + escapeJson(patrol.getDimension()) + "\"}");
            return;
        }

        WebDashboard.executeOnServerThread(server, () -> {
            player.teleport(targetWorld, nextWp.getX() + 0.5, nextWp.getY(), nextWp.getZ() + 0.5,
                    Set.of(), player.getYaw(), player.getPitch());
        });

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("waypointIndex", nextIdx);
        result.addProperty("x", nextWp.getX());
        result.addProperty("y", nextWp.getY());
        result.addProperty("z", nextWp.getZ());
        result.addProperty("dimension", patrol.getDimension());
        WebDashboard.respond(exchange, 200, result.toString());
    }

    private static String resolveNpcName(MinecraftServer server, PatrolData patrol) {
        Entity entity = findEntity(server, patrol.getNpcUuid(), patrol.getDimension());
        if (entity != null) {
            String name = entity.getName().getString();
            if (name != null && !name.isEmpty()) return name;
        }
        return patrol.getNpcUuid().toString().substring(0, 8) + "...";
    }

    private static Entity findEntity(MinecraftServer server, UUID uuid, String dimension) {
        for (ServerWorld world : server.getWorlds()) {
            if (dimension != null && !world.getRegistryKey().getValue().toString().equals(dimension)) continue;
            Entity entity = world.getEntity(uuid);
            if (entity != null) return entity;
        }
        if (dimension != null) {
            for (ServerWorld world : server.getWorlds()) {
                Entity entity = world.getEntity(uuid);
                if (entity != null) return entity;
            }
        }
        return null;
    }

    private static ServerWorld getWorld(MinecraftServer server, String dimension) {
        for (ServerWorld sw : server.getWorlds()) {
            if (sw.getRegistryKey().getValue().toString().equals(dimension)) return sw;
        }
        return null;
    }

    private static JsonObject readBody(HttpExchange exchange) throws IOException {
        String json = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static String escapeJson(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
