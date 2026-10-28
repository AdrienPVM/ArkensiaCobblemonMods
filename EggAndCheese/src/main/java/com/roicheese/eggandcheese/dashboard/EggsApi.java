package com.roicheese.eggandcheese.dashboard;

import com.cheesedash.WebDashboard;
import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.roicheese.eggandcheese.blockentity.AdminCollectibleBlockEntity;
import com.roicheese.eggandcheese.block.AdminEggPlacedEvent;
import com.roicheese.eggandcheese.data.CollectibleEntry;
import com.roicheese.eggandcheese.data.EggRegistryData;
import com.roicheese.eggandcheese.data.LiveAdminEggIndex;
import com.roicheese.eggandcheese.dna.*;
import com.roicheese.eggandcheese.ftb.FtbQuestsHook;
import com.roicheese.eggandcheese.item.CollectibleItem;
import com.roicheese.eggandcheese.network.ModPackets;
import com.roicheese.eggandcheese.registry.ModItems;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * /api/v1/eggs/* — CRUD for collectible eggs, DNA management, discoveries, config, enums, stats.
 */
public class EggsApi {

    public static void handle(HttpExchange ex, MinecraftServer server, String route) throws IOException {
        String method = ex.getRequestMethod();

        // GET /enums — all enum values for dropdowns
        if (route.equals("/enums") && method.equals("GET")) {
            handleEnums(ex);
            return;
        }

        // GET /stats
        if (route.equals("/stats") && method.equals("GET")) {
            handleStats(ex, server);
            return;
        }

        // GET /integrity — duplicate/orphan report from the live loaded-block index
        if (route.equals("/integrity") && method.equals("GET")) {
            handleIntegrity(ex, server);
            return;
        }

        // POST /integrity/reconcile — dedupe duplicates + unbind orphans (loaded chunks only)
        if (route.equals("/integrity/reconcile") && method.equals("POST")) {
            handleIntegrityReconcile(ex, server);
            return;
        }

        // GET /config
        if (route.equals("/config") && method.equals("GET")) {
            handleGetConfig(ex, server);
            return;
        }

        // PUT /config
        if (route.equals("/config") && method.equals("PUT")) {
            handlePutConfig(ex, server);
            return;
        }

        // POST /config/sync-ftb
        if (route.equals("/config/sync-ftb") && method.equals("POST")) {
            handleFtbSync(ex, server);
            return;
        }

        // GET /collectibles — list all
        if (route.equals("/collectibles") && method.equals("GET")) {
            handleList(ex, server);
            return;
        }

        // POST /collectibles — create
        if (route.equals("/collectibles") && method.equals("POST")) {
            handleCreate(ex, server);
            return;
        }

        // Routes with ID: /collectibles/:id/...
        if (route.startsWith("/collectibles/")) {
            String rest = route.substring(14);
            int slashIdx = rest.indexOf('/');
            String id = slashIdx > 0 ? rest.substring(0, slashIdx) : rest;
            String sub = slashIdx > 0 ? rest.substring(slashIdx) : "";
            id = URLDecoder.decode(id, StandardCharsets.UTF_8);

            if (!isValidId(id)) {
                WebDashboard.respond(ex, 400, "{\"error\":\"Invalid collectible ID\"}");
                return;
            }

            handleCollectibleRoutes(ex, server, id, sub, method);
            return;
        }

        WebDashboard.respond(ex, 404, "{\"error\":\"Unknown eggs route: " + escapeJson(route) + "\"}");
    }

    // ── List all ─────────────────────────────────────────────────────────────

    private static void handleList(HttpExchange ex, MinecraftServer server) throws IOException {
        try {
            final JsonArray[] result = new JsonArray[1];
            WebDashboard.executeOnServerThread(server, () -> {
                EggRegistryData registry = EggRegistryData.get(server);
                JsonArray arr = new JsonArray();
                for (CollectibleEntry entry : registry.getEntriesSorted()) {
                    arr.add(buildSummary(entry));
                }
                result[0] = arr;
            });
            WebDashboard.respondJson(ex, result[0]);
        } catch (Exception e) {
            WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    // ── Create ───────────────────────────────────────────────────────────────

    private static void handleCreate(HttpExchange ex, MinecraftServer server) throws IOException {
        String body = WebDashboard.readBody(ex);
        JsonObject data = parseJson(body);
        try {
            final JsonObject[] result = new JsonObject[1];
            WebDashboard.executeOnServerThread(server, () -> {
                EggRegistryData registry = EggRegistryData.get(server);
                CollectibleType type = CollectibleType.EGG;
                if (data.has("type")) {
                    try { type = CollectibleType.fromId(data.get("type").getAsString()); } catch (Exception ignored) {}
                }
                String dimension = data.has("dimension") ? data.get("dimension").getAsString() : "minecraft:overworld";
                int x = data.has("x") ? data.get("x").getAsInt() : 0;
                int y = data.has("y") ? data.get("y").getAsInt() : 64;
                int z = data.has("z") ? data.get("z").getAsInt() : 0;
                BlockPos pos = new BlockPos(x, y, z);

                CollectibleDNA dna;
                if (data.has("seed")) {
                    dna = CollectibleDNA.randomDNA(data.get("seed").getAsLong());
                } else {
                    dna = CollectibleDNA.generate();
                }

                String id = data.has("id") && !data.get("id").getAsString().isBlank()
                        ? data.get("id").getAsString().trim()
                        : registry.generateUniqueId();

                if (registry.hasId(id)) {
                    throw new RuntimeException("ID already exists: " + id);
                }

                CollectibleEntry entry = new CollectibleEntry(id, type, dna, pos, dimension);
                if (data.has("scale")) entry.setScale(data.get("scale").getAsFloat());
                if (data.has("title")) entry.setTitle(data.get("title").getAsString());
                if (data.has("subtitle")) entry.setSubtitle(data.get("subtitle").getAsString());
                registry.addEntry(entry);

                // Auto-create FTB quest if enabled (pass server for live client sync)
                if (registry.isFtbQuestsEnabled()) {
                    long qid = FtbQuestsHook.createQuest(registry.getFtbQuestsChapter(), entry, server);
                    if (qid != 0) { entry.setFtbQuestId(qid); registry.markDirty(); }
                }

                result[0] = buildDetail(entry);
            });
            WebDashboard.respondJson(ex, result[0]);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (e.getCause() != null) msg = e.getCause().getMessage();
            if (msg != null && msg.contains("ID already exists")) {
                WebDashboard.respond(ex, 409, "{\"error\":\"" + escapeJson(msg) + "\"}");
            } else {
                WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(msg) + "\"}");
            }
        }
    }

    // ── Collectible routes ───────────────────────────────────────────────────

    private static void handleCollectibleRoutes(HttpExchange ex, MinecraftServer server,
                                                 String id, String sub, String method) throws IOException {
        // GET /collectibles/:id
        if (sub.isEmpty() && method.equals("GET")) {
            try {
                final JsonObject[] result = new JsonObject[1];
                WebDashboard.executeOnServerThread(server, () -> {
                    EggRegistryData registry = EggRegistryData.get(server);
                    CollectibleEntry entry = registry.getEntry(id).orElse(null);
                    if (entry == null) throw new RuntimeException("NOT_FOUND");
                    result[0] = buildDetail(entry);
                });
                WebDashboard.respondJson(ex, result[0]);
            } catch (Exception e) {
                if (isNotFound(e)) { WebDashboard.respond(ex, 404, "{\"error\":\"Collectible not found\"}"); }
                else WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
            return;
        }

        // PUT /collectibles/:id — update info + DNA
        if (sub.isEmpty() && method.equals("PUT")) {
            String body = WebDashboard.readBody(ex);
            JsonObject data = parseJson(body);
            try {
                final JsonObject[] result = new JsonObject[1];
                WebDashboard.executeOnServerThread(server, () -> {
                    EggRegistryData registry = EggRegistryData.get(server);
                    CollectibleEntry entry = registry.getEntry(id).orElse(null);
                    if (entry == null) throw new RuntimeException("NOT_FOUND");
                    // Save old position/dimension before edits (needed for block relocation)
                    String oldDim = entry.getDimension();
                    BlockPos oldPos = entry.getPos();
                    applyEdits(entry, data);
                    registry.markDirty();
                    // Update linked FTB quest if title/subtitle changed
                    if (registry.isFtbQuestsEnabled() && entry.getFtbQuestId() != 0) {
                        FtbQuestsHook.updateQuest(entry.getFtbQuestId(), entry.getTitle(), entry.getSubtitle(), server);
                    }
                    // Relocate block if position or dimension changed
                    boolean posChanged = data.has("x") || data.has("y") || data.has("z") || data.has("dimension");
                    if (!entry.isPositionLost() && posChanged) {
                        BlockPos newPos = entry.getPos();
                        String newDim = entry.getDimension();
                        ServerWorld oldWorld = null, newWorld = null;
                        for (ServerWorld sw : server.getWorlds()) {
                            String swId = sw.getRegistryKey().getValue().toString();
                            if (swId.equals(oldDim)) oldWorld = sw;
                            if (swId.equals(newDim)) newWorld = sw;
                        }
                        if (oldWorld != null && newWorld != null && (!oldPos.equals(newPos) || !oldDim.equals(newDim))) {
                            // Read data from old block entity
                            AdminCollectibleBlockEntity oldBe = null;
                            if (oldWorld.getBlockEntity(oldPos) instanceof AdminCollectibleBlockEntity be) {
                                oldBe = be;
                            }
                            // Remove old block
                            oldWorld.removeBlock(oldPos, false);
                            // Place new block and configure it
                            newWorld.setBlockState(newPos, com.roicheese.eggandcheese.registry.ModBlocks.ADMIN_EGG.getDefaultState());
                            if (newWorld.getBlockEntity(newPos) instanceof AdminCollectibleBlockEntity newBe) {
                                newBe.setRegistryId(entry.getId());
                                newBe.setDna(entry.getDna());
                                newBe.setScale(entry.getScale());
                                newBe.setRenderDistanceOverride(entry.getRenderDistanceOverride());
                            }
                        }
                    }
                    // Sync block entity if renderDistanceOverride, scale, or DNA changed (in-place, no move)
                    if (!entry.isPositionLost() && !posChanged && (data.has("renderDistanceOverride") || data.has("scale") || data.has("dna"))) {
                        String dim = entry.getDimension();
                        BlockPos bpos = entry.getPos();
                        for (ServerWorld sw : server.getWorlds()) {
                            if (sw.getRegistryKey().getValue().toString().equals(dim)) {
                                if (sw.getBlockEntity(bpos) instanceof AdminCollectibleBlockEntity be) {
                                    if (data.has("renderDistanceOverride"))
                                        be.setRenderDistanceOverride(entry.getRenderDistanceOverride());
                                    if (data.has("scale"))
                                        be.setScale(entry.getScale());
                                    if (data.has("dna"))
                                        be.setDna(entry.getDna());
                                }
                                break;
                            }
                        }
                    }
                    result[0] = buildDetail(entry);
                });
                WebDashboard.respondJson(ex, result[0]);
            } catch (Exception e) {
                if (isNotFound(e)) { WebDashboard.respond(ex, 404, "{\"error\":\"Collectible not found\"}"); }
                else WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
            return;
        }

        // DELETE /collectibles/:id
        if (sub.isEmpty() && method.equals("DELETE")) {
            try {
                WebDashboard.executeOnServerThread(server, () -> {
                    EggRegistryData registry = EggRegistryData.get(server);
                    if (!registry.hasId(id)) throw new RuntimeException("NOT_FOUND");
                    CollectibleEntry delEntry = registry.getEntry(id).orElse(null);
                    if (delEntry != null) {
                        // Delete linked FTB quest
                        if (registry.isFtbQuestsEnabled() && delEntry.getFtbQuestId() != 0) {
                            FtbQuestsHook.deleteQuest(delEntry.getFtbQuestId());
                        }
                        // Remove the physical block from the world
                        if (!delEntry.isPositionLost()) {
                            String dim = delEntry.getDimension();
                            BlockPos pos = delEntry.getPos();
                            for (ServerWorld sw : server.getWorlds()) {
                                if (sw.getRegistryKey().getValue().toString().equals(dim)) {
                                    if (sw.getBlockEntity(pos) instanceof AdminCollectibleBlockEntity) {
                                        sw.removeBlock(pos, false);
                                    }
                                    break;
                                }
                            }
                        }
                    }
                    registry.removeEntry(id);
                });
                WebDashboard.respond(ex, 200, "{\"deleted\":true}");
            } catch (Exception e) {
                if (isNotFound(e)) { WebDashboard.respond(ex, 404, "{\"error\":\"Collectible not found\"}"); }
                else WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
            return;
        }

        // POST /collectibles/:id/rename
        if (sub.equals("/rename") && method.equals("POST")) {
            String body = WebDashboard.readBody(ex);
            JsonObject data = parseJson(body);
            String newId = data.has("newId") ? data.get("newId").getAsString().trim() : "";
            if (newId.isEmpty() || !isValidId(newId)) {
                WebDashboard.respond(ex, 400, "{\"error\":\"Invalid new ID\"}");
                return;
            }
            try {
                final JsonObject[] result = new JsonObject[1];
                WebDashboard.executeOnServerThread(server, () -> {
                    EggRegistryData registry = EggRegistryData.get(server);
                    if (!registry.renameEntry(id, newId)) throw new RuntimeException("Rename failed (ID conflict or not found)");
                    CollectibleEntry entry = registry.getEntry(newId).orElse(null);
                    if (entry == null) throw new RuntimeException("NOT_FOUND");
                    // Update FTB quest title (uses entry title, not egg ID as quest title)
                    if (registry.isFtbQuestsEnabled() && entry.getFtbQuestId() != 0) {
                        FtbQuestsHook.updateQuest(entry.getFtbQuestId(), entry.getTitle(), entry.getSubtitle(), server);
                    }
                    result[0] = buildDetail(entry);
                });
                WebDashboard.respondJson(ex, result[0]);
            } catch (Exception e) {
                WebDashboard.respond(ex, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
            return;
        }

        // POST /collectibles/:id/randomize-dna
        if (sub.equals("/randomize-dna") && method.equals("POST")) {
            String body = WebDashboard.readBody(ex);
            JsonObject data = parseJson(body);
            try {
                final JsonObject[] result = new JsonObject[1];
                WebDashboard.executeOnServerThread(server, () -> {
                    EggRegistryData registry = EggRegistryData.get(server);
                    CollectibleEntry entry = registry.getEntry(id).orElse(null);
                    if (entry == null) throw new RuntimeException("NOT_FOUND");
                    long seed = data.has("seed") ? data.get("seed").getAsLong() : new Random().nextLong();
                    entry.setDna(CollectibleDNA.randomDNA(seed));
                    registry.markDirty();
                    // Sync block entity with new DNA
                    if (!entry.isPositionLost()) {
                        String dim = entry.getDimension();
                        BlockPos bpos = entry.getPos();
                        for (ServerWorld sw : server.getWorlds()) {
                            if (sw.getRegistryKey().getValue().toString().equals(dim)) {
                                if (sw.getBlockEntity(bpos) instanceof AdminCollectibleBlockEntity be) {
                                    be.setDna(entry.getDna());
                                }
                                break;
                            }
                        }
                    }
                    result[0] = buildDetail(entry);
                });
                WebDashboard.respondJson(ex, result[0]);
            } catch (Exception e) {
                if (isNotFound(e)) { WebDashboard.respond(ex, 404, "{\"error\":\"Collectible not found\"}"); }
                else WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
            return;
        }

        // GET /collectibles/:id/discoveries
        if (sub.equals("/discoveries") && method.equals("GET")) {
            try {
                final JsonArray[] result = new JsonArray[1];
                WebDashboard.executeOnServerThread(server, () -> {
                    EggRegistryData registry = EggRegistryData.get(server);
                    CollectibleEntry entry = registry.getEntry(id).orElse(null);
                    if (entry == null) throw new RuntimeException("NOT_FOUND");
                    JsonArray arr = new JsonArray();
                    for (UUID uuid : entry.getDiscoveredBy()) {
                        JsonObject d = new JsonObject();
                        d.addProperty("uuid", uuid.toString());
                        var profile = server.getUserCache().getByUuid(uuid);
                        d.addProperty("name", profile.isPresent() ? profile.get().getName() : uuid.toString().substring(0, 8));
                        arr.add(d);
                    }
                    result[0] = arr;
                });
                WebDashboard.respondJson(ex, result[0]);
            } catch (Exception e) {
                if (isNotFound(e)) { WebDashboard.respond(ex, 404, "{\"error\":\"Collectible not found\"}"); }
                else WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
            return;
        }

        // POST /collectibles/:id/discoveries — add discovery by player name
        if (sub.equals("/discoveries") && method.equals("POST")) {
            String body = WebDashboard.readBody(ex);
            JsonObject data = parseJson(body);
            String playerName = data.has("playerName") ? data.get("playerName").getAsString().trim() : "";
            if (playerName.isEmpty()) {
                WebDashboard.respond(ex, 400, "{\"error\":\"playerName required\"}");
                return;
            }
            try {
                WebDashboard.executeOnServerThread(server, () -> {
                    EggRegistryData registry = EggRegistryData.get(server);
                    CollectibleEntry entry = registry.getEntry(id).orElse(null);
                    if (entry == null) throw new RuntimeException("NOT_FOUND");
                    var profile = server.getUserCache().findByName(playerName);
                    if (profile.isEmpty()) throw new RuntimeException("Player not found: " + playerName);
                    entry.addDiscovery(profile.get().getId());
                    registry.markDirty();
                });
                WebDashboard.respond(ex, 200, "{\"added\":true}");
            } catch (Exception e) {
                if (isNotFound(e)) { WebDashboard.respond(ex, 404, "{\"error\":\"Collectible not found\"}"); }
                else WebDashboard.respond(ex, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
            return;
        }

        // DELETE /collectibles/:id/discoveries — clear all
        if (sub.equals("/discoveries") && method.equals("DELETE")) {
            try {
                WebDashboard.executeOnServerThread(server, () -> {
                    EggRegistryData registry = EggRegistryData.get(server);
                    CollectibleEntry entry = registry.getEntry(id).orElse(null);
                    if (entry == null) throw new RuntimeException("NOT_FOUND");
                    entry.clearDiscoveries();
                    registry.markDirty();
                });
                WebDashboard.respond(ex, 200, "{\"cleared\":true}");
            } catch (Exception e) {
                if (isNotFound(e)) { WebDashboard.respond(ex, 404, "{\"error\":\"Collectible not found\"}"); }
                else WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
            return;
        }

        // POST /collectibles/:id/give-admin — give admin egg item to a player
        if (sub.equals("/give-admin") && method.equals("POST")) {
            String body = WebDashboard.readBody(ex);
            JsonObject data = parseJson(body);
            String playerName = data.has("playerName") ? data.get("playerName").getAsString().trim() : "";
            if (playerName.isEmpty()) {
                WebDashboard.respond(ex, 400, "{\"error\":\"playerName required\"}");
                return;
            }
            try {
                WebDashboard.executeOnServerThread(server, () -> {
                    EggRegistryData registry = EggRegistryData.get(server);
                    CollectibleEntry entry = registry.getEntry(id).orElse(null);
                    if (entry == null) throw new RuntimeException("NOT_FOUND");
                    ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
                    if (player == null) throw new RuntimeException("Player not online: " + playerName);
                    ItemStack stack = new ItemStack(ModItems.ADMIN_EGG);
                    NbtCompound nbt = new NbtCompound();
                    nbt.put("dna", entry.getDna().toNbt());
                    nbt.putString("registryId", entry.getId());
                    nbt.putFloat("scale", entry.getScale());
                    stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
                    if (!player.getInventory().insertStack(stack)) player.dropItem(stack, false);
                });
                WebDashboard.respond(ex, 200, "{\"given\":true,\"type\":\"admin\"}");
            } catch (Exception e) {
                if (isNotFound(e)) { WebDashboard.respond(ex, 404, "{\"error\":\"Collectible not found\"}"); }
                else WebDashboard.respond(ex, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
            return;
        }

        // POST /collectibles/:id/give-deco — give deco egg item to a player
        if (sub.equals("/give-deco") && method.equals("POST")) {
            String body = WebDashboard.readBody(ex);
            JsonObject data = parseJson(body);
            String playerName = data.has("playerName") ? data.get("playerName").getAsString().trim() : "";
            if (playerName.isEmpty()) {
                WebDashboard.respond(ex, 400, "{\"error\":\"playerName required\"}");
                return;
            }
            try {
                WebDashboard.executeOnServerThread(server, () -> {
                    EggRegistryData registry = EggRegistryData.get(server);
                    CollectibleEntry entry = registry.getEntry(id).orElse(null);
                    if (entry == null) throw new RuntimeException("NOT_FOUND");
                    ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
                    if (player == null) throw new RuntimeException("Player not online: " + playerName);
                    ItemStack decoStack = CollectibleItem.create(entry.getType(), entry.getDna());
                    NbtCompound existing = decoStack.get(DataComponentTypes.CUSTOM_DATA) != null
                            ? decoStack.get(DataComponentTypes.CUSTOM_DATA).copyNbt()
                            : new NbtCompound();
                    existing.putString("registryId", entry.getId());
                    decoStack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(existing));
                    if (!player.getInventory().insertStack(decoStack)) player.dropItem(decoStack, false);
                });
                WebDashboard.respond(ex, 200, "{\"given\":true,\"type\":\"deco\"}");
            } catch (Exception e) {
                if (isNotFound(e)) { WebDashboard.respond(ex, 404, "{\"error\":\"Collectible not found\"}"); }
                else WebDashboard.respond(ex, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
            return;
        }

        // DELETE /collectibles/:id/discoveries/:uuid — remove single
        if (sub.startsWith("/discoveries/") && method.equals("DELETE")) {
            String uuidStr = sub.substring(13);
            try {
                UUID uuid = UUID.fromString(uuidStr);
                WebDashboard.executeOnServerThread(server, () -> {
                    EggRegistryData registry = EggRegistryData.get(server);
                    CollectibleEntry entry = registry.getEntry(id).orElse(null);
                    if (entry == null) throw new RuntimeException("NOT_FOUND");
                    entry.getDiscoveredBy().remove(uuid);
                    registry.markDirty();
                });
                WebDashboard.respond(ex, 200, "{\"removed\":true}");
            } catch (IllegalArgumentException e) {
                WebDashboard.respond(ex, 400, "{\"error\":\"Invalid UUID\"}");
            } catch (Exception e) {
                if (isNotFound(e)) { WebDashboard.respond(ex, 404, "{\"error\":\"Collectible not found\"}"); }
                else WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
            return;
        }

        WebDashboard.respond(ex, 404, "{\"error\":\"Unknown collectible route\"}");
    }

    // ── Config ───────────────────────────────────────────────────────────────

    private static void handleGetConfig(HttpExchange ex, MinecraftServer server) throws IOException {
        try {
            final JsonObject[] result = new JsonObject[1];
            WebDashboard.executeOnServerThread(server, () -> {
                EggRegistryData registry = EggRegistryData.get(server);
                JsonObject o = new JsonObject();
                o.addProperty("ftbQuestsEnabled", registry.isFtbQuestsEnabled());
                o.addProperty("ftbQuestsChapter", registry.getFtbQuestsChapter());
                o.addProperty("ftbAutoSync", registry.isFtbAutoSync());
                o.addProperty("textureRenderDistance", registry.getTextureRenderDistance());
                o.addProperty("totalEntries", registry.getAllEntries().size());
                o.addProperty("totalDiscoveries", registry.getTotalDiscoveries());
                result[0] = o;
            });
            WebDashboard.respondJson(ex, result[0]);
        } catch (Exception e) {
            WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private static void handlePutConfig(HttpExchange ex, MinecraftServer server) throws IOException {
        String body = WebDashboard.readBody(ex);
        JsonObject data = parseJson(body);
        try {
            WebDashboard.executeOnServerThread(server, () -> {
                EggRegistryData registry = EggRegistryData.get(server);
                if (data.has("ftbQuestsEnabled")) registry.setFtbQuestsEnabled(data.get("ftbQuestsEnabled").getAsBoolean());
                if (data.has("ftbQuestsChapter")) registry.setFtbQuestsChapter(data.get("ftbQuestsChapter").getAsString());
                if (data.has("ftbAutoSync"))      registry.setFtbAutoSync(data.get("ftbAutoSync").getAsBoolean());
                if (data.has("textureRenderDistance")) registry.setTextureRenderDistance(data.get("textureRenderDistance").getAsInt());
                registry.markDirty();
                // Sync render distance config to all connected clients
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    ModPackets.sendRegistrySync(p, registry);
                }
            });
            handleGetConfig(ex, server);
        } catch (Exception e) {
            WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private static void handleFtbSync(HttpExchange ex, MinecraftServer server) throws IOException {
        try {
            WebDashboard.executeOnServerThread(server, () -> {
                EggRegistryData registry = EggRegistryData.get(server);
                FtbQuestsHook.syncAll(server, registry);
            });
            WebDashboard.respond(ex, 200, "{\"synced\":true}");
        } catch (Exception e) {
            WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    // ── Stats ────────────────────────────────────────────────────────────────

    private static void handleStats(HttpExchange ex, MinecraftServer server) throws IOException {
        try {
            final JsonObject[] result = new JsonObject[1];
            WebDashboard.executeOnServerThread(server, () -> {
                EggRegistryData registry = EggRegistryData.get(server);
                JsonObject o = new JsonObject();
                Collection<CollectibleEntry> all = registry.getAllEntries();
                o.addProperty("totalEntries", all.size());
                o.addProperty("totalDiscoveries", registry.getTotalDiscoveries());

                // By type
                int eggs = 0, cheeses = 0;
                for (CollectibleEntry e : all) {
                    if (e.getType() == CollectibleType.CHEESE) cheeses++;
                    else eggs++;
                }
                o.addProperty("eggCount", eggs);
                o.addProperty("cheeseCount", cheeses);

                // By rarity
                JsonObject byRarity = new JsonObject();
                for (RarityType r : RarityType.values()) byRarity.addProperty(r.name(), 0);
                for (CollectibleEntry e : all) {
                    String rName = e.getDna().rarity().name();
                    byRarity.addProperty(rName, byRarity.get(rName).getAsInt() + 1);
                }
                o.add("byRarity", byRarity);

                result[0] = o;
            });
            WebDashboard.respondJson(ex, result[0]);
        } catch (Exception e) {
            WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    // ── Integrity (duplicate / orphan detection + repair) ──────────────────────

    private static JsonObject locToJson(LiveAdminEggIndex.EggLoc gp) {
        JsonObject j = new JsonObject();
        j.addProperty("dimension", gp.dim());
        j.addProperty("x", gp.pos().getX());
        j.addProperty("y", gp.pos().getY());
        j.addProperty("z", gp.pos().getZ());
        return j;
    }

    private static void handleIntegrity(HttpExchange ex, MinecraftServer server) throws IOException {
        try {
            final JsonObject[] result = new JsonObject[1];
            WebDashboard.executeOnServerThread(server, () -> {
                EggRegistryData reg = EggRegistryData.get(server);
                Map<String, Set<LiveAdminEggIndex.EggLoc>> snap = LiveAdminEggIndex.snapshot();
                JsonObject o = new JsonObject();
                int loaded = 0;
                JsonArray duplicates = new JsonArray();
                JsonArray orphans = new JsonArray();
                for (Map.Entry<String, Set<LiveAdminEggIndex.EggLoc>> e : snap.entrySet()) {
                    String id = e.getKey();
                    Set<LiveAdminEggIndex.EggLoc> locs = e.getValue();
                    loaded += locs.size();
                    if (locs.size() > 1) {
                        JsonObject d = new JsonObject();
                        d.addProperty("id", id);
                        JsonArray ps = new JsonArray();
                        for (LiveAdminEggIndex.EggLoc gp : locs) ps.add(locToJson(gp));
                        d.add("positions", ps);
                        duplicates.add(d);
                    }
                    if (reg.getEntry(id).isEmpty()) {
                        for (LiveAdminEggIndex.EggLoc gp : locs) {
                            JsonObject orf = locToJson(gp);
                            orf.addProperty("id", id);
                            orphans.add(orf);
                        }
                    }
                }
                // Registry entries whose canonical block is not currently loaded (info only — can't verify offline)
                JsonArray unloaded = new JsonArray();
                for (CollectibleEntry entry : reg.getAllEntries()) {
                    if (entry.isPositionLost()) continue;
                    LiveAdminEggIndex.EggLoc loc = new LiveAdminEggIndex.EggLoc(entry.getDimension(), entry.getPos());
                    Set<LiveAdminEggIndex.EggLoc> locs = snap.get(entry.getId());
                    if (locs == null || !locs.contains(loc)) unloaded.add(entry.getId());
                }
                o.addProperty("loadedBlocks", loaded);
                o.addProperty("duplicateCount", duplicates.size());
                o.addProperty("orphanCount", orphans.size());
                o.add("duplicates", duplicates);
                o.add("orphans", orphans);
                o.add("unloadedEntries", unloaded);
                result[0] = o;
            });
            WebDashboard.respondJson(ex, result[0]);
        } catch (Exception e) {
            WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private static void handleIntegrityReconcile(HttpExchange ex, MinecraftServer server) throws IOException {
        try {
            final JsonObject[] result = new JsonObject[1];
            WebDashboard.executeOnServerThread(server, () -> {
                EggRegistryData reg = EggRegistryData.get(server);
                Map<String, Set<LiveAdminEggIndex.EggLoc>> snap = LiveAdminEggIndex.snapshot();
                int dedup = 0, orphansCleared = 0;
                for (Map.Entry<String, Set<LiveAdminEggIndex.EggLoc>> e : snap.entrySet()) {
                    String id = e.getKey();
                    Set<LiveAdminEggIndex.EggLoc> locs = e.getValue();
                    if (reg.getEntry(id).isEmpty()) {
                        // orphan: unbind every block claiming this (non-existent) id
                        for (LiveAdminEggIndex.EggLoc gp : locs) {
                            if (AdminEggPlacedEvent.unbindAt(server, id, gp)) orphansCleared++;
                        }
                    } else if (locs.size() > 1) {
                        int before = locs.size();
                        AdminEggPlacedEvent.reconcile(server, id, null);
                        dedup += Math.max(0, before - 1);
                    }
                }
                JsonObject o = new JsonObject();
                o.addProperty("duplicatesResolved", dedup);
                o.addProperty("orphansCleared", orphansCleared);
                result[0] = o;
            });
            WebDashboard.respondJson(ex, result[0]);
        } catch (Exception e) {
            WebDashboard.respond(ex, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    // ── Enums ────────────────────────────────────────────────────────────────

    private static void handleEnums(HttpExchange ex) throws IOException {
        JsonObject o = new JsonObject();

        o.add("palettes", enumToArray(PaletteType.values(), e -> {
            JsonObject j = new JsonObject();
            j.addProperty("id", e.name());
            j.addProperty("displayName", e.getDisplayName());
            // Convert palette colors to hex strings
            JsonArray colors = new JsonArray();
            for (int c : e.getColors()) colors.add(String.format("%06X", c & 0xFFFFFF));
            j.add("colors", colors);
            return j;
        }));
        o.add("patterns", enumToArray(PatternType.values(), e -> {
            JsonObject j = new JsonObject(); j.addProperty("id", e.name()); j.addProperty("displayName", e.getDisplayName()); return j;
        }));
        o.add("emissives", enumToArray(EmissiveType.values(), e -> {
            JsonObject j = new JsonObject(); j.addProperty("id", e.name()); j.addProperty("displayName", e.getDisplayName()); return j;
        }));
        o.add("rarities", enumToArray(RarityType.values(), e -> {
            JsonObject j = new JsonObject();
            j.addProperty("id", e.name());
            j.addProperty("displayName", e.getDisplayName());
            j.addProperty("color", String.format("#%06X", e.getColor() & 0xFFFFFF));
            return j;
        }));
        o.add("stamps", enumToArray(StampType.values(), e -> {
            JsonObject j = new JsonObject(); j.addProperty("id", e.name()); j.addProperty("displayName", e.getDisplayName()); return j;
        }));
        o.add("microTextures", enumToArray(MicroTextureType.values(), e -> {
            JsonObject j = new JsonObject(); j.addProperty("id", e.name()); j.addProperty("displayName", e.getDisplayName()); return j;
        }));
        o.add("types", enumToArray(CollectibleType.values(), e -> {
            JsonObject j = new JsonObject(); j.addProperty("id", e.getId()); j.addProperty("name", e.name()); return j;
        }));

        WebDashboard.respondJson(ex, o);
    }

    // ── JSON builders ────────────────────────────────────────────────────────

    private static JsonObject buildSummary(CollectibleEntry entry) {
        JsonObject o = new JsonObject();
        o.addProperty("id", entry.getId());
        o.addProperty("type", entry.getType().getId());
        o.addProperty("title", entry.getTitle());
        o.addProperty("subtitle", entry.getSubtitle());
        o.addProperty("dimension", entry.getDimension());
        o.addProperty("x", entry.getPos().getX());
        o.addProperty("y", entry.getPos().getY());
        o.addProperty("z", entry.getPos().getZ());
        o.addProperty("scale", entry.getScale());
        o.addProperty("renderDistanceOverride", entry.getRenderDistanceOverride());
        o.addProperty("positionLost", entry.isPositionLost());
        o.addProperty("discoveryCount", entry.getDiscoveredBy().size());
        o.addProperty("decoCount", entry.getDecoReceivedBy().size());
        // DNA summary fields
        CollectibleDNA dna = entry.getDna();
        o.addProperty("rarity", dna.rarity().name());
        o.addProperty("rarityDisplay", dna.rarity().getDisplayName());
        o.addProperty("rarityColor", String.format("#%06X", dna.rarity().getColor() & 0xFFFFFF));
        o.addProperty("palette", dna.palette().name());
        o.addProperty("paletteDisplay", dna.palette().getDisplayName());
        return o;
    }

    private static JsonObject buildDetail(CollectibleEntry entry) {
        JsonObject o = buildSummary(entry);
        // Full DNA
        CollectibleDNA dna = entry.getDna();
        JsonObject dnaObj = new JsonObject();
        dnaObj.addProperty("seed", dna.seed());
        dnaObj.addProperty("palette", dna.palette().name());
        dnaObj.addProperty("pattern", dna.pattern().name());
        dnaObj.addProperty("patternScale", dna.patternScale());
        dnaObj.addProperty("secondaryPattern", dna.secondaryPattern().name());
        dnaObj.addProperty("patternBlend", dna.patternBlend());
        dnaObj.addProperty("emissive", dna.emissive().name());
        dnaObj.addProperty("emissiveDensity", dna.emissiveDensity());
        dnaObj.addProperty("rarity", dna.rarity().name());
        dnaObj.addProperty("stamp", dna.stamp().name());
        dnaObj.addProperty("microTexture", dna.microTexture().name());
        dnaObj.addProperty("visualFlags", dna.visualFlags());
        dnaObj.addProperty("varnishLevel", dna.varnishLevel());
        dnaObj.addProperty("customPalette", dna.customPalette() != null ? dna.customPalette() : "");
        dnaObj.addProperty("dnaCode", dna.toDnaCode());
        // Visual flags decoded
        dnaObj.addProperty("flagLighting", dna.hasFlag(CollectibleDNA.FLAG_LIGHTING));
        dnaObj.addProperty("flagSpecular", dna.hasFlag(CollectibleDNA.FLAG_SPECULAR));
        dnaObj.addProperty("flagEmboss", dna.hasFlag(CollectibleDNA.FLAG_EMBOSS));
        dnaObj.addProperty("flagVarnish", dna.hasFlag(CollectibleDNA.FLAG_VARNISH));
        dnaObj.addProperty("flagRimGlow", dna.hasFlag(CollectibleDNA.FLAG_RIM_GLOW));
        o.add("dna", dnaObj);

        // Discoveries list (just UUIDs — full list via /discoveries endpoint)
        JsonArray discoveries = new JsonArray();
        entry.getDiscoveredBy().forEach(u -> discoveries.add(u.toString()));
        o.add("discoveredByUuids", discoveries);

        return o;
    }

    // ── Edit applier ─────────────────────────────────────────────────────────

    private static void applyEdits(CollectibleEntry entry, JsonObject j) {
        if (j.has("title")) entry.setTitle(j.get("title").getAsString());
        if (j.has("subtitle")) entry.setSubtitle(j.get("subtitle").getAsString());
        if (j.has("scale")) entry.setScale(j.get("scale").getAsFloat());
        if (j.has("positionLost")) entry.setPositionLost(j.get("positionLost").getAsBoolean());
        if (j.has("renderDistanceOverride")) entry.setRenderDistanceOverride(j.get("renderDistanceOverride").getAsInt());
        if (j.has("dimension")) entry.setDimension(j.get("dimension").getAsString());
        if (j.has("x") || j.has("y") || j.has("z")) {
            int x = j.has("x") ? j.get("x").getAsInt() : entry.getPos().getX();
            int y = j.has("y") ? j.get("y").getAsInt() : entry.getPos().getY();
            int z = j.has("z") ? j.get("z").getAsInt() : entry.getPos().getZ();
            entry.setPos(new BlockPos(x, y, z));
        }
        // DNA update — merge provided fields with existing DNA (since record is immutable, reconstruct)
        if (j.has("dna")) {
            JsonObject d = j.getAsJsonObject("dna");
            CollectibleDNA old = entry.getDna();
            long seed              = d.has("seed")             ? d.get("seed").getAsLong()                : old.seed();
            PaletteType palette    = d.has("palette")          ? safeEnum(PaletteType.class, d.get("palette").getAsString(), old.palette())               : old.palette();
            PatternType pattern    = d.has("pattern")          ? safeEnum(PatternType.class, d.get("pattern").getAsString(), old.pattern())               : old.pattern();
            int patternScale       = d.has("patternScale")     ? clamp(d.get("patternScale").getAsInt(), 1, 4)    : old.patternScale();
            PatternType secPattern = d.has("secondaryPattern") ? safeEnum(PatternType.class, d.get("secondaryPattern").getAsString(), old.secondaryPattern()) : old.secondaryPattern();
            int blend              = d.has("patternBlend")     ? clamp(d.get("patternBlend").getAsInt(), 0, 100)  : old.patternBlend();
            EmissiveType emissive  = d.has("emissive")         ? safeEnum(EmissiveType.class, d.get("emissive").getAsString(), old.emissive())             : old.emissive();
            int density            = d.has("emissiveDensity")  ? clamp(d.get("emissiveDensity").getAsInt(), 0, 100) : old.emissiveDensity();
            RarityType rarity      = d.has("rarity")           ? safeEnum(RarityType.class, d.get("rarity").getAsString(), old.rarity())                   : old.rarity();
            StampType stamp        = d.has("stamp")            ? safeEnum(StampType.class, d.get("stamp").getAsString(), old.stamp())                      : old.stamp();
            MicroTextureType micro = d.has("microTexture")     ? safeEnum(MicroTextureType.class, d.get("microTexture").getAsString(), old.microTexture()) : old.microTexture();
            int flags              = d.has("visualFlags")      ? d.get("visualFlags").getAsInt()                    : old.visualFlags();
            int varnish            = d.has("varnishLevel")     ? clamp(d.get("varnishLevel").getAsInt(), 0, 100)   : old.varnishLevel();
            String customPalette   = d.has("customPalette")    ? d.get("customPalette").getAsString()               : old.customPalette();

            entry.setDna(new CollectibleDNA(seed, palette, pattern, patternScale, secPattern, blend,
                    emissive, density, rarity, stamp, micro, flags, varnish, customPalette != null ? customPalette : ""));
        }
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    private static boolean isValidId(String id) {
        return id != null && !id.isEmpty() && !id.contains("..") && !id.contains("/")
                && !id.contains("\\") && !id.contains("%") && id.length() < 128;
    }

    private static String escapeJson(String s) {
        if (s == null) return "Unknown error";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static JsonObject parseJson(String s) {
        try { return JsonParser.parseString(s).getAsJsonObject(); }
        catch (Exception e) { return new JsonObject(); }
    }

    private static boolean isNotFound(Exception e) {
        String msg = e.getMessage();
        if (msg != null && msg.contains("NOT_FOUND")) return true;
        if (e.getCause() != null) {
            msg = e.getCause().getMessage();
            return msg != null && msg.contains("NOT_FOUND");
        }
        return false;
    }

    private static <T extends Enum<T>> T safeEnum(Class<T> cls, String name, T def) {
        try { return Enum.valueOf(cls, name); }
        catch (Exception e) { return def; }
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    @FunctionalInterface
    private interface EnumMapper<T> { JsonObject map(T value); }

    private static <T> JsonArray enumToArray(T[] values, EnumMapper<T> mapper) {
        JsonArray arr = new JsonArray();
        for (T v : values) arr.add(mapper.map(v));
        return arr;
    }
}
