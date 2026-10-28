package com.sebastian.cobblemondungeons.gui;

import net.minecraft.server.network.ServerPlayerEntity;

public class DungeonsPartyGui {

    public static void open(ServerPlayerEntity player) {
        DungeonScreenHandler.openMode(player, DungeonScreenHandler.ViewMode.DUNGEONS_PARTY);
    }
}
