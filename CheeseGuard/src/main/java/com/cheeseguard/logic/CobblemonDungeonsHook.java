package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.Method;

/**
 * Soft-dependency hook for CobblemonDungeons mod.
 * Uses reflection to call DungeonManager/DungeonTicketItem without compile-time dependency.
 * Handles: DUNGEON_OPEN, DUNGEON_TICKET_GIVE
 */
public class CobblemonDungeonsHook {

    private static boolean initialized = false;
    private static boolean available = false;

    // Cached reflection targets
    private static Method startDungeonMethod;     // DungeonManager.startDungeon(ServerPlayerEntity, String, int) -> boolean
    private static Method getDungeonConfigMethod;  // DungeonManager.getDungeonConfig(String) -> DungeonConfig
    private static Method createTicketStackMethod; // DungeonTicketItem.createStack(TicketConfig, int) -> ItemStack

    // For ticket creation we need the TicketConfig class
    private static Class<?> ticketConfigClass;     // DungeonTicketConfig.TicketConfig
    private static Class<?> dungeonTicketConfigClass; // DungeonTicketConfig

    /**
     * Attempt to initialize CobblemonDungeons reflection hooks.
     * Call once during EventManager.init().
     */
    public static void tryInit() {
        if (initialized) return;
        initialized = true;
        try {
            // DungeonManager
            Class<?> dmClass = Class.forName("com.sebastian.cobblemondungeons.dungeon.DungeonManager");
            startDungeonMethod = dmClass.getMethod("startDungeon", ServerPlayerEntity.class, String.class, int.class);
            getDungeonConfigMethod = dmClass.getMethod("getDungeonConfig", String.class);

            // DungeonTicketItem for creating ticket stacks
            Class<?> ticketItemClass = Class.forName("com.sebastian.cobblemondungeons.item.DungeonTicketItem");
            ticketConfigClass = Class.forName("com.sebastian.cobblemondungeons.config.DungeonTicketConfig$TicketConfig");
            createTicketStackMethod = ticketItemClass.getMethod("createStack", ticketConfigClass, int.class);

            dungeonTicketConfigClass = Class.forName("com.sebastian.cobblemondungeons.config.DungeonTicketConfig");

            available = true;
            CheeseGuardMod.LOGGER.info("[CG] CobblemonDungeons detected — dungeon effects enabled");
        } catch (ClassNotFoundException e) {
            CheeseGuardMod.LOGGER.info("[CG] CobblemonDungeons not installed — dungeon effects disabled");
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.warn("[CG] CobblemonDungeons hook failed: {}", e.getMessage());
        }
    }

    public static boolean isAvailable() { return available; }

    /**
     * Start a dungeon for a player (leader) with specified tier.
     * @return true if dungeon start was initiated
     */
    public static boolean startDungeon(ServerPlayerEntity leader, String dungeonId, int tier) {
        if (!available) return false;
        try {
            return (boolean) startDungeonMethod.invoke(null, leader, dungeonId, tier);
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.debug("[CG] CobblemonDungeons startDungeon failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Give dungeon tickets to a player by creating a TicketConfig with the given params.
     * @return true if items were given
     */
    public static boolean giveDungeonTicket(ServerPlayerEntity player, String dungeonId, int tier, int count) {
        if (!available) return false;
        try {
            // Create a TicketConfig instance via reflection
            Object ticketConfig = ticketConfigClass.getDeclaredConstructor().newInstance();
            // Set fields: dungeonId, tier, itemName
            ticketConfigClass.getField("dungeonId").set(ticketConfig, dungeonId);
            ticketConfigClass.getField("tier").setInt(ticketConfig, tier);
            ticketConfigClass.getField("itemName").set(ticketConfig, "\u00A76Dungeon Ticket [" + dungeonId + " T" + tier + "]");

            // Create the ItemStack
            Object stack = createTicketStackMethod.invoke(null, ticketConfig, count);

            // Give to player: player.getInventory().insertStack(stack)
            Method insertStack = player.getInventory().getClass().getMethod("insertStack", net.minecraft.item.ItemStack.class);
            boolean inserted = (boolean) insertStack.invoke(player.getInventory(), stack);
            if (!inserted) {
                // Drop at player feet if inventory full
                player.dropStack((net.minecraft.item.ItemStack) stack);
            }
            return true;
        } catch (Exception e) {
            CheeseGuardMod.LOGGER.debug("[CG] CobblemonDungeons giveDungeonTicket failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if a dungeon config exists.
     */
    public static boolean dungeonExists(String dungeonId) {
        if (!available) return false;
        try {
            Object config = getDungeonConfigMethod.invoke(null, dungeonId);
            return config != null;
        } catch (Exception e) {
            return false;
        }
    }
}
