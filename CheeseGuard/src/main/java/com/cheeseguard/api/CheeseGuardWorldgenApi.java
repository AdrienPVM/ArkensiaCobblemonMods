package com.cheeseguard.api;

import com.cheeseguard.CheeseGuardMod;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;

/**
 * Public facade exposing worldgen helpers to {@link GenTypeProvider} implementations.
 * Encapsulates the tricky bits (TerraBlender runtime init, overworld surface_rule merge,
 * fresh MultiNoiseBiomeSource) so plugin mods don't have to re-derive them.
 */
public final class CheeseGuardWorldgenApi {

    private static Boolean tbPresent = null;
    private static java.lang.reflect.Method tbInitMethod = null;

    private CheeseGuardWorldgenApi() {}

    /**
     * Check whether Cobblemon raid dens may spawn in the given world.
     * Returns true for unknown / vanilla dims (no opinion). Returns false only
     * if a custom CheeseGuard dim explicitly toggled raidDensEnabled off.
     *
     * Safe to call from any thread (read-only access to the live config list).
     */
    public static boolean isRaidDensEnabled(String worldId) {
        try {
            var mgr = CheeseGuardMod.INSTANCE.getDimensionManager();
            if (mgr == null) return true;
            var cfg = mgr.getConfigByWorldId(worldId);
            return cfg == null || cfg.isRaidDensEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Merge the overworld's live surface_rule into the given settings.
     * Required so modded biomes injected by TerraBlender (BWG, BOP, M&L, etc.)
     * render with their proper surface blocks and bedrock floors.
     *
     * Returns a new ChunkGeneratorSettings with everything from {@code base}
     * except {@code surfaceRule}, which is taken from the live overworld.
     */
    public static ChunkGeneratorSettings withOverworldSurfaceRule(MinecraftServer server, ChunkGeneratorSettings base) {
        var cgsRegistry = server.getRegistryManager().get(RegistryKeys.CHUNK_GENERATOR_SETTINGS);
        ChunkGeneratorSettings overworld = cgsRegistry.getEntry(ChunkGeneratorSettings.OVERWORLD).orElseThrow().value();
        return new ChunkGeneratorSettings(
            base.generationShapeConfig(),
            base.defaultBlock(),
            base.defaultFluid(),
            base.noiseRouter(),
            overworld.surfaceRule(),
            base.spawnTarget(),
            base.seaLevel(),
            base.mobGenerationDisabled(),
            base.aquifers(),
            base.oreVeins(),
            base.usesLegacyRandom()
        );
    }

    /**
     * Build a fresh MultiNoiseBiomeSource from the OVERWORLD preset. This is
     * critical for runtime-created dims: reusing the live overworld's biome
     * source would lock biome XZ coordinates to the overworld (ignoring seed).
     * A fresh source respects the dim's own RandomState.
     */
    public static net.minecraft.world.biome.source.BiomeSource freshOverworldBiomeSource(MinecraftServer server) {
        var preset = server.getRegistryManager()
            .get(RegistryKeys.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
            .getEntry(net.minecraft.world.biome.source.MultiNoiseBiomeSourceParameterLists.OVERWORLD)
            .orElseThrow();
        return net.minecraft.world.biome.source.MultiNoiseBiomeSource.create(preset);
    }

    /**
     * Ensure TerraBlender's per-dim biome routing + surface rules are set up
     * for a runtime-created dimension. No-op if TerraBlender is absent.
     *
     * Providers should call this before registering their ServerWorld, so that
     * modded biomes (BWG, M&L, BOP) render with their proper features.
     */
    public static void ensureTerraBlenderReady(MinecraftServer server,
                                                RegistryKey<net.minecraft.world.World> worldKey,
                                                RegistryEntry<DimensionType> dimType,
                                                ChunkGenerator generator,
                                                long seed) {
        if (tbPresent != null && !tbPresent) return;
        try {
            if (tbInitMethod == null) {
                Class<?> cls = Class.forName("terrablender.util.LevelUtils");
                for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                    if (m.getParameterCount() == 5 && m.getReturnType() == void.class
                        && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                        Class<?>[] pt = m.getParameterTypes();
                        if (pt[0] == net.minecraft.registry.DynamicRegistryManager.class
                            && pt[1] == RegistryEntry.class
                            && pt[2] == RegistryKey.class
                            && pt[3] == ChunkGenerator.class
                            && pt[4] == long.class) {
                            tbInitMethod = m;
                            break;
                        }
                    }
                }
                if (tbInitMethod == null) { tbPresent = false; return; }
                tbPresent = true;
            }
            RegistryKey<DimensionOptions> dimOptKey = RegistryKey.of(RegistryKeys.DIMENSION, worldKey.getValue());
            tbInitMethod.invoke(null, server.getRegistryManager(), dimType, dimOptKey, generator, seed);
            CheeseGuardMod.LOGGER.info("[CG] TerraBlender initialized for dim {}", worldKey.getValue());
        } catch (ClassNotFoundException e) {
            tbPresent = false;
        } catch (Throwable e) {
            CheeseGuardMod.LOGGER.warn("[CG] TerraBlender init skipped for {}: {}", worldKey.getValue(), e.getMessage());
        }
    }
}
