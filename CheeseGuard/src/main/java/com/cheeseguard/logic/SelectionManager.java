package com.cheeseguard.logic;

import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Stores per-player wand selections (pos1 / pos2).
 */
public class SelectionManager {

    public static class Selection {
        public BlockPos pos1;
        public BlockPos pos2;
        public String world;

        public boolean isComplete() {
            return pos1 != null && pos2 != null && world != null;
        }
    }

    private final Map<UUID, Selection> selections = new HashMap<>();

    public Selection getOrCreate(UUID player) {
        return selections.computeIfAbsent(player, k -> new Selection());
    }

    public void setPos1(UUID player, String world, BlockPos pos) {
        Selection s = getOrCreate(player);
        s.pos1 = pos;
        s.world = world;
    }

    public void setPos2(UUID player, String world, BlockPos pos) {
        Selection s = getOrCreate(player);
        s.pos2 = pos;
        s.world = world;
    }

    public Selection getSelection(UUID player) {
        return selections.get(player);
    }

    public void clear(UUID player) {
        selections.remove(player);
    }
}
