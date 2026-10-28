package com.cheeseguard.dashboard;

import com.cheesedash.WebDashboard;
import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.DimensionConfig;
import com.cheeseguard.logic.DimensionManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** /api/v1/cheeseguard/dimensions — full CRUD for custom dimensions */
public class DimensionsApi {

    public static void handle(HttpExchange ex, CheeseGuardMod mod, String route) throws IOException {
        String method = ex.getRequestMethod();
        DimensionManager dimManager = mod.getDimensionManager();
        if (dimManager == null) {
            WebDashboard.respond(ex, 503, "{\"error\":\"DimensionManager unavailable\"}");
            return;
        }

        MinecraftServer server = mod.getServer();

        // ── GET /dimensions/gen-types — list available gen-types (builtin + plugin) ──
        if (route.equals("/dimensions/gen-types") && method.equals("GET")) {
            JsonArray out = new JsonArray();
            for (DimensionConfig.GenType g : DimensionConfig.GenType.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("id", g.name());
                o.addProperty("label", g.label);
                o.addProperty("icon", g.icon);
                o.addProperty("kind", "builtin");
                out.add(o);
            }
            for (var p : com.cheeseguard.api.GenTypeRegistry.all()) {
                JsonObject o = new JsonObject();
                o.addProperty("id", p.id());
                o.addProperty("label", p.label());
                o.addProperty("icon", p.icon());
                o.addProperty("defaultSpawnY", p.defaultSpawnY());
                o.addProperty("kind", "plugin");
                out.add(o);
            }
            WebDashboard.respondJson(ex, out);
            return;
        }

        // ── GET /dimensions — list all ──────────────────────────────────────
        if (route.equals("/dimensions") && method.equals("GET")) {
            List<DimensionConfig> dims = dimManager.getAllDimensions();
            JsonArray arr = new JsonArray();
            for (DimensionConfig c : dims) {
                // Skip player-personal dimensions (they show under their master's /players sub)
                if (isPlayerDim(c)) continue;
                arr.add(buildDimSummary(c, dimManager, server));
            }
            WebDashboard.respondJson(ex, arr);
            return;
        }

        // ── POST /dimensions — create ───────────────────────────────────────
        if (route.equals("/dimensions") && method.equals("POST")) {
            String body = WebDashboard.readBody(ex);
            JsonObject data = JsonParser.parseString(body).getAsJsonObject();
            if (!data.has("name") || (!data.has("genType") && !data.has("customGenTypeId"))) {
                WebDashboard.respond(ex, 400, "{\"error\":\"name and (genType or customGenTypeId) are required\"}");
                return;
            }
            String name = data.get("name").getAsString().trim();
            if (name.isEmpty()) {
                WebDashboard.respond(ex, 400, "{\"error\":\"name cannot be empty\"}");
                return;
            }

            String customGenTypeId = null;
            com.cheeseguard.api.GenTypeProvider provider = null;
            if (data.has("customGenTypeId") && !data.get("customGenTypeId").isJsonNull()) {
                customGenTypeId = data.get("customGenTypeId").getAsString().trim();
                if (customGenTypeId.isEmpty()) customGenTypeId = null;
                else {
                    provider = com.cheeseguard.api.GenTypeRegistry.get(customGenTypeId);
                    if (provider == null) {
                        WebDashboard.respond(ex, 400, "{\"error\":\"Unknown customGenTypeId: " + escapeJson(customGenTypeId) + "\"}");
                        return;
                    }
                }
            }

            DimensionConfig.GenType genType;
            if (customGenTypeId != null) {
                // Plugin gen-type selected: fallback enum is NORMAL (drives DimensionType + default spawnY)
                if (data.has("genType")) {
                    try { genType = DimensionConfig.GenType.valueOf(data.get("genType").getAsString().trim()); }
                    catch (Exception e) { genType = DimensionConfig.GenType.NORMAL; }
                } else {
                    genType = DimensionConfig.GenType.NORMAL;
                }
            } else {
                String genTypeStr = data.get("genType").getAsString().trim();
                try {
                    genType = DimensionConfig.GenType.valueOf(genTypeStr);
                } catch (IllegalArgumentException e) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"Invalid genType: " + escapeJson(genTypeStr) + "\"}");
                    return;
                }
            }
            final String customIdFinal = customGenTypeId;
            final com.cheeseguard.api.GenTypeProvider providerFinal = provider;
            final DimensionConfig.GenType genTypeFinal = genType;

            // Slugify the name for ID
            String id = name.toLowerCase().replaceAll("[^a-z0-9_]", "_").replaceAll("_+", "_");
            if (id.startsWith("_")) id = id.substring(1);
            if (id.endsWith("_")) id = id.substring(0, id.length() - 1);
            if (id.isEmpty()) id = "dim_" + System.currentTimeMillis() % 10000;

            String finalId = id;
            final DimensionConfig[] created = {null};
            try {
                WebDashboard.executeOnServerThread(server, () -> {
                    // Use the pre-creation configurator so seed, flatLayers, generateStructures
                    // are applied BEFORE createWorldRuntime (they affect chunk generation)
                    DimensionConfig c = dimManager.createDimension(finalId, name, genTypeFinal, cfg -> {
                        if (customIdFinal != null) {
                            cfg.setCustomGenTypeId(customIdFinal);
                            if (providerFinal != null) cfg.setSpawnY(providerFinal.defaultSpawnY());
                        }
                        if (data.has("seed")) cfg.setSeed(data.get("seed").getAsLong());
                        if (data.has("flatLayers")) cfg.setFlatLayers(data.get("flatLayers").getAsString());
                        if (data.has("generateStructures")) cfg.setGenerateStructures(data.get("generateStructures").getAsBoolean());
                    });
                    if (c == null) throw new RuntimeException("Dimension already exists or creation failed");
                    // Apply post-creation options (don't affect world gen)
                    if (data.has("spawnX")) c.setSpawnX(data.get("spawnX").getAsDouble());
                    if (data.has("spawnY")) c.setSpawnY(data.get("spawnY").getAsDouble());
                    if (data.has("spawnZ")) c.setSpawnZ(data.get("spawnZ").getAsDouble());
                    if (data.has("pvp")) c.setPvp(data.get("pvp").getAsBoolean());
                    if (data.has("mobSpawning")) c.setMobSpawning(data.get("mobSpawning").getAsBoolean());
                    if (data.has("keepInventory")) c.setKeepInventory(data.get("keepInventory").getAsBoolean());
                    if (data.has("difficulty")) c.setDifficulty(data.get("difficulty").getAsString());
                    if (data.has("timeLocked")) c.setTimeLocked(data.get("timeLocked").getAsBoolean());
                    if (data.has("lockedTime")) c.setLockedTime(data.get("lockedTime").getAsInt());
                    if (data.has("weatherLocked")) c.setWeatherLocked(data.get("weatherLocked").getAsBoolean());
                    if (data.has("clearInventory")) c.setClearInventory(data.get("clearInventory").getAsBoolean());
                    if (data.has("constructionMode")) c.setConstructionMode(data.get("constructionMode").getAsBoolean());
                    if (data.has("perPlayerMode")) c.setPerPlayerMode(data.get("perPlayerMode").getAsBoolean());
                    if (data.has("raidDensEnabled")) c.setRaidDensEnabled(data.get("raidDensEnabled").getAsBoolean());
                    // Apply time lock if set
                    if (data.has("timeLocked") && data.get("timeLocked").getAsBoolean()) {
                        dimManager.applyTimeLock(c);
                    }
                    dimManager.save();
                    created[0] = c;
                });
            } catch (Exception e) {
                WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                return;
            }
            if (created[0] == null) {
                WebDashboard.respond(ex, 500, "{\"error\":\"Creation returned null\"}");
                return;
            }
            WebDashboard.respondJson(ex, buildDimDetail(created[0], dimManager, server));
            return;
        }

        // ── Routes with dimension ID: /dimensions/:id/... ───────────────────
        if (route.startsWith("/dimensions/")) {
            String rest = route.substring("/dimensions/".length());
            int slashIdx = rest.indexOf('/');
            String id = slashIdx > 0 ? rest.substring(0, slashIdx) : rest;
            String sub = slashIdx > 0 ? rest.substring(slashIdx) : "";

            if (!isValidId(id)) {
                WebDashboard.respond(ex, 400, "{\"error\":\"Invalid dimension ID\"}");
                return;
            }

            DimensionConfig config = dimManager.getConfig(id);
            if (config == null) {
                WebDashboard.respond(ex, 404, "{\"error\":\"Dimension not found: " + escapeJson(id) + "\"}");
                return;
            }

            // ── GET /dimensions/:id — detail ────────────────────────────────
            if (sub.isEmpty() && method.equals("GET")) {
                WebDashboard.respondJson(ex, buildDimDetail(config, dimManager, server));
                return;
            }

            // ── PUT /dimensions/:id — update config ─────────────────────────
            if (sub.isEmpty() && method.equals("PUT")) {
                String body = WebDashboard.readBody(ex);
                JsonObject data = JsonParser.parseString(body).getAsJsonObject();
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        if (data.has("displayName")) config.setDisplayName(data.get("displayName").getAsString());
                        if (data.has("spawnX")) config.setSpawnX(data.get("spawnX").getAsDouble());
                        if (data.has("spawnY")) config.setSpawnY(data.get("spawnY").getAsDouble());
                        if (data.has("spawnZ")) config.setSpawnZ(data.get("spawnZ").getAsDouble());
                        if (data.has("pvp")) config.setPvp(data.get("pvp").getAsBoolean());
                        if (data.has("mobSpawning")) config.setMobSpawning(data.get("mobSpawning").getAsBoolean());
                        if (data.has("keepInventory")) config.setKeepInventory(data.get("keepInventory").getAsBoolean());
                        if (data.has("difficulty")) config.setDifficulty(data.get("difficulty").getAsString());
                        if (data.has("commandBlocks")) config.setCommandBlocks(data.get("commandBlocks").getAsBoolean());
                        if (data.has("enderChest")) config.setEnderChest(data.get("enderChest").getAsBoolean());
                        if (data.has("generateStructures")) config.setGenerateStructures(data.get("generateStructures").getAsBoolean());
                        if (data.has("clearInventory")) config.setClearInventory(data.get("clearInventory").getAsBoolean());
                        if (data.has("constructionMode")) config.setConstructionMode(data.get("constructionMode").getAsBoolean());
                        if (data.has("perPlayerMode")) config.setPerPlayerMode(data.get("perPlayerMode").getAsBoolean());
                        if (data.has("enabled")) config.setEnabled(data.get("enabled").getAsBoolean());
                        if (data.has("enabledMessage")) config.setEnabledMessage(data.get("enabledMessage").getAsString());
                        if (data.has("commandsOnBattleLoss")) {
                            java.util.List<String> list = new java.util.ArrayList<>();
                            for (var el : data.getAsJsonArray("commandsOnBattleLoss")) list.add(el.getAsString());
                            config.setCommandsOnBattleLoss(list);
                        }
                        if (data.has("commandsOnLeave")) {
                            java.util.List<String> list = new java.util.ArrayList<>();
                            for (var el : data.getAsJsonArray("commandsOnLeave")) list.add(el.getAsString());
                            config.setCommandsOnLeave(list);
                        }
                        if (data.has("timeLocked")) config.setTimeLocked(data.get("timeLocked").getAsBoolean());
                        if (data.has("lockedTime")) config.setLockedTime(data.get("lockedTime").getAsInt());
                        if (data.has("weatherLocked")) config.setWeatherLocked(data.get("weatherLocked").getAsBoolean());
                        if (data.has("lockedWeather")) config.setLockedWeather(data.get("lockedWeather").getAsString());
                        // Cobblemon blocked — if turning on, purge existing mobs
                        if (data.has("cobblemonBlocked")) {
                            boolean newVal = data.get("cobblemonBlocked").getAsBoolean();
                            config.setCobblemonBlocked(newVal);
                            if (newVal) dimManager.purgeCobblemonFromWorld(config.getWorldId());
                        }
                        if (data.has("raidDensEnabled")) config.setRaidDensEnabled(data.get("raidDensEnabled").getAsBoolean());
                        // Time lock application
                        if (data.has("timeLocked") || data.has("lockedTime")) {
                            dimManager.applyTimeLock(config);
                        }
                        // Weather lock application
                        if (data.has("weatherLocked") || data.has("lockedWeather")) {
                            dimManager.applyWeatherLock(config);
                        }
                        // World border
                        boolean borderTouched = false;
                        if (data.has("borderEnabled")) { config.setBorderEnabled(data.get("borderEnabled").getAsBoolean()); borderTouched = true; }
                        if (data.has("borderCenterX")) { config.setBorderCenterX(data.get("borderCenterX").getAsDouble()); borderTouched = true; }
                        if (data.has("borderCenterZ")) { config.setBorderCenterZ(data.get("borderCenterZ").getAsDouble()); borderTouched = true; }
                        if (data.has("borderSize")) { config.setBorderSize(data.get("borderSize").getAsDouble()); borderTouched = true; }
                        if (data.has("borderDamage")) { config.setBorderDamage(data.get("borderDamage").getAsDouble()); borderTouched = true; }
                        if (data.has("borderWarningBlocks")) { config.setBorderWarningBlocks(data.get("borderWarningBlocks").getAsInt()); borderTouched = true; }
                        if (data.has("borderWarningTime")) { config.setBorderWarningTime(data.get("borderWarningTime").getAsInt()); borderTouched = true; }
                        if (borderTouched) dimManager.applyWorldBorder(config);
                        // Denied flags (full set replace)
                        if (data.has("deniedFlags")) {
                            config.getDeniedFlags().clear();
                            for (var el : data.getAsJsonArray("deniedFlags")) {
                                config.getDeniedFlags().add(el.getAsString());
                            }
                        }
                        dimManager.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respondJson(ex, buildDimDetail(config, dimManager, server));
                return;
            }

            // ── DELETE /dimensions/:id ───────────────────────────────────────
            if (sub.isEmpty() && method.equals("DELETE")) {
                if (config.isBuiltIn()) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"Cannot delete built-in dimension\"}");
                    return;
                }
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        boolean ok = dimManager.deleteDimension(id);
                        if (!ok) throw new RuntimeException("Failed to delete dimension");
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"deleted\":true}");
                return;
            }

            // ── POST /dimensions/:id/snapshot — create snapshot ─────────────
            if (sub.equals("/snapshot") && method.equals("POST")) {
                if (config.isBuiltIn()) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"Cannot snapshot built-in dimension\"}");
                    return;
                }
                final boolean[] success = {false};
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        success[0] = dimManager.createSnapshot(id);
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                if (success[0]) {
                    JsonObject res = new JsonObject();
                    res.addProperty("created", true);
                    res.addProperty("sizeKb", dimManager.getSnapshotSizeKb(id));
                    WebDashboard.respondJson(ex, res);
                } else {
                    WebDashboard.respond(ex, 500, "{\"error\":\"Snapshot creation failed\"}");
                }
                return;
            }

            // ── POST /dimensions/:id/reset — restore from snapshot ──────────
            if (sub.equals("/reset") && method.equals("POST")) {
                if (config.isBuiltIn()) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"Cannot reset built-in dimension\"}");
                    return;
                }
                final String[] result = {null};
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        result[0] = dimManager.restoreSnapshot(id);
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                JsonObject res = new JsonObject();
                res.addProperty("status", result[0] != null ? result[0] : "error");
                WebDashboard.respondJson(ex, res);
                return;
            }

            // ── POST /dimensions/:id/toggle — toggle a boolean flag ─────────
            if (sub.equals("/toggle") && method.equals("POST")) {
                String body = WebDashboard.readBody(ex);
                JsonObject data = JsonParser.parseString(body).getAsJsonObject();
                if (!data.has("key")) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"key is required\"}");
                    return;
                }
                String key = data.get("key").getAsString();
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        switch (key) {
                            case "pvp"               -> config.setPvp(!config.isPvp());
                            case "mobSpawning"       -> config.setMobSpawning(!config.isMobSpawning());
                            case "keepInventory"     -> config.setKeepInventory(!config.isKeepInventory());
                            case "clearInventory"    -> config.setClearInventory(!config.isClearInventory());
                            case "constructionMode"  -> {
                                boolean newVal = !config.isConstructionMode();
                                config.setConstructionMode(newVal);
                                // Construction mode requires clearInventory
                                if (newVal && !config.isClearInventory()) config.setClearInventory(true);
                            }
                            case "perPlayerMode"     -> config.setPerPlayerMode(!config.isPerPlayerMode());
                            case "commandBlocks"     -> config.setCommandBlocks(!config.isCommandBlocks());
                            case "enderChest"        -> config.setEnderChest(!config.isEnderChest());
                            case "generateStructures"-> config.setGenerateStructures(!config.isGenerateStructures());
                            case "cobblemonBlocked"  -> {
                                boolean newVal = !config.isCobblemonBlocked();
                                config.setCobblemonBlocked(newVal);
                                if (newVal) dimManager.purgeCobblemonFromWorld(config.getWorldId());
                            }
                            case "enabled"           -> config.setEnabled(!config.isEnabled());
                            case "timeLocked"        -> {
                                config.setTimeLocked(!config.isTimeLocked());
                                dimManager.applyTimeLock(config);
                            }
                            case "weatherLocked"     -> {
                                config.setWeatherLocked(!config.isWeatherLocked());
                                dimManager.applyWeatherLock(config);
                            }
                            case "allowHome"         -> config.setAllowHome(!config.isAllowHome());
                            case "raidDensEnabled"   -> config.setRaidDensEnabled(!config.isRaidDensEnabled());
                            case "borderEnabled"     -> {
                                config.setBorderEnabled(!config.isBorderEnabled());
                                dimManager.applyWorldBorder(config);
                            }
                            default -> {
                                if (key.startsWith("flag.")) {
                                    String flagId = key.substring(5);
                                    config.setFlagDenied(flagId, !config.isFlagDenied(flagId));
                                } else {
                                    throw new RuntimeException("Unknown toggle key: " + key);
                                }
                            }
                        }
                        dimManager.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respondJson(ex, buildDimDetail(config, dimManager, server));
                return;
            }

            // ── PUT /dimensions/:id/snapshot/config — snapshot trigger config ──
            if (sub.equals("/snapshot/config") && method.equals("PUT")) {
                String body = WebDashboard.readBody(ex);
                JsonObject data = JsonParser.parseString(body).getAsJsonObject();
                if (!data.has("trigger")) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"trigger is required\"}");
                    return;
                }
                String triggerStr = data.get("trigger").getAsString();
                DimensionConfig.ResetTrigger trigger;
                try {
                    trigger = DimensionConfig.ResetTrigger.valueOf(triggerStr);
                } catch (IllegalArgumentException e) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"Invalid trigger: " + escapeJson(triggerStr) + "\"}");
                    return;
                }
                int hour = data.has("hour") ? data.get("hour").getAsInt() : config.getSnapshotResetHour();
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        config.setSnapshotResetTrigger(trigger);
                        config.setSnapshotResetHour(hour);
                        dimManager.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                JsonObject res = new JsonObject();
                res.addProperty("trigger", config.getSnapshotResetTrigger().name());
                res.addProperty("hour", config.getSnapshotResetHour());
                res.addProperty("updated", true);
                WebDashboard.respondJson(ex, res);
                return;
            }

            // ── POST /dimensions/:id/whitelist — add player to whitelist ────
            if (sub.equals("/whitelist") && method.equals("POST")) {
                String body = WebDashboard.readBody(ex);
                JsonObject data = JsonParser.parseString(body).getAsJsonObject();
                if (!data.has("playerName")) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"playerName is required\"}");
                    return;
                }
                String playerName = data.get("playerName").getAsString().trim();
                var profile = server.getUserCache().findByName(playerName);
                if (profile.isEmpty()) {
                    WebDashboard.respond(ex, 404, "{\"error\":\"Player not found: " + escapeJson(playerName) + "\"}");
                    return;
                }
                String uuid = profile.get().getId().toString();
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        config.addWhitelist(uuid);
                        dimManager.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                JsonObject res = new JsonObject();
                res.addProperty("added", true);
                res.addProperty("uuid", uuid);
                res.addProperty("name", profile.get().getName());
                WebDashboard.respondJson(ex, res);
                return;
            }

            // ── DELETE /dimensions/:id/whitelist/:uuid — remove from whitelist ──
            if (sub.startsWith("/whitelist/") && method.equals("DELETE")) {
                String uuid = sub.substring("/whitelist/".length());
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        config.removeWhitelist(uuid);
                        dimManager.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"removed\":true}");
                return;
            }

            // ── POST /dimensions/:id/builders — add builder ─────────────────
            if (sub.equals("/builders") && method.equals("POST")) {
                String body = WebDashboard.readBody(ex);
                JsonObject data = JsonParser.parseString(body).getAsJsonObject();
                if (!data.has("playerName")) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"playerName is required\"}");
                    return;
                }
                String playerName = data.get("playerName").getAsString().trim();
                var profile = server.getUserCache().findByName(playerName);
                if (profile.isEmpty()) {
                    WebDashboard.respond(ex, 404, "{\"error\":\"Player not found: " + escapeJson(playerName) + "\"}");
                    return;
                }
                String uuid = profile.get().getId().toString();
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        config.addConstructionBuilder(uuid);
                        dimManager.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                JsonObject res = new JsonObject();
                res.addProperty("added", true);
                res.addProperty("uuid", uuid);
                res.addProperty("name", profile.get().getName());
                WebDashboard.respondJson(ex, res);
                return;
            }

            // ── DELETE /dimensions/:id/builders/:uuid — remove builder ──────
            if (sub.startsWith("/builders/") && method.equals("DELETE")) {
                String uuid = sub.substring("/builders/".length());
                try {
                    WebDashboard.executeOnServerThread(server, () -> {
                        config.removeConstructionBuilder(uuid);
                        dimManager.save();
                    });
                } catch (Exception e) {
                    WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                    return;
                }
                WebDashboard.respond(ex, 200, "{\"removed\":true}");
                return;
            }

            // ── GET /dimensions/:id/players — list per-player dims ──────────
            if (sub.equals("/players") && method.equals("GET")) {
                List<Map<String, String>> playerDims = dimManager.getPlayerDimsForMaster(id);
                JsonArray arr = new JsonArray();
                for (Map<String, String> pd : playerDims) {
                    JsonObject o = new JsonObject();
                    o.addProperty("configId", pd.get("configId"));
                    o.addProperty("displayName", pd.get("displayName"));
                    o.addProperty("worldId", pd.get("worldId"));
                    o.addProperty("uuid8", pd.get("uuid8"));
                    // Resolve player name from uuid8
                    String playerName = resolveNameFromUuid8(server, pd.get("uuid8"));
                    o.addProperty("playerName", playerName != null ? playerName : pd.get("uuid8"));
                    // Check if online
                    boolean online = isPlayerOnline(server, pd.get("uuid8"));
                    o.addProperty("online", online);
                    arr.add(o);
                }
                WebDashboard.respondJson(ex, arr);
                return;
            }

            // ── Routes with player uuid8: /dimensions/:id/players/:uuid8/... ──
            if (sub.startsWith("/players/")) {
                String playerRest = sub.substring("/players/".length());
                int pSlash = playerRest.indexOf('/');
                String uuid8 = pSlash > 0 ? playerRest.substring(0, pSlash) : playerRest;
                String playerSub = pSlash > 0 ? playerRest.substring(pSlash) : "";

                if (!uuid8.matches("[0-9a-f]{8}")) {
                    WebDashboard.respond(ex, 400, "{\"error\":\"Invalid uuid8 format\"}");
                    return;
                }

                // Resolve full UUID from uuid8
                UUID fullUuid = resolveUuidFromUuid8(dimManager, id, uuid8);
                if (fullUuid == null) {
                    WebDashboard.respond(ex, 404, "{\"error\":\"Player dimension not found for uuid8: " + escapeJson(uuid8) + "\"}");
                    return;
                }

                // ── DELETE /dimensions/:id/players/:uuid8 — delete player dim ──
                if (playerSub.isEmpty() && method.equals("DELETE")) {
                    try {
                        WebDashboard.executeOnServerThread(server, () -> {
                            boolean ok = dimManager.deletePlayerDimension(id, fullUuid);
                            if (!ok) throw new RuntimeException("Failed to delete player dimension");
                        });
                    } catch (Exception e) {
                        WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                        return;
                    }
                    WebDashboard.respond(ex, 200, "{\"deleted\":true}");
                    return;
                }

                // ── POST /dimensions/:id/players/:uuid8/reset — reset player dim ──
                if (playerSub.equals("/reset") && method.equals("POST")) {
                    try {
                        WebDashboard.executeOnServerThread(server, () -> {
                            boolean ok = dimManager.resetPlayerDimension(id, fullUuid);
                            if (!ok) throw new RuntimeException("Failed to reset player dimension");
                        });
                    } catch (Exception e) {
                        WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                        return;
                    }
                    WebDashboard.respond(ex, 200, "{\"reset\":true}");
                    return;
                }
            }
        }

        WebDashboard.respond(ex, 404, "{\"error\":\"Route not found\"}");
    }

    // ── JSON Builders ────────────────────────────────────────────────────────

    private static JsonObject buildDimSummary(DimensionConfig c, DimensionManager dm, MinecraftServer server) {
        JsonObject o = new JsonObject();
        o.addProperty("id", c.getId());
        o.addProperty("displayName", c.getDisplayName());
        o.addProperty("genType", c.getGenType().name());
        o.addProperty("genTypeLabel", c.getGenType().label);
        if (c.getCustomGenTypeId() != null) {
            o.addProperty("customGenTypeId", c.getCustomGenTypeId());
            var prov = com.cheeseguard.api.GenTypeRegistry.get(c.getCustomGenTypeId());
            if (prov != null) {
                o.addProperty("customGenTypeLabel", prov.label());
                o.addProperty("customGenTypeIcon", prov.icon());
            }
        }
        o.addProperty("worldId", c.getWorldId());
        o.addProperty("playerCount", getPlayerCount(server, c));
        o.addProperty("snapshotEnabled", c.isSnapshotEnabled());
        // Built-in dimensions (minecraft:overworld etc.) have ':' in ID which is invalid in file paths on Windows
        // Skip snapshot/playerDim lookups for built-in dims — they never have snapshots or per-player copies
        if (c.isBuiltIn()) {
            o.addProperty("snapshotSizeKb", 0);
            o.addProperty("playerDimCount", 0);
        } else {
            o.addProperty("snapshotSizeKb", dm.getSnapshotSizeKb(c.getId()));
            o.addProperty("playerDimCount", dm.getPlayerDimsForMaster(c.getId()).size());
        }
        o.addProperty("perPlayerMode", c.isPerPlayerMode());
        o.addProperty("enabled", c.isEnabled());
        o.addProperty("builtIn", c.isBuiltIn());
        o.addProperty("pvp", c.isPvp());
        o.addProperty("mobSpawning", c.isMobSpawning());
        o.addProperty("keepInventory", c.isKeepInventory());
        o.addProperty("clearInventory", c.isClearInventory());
        o.addProperty("constructionMode", c.isConstructionMode());
        return o;
    }

    private static JsonObject buildDimDetail(DimensionConfig c, DimensionManager dm, MinecraftServer server) {
        JsonObject o = buildDimSummary(c, dm, server);
        // Full config fields
        o.addProperty("seed", c.getSeed());
        o.addProperty("flatLayers", c.getFlatLayers());
        o.addProperty("spawnX", c.getSpawnX());
        o.addProperty("spawnY", c.getSpawnY());
        o.addProperty("spawnZ", c.getSpawnZ());
        o.addProperty("timeLocked", c.isTimeLocked());
        o.addProperty("lockedTime", c.getLockedTime());
        o.addProperty("weatherLocked", c.isWeatherLocked());
        o.addProperty("lockedWeather", c.getLockedWeather());
        o.addProperty("difficulty", c.getDifficulty());
        o.addProperty("commandBlocks", c.isCommandBlocks());
        o.addProperty("enderChest", c.isEnderChest());
        o.addProperty("generateStructures", c.isGenerateStructures());
        o.addProperty("cobblemonBlocked", c.isCobblemonBlocked());
        o.addProperty("allowHome", c.isAllowHome());
        o.addProperty("raidDensEnabled", c.isRaidDensEnabled());
        o.addProperty("enabledMessage", c.getEnabledMessage());
        com.google.gson.JsonArray blArr = new com.google.gson.JsonArray();
        c.getCommandsOnBattleLoss().forEach(blArr::add);
        o.add("commandsOnBattleLoss", blArr);
        com.google.gson.JsonArray clArr = new com.google.gson.JsonArray();
        c.getCommandsOnLeave().forEach(clArr::add);
        o.add("commandsOnLeave", clArr);
        o.addProperty("snapshotResetTrigger", c.getSnapshotResetTrigger().name());
        o.addProperty("snapshotResetTriggerLabel", c.getSnapshotResetTrigger().label);
        o.addProperty("snapshotResetHour", c.getSnapshotResetHour());
        o.addProperty("lastResetAt", c.getLastResetAt());
        o.addProperty("hasPendingReset", c.isBuiltIn() ? false : dm.hasPendingReset(c.getId()));
        // Whitelist
        JsonArray wl = new JsonArray();
        for (String uuid : c.getWhitelist()) wl.add(uuid);
        o.add("whitelist", wl);
        // Builders
        JsonArray builders = new JsonArray();
        for (String uuid : c.getConstructionBuilders()) builders.add(uuid);
        o.add("constructionBuilders", builders);
        // World border
        o.addProperty("borderEnabled", c.isBorderEnabled());
        o.addProperty("borderCenterX", c.getBorderCenterX());
        o.addProperty("borderCenterZ", c.getBorderCenterZ());
        o.addProperty("borderSize", c.getBorderSize());
        o.addProperty("borderDamage", c.getBorderDamage());
        o.addProperty("borderWarningBlocks", c.getBorderWarningBlocks());
        o.addProperty("borderWarningTime", c.getBorderWarningTime());
        // Denied flags
        JsonArray df = new JsonArray();
        for (String id : c.getDeniedFlags()) df.add(id);
        o.add("deniedFlags", df);
        // All available flags (id, name, icon) for the UI
        JsonArray allFlags = new JsonArray();
        for (com.cheeseguard.data.Flag f : com.cheeseguard.data.Flag.values()) {
            JsonObject fo = new JsonObject();
            fo.addProperty("id", f.getId());
            fo.addProperty("name", f.getDisplayName());
            fo.addProperty("icon", f.getIcon());
            allFlags.add(fo);
        }
        o.add("availableFlags", allFlags);
        return o;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static int getPlayerCount(MinecraftServer server, DimensionConfig config) {
        if (server == null) return 0;
        String worldId = config.getWorldId();
        for (ServerWorld w : server.getWorlds()) {
            if (w.getRegistryKey().getValue().toString().equals(worldId)) {
                return w.getPlayers().size();
            }
        }
        return 0;
    }

    /** Check if a DimensionConfig is a per-player child dim (not a master). */
    private static boolean isPlayerDim(DimensionConfig c) {
        if (c.isBuiltIn() || c.isPerPlayerMode()) return false;
        String id = c.getId();
        // Per-player dims have format <masterid>_<8hex>
        int lastUnderscore = id.lastIndexOf('_');
        if (lastUnderscore < 0) return false;
        String suffix = id.substring(lastUnderscore + 1);
        return suffix.length() == 8 && suffix.matches("[0-9a-f]+");
    }

    /** Resolve a player name from uuid8 by searching the server user cache. */
    private static String resolveNameFromUuid8(MinecraftServer server, String uuid8) {
        if (server == null || uuid8 == null) return null;
        for (var player : server.getPlayerManager().getPlayerList()) {
            String full = player.getUuid().toString().replace("-", "");
            if (full.startsWith(uuid8)) return player.getName().getString();
        }
        // Fallback to user cache
        var cache = server.getUserCache();
        if (cache == null) return null;
        // We can't search by partial UUID in UserCache, so check online players first
        return null;
    }

    /** Check if a player is online by uuid8. */
    private static boolean isPlayerOnline(MinecraftServer server, String uuid8) {
        if (server == null || uuid8 == null) return false;
        for (var player : server.getPlayerManager().getPlayerList()) {
            String full = player.getUuid().toString().replace("-", "");
            if (full.startsWith(uuid8)) return true;
        }
        return false;
    }

    /** Resolve full UUID from uuid8 by looking at the player dims for a given master. */
    private static UUID resolveUuidFromUuid8(DimensionManager dm, String masterConfigId, String uuid8) {
        List<Map<String, String>> playerDims = dm.getPlayerDimsForMaster(masterConfigId);
        for (Map<String, String> pd : playerDims) {
            if (uuid8.equals(pd.get("uuid8"))) {
                // The configId is <masterid>_<uuid8>, and uuid8 is first 8 hex chars of UUID
                // We need to reconstruct the full UUID — search online players first
                // But since deletePlayerDimension and resetPlayerDimension need the full UUID,
                // and we only have 8 hex chars, we iterate over known player UUIDs
                // Actually, the DimensionManager methods accept a UUID where first 8 hex chars match
                // Let's look through the whitelist of the player dim config for the owner
                DimensionConfig playerConfig = dm.getConfig(pd.get("configId"));
                if (playerConfig != null && !playerConfig.getWhitelist().isEmpty()) {
                    for (String uuidStr : playerConfig.getWhitelist()) {
                        try {
                            UUID candidate = UUID.fromString(uuidStr);
                            if (candidate.toString().replace("-", "").startsWith(uuid8)) {
                                return candidate;
                            }
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
            }
        }
        return null;
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
