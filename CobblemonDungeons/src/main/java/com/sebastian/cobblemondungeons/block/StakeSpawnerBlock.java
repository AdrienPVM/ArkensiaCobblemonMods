package com.sebastian.cobblemondungeons.block;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import com.sebastian.cobblemondungeons.dungeon.DungeonManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import com.sebastian.cobblemondungeons.CobblemonDungeons;

public class StakeSpawnerBlock extends Block {
    public static final BooleanProperty STAKE_PRESENT = BooleanProperty.of("stake_present");
    private static final VoxelShape TINY_SHAPE = VoxelShapes.cuboid(0.499, 0.499, 0.499, 0.501, 0.501, 0.501);

    public StakeSpawnerBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(STAKE_PRESENT, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(STAKE_PRESENT);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        if (state.get(STAKE_PRESENT) || isHoldingSpawnerItemClient()) {
            return BlockRenderType.MODEL;
        }
        return BlockRenderType.INVISIBLE;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (state.get(STAKE_PRESENT)) {
            return StakeBlock.SHAPE;
        }
        return context.isHolding(this.asItem()) ? VoxelShapes.fullCube() : TINY_SHAPE;
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (state.get(STAKE_PRESENT)) {
            if (!world.isClient && player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
                DungeonManager.handleStakeFound(serverPlayer);
                world.setBlockState(pos, state.with(STAKE_PRESENT, false));
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.PASS;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.empty();
    }

    @Override
    public VoxelShape getCameraCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.empty();
    }

    @Override
    public float getAmbientOcclusionLightLevel(BlockState state, BlockView world, BlockPos pos) {
        return 1.0F;
    }

    @Override
    public boolean isTransparent(BlockState state, BlockView world, BlockPos pos) {
        return true;
    }

    @Override
    @Environment(EnvType.CLIENT)
    public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
        PlayerEntity player = net.minecraft.client.MinecraftClient.getInstance().player;
        if (player != null && (player.getMainHandStack().isOf(this.asItem()) || player.getOffHandStack().isOf(this.asItem()))) {
            world.addParticle(ParticleTypes.TRIAL_SPAWNER_DETECTION, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 0.0, 0.0, 0.0);
        }
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, net.minecraft.entity.LivingEntity placer, net.minecraft.item.ItemStack itemStack) {
        if (!world.isClient && world instanceof net.minecraft.server.world.ServerWorld serverWorld) {
            Integer id = CobblemonDungeons.STAKE_SPAWNER_BLOCK_IDS.get(this);
            if (id != null) {
                CobblemonDungeons.registerStakeSpawner(serverWorld, pos, id);
            }
        }
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!world.isClient && !state.isOf(newState.getBlock()) && world instanceof net.minecraft.server.world.ServerWorld serverWorld) {
            CobblemonDungeons.unregisterStakeSpawner(serverWorld, pos);
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }

    private boolean isHoldingSpawnerItemClient() {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return false;
        return client.player.getMainHandStack().isOf(CobblemonDungeons.STAKE_SPAWNER_ITEM) || 
               client.player.getOffHandStack().isOf(CobblemonDungeons.STAKE_SPAWNER_ITEM);
    }
}
