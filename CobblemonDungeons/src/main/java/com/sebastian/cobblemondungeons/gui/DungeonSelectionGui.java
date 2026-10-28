package com.sebastian.cobblemondungeons.gui;

import com.sebastian.cobblemondungeons.party.DungeonsPartyManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class DungeonSelectionGui {

    public static void open(ServerPlayerEntity player) {
        if (!DungeonsPartyManager.hasPokemon(player)) {
            player.sendMessage(Text.translatable("cobblemondungeons.need_pokemon").formatted(Formatting.RED));
            return;
        }
        try {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("Opening Dungeon Selection for " + player.getName().getString());
            DungeonScreenHandler.openMode(player, DungeonScreenHandler.ViewMode.DUNGEONS);
        } catch (Exception e) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.error("Error opening DungeonSelectionGui", e);
            player.sendMessage(Text.translatable("cobblemondungeons.gui.open_failed", e.getMessage()).formatted(Formatting.RED));
        }
    }
}
