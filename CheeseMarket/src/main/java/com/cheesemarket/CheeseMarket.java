package com.cheesemarket;

import com.cheesemarket.command.ShopCommands;
import com.cheesemarket.manager.ShopManager;
import com.cheesemarket.network.ShopPayloads.*;
import com.cheesemarket.server.ShopGuiHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public class CheeseMarket implements ModInitializer {

    public static final String MOD_ID = "cheesemarket";

    @Override
    public void onInitialize() {
        // ── Register payload types ─────────────────────────────────────────────
        // Client → Server
        PayloadTypeRegistry.playC2S().register(ShopActionPayload.ID, ShopActionPayload.CODEC);
        // Server → Client
        PayloadTypeRegistry.playS2C().register(ShopSyncPayload.ID, ShopSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ShopOpenPayload.ID, ShopOpenPayload.CODEC);

        // ── Receive action from client ─────────────────────────────────────────
        ServerPlayNetworking.registerGlobalReceiver(ShopActionPayload.ID,
            (payload, context) -> context.server().execute(
                () -> ShopGuiHandler.handle(payload, context.player())
            )
        );

        // ── Register commands ─────────────────────────────────────────────────
        CommandRegistrationCallback.EVENT.register(ShopCommands::register);

        // ── Lifecycle ─────────────────────────────────────────────────────────
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            java.nio.file.Path dataDir = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("data");
            ShopManager.init(dataDir);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ShopManager.getInstance().saveStockData();
        });

        System.out.println("[CheeseMarket] Initialized.");
    }
}
