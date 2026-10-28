package com.cheeseextra.client;

import com.cheeseextra.network.IslandTourPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

public class CheeseExtraClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(IslandTourPayload.ID, (payload, context) ->
            context.client().execute(() -> IslandTourHud.update(payload)));

        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> IslandTourHud.render(drawContext));
    }
}
