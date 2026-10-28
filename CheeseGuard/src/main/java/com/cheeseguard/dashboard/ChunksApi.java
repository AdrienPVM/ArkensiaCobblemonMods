package com.cheeseguard.dashboard;

import com.cheesedash.WebDashboard;
import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.ChunkEntry;
import com.cheeseguard.data.ChunkSet;
import com.cheeseguard.data.ChunkSetStorage;
import com.cheeseguard.data.ChunkSuggestion;
import com.cheeseguard.data.ChunkSuggestionStorage;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import com.cheeseguard.logic.ChunkDeleter;

import java.io.IOException;
import java.nio.file.Path;

/** /api/v1/cheeseguard/chunks — chunk sets and suggestions */
public class ChunksApi {

    public static void handle(HttpExchange ex, CheeseGuardMod mod, String route) throws IOException {
        String method = ex.getRequestMethod();
        MinecraftServer server = mod.getServer();
        ChunkSetStorage storage = mod.getChunkSetStorage();
        ChunkSuggestionStorage suggStorage = mod.getChunkSuggestionStorage();

        if (storage == null || suggStorage == null) {
            WebDashboard.respond(ex, 503, "{\"error\":\"ChunkStorage unavailable\"}");
            return;
        }

        // ── GET /chunks/sets — list all chunk sets ──────────────────────────
        if (route.equals("/chunks/sets") && method.equals("GET")) {
            JsonArray arr = new JsonArray();
            for (var entry : storage.getAllSets().entrySet()) {
                ChunkSet cs = entry.getValue();
                JsonObject o = new JsonObject();
                o.addProperty("name", cs.getName());
                o.addProperty("dimension", cs.getDimension());
                o.addProperty("chunkCount", cs.size());
                o.addProperty("totalAffectedChunks", cs.getTotalAffectedChunks());
                o.addProperty("resetTrigger", cs.getResetTrigger());
                o.addProperty("resetHour", cs.getResetHour());
                o.addProperty("resetMinute", cs.getResetMinute());
                JsonArray days = new JsonArray();
                cs.getResetDays().forEach(days::add);
                o.add("resetDays", days);
                arr.add(o);
            }
            WebDashboard.respondJson(ex, arr);
            return;
        }

        // ── POST /chunks/sets — create chunk set ────────────────────────────
        if (route.equals("/chunks/sets") && method.equals("POST")) {
            String body = WebDashboard.readBody(ex);
            JsonObject data = JsonParser.parseString(body).getAsJsonObject();
            if (!data.has("name")) {
                WebDashboard.respond(ex, 400, "{\"error\":\"name is required\"}");
                return;
            }
            String name = data.get("name").getAsString().trim();
            if (name.isEmpty()) {
                WebDashboard.respond(ex, 400, "{\"error\":\"name cannot be empty\"}");
                return;
            }
            String dimension = data.has("dimension") ? data.get("dimension").getAsString().trim() : "minecraft:overworld";
            try {
                WebDashboard.executeOnServerThread(server, () -> {
                    ChunkSet cs = storage.getOrCreateSet(name);
                    cs.setDimension(dimension);
                    // Add chunks if provided
                    if (data.has("chunks") && data.get("chunks").isJsonArray()) {
                        for (JsonElement el : data.getAsJsonArray("chunks")) {
                            JsonObject co = el.getAsJsonObject();
                            int cx = co.get("x").getAsInt();
                            int cz = co.get("z").getAsInt();
                            int radius = co.has("radius") ? co.get("radius").getAsInt() : 0;
                            String label = co.has("label") ? co.get("label").getAsString() : "";
                            cs.addChunk(new ChunkEntry(cx, cz, radius, label));
                        }
                    }
                    storage.save();
                });
            } catch (Exception e) {
                WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(unwrapMessage(e)) + "\"}");
                return;
            }
            WebDashboard.respond(ex, 200, "{\"created\":true,\"name\":\"" + escapeJson(name) + "\"}");
            return;
        }

        // ── Routes with set name: /chunks/sets/:name[/sub] ──────────────────
        if (route.startsWith("/chunks/sets/")) {
            String rest = route.substring("/chunks/sets/".length());
            int slashIdx = rest.indexOf('/');
            String setName = slashIdx > 0 ? rest.substring(0, slashIdx) : rest;
            String sub = slashIdx > 0 ? rest.substring(slashIdx) : "";
            setName = java.net.URLDecoder.decode(setName, java.nio.charset.StandardCharsets.UTF_8);

            // GET /chunks/sets/:name
            if (sub.isEmpty() && method.equals("GET")) {
                ChunkSet cs = storage.getSet(setName);
                if (cs == null) {
                    WebDashboard.respond(ex, 404, "{\"error\":\"Chunk set not found\"}");
                    return;
                }
                JsonObject o = new JsonObject();
                o.addProperty("name", cs.getName());
                o.addProperty("dimension", cs.getDimension());
                o.addProperty("chunkCount", cs.size());
                o.addProperty("totalAffectedChunks", cs.getTotalAffectedChunks());
                JsonArray chunks = new JsonArray();
                for (ChunkEntry ce : cs.getChunks()) {
                    JsonObject co = new JsonObject();
                    co.addProperty("x", ce.getChunkX());
                    co.addProperty("z", ce.getChunkZ());
                    co.addProperty("radius", ce.getRadius());
                    co.addProperty("label", ce.getLabel());
                    co.addProperty("centerX", ce.getCenterX());
                    co.addProperty("centerZ", ce.getCenterZ());
                    chunks.add(co);
                }
                o.add("chunks", chunks);
                o.addProperty("resetTrigger", cs.getResetTrigger());
                o.addProperty("resetHour", cs.getResetHour());
                o.addProperty("resetMinute", cs.getResetMinute());
                JsonArray daysArr = new JsonArray();
                cs.getResetDays().forEach(daysArr::add);
                o.add("resetDays", daysArr);
                o.addProperty("lastAutoReset", cs.getLastAutoReset());
                WebDashboard.respondJson(ex, o);
                return;
            }

            // DELETE /chunks/sets/:name
            if (sub.isEmpty() && method.equals("DELETE")) {
                String finalName = setName;
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        boolean ok = storage.deleteSet(finalName);
                        if (!ok) throw new RuntimeException("Chunk set not found");
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(unwrapMessage(e)) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"deleted\":true}");
                return;
            }

            // DELETE /chunks/sets/:name/entry — remove a specific entry by x,z,radius
            if (sub.equals("/entry") && method.equals("DELETE")) {
                String body = WebDashboard.readBody(ex);
                JsonObject data = JsonParser.parseString(body).getAsJsonObject();
                if (!data.has("x") || !data.has("z") || !data.has("radius")) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"x, z, radius are required\"}");
                    return;
                }
                int cx = data.get("x").getAsInt();
                int cz = data.get("z").getAsInt();
                int cr = data.get("radius").getAsInt();
                String finalName2 = setName;
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        ChunkSet cs = storage.getSet(finalName2);
                        if (cs == null) throw new RuntimeException("Chunk set not found");
                        boolean removed = cs.removeChunk(new ChunkEntry(cx, cz, cr));
                        if (!removed) throw new RuntimeException("Entry not found in set");
                        storage.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(unwrapMessage(e)) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"removed\":true}");
                return;
            }

            // POST /chunks/sets/:name/reset — delete region files with verified backup
            if (sub.equals("/reset") && method.equals("POST")) {
                ChunkSet cs = storage.getSet(setName);
                if (cs == null) {
                    WebDashboard.respond(ex, 404, "{\"error\":\"Chunk set not found\"}");
                    return;
                }
                if (cs.isEmpty()) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"Chunk set is empty\"}");
                    return;
                }

                String levelName = server.getSaveProperties().getLevelName();
                Path worldPath = server.getRunDirectory().resolve(levelName);

                // Execute: force-save world + backup + delete on MC thread
                ChunkDeleter.DeletionResult[] holder = new ChunkDeleter.DeletionResult[1];
                ChunkSet finalCs = cs;
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        // Force-save the target dimension's world
                        for (var sw : server.getWorlds()) {
                            if (sw.getRegistryKey().getValue().toString().equals(finalCs.getDimension())) {
                                sw.save(null, true, false);
                                break;
                            }
                        }
                        ChunkDeleter deleter = ChunkDeleter.forDimension(worldPath, finalCs.getDimension());
                        holder[0] = deleter.deleteChunks(finalCs);
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(unwrapMessage(e)) + "\"}");
                    return;
                }

                ChunkDeleter.DeletionResult result = holder[0];
                if (result == null) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"Reset returned no result\"}");
                    return;
                }

                int httpStatus = result.isFailed() ? 500 : 200;
                WebDashboard.respond(ex, httpStatus, result.toJson().toString());
                return;
            }

            // PUT /chunks/sets/:name/schedule — configure auto-reset schedule
            if (sub.equals("/schedule") && method.equals("PUT")) {
                ChunkSet cs = storage.getSet(setName);
                if (cs == null) {
                    WebDashboard.respond(ex, 404, "{\"error\":\"Chunk set not found\"}");
                    return;
                }
                String body = new String(ex.getRequestBody().readAllBytes());
                JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                String trigger = obj.has("trigger") ? obj.get("trigger").getAsString() : "MANUAL";
                if (!"MANUAL".equals(trigger) && !"SCHEDULED".equals(trigger)) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"trigger must be MANUAL or SCHEDULED\"}");
                    return;
                }
                cs.setResetTrigger(trigger);
                if (obj.has("dimension")) cs.setDimension(obj.get("dimension").getAsString());
                if (obj.has("hour")) cs.setResetHour(Math.max(0, Math.min(23, obj.get("hour").getAsInt())));
                if (obj.has("minute")) cs.setResetMinute(Math.max(0, Math.min(59, obj.get("minute").getAsInt())));
                if (obj.has("days")) {
                    java.util.List<String> days = new java.util.ArrayList<>();
                    obj.getAsJsonArray("days").forEach(e -> days.add(e.getAsString().toUpperCase()));
                    cs.setResetDays(days);
                }
                storage.save();
                JsonObject resp = new JsonObject();
                resp.addProperty("trigger", cs.getResetTrigger());
                resp.addProperty("dimension", cs.getDimension());
                resp.addProperty("hour", cs.getResetHour());
                resp.addProperty("minute", cs.getResetMinute());
                JsonArray dArr = new JsonArray();
                cs.getResetDays().forEach(dArr::add);
                resp.add("days", dArr);
                WebDashboard.respond(ex, 200, resp.toString());
                return;
            }
        }

        // ── GET /chunks/suggestions — list suggestions ──────────────────────
        if (route.equals("/chunks/suggestions") && method.equals("GET")) {
            JsonArray arr = new JsonArray();
            for (ChunkSuggestion s : suggStorage.getAll()) {
                JsonObject o = new JsonObject();
                o.addProperty("id", s.getId());
                o.addProperty("playerName", s.getPlayerName());
                o.addProperty("world", s.getWorld());
                o.addProperty("x", (int) s.getX());
                o.addProperty("y", (int) s.getY());
                o.addProperty("z", (int) s.getZ());
                o.addProperty("createdAt", s.getCreatedAt());
                arr.add(o);
            }
            WebDashboard.respondJson(ex, arr);
            return;
        }

        // ── Routes with suggestion ID: /chunks/suggestions/:id/... ──────────
        if (route.startsWith("/chunks/suggestions/")) {
            String rest = route.substring("/chunks/suggestions/".length());
            int slashIdx = rest.indexOf('/');
            String suggId = slashIdx > 0 ? rest.substring(0, slashIdx) : rest;
            String sub = slashIdx > 0 ? rest.substring(slashIdx) : "";

            // DELETE /chunks/suggestions/:id — dismiss
            if (sub.isEmpty() && method.equals("DELETE")) {
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        boolean ok = suggStorage.remove(suggId);
                        if (!ok) throw new RuntimeException("Suggestion not found");
                        suggStorage.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(unwrapMessage(e)) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"dismissed\":true}");
                return;
            }

            // POST /chunks/suggestions/:id/accept — add suggestion chunk to an existing set
            // Body: { "setName": "name-of-existing-set", "radius": 1 }
            if (sub.equals("/accept") && method.equals("POST")) {
                String body = WebDashboard.readBody(ex);
                JsonObject data;
                try {
                    data = JsonParser.parseString(body).getAsJsonObject();
                } catch (Exception e) {
                    data = new JsonObject();
                }
                if (!data.has("setName") || data.get("setName").getAsString().trim().isEmpty()) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"setName is required\"}");
                    return;
                }
                String targetSetName = data.get("setName").getAsString().trim();
                int radius = data.has("radius") ? data.get("radius").getAsInt() : 1;
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        ChunkSuggestion sugg = null;
                        for (ChunkSuggestion s : suggStorage.getAll()) {
                            if (s.getId().equals(suggId)) { sugg = s; break; }
                        }
                        if (sugg == null) throw new RuntimeException("Suggestion not found");
                        ChunkSet cs = storage.getSet(targetSetName);
                        if (cs == null) throw new RuntimeException("Chunk set not found: " + targetSetName);
                        // Dimension safety: refuse to add a suggestion from a different dimension
                        // than the set targets — would delete region files from the wrong world.
                        if (!sugg.getWorld().equals(cs.getDimension())) {
                            throw new RuntimeException("Dimension mismatch: suggestion is in '"
                                    + sugg.getWorld() + "' but chunk set '" + targetSetName
                                    + "' targets '" + cs.getDimension() + "'");
                        }
                        // Use Math.floor so fractional negative coords map to the
                        // correct chunk (plain (int) cast truncates toward zero).
                        int chunkX = (int) Math.floor(sugg.getX()) >> 4;
                        int chunkZ = (int) Math.floor(sugg.getZ()) >> 4;
                        cs.addChunk(new ChunkEntry(chunkX, chunkZ, radius, "Suggestion by " + sugg.getPlayerName()));
                        storage.save();
                        suggStorage.remove(suggId);
                        suggStorage.save();
                    });
                } catch (Exception e) {
                    String msg = unwrapMessage(e);
                    int code = (msg != null && msg.startsWith("Dimension mismatch")) ? 409 : 500;
                    WebDashboard.respond(ex, code, "{\"error\":\"" + escapeJson(msg) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"accepted\":true,\"setName\":\"" + escapeJson(targetSetName) + "\"}");
                return;
            }
        }

        WebDashboard.respond(ex, 404, "{\"error\":\"Route not found\"}");
    }

    private static String escapeJson(String s) {
        if (s == null) return "Unknown error";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * Unwraps an ExecutionException (thrown by {@code executeOnServerThread}) so
     * the real cause message is surfaced instead of "java.util.concurrent.ExecutionException: ...".
     */
    private static String unwrapMessage(Exception e) {
        if (e.getCause() != null && e.getCause().getMessage() != null) return e.getCause().getMessage();
        return e.getMessage();
    }
}
