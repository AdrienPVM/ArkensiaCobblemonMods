package com.cheesemarket;

import com.cheesemarket.client.CheeseMarketScreen;
import com.cheesemarket.client.ShopViewScreen;
import com.cheesemarket.network.ShopPayloads.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

public class CheeseMarketClient implements ClientModInitializer {

    // Singleton GUI references (so server can push updates to open screens)
    private static CheeseMarketScreen adminScreen;
    private static ShopViewScreen     playerScreen;

    @Override
    public void onInitializeClient() {

        // ── Register payload types (client-side) ───────────────────────────────
        // Already done server-side for S2C; client needs to register receivers.

        // ── ShopOpenPayload: server tells client to open the GUI ───────────────
        ClientPlayNetworking.registerGlobalReceiver(ShopOpenPayload.ID,
            (payload, context) -> {
                MinecraftClient mc = context.client();
                mc.execute(() -> {
                    if (payload.adminMode()) {
                        adminScreen = new CheeseMarketScreen();
                        mc.setScreen(adminScreen);
                    } else {
                        playerScreen = new ShopViewScreen();
                        mc.setScreen(playerScreen);
                    }
                });
            }
        );

        // ── ShopSyncPayload: server pushes view data ───────────────────────────
        ClientPlayNetworking.registerGlobalReceiver(ShopSyncPayload.ID,
            (payload, context) -> {
                MinecraftClient mc = context.client();
                mc.execute(() -> {
                    String view = payload.viewMode();
                    var data    = payload.data();

                    if ("CLOSE".equals(view)) {
                        mc.setScreen(null);
                        return;
                    }

                    if ("PURCHASE_RESULT".equals(view)) {
                        // Deliver result to player screen if open
                        if (playerScreen != null && mc.currentScreen == playerScreen) {
                            String result = data.has("purchaseResult") ? data.get("purchaseResult").getAsString() : "UNKNOWN";
                            playerScreen.receivePurchaseResult(result);
                        }
                        return;
                    }

                    if ("SHOP_VIEW".equals(view)) {
                        // Player-facing shop view
                        if (playerScreen == null || mc.currentScreen != playerScreen) {
                            playerScreen = new ShopViewScreen();
                            mc.setScreen(playerScreen);
                        }
                        playerScreen.receiveData(data);
                        return;
                    }

                    // All other views → admin screen
                    if (adminScreen == null || mc.currentScreen != adminScreen) {
                        adminScreen = new CheeseMarketScreen();
                        mc.setScreen(adminScreen);
                    }
                    adminScreen.receiveSync(view, data);
                });
            }
        );

        System.out.println("[CheeseMarket] Client initialized.");
    }
}
