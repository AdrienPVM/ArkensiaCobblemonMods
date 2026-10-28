package com.cheeseguard.dashboard;

import com.cheesedash.WebDashboard;
import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.PrisonEntry;
import com.cheeseguard.data.Region;
import com.cheeseguard.logic.PrisonListener;
import com.cheeseguard.logic.PrisonManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.util.UUID;

/**
 * /api/v1/cheeseguard/prison — prison management endpoints.
 *
 * Routes:
 *  GET    /prison                  → { active, history, config }
 *  GET    /prison/active           → [PrisonEntry...]
 *  GET    /prison/history          → [PrisonEntry...]
 *  GET    /prison/config           → { prisonZoneId, welcomeMessage }
 *  PUT    /prison/config           → { prisonZoneId?, welcomeMessage? }
 *  POST   /prison                  → body: { uuid|name, durationMs|duration, reason }
 *  DELETE /prison/:uuid            → release (PARDONED)
 *  PUT    /prison/:uuid            → body: { durationMs|endsAt|extendMs }
 */
public class PrisonApi {

    public static void handle(HttpExchange ex, CheeseGuardMod mod, String route) throws IOException {
        String method = ex.getRequestMethod();
        MinecraftServer server = mod.getServer();
        if (server == null) { WebDashboard.respond(ex, 503, "{\"error\":\"Server unavailable\"}"); return; }
        PrisonManager pm = mod.getPrisonManager();

        // ── Bundle ─────────────────────────────────────────────────────────
        if ((route.equals("/prison") || route.equals("/prison/")) && method.equals("GET")) {
            JsonObject root = new JsonObject();
            root.add("active", buildActiveArray(pm));
            root.add("history", buildHistoryArray(pm));
            root.add("config", buildConfig(pm));
            WebDashboard.respondJson(ex, root);
            return;
        }

        // ── Active ─────────────────────────────────────────────────────────
        if (route.equals("/prison/active") && method.equals("GET")) {
            WebDashboard.respondJson(ex, buildActiveArray(pm));
            return;
        }

        // ── History ────────────────────────────────────────────────────────
        if (route.equals("/prison/history") && method.equals("GET")) {
            WebDashboard.respondJson(ex, buildHistoryArray(pm));
            return;
        }

        // ── Config ─────────────────────────────────────────────────────────
        if (route.equals("/prison/config") && method.equals("GET")) {
            WebDashboard.respondJson(ex, buildConfig(pm));
            return;
        }
        if (route.equals("/prison/config") && method.equals("PUT")) {
            if (!WebDashboard.requireWrite(ex)) return;
            JsonObject body = parseBody(ex);
            if (body == null) { WebDashboard.respond(ex, 400, "{\"error\":\"Invalid JSON\"}"); return; }
            if (body.has("prisonZoneId")) {
                String id = body.get("prisonZoneId").isJsonNull() ? null : body.get("prisonZoneId").getAsString();
                if (id != null && !id.isEmpty() && mod.getStorage().getRegion(id) == null) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"Zone not found: " + escapeJson(id) + "\"}");
                    return;
                }
                pm.setPrisonZoneId(id == null || id.isEmpty() ? null : id);
            }
            if (body.has("welcomeMessage") && !body.get("welcomeMessage").isJsonNull()) {
                pm.setWelcomeMessage(body.get("welcomeMessage").getAsString());
            }
            // releasePoint: object with world/x/y/z (+ optional yaw/pitch), or explicit null to clear.
            if (body.has("releasePoint")) {
                if (body.get("releasePoint").isJsonNull()) {
                    pm.clearReleasePoint();
                } else if (body.get("releasePoint").isJsonObject()) {
                    JsonObject rp = body.getAsJsonObject("releasePoint");
                    if (rp.has("world") && rp.has("x") && rp.has("y") && rp.has("z")) {
                        Float yaw   = rp.has("yaw")   && !rp.get("yaw").isJsonNull()   ? rp.get("yaw").getAsFloat()   : null;
                        Float pitch = rp.has("pitch") && !rp.get("pitch").isJsonNull() ? rp.get("pitch").getAsFloat() : null;
                        pm.setReleasePoint(
                            rp.get("world").getAsString(),
                            rp.get("x").getAsDouble(),
                            rp.get("y").getAsDouble(),
                            rp.get("z").getAsDouble(),
                            yaw, pitch);
                    }
                }
            }
            WebDashboard.respondJson(ex, buildConfig(pm));
            return;
        }

        // ── Imprison ───────────────────────────────────────────────────────
        if ((route.equals("/prison") || route.equals("/prison/")) && method.equals("POST")) {
            if (!WebDashboard.requireWrite(ex)) return;
            if (pm.getPrisonZoneId() == null || mod.getStorage().getRegion(pm.getPrisonZoneId()) == null) {
                WebDashboard.respond(ex, 400, "{\"error\":\"Prison zone not configured\"}");
                return;
            }
            JsonObject body = parseBody(ex);
            if (body == null) { WebDashboard.respond(ex, 400, "{\"error\":\"Invalid JSON\"}"); return; }

            String uuid = body.has("uuid") && !body.get("uuid").isJsonNull() ? body.get("uuid").getAsString() : null;
            String name = body.has("name") && !body.get("name").isJsonNull() ? body.get("name").getAsString() : null;
            String reason = body.has("reason") ? body.get("reason").getAsString() : "";
            long durationMs = -1L;
            if (body.has("durationMs") && !body.get("durationMs").isJsonNull()) {
                try { durationMs = body.get("durationMs").getAsLong(); } catch (Exception ignored) {}
            } else if (body.has("duration") && !body.get("duration").isJsonNull()) {
                durationMs = PrisonManager.parseDuration(body.get("duration").getAsString());
            }
            if (durationMs <= 0) { WebDashboard.respond(ex, 400, "{\"error\":\"Duration is required and must be > 0\"}"); return; }

            ResolvedPlayer rp = resolvePlayer(server, uuid, name);
            if (rp == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Player not found\"}"); return; }

            String issuerUuid = "dashboard";
            String issuerName = "Dashboard";
            try { issuerName = WebDashboard.getSession(ex).getUsername(); issuerUuid = "dashboard:" + issuerName; }
            catch (Throwable ignored) {}

            final long ms = durationMs;
            final String iu = issuerUuid, in_ = issuerName;
            final String r = reason;
            final ResolvedPlayer rpf = rp;
            final PrisonEntry[] result = new PrisonEntry[1];
            try {
                WebDashboard.executeOnServerThread(server, () -> {
                    result[0] = pm.imprison(rpf.uuid().toString(), rpf.name(), r, ms, iu, in_);
                    ServerPlayerEntity online = server.getPlayerManager().getPlayer(rpf.uuid());
                    if (online != null) {
                        online.sendMessage(net.minecraft.text.Text.translatable(
                            "cheeseguard.prison.imprisoned_announce",
                            PrisonManager.formatDuration(ms), r)
                            .formatted(net.minecraft.util.Formatting.RED), false);
                    }
                });
            } catch (Exception e) {
                WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                return;
            }
            if (result[0] != null) PrisonListener.broadcastPrisonEvent("prison_added", result[0]);
            WebDashboard.respondJson(ex, result[0] != null ? result[0].toJson() : new JsonObject());
            return;
        }

        // ── /prison/:uuid (release / extend) ───────────────────────────────
        if (route.startsWith("/prison/")) {
            String uuid = route.substring("/prison/".length());
            // Strip any trailing path parts.
            int slash = uuid.indexOf('/');
            if (slash >= 0) uuid = uuid.substring(0, slash);

            if (method.equals("DELETE")) {
                if (!WebDashboard.requireWrite(ex)) return;
                String byUuid = "dashboard";
                String byName = "Dashboard";
                try { byName = WebDashboard.getSession(ex).getUsername(); byUuid = "dashboard:" + byName; }
                catch (Throwable ignored) {}
                final String byU = byUuid, byN = byName;
                final String uu = uuid;
                final PrisonEntry[] result = new PrisonEntry[1];
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        ServerPlayerEntity online = null;
                        try { online = server.getPlayerManager().getPlayer(UUID.fromString(uu)); }
                        catch (Exception ignored) {}
                        if (online != null) {
                            PrisonListener.releaseAndTpToSpawn(server, online, PrisonEntry.RELEASE_PARDONED, byU, byN);
                        } else {
                            result[0] = pm.release(uu, PrisonEntry.RELEASE_PARDONED, byU, byN);
                            if (result[0] != null) PrisonListener.broadcastPrisonEvent("prison_released", result[0]);
                        }
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"released\":true}");
                return;
            }

            if (method.equals("PUT")) {
                if (!WebDashboard.requireWrite(ex)) return;
                JsonObject body = parseBody(ex);
                if (body == null) { WebDashboard.respond(ex, 400, "{\"error\":\"Invalid JSON\"}"); return; }
                PrisonEntry e = pm.get(uuid);
                if (e == null) { WebDashboard.respond(ex, 404, "{\"error\":\"Not imprisoned\"}"); return; }
                long newEnd = e.getEndsAt();
                if (body.has("endsAt") && !body.get("endsAt").isJsonNull()) {
                    newEnd = body.get("endsAt").getAsLong();
                } else if (body.has("durationMs") && !body.get("durationMs").isJsonNull()) {
                    newEnd = e.getStartedAt() + body.get("durationMs").getAsLong();
                } else if (body.has("duration") && !body.get("duration").isJsonNull()) {
                    long ms = PrisonManager.parseDuration(body.get("duration").getAsString());
                    if (ms > 0) newEnd = e.getStartedAt() + ms;
                } else if (body.has("extendMs") && !body.get("extendMs").isJsonNull()) {
                    newEnd = e.getEndsAt() + body.get("extendMs").getAsLong();
                }
                if (newEnd <= System.currentTimeMillis()) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"endsAt must be in the future\"}");
                    return;
                }
                PrisonEntry updated = pm.adjustEnd(uuid, newEnd);
                if (updated != null) PrisonListener.broadcastPrisonEvent("prison_updated", updated);
                WebDashboard.respondJson(ex, updated != null ? updated.toJson() : new JsonObject());
                return;
            }
        }

        WebDashboard.respond(ex, 404, "{\"error\":\"Prison route not found: " + escapeJson(route) + "\"}");
    }

    // ── Builders ─────────────────────────────────────────────────────────────

    private static JsonArray buildActiveArray(PrisonManager pm) {
        long now = System.currentTimeMillis();
        JsonArray arr = new JsonArray();
        for (PrisonEntry e : pm.getActive()) {
            JsonObject o = e.toJson();
            o.addProperty("remainingMs", e.remainingMs(now));
            arr.add(o);
        }
        return arr;
    }

    private static JsonArray buildHistoryArray(PrisonManager pm) {
        JsonArray arr = new JsonArray();
        for (PrisonEntry e : pm.getHistory()) arr.add(e.toJson());
        return arr;
    }

    private static JsonObject buildConfig(PrisonManager pm) {
        JsonObject o = new JsonObject();
        o.addProperty("prisonZoneId", pm.getPrisonZoneId());
        o.addProperty("welcomeMessage", pm.getWelcomeMessage());
        if (pm.getPrisonZoneId() != null) {
            Region zone = CheeseGuardMod.getInstance().getStorage().getRegion(pm.getPrisonZoneId());
            if (zone != null) {
                JsonObject z = new JsonObject();
                z.addProperty("id", zone.getId());
                z.addProperty("name", zone.getName());
                z.addProperty("world", zone.getWorld());
                o.add("zone", z);
            }
        }
        if (pm.hasReleasePoint()) {
            JsonObject rp = new JsonObject();
            rp.addProperty("world", pm.getReleaseWorld());
            rp.addProperty("x", pm.getReleaseX());
            rp.addProperty("y", pm.getReleaseY());
            rp.addProperty("z", pm.getReleaseZ());
            if (pm.getReleaseYaw()   != null) rp.addProperty("yaw",   pm.getReleaseYaw());
            if (pm.getReleasePitch() != null) rp.addProperty("pitch", pm.getReleasePitch());
            o.add("releasePoint", rp);
        }
        return o;
    }

    // ── Utils ────────────────────────────────────────────────────────────────

    private static JsonObject parseBody(HttpExchange ex) {
        try {
            String body = WebDashboard.readBody(ex);
            if (body == null || body.isEmpty()) return new JsonObject();
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) { return null; }
    }

    private static ResolvedPlayer resolvePlayer(MinecraftServer server, String uuidStr, String name) {
        if (uuidStr != null && !uuidStr.isEmpty()) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                ServerPlayerEntity online = server.getPlayerManager().getPlayer(uuid);
                if (online != null) return new ResolvedPlayer(uuid, online.getName().getString());
                if (server.getUserCache() != null) {
                    var p = server.getUserCache().getByUuid(uuid);
                    if (p.isPresent()) return new ResolvedPlayer(uuid, p.get().getName());
                }
                return new ResolvedPlayer(uuid, name != null ? name : uuid.toString().substring(0, 8));
            } catch (IllegalArgumentException ignored) {}
        }
        if (name != null && !name.isEmpty()) {
            ServerPlayerEntity online = server.getPlayerManager().getPlayer(name);
            if (online != null) return new ResolvedPlayer(online.getUuid(), online.getName().getString());
            if (server.getUserCache() != null) {
                var p = server.getUserCache().findByName(name);
                if (p.isPresent()) return new ResolvedPlayer(p.get().getId(), p.get().getName());
            }
        }
        return null;
    }

    private record ResolvedPlayer(UUID uuid, String name) {}

    private static String escapeJson(String s) {
        if (s == null) return "Unknown";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
