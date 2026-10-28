package com.cheeseguard;

import com.cheeseguard.commands.CheeseGuardCommand;
import com.cheeseguard.data.*;
import com.cheeseguard.logic.*;
import com.cheeseguard.network.GuiSyncPayload;
import com.cheeseguard.network.PropHuntCyclePowerPayload;
import com.google.gson.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

public class CheeseGuardMod implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("CheeseGuard");
    public static CheeseGuardMod INSTANCE;

    private MinecraftServer server;
    private RegionStorage storage;
    private RegionManager regionManager;
    private SelectionManager selectionManager;
    private VisualizationManager visualizationManager;
    private ChunkSetStorage chunkSetStorage;
    private ChunkSuggestionStorage chunkSuggestionStorage;
    private TagConfigStorage tagConfigStorage;
    private ServerManager serverManager;

    // ── Chunk zone notification tracking ─────────────────────────────────────
    // playerUUID -> set of "setName:chunkX:chunkZ" keys the player is currently inside
    private final Map<UUID, Set<String>> playerChunkZones = new HashMap<>();
    private int chunkCheckTick = 0;
    private int chunkResetScheduleTick = 0;

    private final Map<UUID, Consumer<String>> chatInputCallbacks = new HashMap<>();

    private boolean ftbQuestsLoaded = false;
    private final EventManager eventManager = new EventManager();
    private final com.cheeseguard.logic.DimensionManager dimensionManager = new com.cheeseguard.logic.DimensionManager();
    private final com.cheeseguard.logic.ZoneStateManager zoneStateManager = new com.cheeseguard.logic.ZoneStateManager();
    private final PropHuntIconStorage propHuntIconStorage = new PropHuntIconStorage();
    private final PastureLimitsManager pastureLimitsManager = new PastureLimitsManager();
    private final com.cheeseguard.logic.PrisonManager prisonManager = new com.cheeseguard.logic.PrisonManager();
    private final com.cheeseguard.logic.PrisonDoppelgangerManager prisonDoppelgangerManager = new com.cheeseguard.logic.PrisonDoppelgangerManager();

    @Override
    public void onInitialize() {
        INSTANCE = this;
        storage = new RegionStorage();
        regionManager = new RegionManager(storage);
        selectionManager = new SelectionManager();
        visualizationManager = new VisualizationManager(storage);
        chunkSetStorage = new ChunkSetStorage();
        chunkSetStorage.load();
        chunkSuggestionStorage = new ChunkSuggestionStorage();
        chunkSuggestionStorage.load();
        tagConfigStorage = new TagConfigStorage();
        serverManager = new ServerManager();

        com.cheeseguard.api.GenTypeRegistry.discover();

        CommandRegistrationCallback.EVENT.register((d, r, e) -> CheeseGuardCommand.register(d));
        CommandRegistrationCallback.EVENT.register((d, r, e) -> com.cheeseguard.commands.PrisonCommand.register(d));
        new ProtectionListener().register();
        new WandListener().register();
        new com.cheeseguard.logic.PrisonListener().register();

        // Register Hot Potato handler (right-click on player to pass bomb)
        HotPotatoHandler.register();

        // Register Event Leave handler (right-click barrier item to leave event)
        EventLeaveHandler.register();

        // GuiSyncPayload still needed for VOTE_OVERLAY/VOTE_CLEAR signals to client HUD
        PayloadTypeRegistry.playS2C().register(GuiSyncPayload.ID, GuiSyncPayload.CODEC);

        // PropHunt power C2S payloads
        PayloadTypeRegistry.playC2S().register(PropHuntCyclePowerPayload.ID, PropHuntCyclePowerPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(com.cheeseguard.network.PropHuntActivatePowerPayload.ID, com.cheeseguard.network.PropHuntActivatePowerPayload.CODEC);

        // Zone-owner menu payloads (K keybind GUI)
        PayloadTypeRegistry.playC2S().register(com.cheeseguard.network.OpenZoneMenuC2S.ID,
            com.cheeseguard.network.OpenZoneMenuC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(com.cheeseguard.network.ZoneMenuActionC2S.ID,
            com.cheeseguard.network.ZoneMenuActionC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(com.cheeseguard.network.ZoneMenuDataS2C.ID,
            com.cheeseguard.network.ZoneMenuDataS2C.CODEC);

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            Consumer<String> cb = chatInputCallbacks.remove(sender.getUuid());
            if (cb != null) { String t = message.getContent().getString(); server.execute(() -> cb.accept(t)); return false; }
            return true;
        });

        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            this.server = s;
            loadWebhookConfig();
            eventManager.init(s);

            // Handle PropHunt power packets from clients
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
                PropHuntCyclePowerPayload.ID,
                (payload, context) -> {
                    context.server().execute(() -> {
                        eventManager.getMiniGameManager().cyclePower(context.player());
                    });
                }
            );
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
                com.cheeseguard.network.PropHuntActivatePowerPayload.ID,
                (payload, context) -> {
                    context.server().execute(() -> {
                        eventManager.getMiniGameManager().activatePower(context.player());
                    });
                }
            );

            // Zone-owner menu — open request from K keybind
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
                com.cheeseguard.network.OpenZoneMenuC2S.ID,
                (payload, context) -> context.server().execute(() ->
                    com.cheeseguard.logic.ZoneOwnerMenuHandler.onOpenRequest(context.player()))
            );

            // Zone-owner menu — actions (add/remove/transfer/rename/toggle/leave)
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
                com.cheeseguard.network.ZoneMenuActionC2S.ID,
                (payload, context) -> context.server().execute(() ->
                    com.cheeseguard.logic.ZoneOwnerMenuHandler.onAction(
                        context.player(), payload.zoneId(), payload.action(), payload.target()))
            );

            dimensionManager.init(s);
            // Catch dimensions loaded AFTER server start (cobblemonraiddens runtime dims,
            // FabricDimensions API, modded portal-spawned dims). For those, init's boot
            // sweep is too early — this hook applies our stored border config the moment
            // the world is registered with the server.
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents.LOAD.register(
                (srv, world) -> dimensionManager.onServerWorldLoaded(world));
            zoneStateManager.init(s);
            com.cheeseguard.logic.WaystonesHook.tryInit();
            propHuntIconStorage.load();
            propHuntIconStorage.registerWithCheeseItems();
            pastureLimitsManager.load();
            CobbleShiftHook.loadPendingReverts(s.getRunDirectory().resolve("config"));
            DiscordWebhook.serverStarting();
            // Recreate prison doppelgangers for offline prisoners.
            prisonDoppelgangerManager.onServerStarted(s);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            if (!serverManager.isActive()) DiscordWebhook.serverStopping();
            prisonDoppelgangerManager.clear();
        });
        // Tick events
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(srv -> {
            eventManager.tick();
            eventManager.processScheduledTasks();
            dimensionManager.tick();
            zoneStateManager.tick();
            tickChunkZoneNotifications(srv);
            // (FTB Quests finalization moved to CheeseFixQuests — see QuestFinalizerHook.)
            // Chunk auto-reset check every 30 seconds (600 ticks)
            if (++chunkResetScheduleTick >= 600) {
                chunkResetScheduleTick = 0;
                tickChunkAutoReset(srv);
            }
        });
        // Player join (crash-safe inventory restore)
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            ServerPlayerEntity p = handler.getPlayer();
            // Despawn any prison doppelganger for this player BEFORE the join event handlers
            // run, so the connecting player doesn't see (then unsee) themselves.
            prisonDoppelgangerManager.removeFor(p.getUuid());
            eventManager.onPlayerJoin(p);
            dimensionManager.onPlayerJoin(p);
            CobbleShiftHook.onPlayerJoin(p);
            // Sync visibility of existing doppelgangers for this new viewer.
            srv.execute(() -> prisonDoppelgangerManager.onViewerWorldChange(p));
        });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            ServerPlayerEntity p = handler.getPlayer();
            UUID disconnectUuid = p.getUuid();
            dimensionManager.onPlayerDisconnect(disconnectUuid);
            playerChunkZones.remove(disconnectUuid);
            // Snapshot the GameProfile (with skin properties) BEFORE the entity is GC'd.
            com.mojang.authlib.GameProfile profile = p.getGameProfile();
            // Defer doppel creation to the server thread — ServerPlayerEntity construction and
            // packet broadcasting from the netty thread is unreliable (race with PlayerManager
            // mutations, packet flush ordering). Profile is captured immutably above.
            srv.execute(() -> prisonDoppelgangerManager.spawnFor(srv, profile));
        });
        // When a viewer changes dimension, re-sync which doppelgangers they should see.
        net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(
            (player, origin, destination) -> prisonDoppelgangerManager.onViewerWorldChange(player));
        // Player respawn (death during event + dimension keepInventory)
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (!alive) { // alive=false means they actually died (not end portal)
                eventManager.onParticipantRespawn(oldPlayer, newPlayer);
                dimensionManager.onPlayerRespawnKeepInv(oldPlayer, newPlayer);
                dimensionManager.onPlayerRespawnRedirect(newPlayer);
            }
        });
        ftbQuestsLoaded = FabricLoader.getInstance().isModLoaded("ftbquests");
        LOGGER.info("CheeseGuard loaded!" + (ftbQuestsLoaded ? " (FTB Quests detected)" : ""));
    }

    // ── Accessors ──────────────────────────────────────────────────────────────

    public static CheeseGuardMod getInstance() { return INSTANCE; }
    public MinecraftServer getServer() { return server; }
    public RegionStorage getStorage() { return storage; }
    public RegionManager getRegionManager() { return regionManager; }
    public SelectionManager getSelectionManager() { return selectionManager; }
    public VisualizationManager getVisualizationManager() { return visualizationManager; }
    public ChunkSetStorage getChunkSetStorage() { return chunkSetStorage; }
    public ChunkSuggestionStorage getChunkSuggestionStorage() { return chunkSuggestionStorage; }
    public TagConfigStorage getTagConfigStorage() { return tagConfigStorage; }
    public ServerManager getServerManager() { return serverManager; }
    public EventManager getEventManager() { return eventManager; }
    public com.cheeseguard.logic.DimensionManager getDimensionManager() { return dimensionManager; }
    public com.cheeseguard.logic.ZoneStateManager getZoneStateManager() { return zoneStateManager; }
    public boolean isFtbQuestsLoaded() { return ftbQuestsLoaded; }
    public PropHuntIconStorage getPropHuntIconStorage() { return propHuntIconStorage; }
    public PastureLimitsManager getPastureLimitsManager() { return pastureLimitsManager; }
    public com.cheeseguard.logic.PrisonManager getPrisonManager() { return prisonManager; }
    public com.cheeseguard.logic.PrisonDoppelgangerManager getPrisonDoppelgangerManager() { return prisonDoppelgangerManager; }

    // ── Utility methods ──────────────────────────────────────────────────────

    public void giveWand(ServerPlayerEntity player) {
        ItemStack wand = new ItemStack(Items.GOLDEN_AXE);
        wand.set(DataComponentTypes.CUSTOM_NAME, Text.translatable("cheeseguard.cmd.wand_name").formatted(Formatting.GOLD));
        player.getInventory().insertStack(wand);
        player.sendMessage(Text.translatable("cheeseguard.cmd.wand_received").formatted(Formatting.GREEN), true);
    }

    public void createRegion(ServerPlayerEntity player, String name, SelectionManager.Selection sel) {
        if (sel == null || !sel.isComplete()) {
            player.sendMessage(Text.translatable("cheeseguard.cmd.selection_incomplete").formatted(Formatting.RED), false);
            return;
        }
        int x1 = Math.min(sel.pos1.getX(), sel.pos2.getX());
        int y1 = Math.min(sel.pos1.getY(), sel.pos2.getY());
        int z1 = Math.min(sel.pos1.getZ(), sel.pos2.getZ());
        int x2 = Math.max(sel.pos1.getX(), sel.pos2.getX());
        int y2 = Math.max(sel.pos1.getY(), sel.pos2.getY());
        int z2 = Math.max(sel.pos1.getZ(), sel.pos2.getZ());
        String id = name.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
        Region region = new Region(id, name, sel.world, x1, y1, z1, x2, y2, z2,
            new Member(player.getUuid(), player.getName().getString()));
        storage.addRegion(region);
        player.sendMessage(Text.translatable("cheeseguard.cmd.region_created", name).formatted(Formatting.GREEN), false);
    }

    private void loadWebhookConfig() {
        try {
            java.nio.file.Path cfgFile = server.getRunDirectory().resolve("config/cheeseguard_webhook.json");
            if (java.nio.file.Files.exists(cfgFile)) {
                String json = new String(java.nio.file.Files.readAllBytes(cfgFile));
                JsonObject o = JsonParser.parseString(json).getAsJsonObject();
                String url = o.has("webhook_url") ? o.get("webhook_url").getAsString() : "";
                DiscordWebhook.setUrl(url);
            }
        } catch (Exception e) { LOGGER.warn("[CG] Failed to load webhook config: {}", e.getMessage()); }
    }

    public boolean hasClientMod(ServerPlayerEntity p) {
        return ServerPlayNetworking.canSend(p, GuiSyncPayload.ID);
    }

    // ── Chunk auto-reset scheduler ──────────────────────────────────────────

    private void tickChunkAutoReset(MinecraftServer srv) {
        if (chunkSetStorage == null) return;
        var now = java.time.LocalDateTime.now();
        int currentHour = now.getHour();
        int currentMinute = now.getMinute();
        String currentDay = now.getDayOfWeek().name(); // MONDAY, TUESDAY, ...
        long nowMs = System.currentTimeMillis();

        for (ChunkSet cs : chunkSetStorage.getAllSets().values()) {
            if (!cs.isScheduled()) continue;
            if (cs.isEmpty()) continue;
            if (cs.getResetHour() != currentHour) continue;
            if (Math.abs(cs.getResetMinute() - currentMinute) > 1) continue; // within 1 min tolerance

            // Check day-of-week filter
            if (!cs.getResetDays().isEmpty() && !cs.getResetDays().contains(currentDay)) continue;

            // Anti-double: skip if last reset was within 90 seconds
            if (nowMs - cs.getLastAutoReset() < 90_000) continue;

            LOGGER.info("[CG] Auto-reset triggered for chunk set: {}", cs.getName());
            cs.setLastAutoReset(nowMs);
            chunkSetStorage.save();

            // Direct region file deletion with backup (no external mod dependency)
            ChunkSet csRef = cs;
            srv.execute(() -> {
                try {
                    // Force-save the target dimension
                    for (var sw : srv.getWorlds()) {
                        if (sw.getRegistryKey().getValue().toString().equals(csRef.getDimension())) {
                            sw.save(null, true, false);
                            break;
                        }
                    }

                    String levelName = srv.getSaveProperties().getLevelName();
                    java.nio.file.Path worldPath = srv.getRunDirectory().resolve(levelName);
                    ChunkDeleter deleter = ChunkDeleter.forDimension(worldPath, csRef.getDimension());
                    ChunkDeleter.DeletionResult result = deleter.deleteChunks(csRef);

                    if (result.isSuccess() || result.isPartial()) {
                        LOGGER.info("[CG] Auto-reset completed for '{}' (dim: {}): {} deleted, {} failed, backup: {}",
                                csRef.getName(), csRef.getDimension(), result.deletedFiles.size(), result.failedFiles.size(),
                                result.backupPath != null ? result.backupPath.getFileName() : "none");

                        // Broadcast chat message to all players
                        net.minecraft.text.Text chatMsg = net.minecraft.text.Text.translatable(
                                "cheeseguard.chunks.auto_reset_broadcast", csRef.getName(), result.deletedFiles.size())
                                .formatted(net.minecraft.util.Formatting.GOLD);
                        for (var p : srv.getPlayerManager().getPlayerList()) {
                            p.sendMessage(chatMsg, false);
                        }

                        // Discord webhook notification
                        try {
                            if (com.cheesedash.CheeseDashMod.INSTANCE != null) {
                                var discord = com.cheesedash.CheeseDashMod.INSTANCE.getDiscordWebhookManager();
                                if (discord != null) {
                                    java.util.Map<String, String> data = new java.util.HashMap<>();
                                    data.put("message", "Auto-reset chunk set **" + csRef.getName() + "** (" + csRef.getDimension() + "): "
                                            + result.deletedFiles.size() + " region(s) deleted");
                                    discord.sendEvent(com.cheesedash.DiscordWebhookManager.DiscordEventType.SCHEDULED_TASK_FIRED, data);
                                }
                            }
                        } catch (Exception ex) {
                            LOGGER.warn("[CG] Failed to send Discord webhook for chunk auto-reset: {}", ex.getMessage());
                        }
                    } else {
                        LOGGER.error("[CG] Auto-reset failed for '{}': {} — {}",
                                csRef.getName(), result.status, result.error != null ? result.error : "");
                    }
                } catch (Exception e) {
                    LOGGER.error("[CG] Auto-reset error for chunk set {}: {}", csRef.getName(), e.getMessage());
                }
            });
        }
    }

    // ── Chunk zone notifications ──────────────────────────────────────────────

    private void tickChunkZoneNotifications(MinecraftServer srv) {
        chunkCheckTick++;
        if (chunkCheckTick % 40 != 0) return; // every 2 seconds
        if (chunkSetStorage == null) return;

        Map<String, ChunkSet> allSets = chunkSetStorage.getAllSets();
        if (allSets.isEmpty()) return;

        // Pre-compute: for each set, build a set of affected region keys "setName:rX:rZ"
        // A region is 512x512 blocks → chunkX>>5, chunkZ>>5 gives region coords
        Map<String, Set<String>> setRegions = new HashMap<>();
        for (Map.Entry<String, ChunkSet> setEntry : allSets.entrySet()) {
            String setName = setEntry.getKey();
            Set<ChunkCalculator.RegionCoord> regions = ChunkCalculator.getAffectedRegions(
                ChunkCalculator.getAffectedChunks(setEntry.getValue())
            );
            Set<String> keys = new HashSet<>();
            for (ChunkCalculator.RegionCoord r : regions) {
                keys.add(setName + ":" + r.x + ":" + r.z);
            }
            setRegions.put(setName, keys);
        }

        for (ServerPlayerEntity player : srv.getPlayerManager().getPlayerList()) {
            UUID uuid = player.getUuid();
            String playerDim = player.getServerWorld().getRegistryKey().getValue().toString();
            // Region the player is currently in (one region = 32×32 chunks = 512×512 blocks)
            int playerRegionX = (int) Math.floor(player.getX()) >> 9; // block >> 4 (chunk) >> 5 (region) = >> 9
            int playerRegionZ = (int) Math.floor(player.getZ()) >> 9;

            Set<String> currentZones = new HashSet<>();
            for (Map.Entry<String, Set<String>> e : setRegions.entrySet()) {
                String setName = e.getKey();
                // Only match sets that target the player's current dimension
                ChunkSet cs = allSets.get(setName);
                if (cs != null && !playerDim.equals(cs.getDimension())) continue;
                String regionKey = setName + ":" + playerRegionX + ":" + playerRegionZ;
                if (e.getValue().contains(regionKey)) {
                    currentZones.add(regionKey);
                }
            }

            Set<String> previousZones = playerChunkZones.computeIfAbsent(uuid, k -> new HashSet<>());

            // Entered regions
            for (String key : currentZones) {
                if (!previousZones.contains(key)) {
                    String setName = key.split(":")[0];
                    player.sendMessage(
                        Text.translatable("cheeseguard.chunk.entering_regen_zone", setName)
                            .formatted(Formatting.GOLD),
                        false
                    );
                }
            }

            // Exited regions
            for (String key : previousZones) {
                if (!currentZones.contains(key)) {
                    String setName = key.split(":")[0];
                    player.sendMessage(
                        Text.translatable("cheeseguard.chunk.leaving_regen_zone", setName)
                            .formatted(Formatting.GRAY),
                        false
                    );
                }
            }

            playerChunkZones.put(uuid, currentZones);
        }
    }

}