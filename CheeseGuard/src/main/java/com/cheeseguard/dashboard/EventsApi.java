package com.cheeseguard.dashboard;

import com.cheesedash.WebDashboard;
import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.*;
import com.cheeseguard.logic.EventManager;
import com.cheeseguard.logic.EventTemplate;
import com.cheeseguard.logic.MiniGameManager;
import com.cheeseguard.logic.PropHuntIconStorage;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.util.*;

/** /api/v1/cheeseguard/events — full CRUD for events */
public class EventsApi {

    public static void handle(HttpExchange ex, CheeseGuardMod mod, String route) throws IOException {
        String method = ex.getRequestMethod();
        EventManager em = mod.getEventManager();
        if (em == null) {
            WebDashboard.respond(ex, 503, "{\"error\":\"EventManager unavailable\"}");
            return;
        }
        MinecraftServer server = mod.getServer();

        // ── GET /events — list all ──────────────────────────────────────────
        if (route.equals("/events") && method.equals("GET")) {
            JsonArray arr = new JsonArray();
            for (ServerEvent evt : em.getEvents()) {
                arr.add(buildEventSummary(evt));
            }
            WebDashboard.respondJson(ex, arr);
            return;
        }

        // ── POST /events — create ───────────────────────────────────────────
        if (route.equals("/events") && method.equals("POST")) {
            String body = WebDashboard.readBody(ex);
            JsonObject data = JsonParser.parseString(body).getAsJsonObject();
            if (data.has("templateName")) {
                String tName = data.get("templateName").getAsString();
                final ServerEvent[] loaded = {null};
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        loaded[0] = EventTemplate.load(server, tName);
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                if (loaded[0] == null) {
                    WebDashboard.respond(ex, 404, "{\"error\":\"Template not found: " + escapeJson(tName) + "\"}");
                    return;
                }
                WebDashboard.respondJson(ex, buildEventDetail(loaded[0]));
                return;
            }
            String name = data.has("name") ? data.get("name").getAsString().trim() : "Nouvel evenement";
            if (name.isEmpty()) name = "Nouvel evenement";
            String finalName = name;
            final ServerEvent[] created = {null};
            try {
                WebDashboard.executeOnServerThread(server, () -> {
                    created[0] = em.createEvent(finalName);
                    em.save();
                });
            } catch (Exception e) {
                WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                return;
            }
            if (created[0] == null) {
                WebDashboard.respond(ex, 500, "{\"error\":\"Failed to create event\"}");
                return;
            }
            WebDashboard.respondJson(ex, buildEventDetail(created[0]));
            return;
        }

        // ── GET /events/templates — list templates ──────────────────────────
        if (route.equals("/events/templates") && method.equals("GET")) {
            JsonArray arr = new JsonArray();
            List<String> names = EventTemplate.list(server);
            for (String name : names) {
                JsonObject o = new JsonObject();
                o.addProperty("name", name);
                arr.add(o);
            }
            WebDashboard.respondJson(ex, arr);
            return;
        }

        // ── GET /events/minigame-types — list all mini-game types ─────────
        if (route.equals("/events/minigame-types") && method.equals("GET")) {
            JsonArray arr = new JsonArray();
            for (MiniGameManager.MiniGameType t : MiniGameManager.getAllTypes()) {
                JsonObject o = new JsonObject();
                o.addProperty("id", t.name());
                o.addProperty("displayName", t.displayName);
                o.addProperty("description", t.description);
                o.addProperty("icon", t.icon);
                o.addProperty("enabled", t.enabled);
                arr.add(o);
            }
            WebDashboard.respondJson(ex, arr);
            return;
        }

        // ── PropHunt icons ──────────────────────────────────────────────
        if (route.equals("/events/prophunt/icons") && method.equals("GET")) {
            handlePropHuntIconList(ex);
            return;
        }
        if (route.startsWith("/events/prophunt/icons/")) {
            String key = route.substring("/events/prophunt/icons/".length());
            if (key.contains("/")) key = key.substring(0, key.indexOf('/'));
            if (!isValidIconKey(key)) { WebDashboard.respond(ex, 400, "{\"error\":\"Invalid key\"}"); return; }
            switch (method) {
                case "GET" -> handlePropHuntIconServe(ex, key);
                case "POST" -> handlePropHuntIconUpload(ex, key);
                case "DELETE" -> handlePropHuntIconDelete(ex, key);
                default -> WebDashboard.respond(ex, 405, "{\"error\":\"Method not allowed\"}");
            }
            return;
        }

        // ── Routes with template name: /events/templates/:name/... ──────────
        if (route.startsWith("/events/templates/")) {
            String tRest = route.substring("/events/templates/".length());
            int tSlash = tRest.indexOf('/');
            String tName = tSlash > 0 ? tRest.substring(0, tSlash) : tRest;
            String tSub = tSlash > 0 ? tRest.substring(tSlash) : "";

            if (!isValidId(tName)) {
                WebDashboard.respond(ex, 400, "{\"error\":\"Invalid template name\"}");
                return;
            }

            // POST /events/templates/:name/load
            if (tSub.equals("/load") && method.equals("POST")) {
                final ServerEvent[] loaded = {null};
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        ServerEvent evt = EventTemplate.load(server, tName);
                        if (evt != null) {
                            em.getEvents().add(evt);
                            em.save();
                        }
                        loaded[0] = evt;
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                if (loaded[0] == null) {
                    WebDashboard.respond(ex, 404, "{\"error\":\"Template not found\"}");
                    return;
                }
                WebDashboard.respondJson(ex, buildEventDetail(loaded[0]));
                return;
            }

            // DELETE /events/templates/:name
            if (tSub.isEmpty() && method.equals("DELETE")) {
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        boolean ok = EventTemplate.delete(server, tName);
                        if (!ok) throw new RuntimeException("Template not found or delete failed");
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"deleted\":true}");
                return;
            }
        }

        // GET /events/launch-config
        if (route.equals("/events/launch-config") && method.equals("GET")) {
            WebDashboard.respondJson(ex, em.getLaunchConfig().toJson());
            return;
        }
        // PUT /events/launch-config
        if (route.equals("/events/launch-config") && method.equals("PUT")) {
            String body = WebDashboard.readBody(ex);
            JsonObject data = JsonParser.parseString(body).getAsJsonObject();
            em.getLaunchConfig().applyJson(data);
            em.saveLaunchConfig();
            WebDashboard.respondJson(ex, em.getLaunchConfig().toJson());
            return;
        }

        // ── Routes with event ID: /events/:id/... ───────────────────────────
        if (route.startsWith("/events/")) {
            String rest = route.substring("/events/".length());
            if (rest.startsWith("templates")) {
                WebDashboard.respond(ex, 404, "{\"error\":\"Route not found\"}");
                return;
            }
            int slashIdx = rest.indexOf('/');
            String id = slashIdx > 0 ? rest.substring(0, slashIdx) : rest;
            String sub = slashIdx > 0 ? rest.substring(slashIdx) : "";

            if (!isValidId(id)) {
                WebDashboard.respond(ex, 400, "{\"error\":\"Invalid event ID\"}");
                return;
            }

            ServerEvent evt = em.getEvent(id);
            if (evt == null) {
                WebDashboard.respond(ex, 404, "{\"error\":\"Event not found: " + escapeJson(id) + "\"}");
                return;
            }

            // GET /events/:id
            if (sub.isEmpty() && method.equals("GET")) {
                WebDashboard.respondJson(ex, buildEventDetail(evt));
                return;
            }

            // PUT /events/:id
            if (sub.isEmpty() && method.equals("PUT")) {
                String body = WebDashboard.readBody(ex);
                JsonObject data = JsonParser.parseString(body).getAsJsonObject();
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        if (data.has("name")) evt.setName(data.get("name").getAsString());
                        if (data.has("description")) evt.setDescription(data.get("description").getAsString());
                        if (data.has("displayColor")) evt.setDisplayColor(data.get("displayColor").getAsString());
                        if (data.has("mode")) evt.setMode(ServerEvent.EventMode.valueOf(data.get("mode").getAsString()));
                        if (data.has("trigger")) evt.setTrigger(ServerEvent.TriggerType.valueOf(data.get("trigger").getAsString()));
                        if (data.has("announceDurationSeconds")) evt.setAnnounceDurationSeconds(data.get("announceDurationSeconds").getAsInt());
                        if (data.has("countdownSeconds")) evt.setCountdownSeconds(data.get("countdownSeconds").getAsInt());
                        if (data.has("cooldownMinutes")) evt.setCooldownMinutes(data.get("cooldownMinutes").getAsInt());
                        if (data.has("playerLaunchable")) evt.setPlayerLaunchable(data.get("playerLaunchable").getAsBoolean());
                        if (data.has("minParticipants")) evt.setMinParticipants(data.get("minParticipants").getAsInt());
                        if (data.has("maxParticipants")) evt.setMaxParticipants(data.get("maxParticipants").getAsInt());
                        if (data.has("teamMode")) evt.setTeamMode(ServerEvent.TeamMode.valueOf(data.get("teamMode").getAsString()));
                        if (data.has("teamSize")) evt.setTeamSize(data.get("teamSize").getAsInt());
                        if (data.has("deathBehavior")) evt.setDeathBehavior(ServerEvent.DeathBehavior.valueOf(data.get("deathBehavior").getAsString()));
                        if (data.has("victoryCondition")) evt.setVictoryCondition(ServerEvent.VictoryCondition.valueOf(data.get("victoryCondition").getAsString()));
                        if (data.has("victoryTargetScore")) evt.setVictoryTargetScore(data.get("victoryTargetScore").getAsInt());
                        if (data.has("saveInventory")) evt.setSaveInventory(data.get("saveInventory").getAsBoolean());
                        if (data.has("forceEmptyInventory")) evt.setForceEmptyInventory(data.get("forceEmptyInventory").getAsBoolean());
                        if (data.has("disablePokemonSummon")) evt.setDisablePokemonSummon(data.get("disablePokemonSummon").getAsBoolean());
                        if (data.has("voiceChatGroup")) evt.setVoiceChatGroup(data.get("voiceChatGroup").getAsBoolean());
                        if (data.has("bossbarEnabled")) evt.setBossbarEnabled(data.get("bossbarEnabled").getAsBoolean());
                        if (data.has("bossbarColor")) evt.setBossbarColor(data.get("bossbarColor").getAsString());
                        if (data.has("discordNotify")) evt.setDiscordNotify(data.get("discordNotify").getAsBoolean());
                        if (data.has("announceMessage")) evt.setAnnounceMessage(data.get("announceMessage").getAsString());
                        if (data.has("startMessage")) evt.setStartMessage(data.get("startMessage").getAsString());
                        if (data.has("endMessage")) evt.setEndMessage(data.get("endMessage").getAsString());
                        if (data.has("tpParticipantX")) evt.setTpParticipantX(data.get("tpParticipantX").getAsDouble());
                        if (data.has("tpParticipantY")) evt.setTpParticipantY(data.get("tpParticipantY").getAsDouble());
                        if (data.has("tpParticipantZ")) evt.setTpParticipantZ(data.get("tpParticipantZ").getAsDouble());
                        if (data.has("tpParticipantWorld")) evt.setTpParticipantWorld(data.get("tpParticipantWorld").getAsString());
                        if (data.has("tpSpectatorX")) evt.setTpSpectatorX(data.get("tpSpectatorX").getAsDouble());
                        if (data.has("tpSpectatorY")) evt.setTpSpectatorY(data.get("tpSpectatorY").getAsDouble());
                        if (data.has("tpSpectatorZ")) evt.setTpSpectatorZ(data.get("tpSpectatorZ").getAsDouble());
                        if (data.has("tpSpectatorWorld")) evt.setTpSpectatorWorld(data.get("tpSpectatorWorld").getAsString());
                        if (data.has("tpParticipantZoneIds") && data.get("tpParticipantZoneIds").isJsonArray()) {
                            List<String> ids = new ArrayList<>();
                            for (var el : data.getAsJsonArray("tpParticipantZoneIds")) ids.add(el.getAsString());
                            evt.setTpParticipantZoneIds(ids);
                        }
                        if (data.has("tpSpectatorZoneIds") && data.get("tpSpectatorZoneIds").isJsonArray()) {
                            List<String> ids = new ArrayList<>();
                            for (var el : data.getAsJsonArray("tpSpectatorZoneIds")) ids.add(el.getAsString());
                            evt.setTpSpectatorZoneIds(ids);
                        }
                        if (data.has("useActSystem")) evt.setUseActSystem(data.get("useActSystem").getAsBoolean());
                        if (data.has("miniGameType")) evt.setMiniGameType(data.get("miniGameType").getAsString());
                        if (data.has("miniGameConfig") && data.get("miniGameConfig").isJsonObject()) {
                            evt.setMiniGameConfig(data.getAsJsonObject("miniGameConfig"));
                        }
                        if (data.has("scoreboardVisible")) evt.setScoreboardVisible(data.get("scoreboardVisible").getAsBoolean());
                        if (data.has("exclusionGroup")) evt.setExclusionGroup(data.get("exclusionGroup").getAsString());
                        if (data.has("returnMode")) evt.setReturnMode(data.get("returnMode").getAsString());
                        if (data.has("returnX")) evt.setReturnX(data.get("returnX").getAsDouble());
                        if (data.has("returnY")) evt.setReturnY(data.get("returnY").getAsDouble());
                        if (data.has("returnZ")) evt.setReturnZ(data.get("returnZ").getAsDouble());
                        if (data.has("returnWorld")) evt.setReturnWorld(data.get("returnWorld").getAsString());
                        if (data.has("lightningMobsOnEnd")) evt.setLightningMobsOnEnd(data.get("lightningMobsOnEnd").getAsBoolean());
                        if (data.has("scopeZoneIds")) {
                            List<String> zones = new ArrayList<>();
                            for (JsonElement el : data.getAsJsonArray("scopeZoneIds")) zones.add(el.getAsString());
                            evt.setScopeZoneIds(zones);
                        }
                        if (data.has("kitEntryCommands")) {
                            evt.getKitEntryCommands().clear();
                            for (JsonElement el : data.getAsJsonArray("kitEntryCommands")) evt.getKitEntryCommands().add(el.getAsString());
                        }
                        if (data.has("kitExitCommands")) {
                            evt.getKitExitCommands().clear();
                            for (JsonElement el : data.getAsJsonArray("kitExitCommands")) evt.getKitExitCommands().add(el.getAsString());
                        }
                        if (data.has("blacklistItems")) {
                            evt.getBlacklistItems().clear();
                            for (JsonElement el : data.getAsJsonArray("blacklistItems")) evt.getBlacklistItems().add(el.getAsString());
                        }
                        if (data.has("acts")) {
                            evt.getActs().clear();
                            for (JsonElement el : data.getAsJsonArray("acts")) {
                                try {
                                    evt.getActs().add(EventAct.fromJson(el.getAsJsonObject()));
                                } catch (Exception ignored) {}
                            }
                        }
                        em.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respondJson(ex, buildEventDetail(evt));
                return;
            }

            // DELETE /events/:id
            if (sub.isEmpty() && method.equals("DELETE")) {
                try {
                    WebDashboard.executeOnServerThread(server, () -> em.deleteEvent(id));
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"deleted\":true}");
                return;
            }

            // POST /events/:id/start
            if (sub.equals("/start") && method.equals("POST")) {
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        if (evt.isRunning()) throw new RuntimeException("Event already running");
                        em.startEventDirect(evt);
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respondJson(ex, buildEventDetail(evt));
                return;
            }

            // POST /events/:id/stop
            if (sub.equals("/stop") && method.equals("POST")) {
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        if (!evt.isRunning()) throw new RuntimeException("Event not running");
                        em.endEvent(evt);
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respondJson(ex, buildEventDetail(evt));
                return;
            }

            // POST /events/:id/announce
            if (sub.equals("/announce") && method.equals("POST")) {
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        if (evt.isRunning()) throw new RuntimeException("Event already running");
                        em.announceEvent(evt);
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respondJson(ex, buildEventDetail(evt));
                return;
            }

            // GET /events/:id/participants
            if (sub.equals("/participants") && method.equals("GET")) {
                JsonArray arr = new JsonArray();
                List<EventParticipant> sorted = new ArrayList<>(evt.getParticipants().values());
                sorted.sort((a, b) -> b.getScore() - a.getScore());
                for (EventParticipant p : sorted) {
                    JsonObject po = new JsonObject();
                    po.addProperty("uuid", p.getPlayerUuid().toString());
                    po.addProperty("name", p.getPlayerName());
                    po.addProperty("role", p.getRole().name());
                    po.addProperty("score", p.getScore());
                    arr.add(po);
                }
                WebDashboard.respondJson(ex, arr);
                return;
            }

            // POST /events/:id/participants
            if (sub.equals("/participants") && method.equals("POST")) {
                String body = WebDashboard.readBody(ex);
                JsonObject data = JsonParser.parseString(body).getAsJsonObject();
                if (!data.has("playerName")) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"playerName is required\"}");
                    return;
                }
                String playerName = data.get("playerName").getAsString().trim();
                String roleStr = data.has("role") ? data.get("role").getAsString() : "PARTICIPANT";
                EventParticipant.Role role;
                try { role = EventParticipant.Role.valueOf(roleStr); }
                catch (Exception e) { role = EventParticipant.Role.PARTICIPANT; }
                EventParticipant.Role finalRole = role;
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        ServerPlayerEntity target = server.getPlayerManager().getPlayer(playerName);
                        if (target == null) throw new RuntimeException("Player not online: " + playerName);
                        boolean ok = em.joinEvent(evt, target, finalRole);
                        if (!ok) throw new RuntimeException("Failed to add participant");
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"added\":true}");
                return;
            }

            // DELETE /events/:id/participants/:uuid
            if (sub.startsWith("/participants/") && method.equals("DELETE")) {
                String uuidStr = sub.substring("/participants/".length());
                UUID uuid;
                try { uuid = UUID.fromString(uuidStr); }
                catch (IllegalArgumentException e) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"Invalid UUID\"}");
                    return;
                }
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        ServerPlayerEntity target = server.getPlayerManager().getPlayer(uuid);
                        if (target != null) em.leaveEvent(evt, target);
                        else evt.removeParticipant(uuid);
                        em.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"removed\":true}");
                return;
            }

            // POST /events/:id/score
            if (sub.equals("/score") && method.equals("POST")) {
                String body = WebDashboard.readBody(ex);
                JsonObject data = JsonParser.parseString(body).getAsJsonObject();
                if (!data.has("playerName") || !data.has("amount")) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"playerName and amount are required\"}");
                    return;
                }
                String pName = data.get("playerName").getAsString().trim();
                int amount = data.get("amount").getAsInt();
                String operation = data.has("operation") ? data.get("operation").getAsString() : "add";
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        EventParticipant found = null;
                        for (EventParticipant p : evt.getParticipants().values()) {
                            if (p.getPlayerName().equalsIgnoreCase(pName)) { found = p; break; }
                        }
                        if (found == null) throw new RuntimeException("Player not a participant");
                        switch (operation) {
                            case "set" -> found.setScore(amount);
                            case "subtract" -> found.addScore(-amount);
                            default -> found.addScore(amount);
                        }
                        em.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"updated\":true}");
                return;
            }

            // POST /events/:id/save-template
            if (sub.equals("/save-template") && method.equals("POST")) {
                String body = WebDashboard.readBody(ex);
                JsonObject data = JsonParser.parseString(body).getAsJsonObject();
                String tName = data.has("name") ? data.get("name").getAsString().trim() : evt.getName();
                if (tName.isEmpty()) tName = evt.getName();
                String finalTName = tName;
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        boolean ok = EventTemplate.save(server, evt, finalTName);
                        if (!ok) throw new RuntimeException("Failed to save template");
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"saved\":true,\"name\":\"" + escapeJson(tName) + "\"}");
                return;
            }
        }

        WebDashboard.respond(ex, 404, "{\"error\":\"Route not found\"}");
    }

    // ── JSON Builders ────────────────────────────────────────────────────────

    private static JsonObject buildEventSummary(ServerEvent evt) {
        JsonObject o = new JsonObject();
        o.addProperty("id", evt.getId());
        o.addProperty("name", evt.getName());
        o.addProperty("state", evt.getState().name());
        o.addProperty("mode", evt.getMode().name());
        o.addProperty("trigger", evt.getTrigger().name());
        o.addProperty("participantCount", evt.getParticipantCount());
        o.addProperty("spectatorCount", evt.getSpectatorCount());
        o.addProperty("running", evt.isRunning());
        o.addProperty("active", evt.isActive());
        o.addProperty("miniGameType", evt.getMiniGameType());
        o.addProperty("playerLaunchable", evt.isPlayerLaunchable());
        return o;
    }

    private static JsonObject buildEventDetail(ServerEvent evt) {
        JsonObject o = buildEventSummary(evt);
        o.addProperty("description", evt.getDescription());
        o.addProperty("displayColor", evt.getDisplayColor());
        o.addProperty("playerLaunchable", evt.isPlayerLaunchable());
        o.addProperty("launchCommand", "/cg event launch " + evt.getId());
        o.addProperty("announceDurationSeconds", evt.getAnnounceDurationSeconds());
        o.addProperty("countdownSeconds", evt.getCountdownSeconds());
        o.addProperty("cooldownMinutes", evt.getCooldownMinutes());
        o.addProperty("minParticipants", evt.getMinParticipants());
        o.addProperty("maxParticipants", evt.getMaxParticipants());
        o.addProperty("teamMode", evt.getTeamMode().name());
        o.addProperty("teamSize", evt.getTeamSize());
        o.addProperty("deathBehavior", evt.getDeathBehavior().name());
        o.addProperty("victoryCondition", evt.getVictoryCondition().name());
        o.addProperty("victoryTargetScore", evt.getVictoryTargetScore());
        o.addProperty("saveInventory", evt.isSaveInventory());
        o.addProperty("forceEmptyInventory", evt.isForceEmptyInventory());
        o.addProperty("disablePokemonSummon", evt.isDisablePokemonSummon());
        o.addProperty("voiceChatGroup", evt.isVoiceChatGroup());
        o.addProperty("bossbarEnabled", evt.isBossbarEnabled());
        o.addProperty("bossbarColor", evt.getBossbarColor());
        o.addProperty("discordNotify", evt.isDiscordNotify());
        o.addProperty("announceMessage", evt.getAnnounceMessage());
        o.addProperty("startMessage", evt.getStartMessage());
        o.addProperty("endMessage", evt.getEndMessage());
        o.addProperty("tpParticipantX", evt.getTpParticipantX());
        o.addProperty("tpParticipantY", evt.getTpParticipantY());
        o.addProperty("tpParticipantZ", evt.getTpParticipantZ());
        o.addProperty("tpParticipantWorld", evt.getTpParticipantWorld());
        o.addProperty("tpSpectatorX", evt.getTpSpectatorX());
        o.addProperty("tpSpectatorY", evt.getTpSpectatorY());
        o.addProperty("tpSpectatorZ", evt.getTpSpectatorZ());
        o.addProperty("tpSpectatorWorld", evt.getTpSpectatorWorld());
        JsonArray tpPZArr = new JsonArray(); evt.getTpParticipantZoneIds().forEach(tpPZArr::add); o.add("tpParticipantZoneIds", tpPZArr);
        JsonArray tpSZArr = new JsonArray(); evt.getTpSpectatorZoneIds().forEach(tpSZArr::add); o.add("tpSpectatorZoneIds", tpSZArr);
        o.addProperty("useActSystem", evt.isUseActSystem());
        o.addProperty("miniGameType", evt.getMiniGameType());
        o.add("miniGameConfig", evt.getMiniGameConfig());
        o.addProperty("scoreboardVisible", evt.isScoreboardVisible());
        o.addProperty("exclusionGroup", evt.getExclusionGroup());
        o.addProperty("returnMode", evt.getReturnMode());
        o.addProperty("returnX", evt.getReturnX());
        o.addProperty("returnY", evt.getReturnY());
        o.addProperty("returnZ", evt.getReturnZ());
        o.addProperty("returnWorld", evt.getReturnWorld());
        o.addProperty("lightningMobsOnEnd", evt.isLightningMobsOnEnd());
        // Scope zones
        JsonArray scopeZones = new JsonArray();
        for (String z : evt.getScopeZoneIds()) scopeZones.add(z);
        o.add("scopeZoneIds", scopeZones);
        // Kit commands
        JsonArray kitEntry = new JsonArray();
        for (String c : evt.getKitEntryCommands()) kitEntry.add(c);
        o.add("kitEntryCommands", kitEntry);
        JsonArray kitExit = new JsonArray();
        for (String c : evt.getKitExitCommands()) kitExit.add(c);
        o.add("kitExitCommands", kitExit);
        // Blacklists
        JsonArray blItems = new JsonArray();
        for (String i : evt.getBlacklistItems()) blItems.add(i);
        o.add("blacklistItems", blItems);
        JsonArray blEnch = new JsonArray();
        for (String e : evt.getBlacklistEnchants()) blEnch.add(e);
        o.add("blacklistEnchants", blEnch);
        JsonArray blPoke = new JsonArray();
        for (String p : evt.getBlacklistPokemon()) blPoke.add(p);
        o.add("blacklistPokemon", blPoke);
        // Participants sorted by score desc
        JsonArray parts = new JsonArray();
        List<EventParticipant> sorted = new ArrayList<>(evt.getParticipants().values());
        sorted.sort((a, b) -> b.getScore() - a.getScore());
        for (EventParticipant p : sorted) {
            JsonObject po = new JsonObject();
            po.addProperty("uuid", p.getPlayerUuid().toString());
            po.addProperty("name", p.getPlayerName());
            po.addProperty("role", p.getRole().name());
            po.addProperty("score", p.getScore());
            parts.add(po);
        }
        o.add("participants", parts);
        // Rewards
        JsonArray rewards = new JsonArray();
        for (ServerEvent.EventReward r : evt.getRewards()) {
            rewards.add(r.toJson());
        }
        o.add("rewards", rewards);
        // Phases (legacy)
        JsonArray phases = new JsonArray();
        for (EventPhase ph : evt.getPhases()) {
            phases.add(ph.toJson());
        }
        o.add("phases", phases);
        // Acts (new system)
        JsonArray actsArr = new JsonArray();
        for (EventAct act : evt.getActs()) {
            actsArr.add(act.toJson());
        }
        o.add("acts", actsArr);
        return o;
    }

    private static boolean isValidId(String id) {
        return id != null && !id.isEmpty() && !id.contains("..") && !id.contains("/")
            && !id.contains("\\") && !id.contains("%") && id.length() < 128;
    }

    private static String escapeJson(String s) {
        if (s == null) return "Unknown error";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    // ── PropHunt Icon helpers ──────────────────────────────────────────────

    private static boolean isValidIconKey(String key) {
        return key != null && !key.isEmpty() && key.length() < 128
            && key.matches("[a-z0-9_]+");
    }

    private static void handlePropHuntIconList(HttpExchange ex) throws IOException {
        PropHuntIconStorage storage = CheeseGuardMod.getInstance().getPropHuntIconStorage();
        JsonArray arr = new JsonArray();
        for (String key : storage.listIcons()) {
            JsonObject o = new JsonObject();
            o.addProperty("key", key);
            arr.add(o);
        }
        WebDashboard.respondJson(ex, arr);
    }

    private static void handlePropHuntIconUpload(HttpExchange ex, String key) throws IOException {
        String body = WebDashboard.readBody(ex);
        JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
        if (!obj.has("imageBase64")) {
            WebDashboard.respond(ex, 400, "{\"error\":\"imageBase64 required\"}");
            return;
        }
        String raw = obj.get("imageBase64").getAsString();
        if (raw.contains(",")) raw = raw.substring(raw.indexOf(",") + 1);
        byte[] data = java.util.Base64.getDecoder().decode(raw);
        PropHuntIconStorage storage = CheeseGuardMod.getInstance().getPropHuntIconStorage();
        storage.saveIcon(key, data);
        storage.getOrAssignCustomModelData(key);
        storage.save();
        storage.registerWithCheeseItems();
        WebDashboard.respond(ex, 200, "{\"uploaded\":true}");
    }

    private static void handlePropHuntIconServe(HttpExchange ex, String key) throws IOException {
        PropHuntIconStorage storage = CheeseGuardMod.getInstance().getPropHuntIconStorage();
        byte[] data = storage.getIcon(key);
        if (data == null) {
            WebDashboard.respond(ex, 404, "{\"error\":\"Icon not found\"}");
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "image/png");
        ex.getResponseHeaders().set("Cache-Control", "public, max-age=60");
        ex.sendResponseHeaders(200, data.length);
        try (java.io.OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    private static void handlePropHuntIconDelete(HttpExchange ex, String key) throws IOException {
        PropHuntIconStorage storage = CheeseGuardMod.getInstance().getPropHuntIconStorage();
        storage.deleteIcon(key);
        storage.save();
        storage.registerWithCheeseItems();
        WebDashboard.respond(ex, 200, "{\"deleted\":true}");
    }
}
