package com.cobbleshift.dashboard;

import com.cheesedash.WebDashboard;
import com.cobbleshift.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.UUID;

/**
 * REST API for CobbleShift.
 *
 * Routes:
 *   GET    /transforms              — list active transforms
 *   POST   /transform               — transform a player {playerName, species}
 *   POST   /revert                  — revert a player {playerName}
 *   POST   /revertall               — revert all
 *   GET    /abilities               — list active abilities
 *   POST   /ability                 — force/clear ability {playerName, species?}
 *   POST   /ability/global          — toggle global abilities {enabled: bool}
 *   POST   /ability/toggle          — toggle per-player ability {playerName}
 *   GET    /ability/disabled        — list disabled players
 *   GET    /species                 — species listing
 *   GET    /species/:name           — species detail (with attack info)
 *   GET    /config                  — get config
 *   PUT    /config                  — update config
 *   POST   /scale                   — set scale {uuid, scale}
 *   POST   /speed                   — set NPC speed {uuid, speed}
 */
public class ShiftApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseDash");

    public static void handle(HttpExchange exchange, MinecraftServer server, String subRoute) throws IOException {
        String method = exchange.getRequestMethod();

        try {
            // GET /transforms
            if (subRoute.equals("/transforms") && method.equals("GET")) {
                handleListTransforms(exchange);
                return;
            }

            // POST /transform
            if (subRoute.equals("/transform") && method.equals("POST")) {
                handleTransform(exchange, server);
                return;
            }

            // POST /revert
            if (subRoute.equals("/revert") && method.equals("POST")) {
                handleRevert(exchange, server);
                return;
            }

            // POST /revertall
            if (subRoute.equals("/revertall") && method.equals("POST")) {
                handleRevertAll(exchange, server);
                return;
            }

            // GET /abilities
            if (subRoute.equals("/abilities") && method.equals("GET")) {
                handleListAbilities(exchange);
                return;
            }

            // POST /ability/global
            if (subRoute.equals("/ability/global") && method.equals("POST")) {
                handleAbilityGlobal(exchange);
                return;
            }

            // POST /ability/toggle
            if (subRoute.equals("/ability/toggle") && method.equals("POST")) {
                handleAbilityToggle(exchange, server);
                return;
            }

            // GET /ability/disabled
            if (subRoute.equals("/ability/disabled") && method.equals("GET")) {
                handleAbilityDisabled(exchange, server);
                return;
            }

            // POST /ability
            if (subRoute.equals("/ability") && method.equals("POST")) {
                handleAbility(exchange, server);
                return;
            }

            // GET /species/:name
            if (subRoute.matches("/species/[^/]+") && method.equals("GET")) {
                String name = subRoute.split("/")[2];
                handleSpeciesInfo(exchange, name);
                return;
            }

            // GET /species
            if (subRoute.equals("/species") && method.equals("GET")) {
                handleListSpecies(exchange);
                return;
            }

            // GET /config
            if (subRoute.equals("/config") && method.equals("GET")) {
                handleGetConfig(exchange);
                return;
            }

            // PUT /config
            if (subRoute.equals("/config") && method.equals("PUT")) {
                handleUpdateConfig(exchange);
                return;
            }

            // POST /scale
            if (subRoute.equals("/scale") && method.equals("POST")) {
                handleScale(exchange, server);
                return;
            }

            // POST /speed
            if (subRoute.equals("/speed") && method.equals("POST")) {
                handleSpeed(exchange);
                return;
            }

            WebDashboard.respond(exchange, 404, "{\"error\":\"Route not found\"}");

        } catch (Exception e) {
            LOGGER.error("[ShiftApi] Error handling {}: {}", subRoute, e.getMessage(), e);
            WebDashboard.respond(exchange, 500,
                "{\"error\":\"Internal server error\",\"detail\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    // ── GET /transforms ──────────────────────────────────────────────────────

    private static void handleListTransforms(HttpExchange exchange) throws IOException {
        TransformManager mgr = CobbleShiftMod.INSTANCE.getTransformManager();
        Collection<TransformData> transforms = mgr.getAllTransforms();

        JsonArray playerArr = new JsonArray();
        for (TransformData td : transforms) {
            playerArr.add(td.toJson());
        }

        NpcTransformManager npcMgr = CobbleShiftMod.INSTANCE.getNpcTransformManager();
        JsonArray npcArr = new JsonArray();
        for (var entry : npcMgr.getAllTransforms().entrySet()) {
            NpcTransformManager.NpcTransformData nd = entry.getValue();
            JsonObject obj = new JsonObject();
            obj.addProperty("npcUuid", entry.getKey().toString());
            obj.addProperty("species", nd.species());
            obj.addProperty("shiny", nd.shiny());
            if (nd.form() != null) obj.addProperty("form", nd.form());
            obj.addProperty("type1", nd.type1());
            if (nd.type2() != null) obj.addProperty("type2", nd.type2());
            obj.addProperty("scale", nd.scale());
            obj.addProperty("speed", nd.speed());
            npcArr.add(obj);
        }

        JsonObject resp = new JsonObject();
        resp.add("players", playerArr);
        resp.add("npcs", npcArr);
        WebDashboard.respondJson(exchange, resp);
    }

    // ── POST /transform ──────────────────────────────────────────────────────

    private static void handleTransform(HttpExchange exchange, MinecraftServer server) throws IOException {
        if (!WebDashboard.requireWrite(exchange)) return;

        String body = WebDashboard.readBody(exchange);
        JsonObject data = JsonParser.parseString(body).getAsJsonObject();

        String playerName = data.has("playerName") ? data.get("playerName").getAsString() : null;
        String species = data.has("species") ? data.get("species").getAsString() : null;

        if (playerName == null || species == null) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"playerName and species required\"}");
            return;
        }

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Player not online: " + escapeJson(playerName) + "\"}");
            return;
        }

        TransformManager mgr = CobbleShiftMod.INSTANCE.getTransformManager();
        final boolean[] result = {false};

        try {
            WebDashboard.executeOnServerThread(server, () -> {
                result[0] = mgr.transform(player, species);
            });
        } catch (Exception e) {
            WebDashboard.respond(exchange, 500, "{\"error\":\"Server thread error: " + escapeJson(e.getMessage()) + "\"}");
            return;
        }

        if (result[0]) {
            JsonObject resp = new JsonObject();
            resp.addProperty("transformed", true);
            resp.addProperty("player", playerName);
            resp.addProperty("species", species);
            WebDashboard.respondJson(exchange, resp);
        } else {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Transform failed — species not found or Cobblemon unavailable\"}");
        }
    }

    // ── POST /revert ─────────────────────────────────────────────────────────

    private static void handleRevert(HttpExchange exchange, MinecraftServer server) throws IOException {
        if (!WebDashboard.requireWrite(exchange)) return;

        String body = WebDashboard.readBody(exchange);
        JsonObject data = JsonParser.parseString(body).getAsJsonObject();
        String playerName = data.has("playerName") ? data.get("playerName").getAsString() : null;

        if (playerName == null) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"playerName required\"}");
            return;
        }

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Player not online: " + escapeJson(playerName) + "\"}");
            return;
        }

        TransformManager mgr = CobbleShiftMod.INSTANCE.getTransformManager();
        final boolean[] result = {false};

        try {
            WebDashboard.executeOnServerThread(server, () -> {
                result[0] = mgr.revert(player);
            });
        } catch (Exception e) {
            WebDashboard.respond(exchange, 500, "{\"error\":\"Server thread error: " + escapeJson(e.getMessage()) + "\"}");
            return;
        }

        if (result[0]) {
            WebDashboard.respond(exchange, 200, "{\"reverted\":true,\"player\":\"" + escapeJson(playerName) + "\"}");
        } else {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Player is not transformed\"}");
        }
    }

    // ── POST /revertall ──────────────────────────────────────────────────────

    private static void handleRevertAll(HttpExchange exchange, MinecraftServer server) throws IOException {
        if (!WebDashboard.requireWrite(exchange)) return;

        TransformManager mgr = CobbleShiftMod.INSTANCE.getTransformManager();
        int count = mgr.getAllTransforms().size();

        try {
            WebDashboard.executeOnServerThread(server, mgr::revertAll);
        } catch (Exception e) {
            WebDashboard.respond(exchange, 500, "{\"error\":\"Server thread error: " + escapeJson(e.getMessage()) + "\"}");
            return;
        }

        JsonObject resp = new JsonObject();
        resp.addProperty("reverted", count);
        WebDashboard.respondJson(exchange, resp);
    }

    // ── GET /abilities ───────────────────────────────────────────────────────

    private static void handleListAbilities(HttpExchange exchange) throws IOException {
        AbilityManager mgr = CobbleShiftMod.INSTANCE.getAbilityManager();
        Collection<AbilityManager.AbilityData> abilities = mgr.getAllAbilities();

        JsonArray arr = new JsonArray();
        for (AbilityManager.AbilityData ad : abilities) {
            JsonObject obj = ad.toJson();
            ServerPlayerEntity p = CobbleShiftMod.INSTANCE.getServer() != null ?
                CobbleShiftMod.INSTANCE.getServer().getPlayerManager().getPlayer(ad.playerUuid()) : null;
            obj.addProperty("playerName", p != null ? p.getName().getString() : "offline");
            AbilityType t = AbilityType.fromTypeName(ad.type1());
            obj.addProperty("abilityName", t.displayName);
            obj.addProperty("disabled", mgr.isPlayerDisabled(ad.playerUuid()));
            arr.add(obj);
        }
        WebDashboard.respondJson(exchange, arr);
    }

    // ── POST /ability ────────────────────────────────────────────────────────

    private static void handleAbility(HttpExchange exchange, MinecraftServer server) throws IOException {
        if (!WebDashboard.requireWrite(exchange)) return;

        String body = WebDashboard.readBody(exchange);
        JsonObject data = JsonParser.parseString(body).getAsJsonObject();
        String playerName = data.has("playerName") ? data.get("playerName").getAsString() : null;
        String species = data.has("species") ? data.get("species").getAsString() : null;

        if (playerName == null) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"playerName required\"}");
            return;
        }

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Player not online: " + escapeJson(playerName) + "\"}");
            return;
        }

        AbilityManager mgr = CobbleShiftMod.INSTANCE.getAbilityManager();

        try {
            if (species != null && !species.isBlank()) {
                WebDashboard.executeOnServerThread(server, () -> mgr.forceAbility(player, species));
                WebDashboard.respond(exchange, 200, "{\"forced\":true,\"player\":\"" + escapeJson(playerName) + "\",\"species\":\"" + escapeJson(species) + "\"}");
            } else {
                WebDashboard.executeOnServerThread(server, () -> mgr.clearForce(player));
                WebDashboard.respond(exchange, 200, "{\"cleared\":true,\"player\":\"" + escapeJson(playerName) + "\"}");
            }
        } catch (Exception e) {
            WebDashboard.respond(exchange, 500, "{\"error\":\"Server thread error: " + escapeJson(e.getMessage()) + "\"}");
        }
    }

    // ── POST /ability/global ─────────────────────────────────────────────────

    private static void handleAbilityGlobal(HttpExchange exchange) throws IOException {
        if (!WebDashboard.requireAdmin(exchange)) return;

        String body = WebDashboard.readBody(exchange);
        JsonObject data = JsonParser.parseString(body).getAsJsonObject();
        boolean enabled = data.has("enabled") && data.get("enabled").getAsBoolean();

        CobbleShiftConfig cfg = CobbleShiftMod.INSTANCE.getConfig();
        cfg.setAbilityGlobalEnabled(enabled);
        cfg.save();

        // When disabling globally, clear all active abilities and sync HUD on all clients
        if (!enabled) {
            try {
                net.minecraft.server.MinecraftServer server = CobbleShiftMod.INSTANCE.getServer();
                if (server != null) {
                    server.execute(() -> CobbleShiftMod.INSTANCE.getAbilityManager().deactivateAllAndSync());
                }
            } catch (Exception ignored) {}
        }

        JsonObject resp = new JsonObject();
        resp.addProperty("abilityGlobalEnabled", enabled);
        WebDashboard.respondJson(exchange, resp);
    }

    // ── POST /ability/toggle ─────────────────────────────────────────────────

    private static void handleAbilityToggle(HttpExchange exchange, MinecraftServer server) throws IOException {
        if (!WebDashboard.requireWrite(exchange)) return;

        String body = WebDashboard.readBody(exchange);
        JsonObject data = JsonParser.parseString(body).getAsJsonObject();
        String playerName = data.has("playerName") ? data.get("playerName").getAsString() : null;

        if (playerName == null) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"playerName required\"}");
            return;
        }

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Player not online: " + escapeJson(playerName) + "\"}");
            return;
        }

        AbilityManager mgr = CobbleShiftMod.INSTANCE.getAbilityManager();
        boolean nowDisabled = mgr.togglePlayer(player.getUuid());

        JsonObject resp = new JsonObject();
        resp.addProperty("player", playerName);
        resp.addProperty("disabled", nowDisabled);
        WebDashboard.respondJson(exchange, resp);
    }

    // ── GET /ability/disabled ────────────────────────────────────────────────

    private static void handleAbilityDisabled(HttpExchange exchange, MinecraftServer server) throws IOException {
        AbilityManager mgr = CobbleShiftMod.INSTANCE.getAbilityManager();

        JsonArray arr = new JsonArray();
        for (UUID uuid : mgr.getDisabledPlayers()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("uuid", uuid.toString());
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            obj.addProperty("name", p != null ? p.getName().getString() : "offline");
            arr.add(obj);
        }
        WebDashboard.respondJson(exchange, arr);
    }

    // ── GET /species ─────────────────────────────────────────────────────────

    private static void handleListSpecies(HttpExchange exchange) throws IOException {
        JsonObject resp = new JsonObject();
        resp.addProperty("cobblemonAvailable", SpeciesMapper.isCobblemonAvailable());
        resp.addProperty("note", "Use /species/:name to query individual species. Full list requires Cobblemon registry access.");
        WebDashboard.respondJson(exchange, resp);
    }

    // ── GET /species/:name ───────────────────────────────────────────────────

    private static void handleSpeciesInfo(HttpExchange exchange, String name) throws IOException {
        SpeciesMapper.SpeciesStats stats = SpeciesMapper.resolve(name);
        if (stats == null) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"Species not found: " + escapeJson(name) + "\"}");
            return;
        }

        int bst = SpeciesMapper.computeBST(stats.hp(), stats.atk(), stats.def(), stats.spAtk(), stats.spDef(), stats.speed());
        float atkDamage = SpeciesMapper.mapAttackDamage(stats.atk(), stats.spAtk());
        int atkCooldown = SpeciesMapper.mapAttackCooldown(stats.speed());
        int projCount = SpeciesMapper.mapProjectileCount(bst);

        JsonObject obj = new JsonObject();
        obj.addProperty("name", stats.name());
        obj.addProperty("type1", stats.type1());
        if (stats.type2() != null) obj.addProperty("type2", stats.type2());
        obj.addProperty("hp", stats.hp());
        obj.addProperty("atk", stats.atk());
        obj.addProperty("def", stats.def());
        obj.addProperty("spAtk", stats.spAtk());
        obj.addProperty("spDef", stats.spDef());
        obj.addProperty("speed", stats.speed());
        obj.addProperty("height", stats.height());

        // Mapped values
        obj.addProperty("mappedSpeed", SpeciesMapper.mapSpeed(stats.speed()));
        obj.addProperty("mappedHealth", SpeciesMapper.mapMaxHealth(stats.hp()));
        obj.addProperty("mappedScale", SpeciesMapper.mapScale(stats.height()));

        // Attack values
        obj.addProperty("bst", bst);
        obj.addProperty("attackDamage", atkDamage);
        obj.addProperty("attackCooldown", atkCooldown);
        obj.addProperty("projectileCount", projCount);

        // Ability info
        AbilityType t1 = AbilityType.fromTypeName(stats.type1());
        obj.addProperty("ability1Name", t1.displayName);
        if (stats.type2() != null) {
            AbilityType t2 = AbilityType.fromTypeName(stats.type2());
            obj.addProperty("ability2Name", t2.displayName);
        }

        WebDashboard.respondJson(exchange, obj);
    }

    // ── GET /config ──────────────────────────────────────────────────────────

    private static void handleGetConfig(HttpExchange exchange) throws IOException {
        CobbleShiftConfig cfg = CobbleShiftMod.INSTANCE.getConfig();
        WebDashboard.respondJson(exchange, cfg.toJson());
    }

    // ── PUT /config ──────────────────────────────────────────────────────────

    private static void handleUpdateConfig(HttpExchange exchange) throws IOException {
        if (!WebDashboard.requireAdmin(exchange)) return;

        String body = WebDashboard.readBody(exchange);
        JsonObject data = JsonParser.parseString(body).getAsJsonObject();
        CobbleShiftConfig cfg = CobbleShiftMod.INSTANCE.getConfig();

        if (data.has("autoRevertOnDeath")) cfg.setAutoRevertOnDeath(data.get("autoRevertOnDeath").getAsBoolean());
        if (data.has("abilityAutoMode")) cfg.setAbilityAutoMode(data.get("abilityAutoMode").getAsBoolean());
        if (data.has("abilityGlobalEnabled")) cfg.setAbilityGlobalEnabled(data.get("abilityGlobalEnabled").getAsBoolean());
        if (data.has("abilityTickInterval")) cfg.setAbilityTickInterval(data.get("abilityTickInterval").getAsInt());
        if (data.has("abilityEffectDuration")) cfg.setAbilityEffectDuration(data.get("abilityEffectDuration").getAsInt());
        if (data.has("transformScaleEnabled")) cfg.setTransformScaleEnabled(data.get("transformScaleEnabled").getAsBoolean());
        if (data.has("transformSpeedEnabled")) cfg.setTransformSpeedEnabled(data.get("transformSpeedEnabled").getAsBoolean());
        if (data.has("transformHealthEnabled")) cfg.setTransformHealthEnabled(data.get("transformHealthEnabled").getAsBoolean());
        if (data.has("attackDamageMultiplier")) cfg.setAttackDamageMultiplier(data.get("attackDamageMultiplier").getAsFloat());
        if (data.has("attackCooldownMultiplier")) cfg.setAttackCooldownMultiplier(data.get("attackCooldownMultiplier").getAsFloat());
        if (data.has("transformMaxDurationEnabled")) cfg.setTransformMaxDurationEnabled(data.get("transformMaxDurationEnabled").getAsBoolean());
        if (data.has("transformMaxDurationSeconds")) cfg.setTransformMaxDurationSeconds(data.get("transformMaxDurationSeconds").getAsInt());
        if (data.has("transformCooldownEnabled")) cfg.setTransformCooldownEnabled(data.get("transformCooldownEnabled").getAsBoolean());
        if (data.has("transformCooldownSeconds")) cfg.setTransformCooldownSeconds(data.get("transformCooldownSeconds").getAsInt());

        cfg.save();
        WebDashboard.respond(exchange, 200, "{\"saved\":true}");
    }

    // ── POST /scale ───────────────────────────────────────────────────────────

    private static void handleScale(HttpExchange exchange, MinecraftServer server) throws IOException {
        if (!WebDashboard.requireWrite(exchange)) return;

        String body = WebDashboard.readBody(exchange);
        JsonObject data = JsonParser.parseString(body).getAsJsonObject();

        String uuidStr = data.has("uuid") ? data.get("uuid").getAsString() : null;
        float scale = data.has("scale") ? data.get("scale").getAsFloat() : 1.0f;

        if (uuidStr == null) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"uuid required\"}");
            return;
        }

        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid UUID\"}");
            return;
        }

        scale = Math.max(0.1f, Math.min(10.0f, scale));

        // Try player transform first
        TransformManager tmgr = CobbleShiftMod.INSTANCE.getTransformManager();
        if (tmgr.isTransformed(uuid)) {
            final float finalScale = scale;
            final boolean[] result = {false};
            try {
                WebDashboard.executeOnServerThread(server, () -> {
                    result[0] = tmgr.setPlayerScale(uuid, finalScale);
                });
            } catch (Exception e) {
                WebDashboard.respond(exchange, 500, "{\"error\":\"Server thread error: " + escapeJson(e.getMessage()) + "\"}");
                return;
            }
            JsonObject resp = new JsonObject();
            resp.addProperty("type", "player");
            resp.addProperty("uuid", uuidStr);
            resp.addProperty("scale", finalScale);
            resp.addProperty("success", result[0]);
            WebDashboard.respondJson(exchange, resp);
            return;
        }

        // Try NPC transform
        NpcTransformManager npcMgr = CobbleShiftMod.INSTANCE.getNpcTransformManager();
        if (npcMgr.isNpcTransformed(uuid)) {
            boolean ok = npcMgr.setNpcScale(uuid, scale);
            JsonObject resp = new JsonObject();
            resp.addProperty("type", "npc");
            resp.addProperty("uuid", uuidStr);
            resp.addProperty("scale", scale);
            resp.addProperty("success", ok);
            WebDashboard.respondJson(exchange, resp);
            return;
        }

        WebDashboard.respond(exchange, 404, "{\"error\":\"UUID is not a transformed player or NPC\"}");
    }

    // ── POST /speed ──────────────────────────────────────────────────────────

    private static void handleSpeed(HttpExchange exchange) throws IOException {
        if (!WebDashboard.requireWrite(exchange)) return;

        String body = WebDashboard.readBody(exchange);
        JsonObject data = JsonParser.parseString(body).getAsJsonObject();

        String uuidStr = data.has("uuid") ? data.get("uuid").getAsString() : null;
        float speed = data.has("speed") ? data.get("speed").getAsFloat() : 1.0f;

        if (uuidStr == null) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"uuid required\"}");
            return;
        }

        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            WebDashboard.respond(exchange, 400, "{\"error\":\"Invalid UUID\"}");
            return;
        }

        speed = Math.max(0.1f, Math.min(10.0f, speed));

        NpcTransformManager npcMgr = CobbleShiftMod.INSTANCE.getNpcTransformManager();
        if (!npcMgr.isNpcTransformed(uuid)) {
            WebDashboard.respond(exchange, 404, "{\"error\":\"UUID is not a transformed NPC\"}");
            return;
        }

        boolean ok = npcMgr.setNpcSpeed(uuid, speed);
        JsonObject resp = new JsonObject();
        resp.addProperty("uuid", uuidStr);
        resp.addProperty("speed", speed);
        resp.addProperty("success", ok);
        WebDashboard.respondJson(exchange, resp);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
