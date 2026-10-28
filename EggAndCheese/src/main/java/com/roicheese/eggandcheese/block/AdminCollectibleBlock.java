package com.roicheese.eggandcheese.block;

import com.mojang.serialization.MapCodec;
import com.roicheese.eggandcheese.blockentity.AdminCollectibleBlockEntity;
import com.roicheese.eggandcheese.data.EggRegistryData;
import com.roicheese.eggandcheese.dna.CollectibleType;
import com.roicheese.eggandcheese.registry.ModBlockEntities;
import com.roicheese.eggandcheese.item.CollectibleItem;
import com.roicheese.eggandcheese.registry.ModItems;
import com.roicheese.eggandcheese.util.PermissionUtil;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

public class AdminCollectibleBlock extends BlockWithEntity implements Waterloggable {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;
    private final CollectibleType collectibleType;

    public AdminCollectibleBlock(Settings settings, CollectibleType type) {
        super(settings.nonOpaque().noCollision());
        this.collectibleType = type;
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH).with(WATERLOGGED, false));
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() { return MapCodec.unit(this); }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING, WATERLOGGED); }

    @Nullable @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        FluidState fluid = ctx.getWorld().getFluidState(ctx.getBlockPos());
        return getDefaultState()
            .with(FACING, ctx.getHorizontalPlayerFacing().getOpposite())
            .with(WATERLOGGED, fluid.getFluid() == Fluids.WATER);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.get(WATERLOGGED) ? Fluids.WATER.getStill(false) : super.getFluidState(state);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                 WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        if (state.get(WATERLOGGED)) {
            world.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
        }
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }

    // ── Indestructibility ────────────────────────────────────────────────────

    @Override
    public boolean canReplace(BlockState state, ItemPlacementContext context) { return false; }

    @Override
    public float calcBlockBreakingDelta(BlockState state, PlayerEntity player, BlockView world, BlockPos pos) {
        return PermissionUtil.isAdmin(player) ? super.calcBlockBreakingDelta(state, player, world, pos) : 0.0f;
    }

    @Override
    public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        if (!world.isClient && PermissionUtil.isAdmin(player)) {
            if (world.getBlockEntity(pos) instanceof AdminCollectibleBlockEntity be) {
                String regId = be.getRegistryId();

                // Build drop item with DNA + registryId
                ItemStack drop = new ItemStack(ModItems.ADMIN_EGG);
                NbtCompound nbt = new NbtCompound();
                nbt.put("dna", be.getDna().toNbt());
                nbt.putFloat("scale", be.getScale());
                if (!regId.isEmpty()) nbt.putString("registryId", regId);
                drop.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
                // Give directly to inventory (no world drop)
                if (!player.getInventory().insertStack(drop)) player.dropItem(drop, false);

                // Mark registry entry as position lost
                if (!regId.isEmpty() && player instanceof ServerPlayerEntity sp) {
                    EggRegistryData registry = EggRegistryData.get(sp.getServer());
                    registry.getEntry(regId).ifPresent(entry -> {
                        entry.setPositionLost(true);
                        entry.setPos(BlockPos.ORIGIN);
                        registry.markDirty();
                    });
                    sp.sendMessage(Text.translatable(
                        "eggandcheese.position_lost", regId)
                        .formatted(Formatting.GOLD), false);
                }
            }
            return super.onBreak(world, pos, state, player);
        }
        return state;
    }

    // ── Interaction ──────────────────────────────────────────────────────────

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos,
                                  PlayerEntity player, BlockHitResult hit) {
        if (world.isClient) return ActionResult.SUCCESS;
        if (!(world.getBlockEntity(pos) instanceof AdminCollectibleBlockEntity be))
            return ActionResult.PASS;

        boolean isAdmin = PermissionUtil.isAdmin(player);

        // Regular right-click (admin) → receive the deco egg (once per admin)
        if (isAdmin && player instanceof ServerPlayerEntity sp) {
            if (!be.hasPlayerReceivedDeco(sp.getUuid())) {
                be.giveDecoItemTo(sp);
            } else {
                sp.sendMessage(net.minecraft.text.Text.translatable(
                    "eggandcheese.already_received_deco")
                    .formatted(net.minecraft.util.Formatting.YELLOW), true);
            }
            return ActionResult.SUCCESS;
        }

        // Regular right-click (non-admin player) → discovery / quest
        if (player instanceof ServerPlayerEntity sp) {
            // Only give deco once per player per egg
            if (!be.hasPlayerReceivedDeco(sp.getUuid())) {
                be.handlePlayerDiscovery(sp);
            } else {
                sp.sendMessage(net.minecraft.text.Text.translatable("eggandcheese.already_found"), true);
            }
        }
        return ActionResult.SUCCESS;
    }

    // ── Block entity ─────────────────────────────────────────────────────────

    @Nullable @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new AdminCollectibleBlockEntity(pos, state, collectibleType);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) { return BlockRenderType.ENTITYBLOCK_ANIMATED; }

    @Override
    public <T extends BlockEntity> net.minecraft.block.entity.BlockEntityTicker<T> getTicker(
            World world, BlockState state, net.minecraft.block.entity.BlockEntityType<T> type) {
        return world.isClient
            ? validateTicker(type, ModBlockEntities.ADMIN_COLLECTIBLE, AdminCollectibleBlockEntity::tick)
            : null;
    }

    public CollectibleType getCollectibleType() { return collectibleType; }
}
