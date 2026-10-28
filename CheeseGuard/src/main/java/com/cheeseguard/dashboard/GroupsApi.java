package com.cheeseguard.dashboard;

import com.cheesedash.WebDashboard;
import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Flag;
import com.cheeseguard.data.Region;
import com.cheeseguard.data.RegionGroup;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.util.Map;

/** /api/v1/cheeseguard/groups — CRUD for zone groups */
public class GroupsApi {

    public static void handle(HttpExchange ex, CheeseGuardMod mod, String route) throws IOException {
        String method = ex.getRequestMethod();
        var storage = mod.getRegionManager() != null ? mod.getRegionManager().getStorage() : null;
        if (storage == null) {
            WebDashboard.respond(ex, 503, "{\"error\":\"RegionManager unavailable\"}");
            return;
        }

        MinecraftServer server = mod.getServer();

        // GET /groups — list all
        if (route.equals("/groups") && method.equals("GET")) {
            JsonArray arr = new JsonArray();
            for (var g : storage.getGroups()) {
                arr.add(buildGroupSummary(g));
            }
            WebDashboard.respondJson(ex, arr);
            return;
        }

        // POST /groups — create
        if (route.equals("/groups") && method.equals("POST")) {
            String body = WebDashboard.readBody(ex);
            JsonObject data = JsonParser.parseString(body).getAsJsonObject();
            if (!data.has("name") || data.get("name").getAsString().trim().isEmpty()) {
                WebDashboard.respond(ex, 400, "{\"error\":\"name is required\"}");
                return;
            }
            String name = data.get("name").getAsString().trim();
            String id = slugify(name);
            if (id.isEmpty()) {
                WebDashboard.respond(ex, 400, "{\"error\":\"Invalid group name\"}");
                return;
            }
            if (storage.hasGroup(id)) {
                WebDashboard.respond(ex, 409, "{\"error\":\"Group already exists: " + escapeJson(id) + "\"}");
                return;
            }

            try {
                WebDashboard.executeOnServerThread(server, () -> {
                    RegionGroup group = new RegionGroup(id, name);
                    storage.addGroup(group);
                });
            } catch (Exception e) {
                WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                return;
            }
            var created = storage.getGroup(id);
            WebDashboard.respondJson(ex, buildGroupDetail(created, storage));
            return;
        }

        // Routes with group ID: /groups/:id, /groups/:id/zones, /groups/:id/zones/:zoneId
        if (route.startsWith("/groups/")) {
            String rest = route.substring(8); // after "/groups/"
            int slashIdx = rest.indexOf('/');
            String id = slashIdx > 0 ? rest.substring(0, slashIdx) : rest;
            String sub = slashIdx > 0 ? rest.substring(slashIdx) : "";

            if (!isValidId(id)) {
                WebDashboard.respond(ex, 400, "{\"error\":\"Invalid group ID\"}");
                return;
            }

            // GET /groups/:id — detail
            if (sub.isEmpty() && method.equals("GET")) {
                var g = storage.getGroup(id);
                if (g == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Group not found\"}"); return; }
                WebDashboard.respondJson(ex, buildGroupDetail(g, storage));
                return;
            }

            // PUT /groups/:id — update name and/or flags
            if (sub.isEmpty() && method.equals("PUT")) {
                var g = storage.getGroup(id);
                if (g == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Group not found\"}"); return; }
                String body = WebDashboard.readBody(ex);
                JsonObject data = JsonParser.parseString(body).getAsJsonObject();

                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        if (data.has("name") && !data.get("name").isJsonNull()) {
                            g.setName(data.get("name").getAsString().trim());
                        }
                        if (data.has("flags") && data.get("flags").isJsonObject()) {
                            JsonObject flagsObj = data.getAsJsonObject("flags");
                            for (Map.Entry<String, JsonElement> entry : flagsObj.entrySet()) {
                                Flag flag = Flag.fromId(entry.getKey());
                                if (flag == null) continue;
                                String val = entry.getValue().getAsString();
                                switch (val.toUpperCase()) {
                                    case "ALLOW": g.setFlag(flag, true); break;
                                    case "DENY":  g.setFlag(flag, false); break;
                                    case "INHERIT": g.setFlag(flag, null); break;
                                }
                            }
                        }
                        storage.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respondJson(ex, buildGroupDetail(g, storage));
                return;
            }

            // DELETE /groups/:id
            if (sub.isEmpty() && method.equals("DELETE")) {
                if (storage.getGroup(id) == null) {
                    WebDashboard.respond(ex, 404, "{\"error\":\"Group not found\"}");
                    return;
                }
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        storage.removeGroup(id);
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"deleted\":true}");
                return;
            }

            // GET /groups/:id/zones — list zones in this group
            if (sub.equals("/zones") && method.equals("GET")) {
                var g = storage.getGroup(id);
                if (g == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Group not found\"}"); return; }
                JsonArray arr = new JsonArray();
                for (String rid : g.getRegionIds()) {
                    Region r = storage.getRegion(rid);
                    if (r != null) {
                        JsonObject zo = new JsonObject();
                        zo.addProperty("id", r.getId());
                        zo.addProperty("name", r.getName());
                        zo.addProperty("world", r.getWorld());
                        zo.addProperty("priority", r.getPriority());
                        arr.add(zo);
                    }
                }
                WebDashboard.respondJson(ex, arr);
                return;
            }

            // POST /groups/:id/zones/:zoneId — assign zone to group
            if (sub.startsWith("/zones/") && method.equals("POST")) {
                String zoneId = sub.substring(7); // after "/zones/"
                var g = storage.getGroup(id);
                if (g == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Group not found\"}"); return; }
                var r = storage.getRegion(zoneId);
                if (r == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Zone not found: " + escapeJson(zoneId) + "\"}"); return; }

                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        // Remove from old group if exists
                        String oldGroupId = r.getGroupId();
                        if (oldGroupId != null && !oldGroupId.equals(id)) {
                            var oldGroup = storage.getGroup(oldGroupId);
                            if (oldGroup != null) oldGroup.removeRegion(zoneId);
                        }
                        g.addRegion(zoneId);
                        r.setGroupId(id);
                        storage.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"assigned\":true}");
                return;
            }

            // DELETE /groups/:id/zones/:zoneId — unassign zone from group
            if (sub.startsWith("/zones/") && method.equals("DELETE")) {
                String zoneId = sub.substring(7);
                var g = storage.getGroup(id);
                if (g == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Group not found\"}"); return; }

                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        g.removeRegion(zoneId);
                        var r = storage.getRegion(zoneId);
                        if (r != null && id.equals(r.getGroupId())) {
                            r.setGroupId(null);
                        }
                        storage.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"unassigned\":true}");
                return;
            }
        }

        WebDashboard.respond(ex, 404, "{\"error\":\"Route not found\"}");
    }

    // ── JSON Builders ─────────────────────────────────────────────────────────

    private static JsonObject buildGroupSummary(RegionGroup g) {
        JsonObject o = new JsonObject();
        o.addProperty("id", g.getId());
        o.addProperty("name", g.getName());
        o.addProperty("zoneCount", g.getRegionIds().size());
        // Count explicit flags (non-null)
        int flagCount = 0;
        for (Flag f : Flag.values()) {
            if (g.getFlag(f) != null) flagCount++;
        }
        o.addProperty("flagCount", flagCount);
        return o;
    }

    private static JsonObject buildGroupDetail(RegionGroup g, com.cheeseguard.data.RegionStorage storage) {
        JsonObject o = buildGroupSummary(g);
        // Flags — all 3-state values
        JsonObject flags = new JsonObject();
        for (Flag f : Flag.values()) {
            Boolean v = g.getFlag(f);
            String state = v == null ? "INHERIT" : (v ? "ALLOW" : "DENY");
            flags.addProperty(f.getId(), state);
        }
        o.add("flags", flags);
        // Region IDs
        JsonArray regionIds = new JsonArray();
        g.getRegionIds().forEach(regionIds::add);
        o.add("regionIds", regionIds);
        // Zones detail
        JsonArray zones = new JsonArray();
        for (String rid : g.getRegionIds()) {
            Region r = storage.getRegion(rid);
            if (r != null) {
                JsonObject zo = new JsonObject();
                zo.addProperty("id", r.getId());
                zo.addProperty("name", r.getName());
                zo.addProperty("world", r.getWorld());
                zo.addProperty("priority", r.getPriority());
                zones.add(zo);
            }
        }
        o.add("zones", zones);
        return o;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static String slugify(String name) {
        return name.toLowerCase()
            .replaceAll("[^a-z0-9\\s-]", "")
            .trim()
            .replaceAll("\\s+", "-")
            .replaceAll("-+", "-");
    }

    private static boolean isValidId(String id) {
        return id != null && !id.isEmpty() && !id.contains("..") && !id.contains("/")
            && !id.contains("\\") && !id.contains("%") && id.length() < 128;
    }

    private static String escapeJson(String s) {
        if (s == null) return "Unknown error";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
