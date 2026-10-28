package com.example.autocoinpurse;

import com.example.autocoinpurse.network.ToggleAutoCollectPayload;
import com.glisco.numismaticoverhaul.ModComponents;
import com.glisco.numismaticoverhaul.currency.CurrencyHelper;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class AutoCoinPurse implements ModInitializer {

    public static final String MOD_ID = "autocoinpurse";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * Server-side set of player UUIDs who have auto-collect enabled.
     * In a production mod, you'd persist this via a player data attachment
     * or Cardinal Components. Here we keep it in memory for simplicity —
     * the client re-sends its state on login via the toggle packet if needed,
     * or the player just toggles it again after a restart.
     *
     * NOTE: The client stores its own state in a config file so it always
     * knows what to display without asking the server.
     */
    public static final Set<UUID> AUTO_COLLECT_PLAYERS = new HashSet<>();

    // How often (in ticks) we scan inventories. 10 ticks = 0.5 seconds.
    private static final int SCAN_INTERVAL_TICKS = 10;
    private int tickCounter = 0;

    @Override
    public void onInitialize() {
        LOGGER.info("[AutoCoinPurse] Initializing…");

        // Register the C2S packet type
        PayloadTypeRegistry.playC2S().register(
                ToggleAutoCollectPayload.ID,
                ToggleAutoCollectPayload.CODEC
        );

        // Handle toggle request from client
        ServerPlayNetworking.registerGlobalReceiver(
                ToggleAutoCollectPayload.ID,
                (payload, context) -> {
                    ServerPlayerEntity player = context.player();
                    UUID uuid = player.getUuid();
                    if (payload.enabled()) {
                        AUTO_COLLECT_PLAYERS.add(uuid);
                        LOGGER.debug("[AutoCoinPurse] Auto-collect ENABLED for {}", player.getName().getString());
                    } else {
                        AUTO_COLLECT_PLAYERS.remove(uuid);
                        LOGGER.debug("[AutoCoinPurse] Auto-collect DISABLED for {}", player.getName().getString());
                    }
                }
        );

        // Tick-based scanner: for every player with toggle ON, collect their coins
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (tickCounter < SCAN_INTERVAL_TICKS) return;
            tickCounter = 0;

            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (!AUTO_COLLECT_PLAYERS.contains(player.getUuid())) continue;
                collectCoinsForPlayer(player);
            }
        });

        LOGGER.info("[AutoCoinPurse] Ready.");
    }

    /**
     * Collects all CurrencyItems from the player's inventory and adds their
     * total value to the player's Numismatic Overhaul purse.
     *
     * This replicates exactly what Numismatic Overhaul's own "Store All"
     * button does internally (RequestPurseActionC2SPacket, STORE_ALL action).
     */
    public static void collectCoinsForPlayer(ServerPlayerEntity player) {
        // getMoneyInInventory(player, remove=true) does two things:
        //   1. Scans all inventory slots for CurrencyItem instances
        //   2. If remove=true, clears those stacks from the inventory
        //   Returns the total value in base currency units (bronze)
        long totalValue = CurrencyHelper.getMoneyInInventory(player, true);

        if (totalValue <= 0) return; // Nothing to collect

        // Add the collected value to the player's global purse component
        ModComponents.CURRENCY.get(player).modify(totalValue);

        LOGGER.debug("[AutoCoinPurse] Collected {} bronze units for {}",
                totalValue, player.getName().getString());
    }
}
