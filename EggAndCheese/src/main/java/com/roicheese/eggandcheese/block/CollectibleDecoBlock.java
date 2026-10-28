package com.roicheese.eggandcheese.block;

import com.mojang.serialization.MapCodec;
import com.roicheese.eggandcheese.blockentity.CollectibleDecoBlockEntity;
import com.roicheese.eggandcheese.dna.CollectibleType;
import com.roicheese.eggandcheese.item.CollectibleItem;
import com.roicheese.eggandcheese.registry.ModBlockEntities;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public class CollectibleDecoBlock extends BlockWithEntity {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    private final CollectibleType collectibleType;

    public CollectibleDecoBlock(Settings settings, CollectibleType type) {
        super(settings.nonOpaque().noCollision());
        this.collectibleType = type;
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() { return MapCodec.unit(this); }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING); }

    @Nullable @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    /** Drop the collectible item (with its DNA) when broken */
    @Override
    public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        if (!world.isClient && world.getBlockEntity(pos) instanceof CollectibleDecoBlockEntity be) {
            ItemStack drop = CollectibleItem.create(be.getCollectibleType(), be.getDna());
            dropStack(world, pos, drop);
        }
        return super.onBreak(world, pos, state, player);
    }

    @Override public BlockRenderType getRenderType(BlockState state) { return BlockRenderType.ENTITYBLOCK_ANIMATED; }

    @Nullable @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new CollectibleDecoBlockEntity(pos, state, collectibleType);
    }

    @Override
    public <T extends BlockEntity> net.minecraft.block.entity.BlockEntityTicker<T> getTicker(
            World world, BlockState state, net.minecraft.block.entity.BlockEntityType<T> type) {
        return world.isClient
            ? validateTicker(type, ModBlockEntities.COLLECTIBLE_DECO, CollectibleDecoBlockEntity::tick)
            : null;
    }

    public CollectibleType getCollectibleType() { return collectibleType; }
}
