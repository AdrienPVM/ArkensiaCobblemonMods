package com.cheeseraids.dashboard;

import com.cheesedash.WebDashboard;
import com.cheeseraids.CheeseRaidsMod;
import com.cheeseraids.data.PlayerRaidState;
import com.cheeseraids.data.RaidDefinition;
import com.cheeseraids.data.RaidInstance;
import com.cheeseraids.logic.RaidManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;

/** REST API for /api/v1/raids/* — definitions, active instances, start/stop/reload. */
public final class RaidsApi {
    private RaidsApi() {}
    private static final Gson GSON = new Gson();

    public static void handle(HttpExchange exchange, MinecraftServer server, String subRoute) throws IOException {
        String method = exchange.getRequestMethod();
        RaidManager mgr = CheeseRaidsMod.INSTANCE.getRaidManager();
        String route = (subRoute == null || subRoute.isEmpty()) ? "/" : subRoute;

        try {
            if (("/".equals(route) || "/overview".equals(route)) && "GET".equals(method)) {
                WebDashboard.respond(exchange, 200, overview(mgr).toString());
                return;
            }
            if ("/definitions".equals(route) && "GET".equals(method)) {
                JsonArray arr = new JsonArray();
                for (RaidDefinition d : mgr.definitions()) arr.add(JsonParser.parseString(GSON.toJson(d)));
                WebDashboard.respond(exchange, 200, arr.toString());
                return;
            }
            if ("/instances".equals(route) && "GET".equals(method)) {
                JsonArray arr = new JsonArray();
                for (RaidInstance i : mgr.activeInstances()) arr.add(instanceJson(i));
                WebDashboard.respond(exchange, 200, arr.toString());
                return;
            }
            if ("/start".equals(route) && "POST".equals(method)) {
                if (!WebDashboard.requireWrite(exchange)) return;
                String raid = bodyRaid(exchange);
                boolean[] ok = {false};
                WebDashboard.executeOnServerThread(server, () -> ok[0] = mgr.start(raid));
                WebDashboard.respond(exchange, ok[0] ? 200 : 409,
                    "{\"started\":" + ok[0] + ",\"raid\":\"" + esc(raid) + "\"}");
                return;
            }
            if ("/stop".equals(route) && "POST".equals(method)) {
                if (!WebDashboard.requireWrite(exchange)) return;
                String raid = bodyRaid(exchange);
                boolean[] ok = {false};
                WebDashboard.executeOnServerThread(server, () -> ok[0] = mgr.stop(raid));
                WebDashboard.respond(exchange, ok[0] ? 200 : 404, "{\"stopped\":" + ok[0] + "}");
                return;
            }
            if ("/reload".equals(route) && "POST".equals(method)) {
                if (!WebDashboard.requireWrite(exchange)) return;
                WebDashboard.executeOnServerThread(server, mgr::reload);
                WebDashboard.respond(exchange, 200, "{\"reloaded\":true}");
                return;
            }
            WebDashboard.respond(exchange, 404, "{\"error\":\"Not found\"}");
        } catch (Exception e) {
            CheeseRaidsMod.LOGGER.error("[CheeseRaids] API error: {}", e.getMessage(), e);
            WebDashboard.respond(exchange, 500, "{\"error\":\"Internal server error\"}");
        }
    }

    private static JsonObject overview(RaidManager mgr) {
        JsonObject o = new JsonObject();
        JsonArray defs = new JsonArray();
        for (RaidDefinition d : mgr.definitions()) {
            JsonObject jd = new JsonObject();
            jd.addProperty("id", d.id);
            jd.addProperty("displayName", d.displayName);
            jd.addProperty("icon", d.icon);
            jd.addProperty("enabled", d.enabled);
            jd.addProperty("autoTrigger", d.schedule.autoTrigger);
            jd.addProperty("hour", d.schedule.hour);
            jd.addProperty("minute", d.schedule.minute);
            jd.addProperty("everyNDays", d.schedule.everyNDays);
            RaidInstance inst = mgr.activeInstance(d.id);
            jd.addProperty("active", inst != null);
            if (inst != null) jd.addProperty("phase", inst.phase.name());
            defs.add(jd);
        }
        o.add("definitions", defs);
        JsonArray insts = new JsonArray();
        for (RaidInstance i : mgr.activeInstances()) insts.add(instanceJson(i));
        o.add("instances", insts);
        return o;
    }

    private static JsonObject instanceJson(RaidInstance i) {
        JsonObject o = new JsonObject();
        o.addProperty("raidId", i.raidId);
        o.addProperty("phase", i.phase.name());
        o.addProperty("participants", i.participantCount());
        o.addProperty("waveTotal", i.waveTotal);
        o.addProperty("bossSpawned", i.bossSpawned);
        JsonArray players = new JsonArray();
        for (PlayerRaidState p : i.players.values()) {
            JsonObject jp = new JsonObject();
            jp.addProperty("name", p.name);
            jp.addProperty("slot", p.slot);
            jp.addProperty("entered", p.entered);
            jp.addProperty("arrived", p.arrived);
            jp.addProperty("finished", p.finished);
            players.add(jp);
        }
        o.add("players", players);
        return o;
    }

    private static String bodyRaid(HttpExchange exchange) throws IOException {
        try {
            JsonObject body = JsonParser.parseString(WebDashboard.readBody(exchange)).getAsJsonObject();
            return body.has("raid") ? body.get("raid").getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String esc(String s) { return s == null ? "" : s.replace("\"", "\\\""); }
}
