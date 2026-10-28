package com.cheesecompat.block;

import com.cheesecompat.CheeseCompatMod;
import maxigregrze.cobblesafari.teleporter.TeleporterTickHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Portal block that fills the interior of a CobbleSafari portal frame.
 * When a player walks through, it triggers the CobbleSafari teleporter logic
 * (same as stepping on cobblesafari:safari_teleporter).
 */
public class CobbleSafariPortalBlock extends Block {

    public static final EnumProperty<Direction.Axis> AXIS = Properties.AXIS;

    private static final VoxelShape X_SHAPE = Block.createCuboidShape(0, 0, 6, 16, 16, 10);
    private static final VoxelShape Z_SHAPE = Block.createCuboidShape(6, 0, 0, 10, 16, 16);

    private static final Map<UUID, Long> messageCooldown = new ConcurrentHashMap<>();

    public CobbleSafariPortalBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(AXIS, Direction.Axis.X));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        return state.get(AXIS) == Direction.Axis.X ? X_SHAPE : Z_SHAPE;
    }

    @Override
    public void onEntityCollision(BlockState state, World world, BlockPos pos, Entity entity) {
        if (world.isClient()) return;
        if (!(entity instanceof ServerPlayerEntity player)) return;

        String dimId = world.getRegistryKey().getValue().toString();

        // Block teleportation if already in a CobbleSafari dimension
        if (dimId.startsWith("cobblesafari:")) {
            UUID playerId = player.getUuid();
            long currentTick = world.getServer().getTicks();
            Long lastMsg = messageCooldown.get(playerId);
            if (lastMsg == null || currentTick - lastMsg > 100L) {
                player.sendMessage(Text.translatable("cobblesafari.teleporter.overworld_only"), false);
                messageCooldown.put(playerId, currentTick);
            }
            return;
        }

        // Trigger CobbleSafari teleporter logic
        TeleporterTickHandler.updatePlayerOnTeleporter(player);
    }

    /**
     * If a neighboring frame block is removed, this portal block should disappear.
     */
    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                 WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        Direction.Axis portalAxis = state.get(AXIS);
        // Only check perpendicular directions and vertical
        Direction.Axis dirAxis = direction.getAxis();
        if (dirAxis == portalAxis) {
            // Neighbor is along the portal plane — not a frame direction
            return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
        }
        // Check if the neighbor was a frame block and is now gone
        if (!neighborState.isOf(CobbleSafariBlocks.COBBLESAFARI_PORTAL_FRAME)
            && !neighborState.isOf(CobbleSafariBlocks.COBBLESAFARI_PORTAL)) {
            // Neighbor is no longer frame or portal — check if we're still supported
            if (direction == Direction.UP || direction == Direction.DOWN
                || direction.getAxis() != portalAxis) {
                return net.minecraft.block.Blocks.AIR.getDefaultState();
            }
        }
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }

    @Override
    public BlockState onBreak(World world, BlockPos pos, BlockState state, net.minecraft.entity.player.PlayerEntity player) {
        // Portal blocks should not drop items
        return super.onBreak(world, pos, state, player);
    }

    // Portal blocks are non-solid, transparent, no collision
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        return net.minecraft.util.shape.VoxelShapes.empty();
    }

    @Override
    public boolean isTransparent(BlockState state, BlockView world, BlockPos pos) {
        return true;
    }

    @Override
    public int getOpacity(BlockState state, BlockView world, BlockPos pos) {
        return 0;
    }

    @Override
    public float getAmbientOcclusionLightLevel(BlockState state, BlockView world, BlockPos pos) {
        return 1.0f;
    }
}
