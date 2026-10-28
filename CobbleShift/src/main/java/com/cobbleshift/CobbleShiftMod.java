package com.cobbleshift;

import com.cobbleshift.network.AbilitySyncPayload;
import com.cobbleshift.network.AnimationStateSyncPayload;
import com.cobbleshift.network.AttackAbilityPayload;
import com.cobbleshift.network.TransformSyncPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CobbleShift — Transform players into Pokémon with passive abilities + type attacks.
 * Server + client mod (client for model swap, HUD overlay, attack input).
 */
public class CobbleShiftMod implements ModInitializer {

    public static final String MOD_ID = "cobbleshift";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static CobbleShiftMod INSTANCE;

    private CobbleShiftConfig config;
    private TransformManager transformManager;
    private AbilityManager abilityManager;
    private NpcTransformManager npcTransformManager;
    private MinecraftServer server;

    @Override
    public void onInitialize() {
        INSTANCE = this;
        LOGGER.info("[CobbleShift] Initializing...");

        // Force-load TypeProjectileEntity class to register the entity type early
        // (static field TYPE registers via Registry.register in class initializer)
        try {
            Class.forName("com.cobbleshift.TypeProjectileEntity");
            LOGGER.info("[CobbleShift] TypeProjectileEntity registered");
        } catch (ClassNotFoundException e) {
            LOGGER.error("[CobbleShift] Failed to load TypeProjectileEntity!", e);
        }

        // Register S2C payload types
        TransformSyncPayload.register();
        AbilitySyncPayload.register();
        AnimationStateSyncPayload.register();

        // Register C2S payload types
        AttackAbilityPayload.registerC2S();

        config = CobbleShiftConfig.load();
        transformManager = new TransformManager();
        abilityManager = new AbilityManager(config);
        npcTransformManager = new NpcTransformManager();

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            CobbleShiftCommand.register(dispatcher)
        );

        // Server lifecycle
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

        // Tick loop — cooldown ticks + ability refresh
        ServerTickEvents.END_SERVER_TICK.register(this::onTick);

        // Player events
        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            transformManager.onPlayerJoin(handler.getPlayer());
            npcTransformManager.broadcastAllNpcTransforms(handler.getPlayer());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) ->
            transformManager.onPlayerDisconnect(handler.getPlayer().getUuid())
        );

        // C2S handler: attack ability
        ServerPlayNetworking.registerGlobalReceiver(AttackAbilityPayload.ID, (payload, context) -> {
            boolean hasAnim = payload.hasAnimation();
            context.server().execute(() -> {
                AttackHandler.handleAttack(context.player(), hasAnim);
            });
        });

        LOGGER.info("[CobbleShift] Initialized successfully!");
    }

    private void onServerStarted(MinecraftServer minecraftServer) {
        this.server = minecraftServer;
        transformManager.setServer(minecraftServer);
        abilityManager.setServer(minecraftServer);
        npcTransformManager.onServerStart(minecraftServer);
        LOGGER.info("[CobbleShift] Server started");
    }

    private void onServerStopping(MinecraftServer minecraftServer) {
        LOGGER.info("[CobbleShift] Server stopping — reverting all transforms...");
        transformManager.revertAll();
        abilityManager.deactivateAll();
        npcTransformManager.onServerStop(); // save NPC transforms (persist, not revert)
        this.server = null;
        LOGGER.info("[CobbleShift] Shutdown complete");
    }

    private void onTick(MinecraftServer srv) {
        transformManager.tick(srv);
        abilityManager.tick(srv);
        AttackHandler.tick(srv);
        npcTransformManager.tick(srv);
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public CobbleShiftConfig getConfig() { return config; }
    public TransformManager getTransformManager() { return transformManager; }
    public AbilityManager getAbilityManager() { return abilityManager; }
    public NpcTransformManager getNpcTransformManager() { return npcTransformManager; }
    public MinecraftServer getServer() { return server; }
}
