package com.cheesestore;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class CheeseStoreMod implements ModInitializer {

    public static final String MOD_ID = "cheesestore";
    public static final Logger LOGGER = LoggerFactory.getLogger("CheeseStore");
    public static CheeseStoreMod INSTANCE;

    private CheeseStoreConfig config;
    private DeliveryManager deliveryManager;
    private StripeClient stripeClient;
    private MinecraftServer server;

    @Override
    public void onInitialize() {
        INSTANCE = this;
        LOGGER.info("[CheeseStore] Initializing...");

        Path configDir = FabricLoader.getInstance().getConfigDir();
        config = new CheeseStoreConfig(configDir);
        config.load();

        deliveryManager = new DeliveryManager(configDir);

        // Initialize wallet manager (fromage premium currency)
        WalletManager.init(configDir);

        // Initialize cosmetics manager (credit-purchasable website cosmetics — legacy product model)
        CosmeticManager.init(configDir);

        // Initialize the playable cosmetics system (trails / kill+death effects / chat tags /
        // name colours / pets). Wires up managers + sub-systems + the /cosmetics command.
        com.cheesestore.cosmetic.CosmeticsModule.init();

        // Register /premium and /store commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            PremiumCommand.register(dispatcher);
            StoreCommand.register(dispatcher);
        });

        // Initialise the OAuth account linker (loads persisted links from disk).
        com.cheesestore.auth.AccountLinker.getInstance();

        // Initialize Stripe client (recreated when config changes)
        if (!config.getStripeSecretKey().isEmpty()) {
            stripeClient = new StripeClient(config.getStripeSecretKey());
        }

        // ── Player join: deliver pending purchases ───────────────────────
        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            ServerPlayerEntity player = handler.getPlayer();
            String uuid = player.getUuidAsString();
            String name = player.getName().getString();

            // Execute on server thread (already on it via JOIN event, but be safe)
            srv.execute(() -> {
                int delivered = deliveryManager.deliverPending(srv, uuid);
                if (delivered > 0) {
                    LOGGER.info("[CheeseStore] Delivered {} pending purchase(s) to {}",
                        delivered, name);
                }
            });
        });

        // ── Server lifecycle ─────────────────────────────────────────────
        ServerLifecycleEvents.SERVER_STARTED.register(srv -> {
            this.server = srv;
            LOGGER.info("[CheeseStore] Server started");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
            this.server = null;
        });

        // Warn if storeBaseUrl is not configured (would break Stripe redirects)
        String baseUrl = config.getStoreBaseUrl();
        if (baseUrl.isEmpty() || baseUrl.contains("localhost")) {
            LOGGER.warn("[CheeseStore] ⚠ storeBaseUrl is not configured! Checkout will be blocked. Set it in config/cheesestore.json or via CheeseDash admin.");
        }

        LOGGER.info("[CheeseStore] Initialized successfully!");
    }

    /**
     * Recreate StripeClient after config change.
     */
    public void refreshStripeClient() {
        if (!config.getStripeSecretKey().isEmpty()) {
            stripeClient = new StripeClient(config.getStripeSecretKey());
        } else {
            stripeClient = null;
        }
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public CheeseStoreConfig getConfig() { return config; }
    public DeliveryManager getDeliveryManager() { return deliveryManager; }
    public StripeClient getStripeClient() { return stripeClient; }
    public MinecraftServer getServer() { return server; }
}
