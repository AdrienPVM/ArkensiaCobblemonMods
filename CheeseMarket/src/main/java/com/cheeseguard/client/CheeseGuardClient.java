package com.cheeseguard.client;

import com.cheeseguard.network.GuiSyncPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;

@Environment(EnvType.CLIENT)
public class CheeseGuardClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Vote HUD overlay — rendered every frame over the game world
        HudRenderCallback.EVENT.register((drawContext, tickDeltaManager) -> {
            VoteHudRenderer.render(drawContext);
        });

        // Handle incoming GUI sync from server (vote overlay + close only)
        ClientPlayNetworking.registerGlobalReceiver(GuiSyncPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                MinecraftClient mc = context.client();

                // CLOSE signal: server wants to close the screen (e.g., awaiting chat input)
                if ("CLOSE".equals(payload.viewMode())) {
                    mc.setScreen(null);
                    return;
                }

                // VOTE_OVERLAY: update the in-world HUD (no screen needed)
                if ("VOTE_OVERLAY".equals(payload.viewMode())) {
                    VoteHudRenderer.update(payload.data());
                    return;
                }

                // VOTE_CLEAR: vote ended, clear HUD
                if ("VOTE_CLEAR".equals(payload.viewMode())) {
                    VoteHudRenderer.clear();
                    return;
                }
            });
        });
    }
}
