package com.cheeseguard.dashboard;

import com.cheesedash.WebDashboard;
import com.cheeseguard.CheeseGuardMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** /api/v1/cheeseguard/players — player management */
public class PlayersApi {

    public static void handle(HttpExchange ex, CheeseGuardMod mod, String route) throws IOException {
        String method = ex.getRequestMethod();
        MinecraftServer server = mod.getServer();
        if (server == null) {
            WebDashboard.respond(ex, 503, "{\"error\":\"Server unavailable\"}");
            return;
        }

        // ── GET /players — online players ───────────────────────────────────
        if (route.equals("/players") && method.equals("GET")) {
            JsonArray arr = new JsonArray();
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                arr.add(buildPlayerSummary(p));
            }
            WebDashboard.respondJson(ex, arr);
            return;
        }

        // ── GET /players/offline — recent offline players ───────────────────
        if (route.equals("/players/offline") && method.equals("GET")) {
            String query = ex.getRequestURI().getQuery();
            int n = 20;
            if (query != null) {
                for (String param : query.split("&")) {
                    if (param.startsWith("n=")) {
                        try { n = Math.min(100, Math.max(1, Integer.parseInt(param.substring(2)))); }
                        catch (NumberFormatException ignored) {}
                    }
                }
            }
            JsonArray arr = new JsonArray();
            // Read usercache.json for offline players
            Path cacheFile = server.getRunDirectory().resolve("usercache.json");
            if (Files.exists(cacheFile)) {
                try {
                    String content = Files.readString(cacheFile);
                    JsonArray cache = JsonParser.parseString(content).getAsJsonArray();
                    Set<String> onlineNames = new HashSet<>();
                    for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                        onlineNames.add(p.getName().getString().toLowerCase());
                    }
                    int count = 0;
                    // usercache is ordered most recent first
                    for (int i = cache.size() - 1; i >= 0 && count < n; i--) {
                        JsonObject entry = cache.get(i).getAsJsonObject();
                        String name = entry.has("name") ? entry.get("name").getAsString() : "";
                        if (!onlineNames.contains(name.toLowerCase())) {
                            JsonObject o = new JsonObject();
                            o.addProperty("name", name);
                            o.addProperty("uuid", entry.has("uuid") ? entry.get("uuid").getAsString() : "");
                            o.addProperty("online", false);
                            arr.add(o);
                            count++;
                        }
                    }
                } catch (Exception ignored) {}
            }
            WebDashboard.respondJson(ex, arr);
            return;
        }

        // ── Routes with player name: /players/:name/... ─────────────────────
        if (route.startsWith("/players/")) {
            String rest = route.substring("/players/".length());
            if (rest.equals("offline")) { // already handled
                WebDashboard.respond(ex, 404, "{\"error\":\"Route not found\"}");
                return;
            }
            int slashIdx = rest.indexOf('/');
            String name = slashIdx > 0 ? rest.substring(0, slashIdx) : rest;
            String sub = slashIdx > 0 ? rest.substring(slashIdx) : "";

            name = java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8);

            // ── GET /players/:name — detail ──────────────────────────────────
            if (sub.isEmpty() && method.equals("GET")) {
                ServerPlayerEntity target = server.getPlayerManager().getPlayer(name);
                if (target != null) {
                    WebDashboard.respondJson(ex, buildPlayerDetail(target, server));
                } else {
                    // Offline — check usercache
                    JsonObject o = new JsonObject();
                    o.addProperty("name", name);
                    o.addProperty("online", false);
                    if (server.getUserCache() != null) {
                        var profile = server.getUserCache().findByName(name);
                        if (profile.isPresent()) {
                            o.addProperty("uuid", profile.get().getId().toString());
                        }
                    }
                    WebDashboard.respondJson(ex, o);
                }
                return;
            }

            // For action routes, the player must be online
            ServerPlayerEntity target = server.getPlayerManager().getPlayer(name);

            // ── POST /players/:name/kick ─────────────────────────────────────
            if (sub.equals("/kick") && method.equals("POST")) {
                if (target == null) {
                    WebDashboard.respond(ex, 404, "{\"error\":\"Player not online\"}");
                    return;
                }
                String body = WebDashboard.readBody(ex);
                JsonObject data = JsonParser.parseString(body).getAsJsonObject();
                String msg = data.has("message") ? data.get("message").getAsString() : "Kicked by admin";
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        target.networkHandler.disconnect(Text.literal(msg));
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"kicked\":true}");
                return;
            }

            // ── POST /players/:name/tp ───────────────────────────────────────
            if (sub.equals("/tp") && method.equals("POST")) {
                if (target == null) {
                    WebDashboard.respond(ex, 404, "{\"error\":\"Player not online\"}");
                    return;
                }
                String body = WebDashboard.readBody(ex);
                JsonObject data = JsonParser.parseString(body).getAsJsonObject();
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        boolean hasCoords = data.has("x") || data.has("y") || data.has("z");
                        if (data.has("dimension") && hasCoords) {
                            // TP to specific coordinates in a specific dimension
                            String dimId = data.get("dimension").getAsString();
                            net.minecraft.server.world.ServerWorld targetWorld = null;
                            for (net.minecraft.server.world.ServerWorld w : server.getWorlds()) {
                                if (w.getRegistryKey().getValue().toString().equals(dimId)) {
                                    targetWorld = w;
                                    break;
                                }
                            }
                            if (targetWorld == null) throw new RuntimeException("Dimension not found: " + dimId);
                            double x = data.has("x") ? data.get("x").getAsDouble() : 0;
                            double y = data.has("y") ? data.get("y").getAsDouble() : 64;
                            double z = data.has("z") ? data.get("z").getAsDouble() : 0;
                            target.teleport(targetWorld, x, y, z,
                                Set.of(), target.getYaw(), target.getPitch());
                        } else if (data.has("dimension")) {
                            // TP to dimension spawn point
                            String dimId = data.get("dimension").getAsString();
                            if (mod.getDimensionManager() != null) {
                                mod.getDimensionManager().tpToDimension(target, dimId);
                            }
                        } else {
                            // TP to coordinates in current dimension
                            double x = data.has("x") ? data.get("x").getAsDouble() : target.getX();
                            double y = data.has("y") ? data.get("y").getAsDouble() : target.getY();
                            double z = data.has("z") ? data.get("z").getAsDouble() : target.getZ();
                            target.teleport(target.getServerWorld(), x, y, z,
                                Set.of(), target.getYaw(), target.getPitch());
                        }
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"teleported\":true}");
                return;
            }

            // ── POST /players/:name/gamemode ─────────────────────────────────
            if (sub.equals("/gamemode") && method.equals("POST")) {
                if (target == null) {
                    WebDashboard.respond(ex, 404, "{\"error\":\"Player not online\"}");
                    return;
                }
                String body = WebDashboard.readBody(ex);
                JsonObject data = JsonParser.parseString(body).getAsJsonObject();
                if (!data.has("mode")) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"mode is required\"}");
                    return;
                }
                String modeStr = data.get("mode").getAsString().toUpperCase();
                try {
                    GameMode gm = switch (modeStr) {
                        case "SURVIVAL" -> GameMode.SURVIVAL;
                        case "CREATIVE" -> GameMode.CREATIVE;
                        case "ADVENTURE" -> GameMode.ADVENTURE;
                        case "SPECTATOR" -> GameMode.SPECTATOR;
                        default -> throw new RuntimeException("Invalid gamemode: " + modeStr);
                    };
                    WebDashboard.executeOnServerThread(server, () -> {
                        target.changeGameMode(gm);
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"changed\":true}");
                return;
            }

            // ── POST /players/:name/message ──────────────────────────────────
            if (sub.equals("/message") && method.equals("POST")) {
                if (target == null) {
                    WebDashboard.respond(ex, 404, "{\"error\":\"Player not online\"}");
                    return;
                }
                String body = WebDashboard.readBody(ex);
                JsonObject data = JsonParser.parseString(body).getAsJsonObject();
                if (!data.has("text")) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"text is required\"}");
                    return;
                }
                String text = data.get("text").getAsString();
                String type = data.has("type") ? data.get("type").getAsString() : "chat";
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        switch (type) {
                            case "actionbar" -> target.sendMessage(Text.literal(text), true);
                            case "title" -> server.getCommandManager().executeWithPrefix(
                                server.getCommandSource(), "title " + target.getName().getString() + " title {\"text\":\"" + text.replace("\"", "\\\"") + "\"}");
                            case "subtitle" -> server.getCommandManager().executeWithPrefix(
                                server.getCommandSource(), "title " + target.getName().getString() + " subtitle {\"text\":\"" + text.replace("\"", "\\\"") + "\"}");
                            default -> target.sendMessage(Text.literal(text), false);
                        }
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"sent\":true}");
                return;
            }
        }

        WebDashboard.respond(ex, 404, "{\"error\":\"Route not found\"}");
    }

    // ── JSON Builders ────────────────────────────────────────────────────────

    private static JsonObject buildPlayerSummary(ServerPlayerEntity p) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", p.getUuid().toString());
        o.addProperty("name", p.getName().getString());
        o.addProperty("world", p.getServerWorld().getRegistryKey().getValue().toString());
        o.addProperty("x", (int) p.getX());
        o.addProperty("y", (int) p.getY());
        o.addProperty("z", (int) p.getZ());
        o.addProperty("health", Math.round(p.getHealth() * 10.0) / 10.0);
        o.addProperty("maxHealth", Math.round(p.getMaxHealth() * 10.0) / 10.0);
        o.addProperty("gameMode", p.interactionManager.getGameMode().getName());
        o.addProperty("ping", p.networkHandler.getLatency());
        o.addProperty("online", true);
        return o;
    }

    private static JsonObject buildPlayerDetail(ServerPlayerEntity p, MinecraftServer server) {
        JsonObject o = buildPlayerSummary(p);
        o.addProperty("food", p.getHungerManager().getFoodLevel());
        o.addProperty("xpLevel", p.experienceLevel);
        o.addProperty("fly", p.getAbilities().allowFlying);
        // Count advancements
        int advDone = 0, advTotal = 0;
        var tracker = p.getAdvancementTracker();
        for (var adv : server.getAdvancementLoader().getAdvancements()) {
            String id = adv.id().toString();
            if (id.contains("recipes/")) continue;
            advTotal++;
            if (tracker.getProgress(adv).isDone()) advDone++;
        }
        o.addProperty("advDone", advDone);
        o.addProperty("advTotal", advTotal);
        return o;
    }

    private static String escapeJson(String s) {
        if (s == null) return "Unknown error";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
