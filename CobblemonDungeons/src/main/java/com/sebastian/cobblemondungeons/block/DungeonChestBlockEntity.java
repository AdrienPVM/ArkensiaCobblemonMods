package com.sebastian.cobblemondungeons.block;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.util.math.BlockPos;
import com.sebastian.cobblemondungeons.CobblemonDungeons;

public class DungeonChestBlockEntity extends ChestBlockEntity {
    public DungeonChestBlockEntity(BlockPos pos, BlockState state) {
        super(CobblemonDungeons.DUNGEON_CHEST_BLOCK_ENTITY, pos, state);
    }
}
