package com.roicheese.eggandcheese;

import com.roicheese.eggandcheese.network.ModPackets;
import com.roicheese.eggandcheese.network.ModPayloads;
import com.roicheese.eggandcheese.registry.ModBlockEntities;
import com.roicheese.eggandcheese.registry.ModItems;
import com.roicheese.eggandcheese.renderer.AdminCollectibleBESR;
import com.roicheese.eggandcheese.renderer.CollectibleDecoBESR;
import com.roicheese.eggandcheese.renderer.EggItemRenderer;
import com.roicheese.eggandcheese.renderer.EggRenderConfig;
import com.roicheese.eggandcheese.renderer.PedestalBESR;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;

public class EggAndCheeseClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EggAndCheese.LOGGER.info("[EggAndCheese] Client initializing...");

        // Block entity renderers
        BlockEntityRendererRegistry.register(ModBlockEntities.ADMIN_COLLECTIBLE, AdminCollectibleBESR::new);
        BlockEntityRendererRegistry.register(ModBlockEntities.COLLECTIBLE_DECO,  CollectibleDecoBESR::new);
        BlockEntityRendererRegistry.register(ModBlockEntities.PEDESTAL,           PedestalBESR::new);

        // 3D item renderers — makes eggs show as 3D model when held/in GUI
        BuiltinItemRendererRegistry.INSTANCE.register(ModItems.ADMIN_EGG,      EggItemRenderer::render);
        BuiltinItemRendererRegistry.INSTANCE.register(ModItems.COLLECTIBLE_EGG, EggItemRenderer::render);

        registerClientPackets();
        EggAndCheese.LOGGER.info("[EggAndCheese] Client ready!");
    }

    private void registerClientPackets() {
        // Sync registry — updates render distance config from server
        ClientPlayNetworking.registerGlobalReceiver(ModPayloads.SyncRegistryPayload.ID, (payload, ctx) -> {
            Object[] data = ModPackets.readRegistryFromBufFull(ModPayloads.fromBytes(payload.data()));
            if (data.length > 3) EggRenderConfig.textureRenderDistance = (Integer) data[3];
        });
    }
}
