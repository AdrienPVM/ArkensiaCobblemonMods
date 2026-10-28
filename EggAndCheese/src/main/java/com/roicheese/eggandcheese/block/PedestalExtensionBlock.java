package com.roicheese.eggandcheese.block;

import com.roicheese.eggandcheese.blockentity.PedestalBlockEntity;
import net.minecraft.block.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * Invisible collision-proxy block placed above tall pedestals.
 * Stores its vertical offset (1–4) from the base PedestalBlock.
 * Delegates shape and interactions to the base pedestal.
 */
public class PedestalExtensionBlock extends Block {
    public static final IntProperty OFFSET = IntProperty.of("offset", 1, 4);

    public PedestalExtensionBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(OFFSET, 1));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(OFFSET);
    }

    // ── Shape delegation ─────────────────────────────────────────────────────

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        int offset = state.get(OFFSET);
        BlockPos basePos = pos.down(offset);
        if (world.getBlockEntity(basePos) instanceof PedestalBlockEntity be) {
            return be.getShapeForOffset(offset);
        }
        return VoxelShapes.empty();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        return getOutlineShape(state, world, pos, ctx);
    }

    // ── Invisible rendering ──────────────────────────────────────────────────

    @Override
    protected BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.INVISIBLE;
    }

    @Override
    public boolean isSideInvisible(BlockState state, BlockState stateFrom, net.minecraft.util.math.Direction direction) {
        return true;
    }

    // ── Interaction forwarding ───────────────────────────────────────────────

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                  PlayerEntity player, BlockHitResult hit) {
        int offset = state.get(OFFSET);
        BlockPos basePos = pos.down(offset);
        BlockState baseState = world.getBlockState(basePos);
        if (baseState.getBlock() instanceof PedestalBlock pb) {
            return pb.handleUse(baseState, world, basePos, player, hit);
        }
        return ActionResult.PASS;
    }

    @Override
    protected ItemActionResult onUseWithItem(ItemStack stack, BlockState state, World world,
                                              BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        int offset = state.get(OFFSET);
        BlockPos basePos = pos.down(offset);
        BlockState baseState = world.getBlockState(basePos);
        if (baseState.getBlock() instanceof PedestalBlock pb) {
            return pb.handleUseWithItem(stack, baseState, world, basePos, player, hand, hit);
        }
        return ItemActionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
}
