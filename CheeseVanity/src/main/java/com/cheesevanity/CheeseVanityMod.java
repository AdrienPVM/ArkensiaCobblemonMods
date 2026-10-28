package com.cheesevanity;

import com.cheesevanity.network.SyncHiddenSlotsS2CPayload;
import com.cheesevanity.network.ToggleSlotC2SPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

public class CheeseVanityMod implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("CheeseVanity");
    private static VanityConfig config;

    @Override
    public void onInitialize() {
        config = VanityConfig.load();
        VanityData.load();
        LOGGER.info("[CheeseVanity] Loaded — vanilla armor: {}, trinkets: {}",
                config.enableVanillaArmor, config.enableTrinkets);

        // Register payloads
        PayloadTypeRegistry.playC2S().register(ToggleSlotC2SPayload.ID, ToggleSlotC2SPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SyncHiddenSlotsS2CPayload.ID, SyncHiddenSlotsS2CPayload.CODEC);

        // Handle C2S toggle request
        ServerPlayNetworking.registerGlobalReceiver(ToggleSlotC2SPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            String slotKey = payload.slotKey();

            // Validate slot key
            if (slotKey == null || slotKey.isEmpty() || slotKey.length() > 64) return;
            if (!config.isSlotToggleable(slotKey)) {
                LOGGER.info("[CheeseVanity] Slot '{}' not toggleable (enableVanillaArmor={}, enableTrinkets={}, isVanillaArmor={})",
                        slotKey, config.enableVanillaArmor, config.enableTrinkets, VanityConfig.isVanillaArmorSlot(slotKey));
                return;
            }

            context.server().execute(() -> {
                boolean nowHidden = VanityData.toggle(player.getUuid(), slotKey);
                VanityData.save();

                // Feedback to the player
                String msg = nowHidden
                        ? "[CheeseVanity] " + slotKey + " hidden"
                        : "[CheeseVanity] " + slotKey + " visible";
                player.sendMessage(Text.literal(msg).formatted(
                        nowHidden ? Formatting.RED : Formatting.GREEN), true);

                // Broadcast updated state to all players
                broadcastPlayerState(context.server(), player);
            });
        });

        // On player join: send them all existing hidden slot data
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity joiner = handler.getPlayer();
            // Send all players' hidden states to the joiner
            for (var entry : VanityData.getAll().entrySet()) {
                if (entry.getValue().isEmpty()) continue;
                sender.sendPacket(new SyncHiddenSlotsS2CPayload(
                        entry.getKey(), new HashSet<>(entry.getValue())));
            }
            // Also broadcast the joiner's state (if any) to all other players
            Set<String> joinerHidden = VanityData.getHidden(joiner.getUuid());
            if (joinerHidden != null && !joinerHidden.isEmpty()) {
                broadcastPlayerState(server, joiner);
            }
        });
    }

    /** Broadcast a player's hidden slot state to all online players. */
    private void broadcastPlayerState(MinecraftServer server, ServerPlayerEntity target) {
        Set<String> hidden = VanityData.getHidden(target.getUuid());
        SyncHiddenSlotsS2CPayload payload = new SyncHiddenSlotsS2CPayload(
                target.getUuid(), hidden != null ? new HashSet<>(hidden) : new HashSet<>());
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    public static VanityConfig getConfig() {
        return config;
    }
}
