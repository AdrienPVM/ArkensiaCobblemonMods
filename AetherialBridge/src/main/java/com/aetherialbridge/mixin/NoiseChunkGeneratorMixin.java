package com.aetherialbridge.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.AquiferSampler;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Random;

/**
 * Override pipeline for very-low-sea-level dimensions (sea_level &lt; -25), e.g. the
 * Aetherial Islands floating dim.
 *
 * <ul>
 *   <li>{@code createFluidLevelSampler}: bypass vanilla's lava-or-water picker — force water.</li>
 *   <li>{@code carve} RETURN (AIR step): force-write seafloor — bedrock + multi-octave
 *       sand/gravel/clay/dirt/prismarine + water column + decorations.</li>
 * </ul>
 */
@Mixin(NoiseChunkGenerator.class)
public abstract class NoiseChunkGeneratorMixin {

    @Shadow @Final
    private RegistryEntry<ChunkGeneratorSettings> settings;

    @Inject(
        method = "createFluidLevelSampler(Lnet/minecraft/world/gen/chunk/ChunkGeneratorSettings;)Lnet/minecraft/world/gen/chunk/AquiferSampler$FluidLevelSampler;",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void aetherialbridge$forceWaterFluidPicker(
            ChunkGeneratorSettings settings,
            CallbackInfoReturnable<AquiferSampler.FluidLevelSampler> cir) {
        if (settings.seaLevel() >= -25) return;
        BlockState water = settings.defaultFluid();
        AquiferSampler.FluidLevel waterLevel = new AquiferSampler.FluidLevel(settings.seaLevel() + 1, water);
        cir.setReturnValue((x, y, z) -> waterLevel);
    }

    @Inject(
        method = "carve(Lnet/minecraft/world/ChunkRegion;JLnet/minecraft/world/gen/noise/NoiseConfig;Lnet/minecraft/world/biome/source/BiomeAccess;Lnet/minecraft/world/gen/StructureAccessor;Lnet/minecraft/world/chunk/Chunk;Lnet/minecraft/world/gen/GenerationStep$Carver;)V",
        at = @At("RETURN")
    )
    private void aetherialbridge$forceSeafloor(ChunkRegion region, long seed, NoiseConfig noiseConfig,
                                                BiomeAccess biomes, StructureAccessor structures,
                                                Chunk chunk, GenerationStep.Carver step,
                                                CallbackInfo ci) {
        if (step != GenerationStep.Carver.AIR) return;
        ChunkGeneratorSettings cgs = this.settings.value();
        int seaLevel = cgs.seaLevel();
        if (seaLevel >= -25) return;

        int minY = cgs.generationShapeConfig().minimumY();
        int bedrockY = minY;
        int baseSandY = minY + 1;
        int maxSandY = seaLevel - 3;
        BlockState bedrock = Blocks.BEDROCK.getDefaultState();
        BlockState sand = Blocks.SAND.getDefaultState();
        BlockState gravel = Blocks.GRAVEL.getDefaultState();
        BlockState clay = Blocks.CLAY.getDefaultState();
        BlockState dirt = Blocks.DIRT.getDefaultState();
        BlockState prismarine = Blocks.PRISMARINE.getDefaultState();
        BlockState water = cgs.defaultFluid();

        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();
        BlockPos.Mutable pos = new BlockPos.Mutable();

        double phaseX = ((seed & 0xFFFFL) / 65535.0) * Math.PI * 2.0;
        double phaseZ = (((seed >>> 16) & 0xFFFFL) / 65535.0) * Math.PI * 2.0;
        double phaseD = (((seed >>> 32) & 0xFFFFL) / 65535.0) * Math.PI * 2.0;

        int[] sandTops = new int[16 * 16];
        BlockState[] floorTypes = new BlockState[16 * 16];

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = startX + dx;
                int z = startZ + dz;

                double big = Math.sin(x * 0.045 + phaseX) * Math.cos(z * 0.052 + phaseZ) * 8.0;
                double med = Math.sin(x * 0.21 + 1.3 + phaseD) * Math.cos(z * 0.19 - 0.7) * 4.0;
                double small = Math.sin(x * 0.6 + z * 0.5) * 1.5;
                double cross = Math.sin((x + z) * 0.07 + phaseX * 0.5) * 3.0;
                double n = big + med + small + cross;

                int extra = (int) Math.round(n + 14.0);
                int sandTopY = Math.min(maxSandY, Math.max(baseSandY, baseSandY + extra));
                sandTops[dx * 16 + dz] = sandTopY;

                // Floor diversity via independent slow patch noise.
                double patch = Math.sin(x * 0.08 + phaseD * 1.7) * Math.cos(z * 0.075 + phaseX * 1.3)
                             + Math.sin((x - z) * 0.13 + phaseZ) * 0.5;
                BlockState floorBlock;
                if (patch > 1.1) floorBlock = prismarine;
                else if (patch > 0.55) floorBlock = gravel;
                else if (patch < -1.05) floorBlock = clay;
                else if (patch < -0.6) floorBlock = dirt;
                else floorBlock = sand;
                floorTypes[dx * 16 + dz] = floorBlock;

                pos.set(x, bedrockY, z);
                chunk.setBlockState(pos, bedrock, false);

                for (int y = baseSandY; y <= sandTopY; y++) {
                    pos.set(x, y, z);
                    chunk.setBlockState(pos, floorBlock, false);
                }

                for (int y = sandTopY + 1; y <= seaLevel; y++) {
                    pos.set(x, y, z);
                    chunk.setBlockState(pos, water, false);
                }
            }
        }

        aetherialbridge$decorate(chunk, startX, startZ, seaLevel, sandTops, floorTypes, seed, pos);
    }

    private void aetherialbridge$decorate(Chunk chunk, int startX, int startZ, int seaLevel,
                                          int[] sandTops, BlockState[] floorTypes, long worldSeed,
                                          BlockPos.Mutable pos) {
        long chunkSeed = worldSeed
            ^ (((long) chunk.getPos().x) * 341873128712L)
            ^ (((long) chunk.getPos().z) * 132897987541L);
        Random rng = new Random(chunkSeed);

        BlockState[] coralBlocks = new BlockState[] {
            Blocks.BRAIN_CORAL_BLOCK.getDefaultState(),
            Blocks.BUBBLE_CORAL_BLOCK.getDefaultState(),
            Blocks.FIRE_CORAL_BLOCK.getDefaultState(),
            Blocks.HORN_CORAL_BLOCK.getDefaultState(),
            Blocks.TUBE_CORAL_BLOCK.getDefaultState()
        };
        BlockState[] coralFans = new BlockState[] {
            Blocks.BRAIN_CORAL_FAN.getDefaultState().with(Properties.WATERLOGGED, true),
            Blocks.BUBBLE_CORAL_FAN.getDefaultState().with(Properties.WATERLOGGED, true),
            Blocks.FIRE_CORAL_FAN.getDefaultState().with(Properties.WATERLOGGED, true),
            Blocks.HORN_CORAL_FAN.getDefaultState().with(Properties.WATERLOGGED, true),
            Blocks.TUBE_CORAL_FAN.getDefaultState().with(Properties.WATERLOGGED, true)
        };
        BlockState[] coralPlants = new BlockState[] {
            Blocks.BRAIN_CORAL.getDefaultState().with(Properties.WATERLOGGED, true),
            Blocks.BUBBLE_CORAL.getDefaultState().with(Properties.WATERLOGGED, true),
            Blocks.FIRE_CORAL.getDefaultState().with(Properties.WATERLOGGED, true),
            Blocks.HORN_CORAL.getDefaultState().with(Properties.WATERLOGGED, true),
            Blocks.TUBE_CORAL.getDefaultState().with(Properties.WATERLOGGED, true)
        };
        BlockState seagrass = Blocks.SEAGRASS.getDefaultState();
        BlockState tallSeagrassLower = Blocks.TALL_SEAGRASS.getDefaultState()
            .with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        BlockState tallSeagrassUpper = Blocks.TALL_SEAGRASS.getDefaultState()
            .with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        BlockState kelpPlant = Blocks.KELP_PLANT.getDefaultState();
        BlockState kelpTip = Blocks.KELP.getDefaultState();
        BlockState sponge = Blocks.SPONGE.getDefaultState();
        BlockState wetSponge = Blocks.WET_SPONGE.getDefaultState();

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int sandTopY = sandTops[dx * 16 + dz];
                int waterDepth = seaLevel - sandTopY;
                if (waterDepth < 2) continue;

                int x = startX + dx;
                int z = startZ + dz;

                BlockState floor = floorTypes[dx * 16 + dz];
                boolean isSandy = floor.isOf(Blocks.SAND) || floor.isOf(Blocks.GRAVEL);
                boolean isPrismarine = floor.isOf(Blocks.PRISMARINE);
                double roll = rng.nextDouble();

                if (isPrismarine && roll < 0.04) {
                    aetherialbridge$placeCoralCluster(chunk, pos, dx, dz, sandTopY, seaLevel,
                        startX, startZ, sandTops, rng, coralBlocks, coralFans, coralPlants);
                } else if (roll < 0.005) {
                    aetherialbridge$placeCoralCluster(chunk, pos, dx, dz, sandTopY, seaLevel,
                        startX, startZ, sandTops, rng, coralBlocks, coralFans, coralPlants);
                } else if (isSandy && roll < 0.06) {
                    int maxKelp = Math.min(14, waterDepth - 1);
                    if (maxKelp < 3) continue;
                    int h = 3 + rng.nextInt(maxKelp - 2);
                    for (int i = 0; i < h - 1; i++) {
                        pos.set(x, sandTopY + 1 + i, z);
                        chunk.setBlockState(pos, kelpPlant, false);
                    }
                    pos.set(x, sandTopY + h, z);
                    chunk.setBlockState(pos, kelpTip, false);
                } else if (roll < 0.10 && waterDepth >= 3) {
                    pos.set(x, sandTopY + 1, z);
                    chunk.setBlockState(pos, tallSeagrassLower, false);
                    pos.set(x, sandTopY + 2, z);
                    chunk.setBlockState(pos, tallSeagrassUpper, false);
                } else if (roll < 0.22) {
                    pos.set(x, sandTopY + 1, z);
                    chunk.setBlockState(pos, seagrass, false);
                } else if (roll < 0.235) {
                    pos.set(x, sandTopY + 1, z);
                    int picks = 1 + rng.nextInt(4);
                    chunk.setBlockState(pos,
                        Blocks.SEA_PICKLE.getDefaultState()
                            .with(Properties.PICKLES, picks)
                            .with(Properties.WATERLOGGED, true), false);
                } else if (roll < 0.238) {
                    pos.set(x, sandTopY + 1, z);
                    chunk.setBlockState(pos, rng.nextBoolean() ? sponge : wetSponge, false);
                }
            }
        }
    }

    private void aetherialbridge$placeCoralCluster(Chunk chunk, BlockPos.Mutable pos,
                                                    int dxCenter, int dzCenter, int topY, int seaLevel,
                                                    int startX, int startZ, int[] sandTops, Random rng,
                                                    BlockState[] coralBlocks, BlockState[] coralFans,
                                                    BlockState[] coralPlants) {
        int variant = rng.nextInt(coralBlocks.length);
        BlockState block = coralBlocks[variant];
        int n = 4 + rng.nextInt(6);
        for (int i = 0; i < n; i++) {
            int ddx = dxCenter + rng.nextInt(3) - 1;
            int ddz = dzCenter + rng.nextInt(3) - 1;
            if (ddx < 0 || ddx >= 16 || ddz < 0 || ddz >= 16) continue;
            int localTop = sandTops[ddx * 16 + ddz];
            int height = 1 + rng.nextInt(2);
            int blockY = Math.min(seaLevel - 2, localTop + height);
            int x = startX + ddx;
            int z = startZ + ddz;

            for (int y = localTop + 1; y <= blockY; y++) {
                pos.set(x, y, z);
                chunk.setBlockState(pos, block, false);
            }
            int topper = rng.nextInt(3);
            int topY2 = blockY + 1;
            if (topY2 <= seaLevel) {
                pos.set(x, topY2, z);
                if (topper == 0) {
                    chunk.setBlockState(pos, coralFans[rng.nextInt(coralFans.length)], false);
                } else if (topper == 1) {
                    chunk.setBlockState(pos, coralPlants[rng.nextInt(coralPlants.length)], false);
                }
            }
        }
    }
}
