package com.cheeseguard.dashboard;

import com.cheesedash.WebDashboard;
import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Flag;
import com.cheeseguard.data.Member;
import com.cheeseguard.data.Region;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/** /api/v1/cheeseguard/zones — read/write protection zones */
public class ZonesApi {

    public static void handle(HttpExchange ex, CheeseGuardMod mod, String route) throws IOException {
        String method = ex.getRequestMethod();
        var storage = mod.getRegionManager() != null ? mod.getRegionManager().getStorage() : null;
        if (storage == null) {
            WebDashboard.respond(ex, 503, "{\"error\":\"RegionManager unavailable\"}");
            return;
        }

        MinecraftServer server = mod.getServer();
        String query = ex.getRequestURI().getQuery();

        // GET /zones — list all (optional ?world= filter)
        if (route.equals("/zones") && method.equals("GET")) {
            String worldFilter = null;
            if (query != null) {
                for (String param : query.split("&")) {
                    if (param.startsWith("world=")) {
                        worldFilter = java.net.URLDecoder.decode(param.substring(6), java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            }
            JsonArray arr = new JsonArray();
            for (var r : storage.getRegions()) {
                if (worldFilter != null && !r.getWorld().equals(worldFilter)) continue;
                arr.add(buildZoneSummary(r));
            }
            WebDashboard.respondJson(ex, arr);
            return;
        }

        // POST /zones — create zone from wand selection
        if (route.equals("/zones") && method.equals("POST")) {
            String body = WebDashboard.readBody(ex);
            JsonObject data = JsonParser.parseString(body).getAsJsonObject();

            String name = data.has("name") && !data.get("name").getAsString().isEmpty()
                ? data.get("name").getAsString().trim() : null;
            if (name == null) {
                WebDashboard.respond(ex, 400, "{\"error\":\"name is required\"}");
                return;
            }

            // Resolve player for selection lookup
            String playerName = data.has("playerName") && !data.get("playerName").getAsString().isEmpty()
                ? data.get("playerName").getAsString() : null;
            if (playerName == null && server.getPlayerManager() != null) {
                for (var p : server.getPlayerManager().getPlayerList()) {
                    if (p.hasPermissionLevel(2)) { playerName = p.getName().getString(); break; }
                }
            }
            if (playerName == null) {
                WebDashboard.respond(ex, 400, "{\"error\":\"No playerName provided and no op player online\"}");
                return;
            }

            UUID playerUuid = null;
            if (server.getPlayerManager() != null) {
                var onlinePlayer = server.getPlayerManager().getPlayer(playerName);
                if (onlinePlayer != null) playerUuid = onlinePlayer.getUuid();
            }
            if (playerUuid == null) {
                var profile = server.getUserCache().findByName(playerName);
                if (profile.isPresent()) playerUuid = profile.get().getId();
            }
            if (playerUuid == null) {
                WebDashboard.respond(ex, 404, "{\"error\":\"Player not found: " + escapeJson(playerName) + "\"}");
                return;
            }

            var selection = mod.getSelectionManager().getSelection(playerUuid);
            if (selection == null || !selection.isComplete()) {
                WebDashboard.respond(ex, 400, "{\"error\":\"No complete wand selection for player " + escapeJson(playerName) + "\"}");
                return;
            }

            int x1 = Math.min(selection.pos1.getX(), selection.pos2.getX());
            int y1 = Math.min(selection.pos1.getY(), selection.pos2.getY());
            int z1 = Math.min(selection.pos1.getZ(), selection.pos2.getZ());
            int x2 = Math.max(selection.pos1.getX(), selection.pos2.getX());
            int y2 = Math.max(selection.pos1.getY(), selection.pos2.getY());
            int z2 = Math.max(selection.pos1.getZ(), selection.pos2.getZ());
            String selWorld = selection.world;

            // Generate ID from name
            String id = name.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
            if (storage.getRegion(id) != null) {
                WebDashboard.respond(ex, 409, "{\"error\":\"Zone ID already exists: " + escapeJson(id) + "\"}");
                return;
            }

            // Resolve owner
            final UUID ownerUuid = playerUuid;
            final String ownerName = playerName;

            try {
                WebDashboard.executeOnServerThread(server, () -> {
                    Region region = new Region(id, name, selWorld, x1, y1, z1, x2, y2, z2,
                        new com.cheeseguard.data.Member(ownerUuid, ownerName));
                    storage.addRegion(region);
                });
            } catch (Exception e) {
                WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                return;
            }

            WebDashboard.respondJson(ex, buildZoneDetail(storage.getRegion(id)));
            return;
        }

        // GET /zones/tags — all unique tags across all zones (for autocomplete)
        if (route.equals("/zones/tags") && method.equals("GET")) {
            java.util.Set<String> uniqueTags = new java.util.TreeSet<>();
            for (var r : storage.getRegions()) {
                uniqueTags.addAll(r.getTags());
            }
            JsonArray arr = new JsonArray();
            uniqueTags.forEach(arr::add);
            WebDashboard.respondJson(ex, arr);
            return;
        }

        // GET /zones/tag-configs — list all tag configurations
        if (route.equals("/zones/tag-configs") && method.equals("GET")) {
            var tagStorage = mod.getTagConfigStorage();
            if (tagStorage == null) {
                WebDashboard.respond(ex, 503, "{\"error\":\"TagConfigStorage unavailable\"}");
                return;
            }
            JsonArray arr = new JsonArray();
            for (var tc : tagStorage.getAll()) {
                JsonObject tcJson = buildTagConfigJson(tc);
                // Count zones using this tag
                int zoneCount = 0;
                for (var r : storage.getRegions()) {
                    if (r.getTags().contains(tc.getId())) zoneCount++;
                }
                tcJson.addProperty("zoneCount", zoneCount);
                arr.add(tcJson);
            }
            WebDashboard.respondJson(ex, arr);
            return;
        }

        // PUT /zones/tag-configs/:tagId — create or update a tag config
        // GET /zones/tag-configs/:tagId — get a single tag config
        // DELETE /zones/tag-configs/:tagId — delete a tag config
        if (route.startsWith("/zones/tag-configs/")) {
            var tagStorage = mod.getTagConfigStorage();
            if (tagStorage == null) {
                WebDashboard.respond(ex, 503, "{\"error\":\"TagConfigStorage unavailable\"}");
                return;
            }
            String tagId = route.substring("/zones/tag-configs/".length());
            if (tagId.endsWith("/")) tagId = tagId.substring(0, tagId.length() - 1);

            if (!com.cheeseguard.data.TagConfigStorage.isValidTagId(tagId)) {
                WebDashboard.respond(ex, 400, "{\"error\":\"Invalid tag ID. Use lowercase letters, digits, and hyphens only.\"}");
                return;
            }

            if (method.equals("PUT")) {
                String body = WebDashboard.readBody(ex);
                JsonObject data = JsonParser.parseString(body).getAsJsonObject();
                String label = data.has("label") && !data.get("label").isJsonNull()
                    ? data.get("label").getAsString() : null;
                String color = data.has("color") && !data.get("color").isJsonNull()
                    ? data.get("color").getAsString() : null;
                int ownershipLimit = data.has("ownershipLimit")
                    ? data.get("ownershipLimit").getAsInt() : 0;

                final String fTagId = tagId;
                final String fLabel = label;
                final String fColor = color;
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        tagStorage.put(new com.cheeseguard.data.TagConfig(fTagId, fLabel, fColor, ownershipLimit));
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respondJson(ex, buildTagConfigJson(tagStorage.get(tagId)));
                return;
            }

            if (method.equals("DELETE")) {
                final String fTagId = tagId;
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        tagStorage.remove(fTagId);
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"deleted\":true}");
                return;
            }

            if (method.equals("GET")) {
                var tc = tagStorage.get(tagId);
                if (tc == null) {
                    WebDashboard.respond(ex, 404, "{\"error\":\"Tag config not found\"}");
                    return;
                }
                WebDashboard.respondJson(ex, buildTagConfigJson(tc));
                return;
            }
        }

        // Routes with zone ID: /zones/:id, /zones/:id/members, /zones/:id/members/:uuid
        if (route.startsWith("/zones/")) {
            String rest = route.substring(7); // after "/zones/"
            int slashIdx = rest.indexOf('/');
            String id = slashIdx > 0 ? rest.substring(0, slashIdx) : rest;
            String sub = slashIdx > 0 ? rest.substring(slashIdx) : "";

            if (!isValidId(id)) {
                WebDashboard.respond(ex, 400, "{\"error\":\"Invalid zone ID\"}");
                return;
            }

            // ── State endpoints ───────────────────────────────────────────────

            if (sub.startsWith("/states")) {
                var stateMgr = mod.getZoneStateManager();
                String stateRest = sub.length() > 7 ? sub.substring(7) : ""; // after "/states"

                // POST /zones/:id/states/cancel — cancel running animation
                if (stateRest.equals("/cancel") && method.equals("POST")) {
                    boolean cancelled = stateMgr.cancelAnimation(id);
                    JsonObject result = new JsonObject();
                    result.addProperty("cancelled", cancelled);
                    WebDashboard.respondJson(ex, result);
                    return;
                }

                // GET /zones/:id/states — list all states (+animating flag)
                if (stateRest.isEmpty() && method.equals("GET")) {
                    var states = stateMgr.listStates(id);
                    JsonArray arr = new JsonArray();
                    for (var s : states) {
                        JsonObject so = new JsonObject();
                        so.addProperty("id", s.getId());
                        so.addProperty("name", s.getName());
                        so.addProperty("createdAt", s.getCreatedAt());
                        so.addProperty("blockCount", s.getBlockCount());
                        so.addProperty("entityCount", s.getEntityCount());
                        arr.add(so);
                    }
                    JsonObject result = new JsonObject();
                    result.add("states", arr);
                    result.addProperty("animating", stateMgr.isAnimating(id));
                    WebDashboard.respondJson(ex, result);
                    return;
                }

                // POST /zones/:id/states — capture new state
                if (stateRest.isEmpty() && method.equals("POST")) {
                    String body = WebDashboard.readBody(ex);
                    JsonObject data = JsonParser.parseString(body).getAsJsonObject();
                    String name = data.has("name") ? data.get("name").getAsString() : "State";
                    try {
                        final String captureName = name;
                        final com.cheeseguard.data.ZoneState[] result = {null};
                        WebDashboard.executeOnServerThread(server, () -> {
                            result[0] = stateMgr.capture(id, captureName);
                        });
                        if (result[0] == null) {
                            WebDashboard.respond(ex, 500, "{\"error\":\"Failed to capture state\"}");
                            return;
                        }
                        JsonObject so = new JsonObject();
                        so.addProperty("id", result[0].getId());
                        so.addProperty("name", result[0].getName());
                        so.addProperty("createdAt", result[0].getCreatedAt());
                        so.addProperty("blockCount", result[0].getBlockCount());
                        so.addProperty("entityCount", result[0].getEntityCount());
                        WebDashboard.respondJson(ex, so);
                    } catch (Exception e) {
                        WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    }
                    return;
                }

                // Routes with stateId: /states/{stateId} or /states/{stateId}/apply
                if (stateRest.startsWith("/")) {
                    String stateRoute = stateRest.substring(1);
                    int stateSlash = stateRoute.indexOf('/');
                    String stateId = stateSlash > 0 ? stateRoute.substring(0, stateSlash) : stateRoute;
                    String stateSub = stateSlash > 0 ? stateRoute.substring(stateSlash) : "";

                    if (!isValidId(stateId)) {
                        WebDashboard.respond(ex, 400, "{\"error\":\"Invalid state ID\"}");
                        return;
                    }

                    // PUT /zones/:id/states/:stateId — rename
                    if (stateSub.isEmpty() && method.equals("PUT")) {
                        String body = WebDashboard.readBody(ex);
                        JsonObject data = JsonParser.parseString(body).getAsJsonObject();
                        String newName = data.has("name") ? data.get("name").getAsString() : null;
                        if (newName == null || newName.isBlank()) {
                            WebDashboard.respond(ex, 400, "{\"error\":\"Name required\"}");
                            return;
                        }
                        if (stateMgr.renameState(id, stateId, newName)) {
                            WebDashboard.respond(ex, 200, "{\"renamed\":true}");
                        } else {
                            WebDashboard.respond(ex, 404, "{\"error\":\"State not found\"}");
                        }
                        return;
                    }

                    // DELETE /zones/:id/states/:stateId — delete
                    if (stateSub.isEmpty() && method.equals("DELETE")) {
                        if (stateMgr.deleteState(id, stateId)) {
                            WebDashboard.respond(ex, 200, "{\"deleted\":true}");
                        } else {
                            WebDashboard.respond(ex, 404, "{\"error\":\"State not found\"}");
                        }
                        return;
                    }

                    // POST /zones/:id/states/:stateId/apply — apply state
                    if (stateSub.equals("/apply") && method.equals("POST")) {
                        String body = WebDashboard.readBody(ex);
                        JsonObject data = JsonParser.parseString(body).getAsJsonObject();
                        boolean animated = data.has("animated") && data.get("animated").getAsBoolean();
                        String dirStr = data.has("direction") ? data.get("direction").getAsString() : "bottom_up";
                        int speed = data.has("ticksPerLayer") ? data.get("ticksPerLayer").getAsInt() : 2;
                        String particleStr = data.has("particle") ? data.get("particle").getAsString() : "cloud";

                        boolean blockByBlock = data.has("blockByBlock") && data.get("blockByBlock").getAsBoolean();
                        var dir = com.cheeseguard.logic.ZoneStateManager.Direction.fromString(dirStr);
                        var particle = com.cheeseguard.logic.ZoneStateManager.StateParticle.fromString(particleStr);

                        try {
                            final String[] error = {null};
                            WebDashboard.executeOnServerThread(server, () -> {
                                error[0] = stateMgr.applyState(id, stateId, animated, dir, speed, particle, blockByBlock, null);
                            });
                            if (error[0] != null) {
                                WebDashboard.respond(ex, 400, "{\"error\":\"" + escapeJson(error[0]) + "\"}");
                            } else {
                                JsonObject result = new JsonObject();
                                result.addProperty("applied", true);
                                result.addProperty("animated", animated);
                                WebDashboard.respondJson(ex, result);
                            }
                        } catch (Exception e) {
                            WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                        }
                        return;
                    }
                }

                WebDashboard.respond(ex, 404, "{\"error\":\"State route not found\"}");
                return;
            }

            // ── Sale endpoints ────────────────────────────────────────────────

            // PUT /zones/:id/sale — set/update sale config
            if (sub.equals("/sale") && method.equals("PUT")) {
                var r = storage.getRegion(id);
                if (r == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Zone not found\"}"); return; }
                String body = WebDashboard.readBody(ex);
                JsonObject data = JsonParser.parseString(body).getAsJsonObject();
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        com.cheeseguard.data.SaleConfig sc = r.getSaleConfig();
                        if (sc == null) { sc = new com.cheeseguard.data.SaleConfig(); r.setSaleConfig(sc); }
                        if (data.has("enabled")) sc.setEnabled(data.get("enabled").getAsBoolean());
                        if (data.has("price")) sc.setPrice(data.get("price").getAsLong());
                        if (data.has("currency")) sc.setCurrency(data.get("currency").getAsString());
                        if (data.has("itemId")) sc.setItemId(data.get("itemId").isJsonNull() ? null : data.get("itemId").getAsString());
                        if (data.has("itemCount")) sc.setItemCount(data.get("itemCount").getAsInt());
                        if (data.has("signText") && data.get("signText").isJsonArray()) {
                            sc.setSignText(jsonArrayToList(data.getAsJsonArray("signText")));
                        }
                        if (data.has("signLine1")) sc.setSignLine1(data.get("signLine1").isJsonNull() ? null : data.get("signLine1").getAsString());
                        if (data.has("signLine2")) sc.setSignLine2(data.get("signLine2").isJsonNull() ? null : data.get("signLine2").getAsString());
                        if (data.has("signType")) sc.setSignType(data.get("signType").isJsonNull() ? "oak" : data.get("signType").getAsString());
                        storage.save();
                        // Update hologram text if one exists
                        if (sc.getHologramUuid() != null) {
                            com.cheeseguard.logic.SaleManager.updateHologramText(r, server);
                        }
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respondJson(ex, buildSaleConfigJson(r.getSaleConfig()));
                return;
            }

            // DELETE /zones/:id/sale — remove sale config
            if (sub.equals("/sale") && method.equals("DELETE")) {
                var r = storage.getRegion(id);
                if (r == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Zone not found\"}"); return; }
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        if (r.getSaleConfig() != null && r.getSaleConfig().getHologramUuid() != null) {
                            com.cheeseguard.logic.SaleManager.removeHologram(r, server);
                        }
                        r.setSaleConfig(null);
                        storage.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"deleted\":true}");
                return;
            }

            // POST /zones/:id/sale/hologram — spawn hologram
            if (sub.equals("/sale/hologram") && method.equals("POST")) {
                var r = storage.getRegion(id);
                if (r == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Zone not found\"}"); return; }
                if (r.getSaleConfig() == null || !r.getSaleConfig().isEnabled()) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"Zone is not configured for sale\"}");
                    return;
                }
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        com.cheeseguard.logic.SaleManager.spawnHologram(r, server);
                        storage.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"success\":true,\"hasHologram\":true}");
                return;
            }

            // DELETE /zones/:id/sale/hologram — remove hologram
            if (sub.equals("/sale/hologram") && method.equals("DELETE")) {
                var r = storage.getRegion(id);
                if (r == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Zone not found\"}"); return; }
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        com.cheeseguard.logic.SaleManager.removeHologram(r, server);
                        storage.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"deleted\":true}");
                return;
            }

            // POST /zones/:id/sale/sign — give sale sign to admin
            if (sub.equals("/sale/sign") && method.equals("POST")) {
                var r = storage.getRegion(id);
                if (r == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Zone not found\"}"); return; }
                if (r.getSaleConfig() == null || !r.getSaleConfig().isEnabled()) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"Zone is not configured for sale\"}");
                    return;
                }
                String signQuery = ex.getRequestURI().getQuery();
                String targetPlayer = null;
                if (signQuery != null) {
                    for (String param : signQuery.split("&")) {
                        if (param.startsWith("player=")) {
                            targetPlayer = java.net.URLDecoder.decode(param.substring(7), java.nio.charset.StandardCharsets.UTF_8);
                        }
                    }
                }
                if (targetPlayer == null || targetPlayer.isEmpty()) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"Missing ?player=<name> parameter\"}");
                    return;
                }
                final String fTarget = targetPlayer;
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        var sp = server.getPlayerManager().getPlayer(fTarget);
                        if (sp == null) throw new RuntimeException("Player not online: " + fTarget);
                        var sign = com.cheeseguard.logic.SaleManager.createSaleSign(r, server);
                        sp.getInventory().offerOrDrop(sign);
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"success\":true}");
                return;
            }

            // POST /zones/:id/sale/relist — re-list a sold zone
            if (sub.equals("/sale/relist") && method.equals("POST")) {
                var r = storage.getRegion(id);
                if (r == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Zone not found\"}"); return; }
                if (r.getSaleConfig() == null) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"Zone has no sale config\"}");
                    return;
                }
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        r.getSaleConfig().setSold(false);
                        r.getSaleConfig().setBuyerUuid(null);
                        r.getSaleConfig().setBuyerName(null);
                        r.getSaleConfig().setEnabled(true);
                        if (r.getSaleConfig().getHologramUuid() != null) {
                            com.cheeseguard.logic.SaleManager.updateHologramText(r, server);
                        }
                        storage.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respondJson(ex, buildSaleConfigJson(r.getSaleConfig()));
                return;
            }

            // ── End sale endpoints ────────────────────────────────────────────

            // GET /zones/:id — detail
            if (sub.isEmpty() && method.equals("GET")) {
                var r = storage.getRegion(id);
                if (r == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Zone not found\"}"); return; }
                WebDashboard.respondJson(ex, buildZoneDetail(r));
                return;
            }

            // PUT /zones/:id — update
            if (sub.isEmpty() && method.equals("PUT")) {
                var r = storage.getRegion(id);
                if (r == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Zone not found\"}"); return; }
                String body = WebDashboard.readBody(ex);
                JsonObject data = JsonParser.parseString(body).getAsJsonObject();

                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        if (data.has("name") && !data.get("name").isJsonNull()) {
                            r.setName(data.get("name").getAsString());
                        }
                        if (data.has("priority")) {
                            r.setPriority(data.get("priority").getAsInt());
                        }
                        if (data.has("groupId")) {
                            String gid = data.get("groupId").isJsonNull() ? null : data.get("groupId").getAsString();
                            if (gid != null && gid.isEmpty()) gid = null;
                            String oldGroupId = r.getGroupId();
                            if (oldGroupId != null && !oldGroupId.equals(gid)) {
                                var oldGroup = storage.getGroup(oldGroupId);
                                if (oldGroup != null) oldGroup.removeRegion(id);
                            }
                            r.setGroupId(gid);
                            if (gid != null) {
                                var newGroup = storage.getGroup(gid);
                                if (newGroup != null) newGroup.addRegion(id);
                            }
                        }
                        if (data.has("parentId")) {
                            String pid = data.get("parentId").isJsonNull() ? null : data.get("parentId").getAsString();
                            r.setParentId(pid != null && pid.isEmpty() ? null : pid);
                        }
                        if (data.has("flags") && data.get("flags").isJsonObject()) {
                            JsonObject flagsObj = data.getAsJsonObject("flags");
                            for (Map.Entry<String, JsonElement> entry : flagsObj.entrySet()) {
                                Flag flag = Flag.fromId(entry.getKey());
                                if (flag == null) continue;
                                String val = entry.getValue().getAsString();
                                switch (val.toUpperCase()) {
                                    case "ALLOW": r.setFlag(flag, true); break;
                                    case "DENY":  r.setFlag(flag, false); break;
                                    case "INHERIT": r.setFlag(flag, null); break;
                                }
                            }
                        }
                        // Entry/Exit messages
                        if (data.has("entryMessageEnabled")) r.setEntryMessageEnabled(data.get("entryMessageEnabled").getAsBoolean());
                        if (data.has("entryMessage")) r.setEntryMessage(data.get("entryMessage").getAsString());
                        if (data.has("exitMessageEnabled")) r.setExitMessageEnabled(data.get("exitMessageEnabled").getAsBoolean());
                        if (data.has("exitMessage")) r.setExitMessage(data.get("exitMessage").getAsString());
                        if (data.has("protectionMessage")) r.setProtectionMessage(data.get("protectionMessage").getAsString());
                        // Particle color
                        if (data.has("particleColor")) r.setParticleColor(data.get("particleColor").getAsString());
                        // Advancement requirements
                        if (data.has("requiredAdvancementsEntry")) r.setRequiredAdvancementsEntry(jsonArrayToList(data.getAsJsonArray("requiredAdvancementsEntry")));
                        if (data.has("requiredAdvancementsExit")) r.setRequiredAdvancementsExit(jsonArrayToList(data.getAsJsonArray("requiredAdvancementsExit")));
                        if (data.has("advancementDeniedMessage")) r.setAdvancementDeniedMessage(data.get("advancementDeniedMessage").getAsString());
                        // Quest requirements
                        if (data.has("requiredQuestsEntry")) r.setRequiredQuestsEntry(jsonArrayToList(data.getAsJsonArray("requiredQuestsEntry")));
                        if (data.has("requiredQuestsExit")) r.setRequiredQuestsExit(jsonArrayToList(data.getAsJsonArray("requiredQuestsExit")));
                        // Command triggers
                        if (data.has("commandsOnEnter")) r.setCommandsOnEnter(jsonArrayToList(data.getAsJsonArray("commandsOnEnter")));
                        if (data.has("commandsOnExit")) r.setCommandsOnExit(jsonArrayToList(data.getAsJsonArray("commandsOnExit")));
                        if (data.has("commandsOnBattleLoss")) r.setCommandsOnBattleLoss(jsonArrayToList(data.getAsJsonArray("commandsOnBattleLoss")));
                        // Tags
                        if (data.has("tags") && data.get("tags").isJsonArray()) {
                            r.setTags(jsonArrayToList(data.getAsJsonArray("tags")));
                        }
                        storage.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respondJson(ex, buildZoneDetail(r));
                return;
            }

            // DELETE /zones/:id
            if (sub.isEmpty() && method.equals("DELETE")) {
                var r = storage.getRegion(id);
                if (r == null) {
                    WebDashboard.respond(ex, 404, "{\"error\":\"Zone not found\"}");
                    return;
                }
                // Refuse if this zone is the active prison zone with prisoners inside.
                var pm = mod.getPrisonManager();
                if (pm != null && id.equals(pm.getPrisonZoneId()) && !pm.getActive().isEmpty()) {
                    WebDashboard.respond(ex, 409,
                        "{\"error\":\"This zone is the active prison and has " + pm.getActive().size()
                        + " prisoner(s). Release them or change the prison zone first.\"}");
                    return;
                }
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        // Clean up group reference before removing
                        String groupId = r.getGroupId();
                        if (groupId != null) {
                            var group = storage.getGroup(groupId);
                            if (group != null) group.removeRegion(id);
                        }
                        storage.removeRegion(id);
                        storage.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"deleted\":true}");
                return;
            }

            // GET /zones/:id/members — includes owner (role OWNER) + members (role MEMBER)
            if (sub.equals("/members") && method.equals("GET")) {
                var r = storage.getRegion(id);
                if (r == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Zone not found\"}"); return; }
                JsonArray arr = new JsonArray();
                // Owner first (if exists)
                if (r.getOwner() != null) {
                    JsonObject ow = new JsonObject();
                    ow.addProperty("uuid", r.getOwner().getUuid().toString());
                    ow.addProperty("name", r.getOwner().getName());
                    ow.addProperty("role", "OWNER");
                    arr.add(ow);
                }
                // Then members
                for (Member m : r.getMembers()) {
                    JsonObject mo = new JsonObject();
                    mo.addProperty("uuid", m.getUuid().toString());
                    mo.addProperty("name", m.getName());
                    mo.addProperty("role", "MEMBER");
                    arr.add(mo);
                }
                WebDashboard.respondJson(ex, arr);
                return;
            }

            // POST /zones/:id/members — body: {playerName}
            if (sub.equals("/members") && method.equals("POST")) {
                var r = storage.getRegion(id);
                if (r == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Zone not found\"}"); return; }
                String body = WebDashboard.readBody(ex);
                JsonObject data = JsonParser.parseString(body).getAsJsonObject();
                if (!data.has("playerName") || data.get("playerName").getAsString().isEmpty()) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"playerName is required\"}");
                    return;
                }
                String playerName = data.get("playerName").getAsString().trim();

                // Resolve UUID via server user cache
                var profile = server.getUserCache().findByName(playerName);
                if (profile.isEmpty()) {
                    WebDashboard.respond(ex, 404, "{\"error\":\"Player not found: " + escapeJson(playerName) + "\"}");
                    return;
                }
                UUID uuid = profile.get().getId();
                String resolvedName = profile.get().getName();

                // Check if already a member
                if (r.isMember(uuid)) {
                    WebDashboard.respond(ex, 409, "{\"error\":\"Player is already a member\"}");
                    return;
                }

                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        r.addMember(new Member(uuid, resolvedName));
                        storage.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                JsonObject result = new JsonObject();
                result.addProperty("uuid", uuid.toString());
                result.addProperty("name", resolvedName);
                result.addProperty("added", true);
                WebDashboard.respondJson(ex, result);
                return;
            }

            // DELETE /zones/:id/members/:uuid — cannot remove owner via this endpoint
            if (sub.startsWith("/members/") && method.equals("DELETE")) {
                var r = storage.getRegion(id);
                if (r == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Zone not found\"}"); return; }
                String uuidStr = sub.substring("/members/".length());
                UUID uuid;
                try {
                    uuid = UUID.fromString(uuidStr);
                } catch (IllegalArgumentException e) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"Invalid UUID\"}");
                    return;
                }
                // Prevent removing the owner via this endpoint
                if (r.getOwner() != null && r.getOwner().getUuid().equals(uuid)) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"Cannot remove owner via members endpoint. Use DELETE /zones/:id/owner instead.\"}");
                    return;
                }
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        r.removeMember(uuid);
                        storage.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"removed\":true}");
                return;
            }

            // PUT /zones/:id/owner — transfer ownership
            if (sub.equals("/owner") && method.equals("PUT")) {
                var r = storage.getRegion(id);
                if (r == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Zone not found\"}"); return; }
                String body = WebDashboard.readBody(ex);
                JsonObject data = JsonParser.parseString(body).getAsJsonObject();
                if (!data.has("playerName") || data.get("playerName").getAsString().isEmpty()) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"playerName is required\"}");
                    return;
                }
                String playerName = data.get("playerName").getAsString().trim();

                // Resolve UUID via server user cache
                var profile = server.getUserCache().findByName(playerName);
                if (profile.isEmpty()) {
                    WebDashboard.respond(ex, 404, "{\"error\":\"Player not found: " + escapeJson(playerName) + "\"}");
                    return;
                }
                UUID newOwnerUuid = profile.get().getId();
                String resolvedName = profile.get().getName();

                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        r.transferOwnership(new Member(newOwnerUuid, resolvedName, Member.Role.OWNER));
                        storage.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                JsonObject result = new JsonObject();
                result.addProperty("uuid", newOwnerUuid.toString());
                result.addProperty("name", resolvedName);
                result.addProperty("transferred", true);
                WebDashboard.respondJson(ex, result);
                return;
            }

            // DELETE /zones/:id/owner — remove owner (zone becomes ownerless)
            if (sub.equals("/owner") && method.equals("DELETE")) {
                var r = storage.getRegion(id);
                if (r == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Zone not found\"}"); return; }
                if (r.getOwner() == null) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"Zone has no owner\"}");
                    return;
                }
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        r.removeOwner();
                        storage.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"removed\":true}");
                return;
            }

            // POST /zones/:id/replace-selection — replace zone coords from wand selection
            if (sub.equals("/replace-selection") && method.equals("POST")) {
                var r = storage.getRegion(id);
                if (r == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Zone not found\"}"); return; }

                String body = WebDashboard.readBody(ex);
                JsonObject data = JsonParser.parseString(body).getAsJsonObject();

                // Resolve player name → UUID for selection lookup
                // If no playerName provided, use the first online op player
                String playerName = data.has("playerName") && !data.get("playerName").getAsString().isEmpty()
                    ? data.get("playerName").getAsString()
                    : null;

                if (playerName == null && server.getPlayerManager() != null) {
                    // Default to first online op player
                    for (var p : server.getPlayerManager().getPlayerList()) {
                        if (p.hasPermissionLevel(2)) {
                            playerName = p.getName().getString();
                            break;
                        }
                    }
                }

                if (playerName == null || playerName.isEmpty()) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"No playerName provided and no op player online\"}");
                    return;
                }

                // Try online player first
                UUID playerUuid = null;
                if (server.getPlayerManager() != null) {
                    var onlinePlayer = server.getPlayerManager().getPlayer(playerName);
                    if (onlinePlayer != null) {
                        playerUuid = onlinePlayer.getUuid();
                    }
                }
                // Fallback to user cache
                if (playerUuid == null) {
                    var profile = server.getUserCache().findByName(playerName);
                    if (profile.isPresent()) {
                        playerUuid = profile.get().getId();
                    }
                }
                if (playerUuid == null) {
                    WebDashboard.respond(ex, 404, "{\"error\":\"Player not found: " + escapeJson(playerName) + "\"}");
                    return;
                }

                var selection = mod.getSelectionManager().getSelection(playerUuid);
                if (selection == null || !selection.isComplete()) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"No complete wand selection for player " + escapeJson(playerName) + "\"}");
                    return;
                }

                int x1 = Math.min(selection.pos1.getX(), selection.pos2.getX());
                int y1 = Math.min(selection.pos1.getY(), selection.pos2.getY());
                int z1 = Math.min(selection.pos1.getZ(), selection.pos2.getZ());
                int x2 = Math.max(selection.pos1.getX(), selection.pos2.getX());
                int y2 = Math.max(selection.pos1.getY(), selection.pos2.getY());
                int z2 = Math.max(selection.pos1.getZ(), selection.pos2.getZ());
                String selWorld = selection.world;

                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        r.setBounds(x1, y1, z1, x2, y2, z2);
                        r.setWorld(selWorld);
                        storage.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }

                JsonObject result = new JsonObject();
                result.addProperty("replaced", true);
                result.addProperty("world", selWorld);
                result.addProperty("minX", x1); result.addProperty("minY", y1); result.addProperty("minZ", z1);
                result.addProperty("maxX", x2); result.addProperty("maxY", y2); result.addProperty("maxZ", z2);
                WebDashboard.respondJson(ex, result);
                return;
            }
        }

        WebDashboard.respond(ex, 404, "{\"error\":\"Route not found\"}");
    }

    // ── JSON Builders ─────────────────────────────────────────────────────────

    private static JsonObject buildZoneSummary(Region r) {
        JsonObject o = new JsonObject();
        o.addProperty("id",       r.getId());
        o.addProperty("name",     r.getName());
        o.addProperty("world",    r.getWorld());
        o.addProperty("minX", r.getMinX()); o.addProperty("minY", r.getMinY()); o.addProperty("minZ", r.getMinZ());
        o.addProperty("maxX", r.getMaxX()); o.addProperty("maxY", r.getMaxY()); o.addProperty("maxZ", r.getMaxZ());
        o.addProperty("priority", r.getPriority());
        o.addProperty("groupId",  r.getGroupId() != null ? r.getGroupId() : "");
        o.addProperty("members",  r.getMembers().size());
        // Tags
        JsonArray tagsArr = new JsonArray();
        r.getTags().forEach(tagsArr::add);
        o.add("tags", tagsArr);
        // Owner name for summary display
        if (r.getOwner() != null) {
            o.addProperty("ownerName", r.getOwner().getName());
        }
        // Sale status
        com.cheeseguard.data.SaleConfig sc = r.getSaleConfig();
        if (sc != null && sc.isEnabled()) {
            o.addProperty("forSale", !sc.isSold());
            o.addProperty("sold", sc.isSold());
            o.addProperty("salePrice", sc.getPrice());
            o.addProperty("saleCurrency", sc.getCurrency());
            if (sc.isSold() && sc.getBuyerName() != null) {
                o.addProperty("buyerName", sc.getBuyerName());
            }
        } else {
            o.addProperty("forSale", false);
            o.addProperty("sold", false);
        }
        return o;
    }

    private static JsonObject buildZoneDetail(Region r) {
        JsonObject o = buildZoneSummary(r);
        // Flags — all 3-state values
        JsonObject flags = new JsonObject();
        for (Flag f : Flag.values()) {
            Boolean v = r.getFlag(f);
            String state = v == null ? "INHERIT" : (v ? "ALLOW" : "DENY");
            flags.addProperty(f.getId(), state);
        }
        o.add("flags", flags);
        // Members list (includes role)
        JsonArray membersArr = new JsonArray();
        for (Member m : r.getMembers()) {
            JsonObject mo = new JsonObject();
            mo.addProperty("uuid", m.getUuid().toString());
            mo.addProperty("name", m.getName());
            mo.addProperty("role", "MEMBER");
            membersArr.add(mo);
        }
        o.add("memberList", membersArr);
        // Owner (includes role)
        if (r.getOwner() != null) {
            JsonObject ow = new JsonObject();
            ow.addProperty("uuid", r.getOwner().getUuid().toString());
            ow.addProperty("name", r.getOwner().getName());
            ow.addProperty("role", "OWNER");
            o.add("owner", ow);
        }
        // Parent zone
        o.addProperty("parentId", r.getParentId() != null ? r.getParentId() : "");
        // Entry/Exit messages
        o.addProperty("entryMessageEnabled", r.isEntryMessageEnabled());
        o.addProperty("entryMessage", r.getEntryMessage() != null ? r.getEntryMessage() : "");
        o.addProperty("exitMessageEnabled", r.isExitMessageEnabled());
        o.addProperty("exitMessage", r.getExitMessage() != null ? r.getExitMessage() : "");
        o.addProperty("protectionMessage", r.getProtectionMessage() != null ? r.getProtectionMessage() : "");
        // Particle color
        o.addProperty("particleColor", r.getParticleColor() != null ? r.getParticleColor() : "green");
        // Advancement requirements
        o.add("requiredAdvancementsEntry", toJsonArray(r.getRequiredAdvancementsEntry()));
        o.add("requiredAdvancementsExit", toJsonArray(r.getRequiredAdvancementsExit()));
        o.addProperty("advancementDeniedMessage", r.getAdvancementDeniedMessage() != null ? r.getAdvancementDeniedMessage() : "");
        // Quest requirements
        o.add("requiredQuestsEntry", toJsonArray(r.getRequiredQuestsEntry()));
        o.add("requiredQuestsExit", toJsonArray(r.getRequiredQuestsExit()));
        // Command triggers
        o.add("commandsOnEnter", toJsonArray(r.getCommandsOnEnter()));
        o.add("commandsOnExit", toJsonArray(r.getCommandsOnExit()));
        o.add("commandsOnBattleLoss", toJsonArray(r.getCommandsOnBattleLoss()));
        // Full sale config
        if (r.getSaleConfig() != null) {
            o.add("saleConfig", buildSaleConfigJson(r.getSaleConfig()));
        }
        return o;
    }

    private static JsonArray toJsonArray(java.util.List<String> list) {
        JsonArray arr = new JsonArray();
        if (list != null) list.forEach(arr::add);
        return arr;
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private static java.util.List<String> jsonArrayToList(JsonArray arr) {
        java.util.List<String> list = new java.util.ArrayList<>();
        if (arr != null) arr.forEach(el -> list.add(el.getAsString()));
        return list;
    }

    private static boolean isValidId(String id) {
        return id != null && !id.isEmpty() && !id.contains("..") && !id.contains("/")
            && !id.contains("\\") && !id.contains("%") && id.length() < 128;
    }

    private static String escapeJson(String s) {
        if (s == null) return "Unknown error";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static JsonObject buildSaleConfigJson(com.cheeseguard.data.SaleConfig sc) {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", sc.isEnabled());
        o.addProperty("price", sc.getPrice());
        o.addProperty("currency", sc.getCurrency());
        if (sc.getItemId() != null) o.addProperty("itemId", sc.getItemId());
        o.addProperty("itemCount", sc.getItemCount());
        JsonArray st = new JsonArray();
        sc.getSignText().forEach(st::add);
        o.add("signText", st);
        o.addProperty("signLine1", sc.getSignLine1() != null ? sc.getSignLine1() : "");
        o.addProperty("signLine2", sc.getSignLine2() != null ? sc.getSignLine2() : "");
        o.addProperty("signType", sc.getSignType());
        o.addProperty("sold", sc.isSold());
        if (sc.getBuyerUuid() != null) o.addProperty("buyerUuid", sc.getBuyerUuid());
        if (sc.getBuyerName() != null) o.addProperty("buyerName", sc.getBuyerName());
        o.addProperty("hasHologram", sc.getHologramUuid() != null);
        return o;
    }

    private static JsonObject buildTagConfigJson(com.cheeseguard.data.TagConfig tc) {
        JsonObject o = new JsonObject();
        o.addProperty("id", tc.getId());
        o.addProperty("label", tc.getLabel());
        o.addProperty("color", tc.getColor());
        o.addProperty("displayColor", tc.getDisplayColor());
        o.addProperty("ownershipLimit", tc.getOwnershipLimit());
        return o;
    }
}
