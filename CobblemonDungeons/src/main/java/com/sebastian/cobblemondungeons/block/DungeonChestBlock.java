package com.sebastian.cobblemondungeons.block;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.EntityShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import com.sebastian.cobblemondungeons.util.InteractionHandler;
import com.sebastian.cobblemondungeons.CobblemonDungeons;

public class DungeonChestBlock extends ChestBlock {
    private static final VoxelShape CHEST_SHAPE = VoxelShapes.cuboid(0.0625, 0.0, 0.0625, 0.9375, 0.875, 0.9375);

    public DungeonChestBlock(Settings settings) {
        super(settings, () -> CobblemonDungeons.DUNGEON_CHEST_BLOCK_ENTITY);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new DungeonChestBlockEntity(pos, state);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (!world.isClient) {
            return InteractionHandler.handleDungeonChestInteract(player, world, pos, state.getBlock());
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return CHEST_SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (context instanceof EntityShapeContext entityContext && entityContext.getEntity() instanceof PokemonEntity pokemon) {
            if (pokemon.getPokemon().getAspects().contains("alpha")) {
                return VoxelShapes.empty();
            }
        }
        return CHEST_SHAPE;
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, net.minecraft.entity.LivingEntity placer, net.minecraft.item.ItemStack itemStack) {
        if (!world.isClient && world instanceof net.minecraft.server.world.ServerWorld serverWorld) {
            Integer id = CobblemonDungeons.DUNGEON_CHEST_BLOCK_IDS.get(this);
            if (id != null) {
                CobblemonDungeons.registerDungeonChest(serverWorld, pos, id);
            }
        }
        super.onPlaced(world, pos, state, placer, itemStack);
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!world.isClient && !state.isOf(newState.getBlock()) && world instanceof net.minecraft.server.world.ServerWorld serverWorld) {
            CobblemonDungeons.unregisterDungeonChest(serverWorld, pos);
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }

    @Override
    @Environment(EnvType.CLIENT)
    public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
        if (random.nextInt(8) == 0) {
            world.addParticle(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 0.0, 0.0, 0.0);
        }
    }
}
