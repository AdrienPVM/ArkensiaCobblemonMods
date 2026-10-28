package com.roicheese.eggandcheese.block;

import com.mojang.serialization.MapCodec;
import com.roicheese.eggandcheese.blockentity.PedestalBlockEntity;
import com.roicheese.eggandcheese.item.AdminCollectibleItem;
import com.roicheese.eggandcheese.registry.ModBlockEntities;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public class PedestalBlock extends BlockWithEntity {

    private final PedestalVariant variant;

    public PedestalBlock(Settings settings, PedestalVariant variant) {
        super(settings);
        this.variant = variant;
    }

    public PedestalVariant getVariant() { return variant; }

    @Override
    public boolean isSideInvisible(BlockState state, BlockState stateFrom, net.minecraft.util.math.Direction direction) {
        return false;
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() { return MapCodec.unit(this); }

    // ── Dynamic collision/outline shape ───────────────────────────────────────

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (world.getBlockEntity(pos) instanceof PedestalBlockEntity pedestal) {
            return pedestal.getShape();
        }
        return VoxelShapes.fullCube();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return getOutlineShape(state, world, pos, context);
    }

    // ── Interaction ───────────────────────────────────────────────────────────

    /** Empty-hand right-click: remove displayed item */
    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                  PlayerEntity player, BlockHitResult hit) {
        if (world.isClient) return ActionResult.SUCCESS;
        if (!(world.getBlockEntity(pos) instanceof PedestalBlockEntity pedestal)) return ActionResult.PASS;
        if (!pedestal.hasCollectible()) return ActionResult.PASS;

        ItemStack removed = pedestal.removeCollectible();
        if (!player.getInventory().insertStack(removed)) player.dropItem(removed, false);
        return ActionResult.SUCCESS;
    }

    /** Place item on pedestal when holding one */
    @Override
    protected ItemActionResult onUseWithItem(ItemStack stack, BlockState state, World world,
                                              BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!(world.getBlockEntity(pos) instanceof PedestalBlockEntity pedestal)) return ItemActionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        if (pedestal.hasCollectible() || stack.isEmpty()) {
            return ItemActionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // Admin eggs cannot be placed on pedestals
        if (stack.getItem() instanceof AdminCollectibleItem) {
            return ItemActionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (world.isClient) return ItemActionResult.SUCCESS;

        ItemStack toPlace = stack.copyWithCount(1);
        stack.decrement(1);
        pedestal.setCollectible(toPlace);
        return ItemActionResult.SUCCESS;
    }

    // ── Public forwarding for PedestalExtensionBlock ─────────────────────────

    public ActionResult handleUse(BlockState state, World world, BlockPos pos,
                                   PlayerEntity player, BlockHitResult hit) {
        return onUse(state, world, pos, player, hit);
    }

    public ItemActionResult handleUseWithItem(ItemStack stack, BlockState state, World world,
                                               BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        return onUseWithItem(stack, state, world, pos, player, hand, hit);
    }

    // ── Cleanup extensions on break ──────────────────────────────────────────

    @Override
    protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock())) {
            if (world.getBlockEntity(pos) instanceof PedestalBlockEntity be) {
                be.removeAllExtensions();
            }
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }

    // ── Block entity ──────────────────────────────────────────────────────────

    @Override public BlockRenderType getRenderType(BlockState state) { return BlockRenderType.ENTITYBLOCK_ANIMATED; }

    @Nullable @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) { return new PedestalBlockEntity(pos, state); }

    @Override
    public <T extends BlockEntity> net.minecraft.block.entity.BlockEntityTicker<T> getTicker(
            World world, BlockState state, net.minecraft.block.entity.BlockEntityType<T> type) {
        return world.isClient ? validateTicker(type, ModBlockEntities.PEDESTAL, PedestalBlockEntity::tick) : null;
    }
}
