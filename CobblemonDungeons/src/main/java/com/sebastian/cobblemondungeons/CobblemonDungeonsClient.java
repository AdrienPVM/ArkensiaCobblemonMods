package com.sebastian.cobblemondungeons;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screen.ingame.HandledScreens;

import net.minecraft.client.render.RenderLayer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.fabricmc.fabric.api.client.render.fluid.v1.SimpleFluidRenderHandler;
import net.minecraft.util.Identifier;

import net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import com.sebastian.cobblemondungeons.networking.DungeonJoinPayload;
import com.sebastian.cobblemondungeons.networking.DungeonHudPayload;
import com.sebastian.cobblemondungeons.networking.DungeonTicketSyncPayload;
import com.sebastian.cobblemondungeons.gui.DungeonHud;
import com.sebastian.cobblemondungeons.config.ConfigLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.nbt.NbtCompound;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.minecraft.item.Item;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import com.sebastian.cobblemondungeons.block.DungeonChestBlockEntity;

public class CobblemonDungeonsClient implements ClientModInitializer {
    private static String pendingDungeonId = null;
    private static java.util.UUID pendingPartyId = null;
    private static int pendingPartySize = 1;
    private static NbtCompound pendingSyncData = null;
    private static int pendingTier = 1;

    @Override
    public void onInitializeClient() {
        // Register Networking Payloads for Client
        ClientPlayNetworking.registerGlobalReceiver(DungeonJoinPayload.ID, (payload, context) -> {
            pendingDungeonId = payload.dungeonId();
            pendingPartyId = payload.partyId();
            pendingPartySize = payload.partySize();
            pendingSyncData = payload.syncData();
            pendingTier = payload.tier();
            CobblemonDungeons.LOGGER.info("Received pending dungeon: " + pendingDungeonId + (pendingPartyId != null ? " (Party: " + pendingPartyId + ", Size: " + pendingPartySize + ")" : "") + (pendingSyncData != null ? " (with sync data)" : "") + " (Tier " + pendingTier + ")");
        });

        ClientPlayNetworking.registerGlobalReceiver(DungeonHudPayload.ID, (payload, context) -> {
            DungeonHud.update(payload);
        });

        ClientPlayNetworking.registerGlobalReceiver(DungeonTicketSyncPayload.ID, (payload, context) -> {
            ConfigLoader.updateTicketConfig(payload.json());
        });

        DungeonHud.init();

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            try {
                DungeonHud.clear();
                if (pendingDungeonId != null || pendingSyncData != null) {
                    CobblemonDungeons.LOGGER.info("Joining new server, sending pending dungeon: " + pendingDungeonId);
                    sender.sendPacket(new DungeonJoinPayload(pendingDungeonId != null ? pendingDungeonId : "", pendingPartyId, pendingPartySize, pendingSyncData, pendingTier));
                    pendingDungeonId = null; // Clear it after sending
                    pendingPartyId = null;
                    pendingPartySize = 1;
                    pendingSyncData = null;
                    pendingTier = 1;
                }
            } catch (Exception e) {
                CobblemonDungeons.LOGGER.error("Error during client join handling", e);
            }
        });

        // Poison Water Rendering
        FluidRenderHandlerRegistry.INSTANCE.register(CobblemonDungeons.STILL_POISON_WATER, CobblemonDungeons.FLOWING_POISON_WATER, new SimpleFluidRenderHandler(
                Identifier.of("minecraft:block/water_still"),
                Identifier.of("minecraft:block/water_flow"),
                0x8800FF00 // Purple/Green tint (AARRGGBB) - let's go with purple: 0x88A020F0
        ));

        BlockRenderLayerMap.INSTANCE.putFluids(RenderLayer.getTranslucent(), CobblemonDungeons.STILL_POISON_WATER, CobblemonDungeons.FLOWING_POISON_WATER);
        
        // Poison Gas Rendering
        BlockRenderLayerMap.INSTANCE.putBlock(CobblemonDungeons.GAS_BLOCK, RenderLayer.getTranslucent());

        // Register the screens on the client side
        HandledScreens.register(CobblemonDungeons.DUNGEONS_SCREEN_HANDLER, com.sebastian.cobblemondungeons.gui.DungeonScreen::new);
        HandledScreens.register(CobblemonDungeons.DUNGEONS_PARTY_SCREEN_HANDLER, com.sebastian.cobblemondungeons.gui.DungeonScreen::new);
        HandledScreens.register(CobblemonDungeons.SEARCHING_SCREEN_HANDLER, com.sebastian.cobblemondungeons.gui.DungeonScreen::new);
        HandledScreens.register(CobblemonDungeons.INVITES_SCREEN_HANDLER, com.sebastian.cobblemondungeons.gui.DungeonScreen::new);
        HandledScreens.register(CobblemonDungeons.INVITE_PLAYERS_SCREEN_HANDLER, com.sebastian.cobblemondungeons.gui.DungeonScreen::new);
        HandledScreens.register(CobblemonDungeons.BOSS_PROMPT_SCREEN_HANDLER, com.sebastian.cobblemondungeons.gui.DungeonScreen::new);
        HandledScreens.register(CobblemonDungeons.DUNGEON_GO_SCREEN_HANDLER, com.sebastian.cobblemondungeons.gui.DungeonScreen::new);
        HandledScreens.register(CobblemonDungeons.DUNGEON_INFO_SCREEN_HANDLER, com.sebastian.cobblemondungeons.gui.DungeonScreen::new);
        HandledScreens.register(CobblemonDungeons.TICKET_EXCHANGE_SCREEN_HANDLER, com.sebastian.cobblemondungeons.gui.DungeonScreen::new);

        // Register Armor Renderer for 3D Gas Mask
        ArmorRenderer.register((matrices, vertexConsumers, stack, entity, slot, light, contextModel) -> {
            if (stack.isEmpty()) return;
            
            matrices.push();
            // Align with head
            if (contextModel.head != null) {
                contextModel.head.rotate(matrices);
            }
            
            MinecraftClient.getInstance().getItemRenderer().renderItem(
                stack, 
                ModelTransformationMode.HEAD, 
                light, 
                OverlayTexture.DEFAULT_UV, 
                matrices, 
                vertexConsumers, 
                entity.getWorld(), 
                entity.getId()
            );
            matrices.pop();
        }, CobblemonDungeons.GAS_MASK);

        // Ensure scale is correct on client side when entity is loaded
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof PokemonEntity pokemonEntity) {
                CobblemonDungeons.applyCustomScale(pokemonEntity);
            }
        });
    }
}
