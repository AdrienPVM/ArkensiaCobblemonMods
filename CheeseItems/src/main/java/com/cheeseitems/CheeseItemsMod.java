package com.cheeseitems;

import com.cheeseitems.morpho.MorphoCatalog;
import com.cheeseitems.morpho.MorphoCodex;
import com.cheeseitems.morpho.MorphoEnduranceTracker;
import com.cheeseitems.morpho.MorphoManager;
import com.cheeseitems.network.MorphoCodexSyncS2CPayload;
import com.cheeseitems.network.MorphoOpenGuiS2CPayload;
import com.cheeseitems.network.MorphoSetActiveC2SPayload;
import com.cheeseitems.network.MorphoToggleC2SPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.common.ResourcePackRemoveS2CPacket;
import net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

/**
 * CheeseItems — Custom quest items managed via CheeseDash dashboard.
 * Server-only mod.
 */
public class CheeseItemsMod implements ModInitializer {

    public static final String MOD_ID = "cheeseitems";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static CheeseItemsMod INSTANCE;

    private CheeseItemsConfig config;
    private ItemManager itemManager;
    private UseHandler useHandler;
    private ResourcePackGenerator resourcePackGenerator;
    private MorphoCodex morphoCodex;
    private MorphoManager morphoManager;
    private MinecraftServer server;
    private static final UUID RESOURCE_PACK_UUID = UUID.nameUUIDFromBytes("cheeseitems-pack".getBytes());

    @Override
    public void onInitialize() {
        INSTANCE = this;
        LOGGER.info("[CheeseItems] Initializing...");

        config = new CheeseItemsConfig();
        config.load();

        itemManager = new ItemManager(config);
        useHandler = new UseHandler(itemManager);
        resourcePackGenerator = new ResourcePackGenerator();

        morphoCodex = new MorphoCodex();
        morphoCodex.load();
        morphoManager = new MorphoManager(morphoCodex);

        // Eagerly initialise the catalog (singleton, loads from disk on first access).
        MorphoCatalog.getInstance();

        // Register morpho payloads (C2S + S2C)
        PayloadTypeRegistry.playC2S().register(MorphoToggleC2SPayload.ID, MorphoToggleC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(MorphoSetActiveC2SPayload.ID, MorphoSetActiveC2SPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MorphoCodexSyncS2CPayload.ID, MorphoCodexSyncS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MorphoOpenGuiS2CPayload.ID, MorphoOpenGuiS2CPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(MorphoToggleC2SPayload.ID, (payload, ctx) -> {
            ctx.server().execute(() -> morphoManager.handleToggle(ctx.player()));
        });

        ServerPlayNetworking.registerGlobalReceiver(MorphoSetActiveC2SPayload.ID, (payload, ctx) -> {
            ctx.server().execute(() -> morphoManager.setActive(ctx.player(), payload.itemId()));
        });

        // Register right-click, tick, passive effects
        useHandler.registerEvents();

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            CheeseItemsCommand.register(dispatcher);
            MorphoCommand.register(dispatcher);
        });

        // Server lifecycle
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

        // Death handling: soulbound + disappearOnDeath
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity sp) {
                useHandler.onPlayerDeath(sp);
            }
            return true; // always allow death
        });

        // After respawn: restore soulbound
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            // After death is too early — need after respawn
            // We'll handle this in tick by tracking stash
        });

        // Player disconnect: cleanup endurance (execute depletion commands if active)
        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            useHandler.onPlayerDisconnect(handler.getPlayer().getUuid());
            MorphoEnduranceTracker.getInstance().clear(handler.getPlayer().getUuid());
        });

        // Tick the morpho endurance pool every server tick.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
            srv -> MorphoEnduranceTracker.getInstance().tick(srv));

        LOGGER.info("[CheeseItems] Initialized successfully!");
    }

    private void onServerStarted(MinecraftServer minecraftServer) {
        this.server = minecraftServer;
        itemManager.setServer(minecraftServer);
        useHandler.setServer(minecraftServer);

        // Generate resource pack on server start
        resourcePackGenerator.generate();

        // Send resource pack to players on join
        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            sendResourcePack(handler.getPlayer());
            // Sync the player's morpho codex on join (one tick later so client networking is ready).
            if (morphoManager != null) {
                srv.execute(() -> morphoManager.onPlayerJoin(handler.getPlayer(), srv));
            }
        });

        LOGGER.info("[CheeseItems] Server started — {} items loaded", config.getItems().size());
    }

    /**
     * Send the custom resource pack to a player.
     * First removes any existing pack with the same UUID to avoid client-side
     * conflicts when the SHA1 hash has changed (prevents options.txt corruption).
     */
    public void sendResourcePack(ServerPlayerEntity player) {
        if (!resourcePackGenerator.hasPack()) return;
        String host = resolveResourcePackHost();
        if (host == null || host.isBlank()) {
            LOGGER.warn("[CheeseItems] resourcePackHost not configured in cheeseitems.json — cannot send pack to {}", player.getName().getString());
            return;
        }
        String url = "http://" + host + "/cheeseitems/api/pack.zip";
        String sha1 = resourcePackGenerator.getSha1();
        try {
            // Remove old pack first to prevent UUID/SHA1 mismatch on client
            player.networkHandler.sendPacket(new ResourcePackRemoveS2CPacket(Optional.of(RESOURCE_PACK_UUID)));
            // Then send the current pack
            player.networkHandler.sendPacket(new ResourcePackSendS2CPacket(
                    RESOURCE_PACK_UUID, url, sha1, false, Optional.of(Text.literal("§6Custom item icons for Cacademy"))
            ));
            LOGGER.debug("[CheeseItems] Sent resource pack to {}", player.getName().getString());
        } catch (Exception e) {
            LOGGER.error("[CheeseItems] Failed to send resource pack to {}: {}", player.getName().getString(), e.getMessage());
        }
    }

    /**
     * Regenerate pack and send to all online players (only if pack actually changed).
     */
    public void regenerateAndPushPack() {
        boolean changed = resourcePackGenerator.generate();
        if (changed && server != null) {
            LOGGER.info("[CheeseItems] Pack changed — pushing to {} online players", server.getPlayerManager().getPlayerList().size());
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                sendResourcePack(player);
            }
        }
    }

    private void onServerStopping(MinecraftServer minecraftServer) {
        if (morphoCodex != null) morphoCodex.flush();
        try { MorphoCatalog.getInstance().flush(); } catch (Exception ignored) {}
        this.server = null;
        LOGGER.info("[CheeseItems] Server stopping");
    }

    /**
     * Resolve the resource pack host. If not configured, auto-detect from CheeseDash port.
     */
    private String resolveResourcePackHost() {
        String host = config.getResourcePackHost();
        if (host != null && !host.isBlank()) return host;

        // Auto-detect from CheeseDash config
        try {
            com.cheesedash.CheeseDashMod dashMod = com.cheesedash.CheeseDashMod.INSTANCE;
            if (dashMod != null && dashMod.getConfig() != null) {
                int port = dashMod.getConfig().getPort();
                String resolved = "localhost:" + port;
                LOGGER.info("[CheeseItems] resourcePackHost auto-resolved to {} (set resourcePackHost in cheeseitems.json for production)", resolved);
                return resolved;
            }
        } catch (Exception e) {
            LOGGER.debug("[CheeseItems] Could not auto-resolve resourcePackHost from CheeseDash: {}", e.getMessage());
        }

        // Fallback
        return "localhost:44311";
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public CheeseItemsConfig getConfig() { return config; }
    public ItemManager getItemManager() { return itemManager; }
    public UseHandler getUseHandler() { return useHandler; }
    public ResourcePackGenerator getResourcePackGenerator() { return resourcePackGenerator; }
    public MinecraftServer getServer() { return server; }
    public MorphoCodex getMorphoCodex() { return morphoCodex; }
    public MorphoManager getMorphoManager() { return morphoManager; }
}
