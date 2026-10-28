package com.sebastian.cobblemondungeons.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.util.math.random.Random;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

public class GuardianSpawnerBlock extends Block {
    private static final VoxelShape TINY_SHAPE = VoxelShapes.cuboid(0.499, 0.499, 0.499, 0.501, 0.501, 0.501);

    public GuardianSpawnerBlock(Settings settings) {
        super(settings);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        if (isHoldingSpawnerItemClient()) {
            return BlockRenderType.MODEL;
        }
        return BlockRenderType.INVISIBLE;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return context.isHolding(com.sebastian.cobblemondungeons.CobblemonDungeons.GUARDIAN_SPAWNER_ITEM) ? VoxelShapes.fullCube() : TINY_SHAPE;
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
    public void onPlaced(World world, BlockPos pos, BlockState state, net.minecraft.entity.LivingEntity placer, net.minecraft.item.ItemStack itemStack) {
        if (!world.isClient && world instanceof net.minecraft.server.world.ServerWorld serverWorld) {
            Integer id = com.sebastian.cobblemondungeons.CobblemonDungeons.GUARDIAN_SPAWNER_BLOCK_IDS.get(this);
            if (id != null) {
                com.sebastian.cobblemondungeons.CobblemonDungeons.registerGuardianSpawner(serverWorld, pos, id);
            }
        }
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!world.isClient && !state.isOf(newState.getBlock()) && world instanceof net.minecraft.server.world.ServerWorld serverWorld) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.unregisterGuardianSpawner(serverWorld, pos);
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }

    @Override
    @Environment(EnvType.CLIENT)
    public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
        PlayerEntity player = net.minecraft.client.MinecraftClient.getInstance().player;
        if (player != null && (player.getMainHandStack().isOf(this.asItem()) || player.getOffHandStack().isOf(this.asItem()))) {
            // Using a different particle to distinguish it from Alpha spawner if needed, 
            // but user said "similar to the alpha pokemon spawning block", so maybe same or similar.
            // Let's use SOUL particle for guardians.
            world.addParticle(ParticleTypes.SOUL, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 0.0, 0.0, 0.0);
        }
    }
    private boolean isHoldingSpawnerItemClient() {
        if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType() != net.fabricmc.api.EnvType.CLIENT) {
            return false;
        }
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client == null || client.player == null) return false;
        return client.player.getMainHandStack().isOf(com.sebastian.cobblemondungeons.CobblemonDungeons.GUARDIAN_SPAWNER_ITEM) || 
               client.player.getOffHandStack().isOf(com.sebastian.cobblemondungeons.CobblemonDungeons.GUARDIAN_SPAWNER_ITEM);
    }
}
