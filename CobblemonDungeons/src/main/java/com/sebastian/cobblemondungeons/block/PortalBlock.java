package com.sebastian.cobblemondungeons.block;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import com.sebastian.cobblemondungeons.dungeon.DungeonManager;
import com.sebastian.cobblemondungeons.dungeon.DungeonInstance;
import com.sebastian.cobblemondungeons.gui.DungeonScreenHandler;
import com.sebastian.cobblemondungeons.CobblemonDungeons;
import java.util.List;
import java.util.Collections;

public class PortalBlock extends Block {
    public static final BooleanProperty VISIBLE = BooleanProperty.of("visible");
    private static final VoxelShape TINY_SHAPE = VoxelShapes.cuboid(0.499, 0.499, 0.499, 0.501, 0.501, 0.501);

    public PortalBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(VISIBLE, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(VISIBLE);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        if (state.get(VISIBLE) || isHoldingPortalItemClient()) {
            return BlockRenderType.MODEL;
        }
        return BlockRenderType.INVISIBLE;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (state.get(VISIBLE)) {
            return VoxelShapes.fullCube();
        }
        return context.isHolding(this.asItem()) ? VoxelShapes.fullCube() : TINY_SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.empty(); // Non-solid so players can stand in it
    }

    @Override
    public void onEntityCollision(BlockState state, World world, BlockPos pos, Entity entity) {
        // No longer teleport on step
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (state.get(VISIBLE)) {
            if (!world.isClient && player instanceof ServerPlayerEntity serverPlayer) {
                com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("[DEBUG] Portal right-clicked by " + player.getName().getString() + " at " + pos.toShortString());
                openBossPrompt(serverPlayer);
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.PASS;
    }

    private void openBossPrompt(ServerPlayerEntity player) {
        DungeonInstance instance = DungeonManager.getInstance(player.getUuid());
        if (instance == null) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("[DEBUG] No dungeon instance found for player " + player.getName().getString());
            player.sendMessage(Text.translatable("cobblemondungeons.portal.not_in_dungeon").formatted(Formatting.RED), true);
            return;
        }

        if (!instance.canAccessBoss()) {
            com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("[DEBUG] Player " + player.getName().getString() + " tried to use portal but hasn't completed objectives.");
            player.sendMessage(Text.translatable("cobblemondungeons.portal.objectives_incomplete").formatted(Formatting.RED), true);
            return;
        }

        com.sebastian.cobblemondungeons.CobblemonDungeons.LOGGER.info("[DEBUG] Opening boss prompt GUI for player " + player.getName().getString());
        DungeonScreenHandler.openMode(player, DungeonScreenHandler.ViewMode.BOSS_PROMPT, instance.getConfig().general.id);
    }

    @Override
    public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
        if (state.get(VISIBLE)) {
            double x = pos.getX() + 0.5;
            double y = pos.getY() + 0.5;
            double z = pos.getZ() + 0.5;
            for (int i = 0; i < 3; i++) {
                world.addParticle(ParticleTypes.SOUL, 
                    x + (random.nextDouble() - 0.5), 
                    y + (random.nextDouble() - 0.5), 
                    z + (random.nextDouble() - 0.5), 
                    0, 0.05, 0);
            }
        }
        if (isHoldingPortalItemClient()) {
            world.addParticle(ParticleTypes.TRIAL_SPAWNER_DETECTION, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 0.0, 0.0, 0.0);
        }
    }

    private boolean isHoldingPortalItemClient() {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
            return false;
        }
        return clientHoldingCheck();
    }

    @Environment(EnvType.CLIENT)
    private boolean clientHoldingCheck() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return false;
        return client.player.getMainHandStack().isOf(this.asItem()) || 
               client.player.getOffHandStack().isOf(this.asItem());
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, net.minecraft.entity.LivingEntity placer, net.minecraft.item.ItemStack itemStack) {
        if (!world.isClient && world instanceof net.minecraft.server.world.ServerWorld serverWorld) {
            Integer id = CobblemonDungeons.PORTAL_BLOCK_IDS.get(this);
            if (id != null) {
                CobblemonDungeons.registerPortalBlock(serverWorld, pos, id);
            }
        }
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!world.isClient && !state.isOf(newState.getBlock()) && world instanceof net.minecraft.server.world.ServerWorld serverWorld) {
            CobblemonDungeons.unregisterPortalBlock(serverWorld, pos);
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }
}

