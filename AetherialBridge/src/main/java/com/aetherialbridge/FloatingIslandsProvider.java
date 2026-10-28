package com.aetherialbridge;

import com.cheeseguard.api.CheeseGuardWorldgenApi;
import com.cheeseguard.api.GenTypeProvider;
import com.cheeseguard.data.DimensionConfig;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.dimension.DimensionTypes;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;

/**
 * Floating-islands GenTypeProvider — exposes the Aetherial Islands datapack
 * (repackaged into the {@code aetherial_islands} namespace) as a CheeseGuard dimension type.
 *
 * Vanilla {@code minecraft:overworld} is left untouched: the original datapack overrode
 * {@code data/minecraft/worldgen/noise_settings/overworld.json}; the DimScope build moves
 * that file to {@code data/aetherial_islands/worldgen/noise_settings/floating.json} and
 * removes the carver/feature/structure overrides. This provider then references the
 * relocated settings to build a {@link NoiseChunkGenerator} for a single dimension.
 */
public final class FloatingIslandsProvider implements GenTypeProvider {

    public static final String PROVIDER_ID = "aetherial-islands:floating";
    private static final Identifier SETTINGS_ID = Identifier.of("aetherial_islands", "floating");
    private static final RegistryKey<ChunkGeneratorSettings> SETTINGS_KEY =
        RegistryKey.of(RegistryKeys.CHUNK_GENERATOR_SETTINGS, SETTINGS_ID);

    @Override public String id()        { return PROVIDER_ID; }
    @Override public String label()     { return "Aetherial Islands"; }
    @Override public String icon()      { return "\u2601"; } // ☁
    @Override public double defaultSpawnY() { return 100.0; }
    @Override public RegistryKey<DimensionType> dimensionType() { return DimensionTypes.OVERWORLD; }

    @Override
    public ChunkGenerator createChunkGenerator(MinecraftServer server, DimensionConfig config) {
        var cgsRegistry = server.getRegistryManager().get(RegistryKeys.CHUNK_GENERATOR_SETTINGS);
        RegistryEntry<ChunkGeneratorSettings> settingsEntry = cgsRegistry.getEntry(SETTINGS_KEY)
            .orElseThrow(() -> new IllegalStateException(
                "ChunkGeneratorSettings '" + SETTINGS_ID
                    + "' not loaded — is the aetherial-islands-2.0.2-dimscope.jar present in mods/?"));

        BiomeSource biomeSource = CheeseGuardWorldgenApi.freshOverworldBiomeSource(server);

        AetherialBridgeMod.LOGGER.info(
            "[AetherialBridge] Built NoiseChunkGenerator for dim '{}' using {} (min_y={}, height={})",
            config.getId(), SETTINGS_ID,
            settingsEntry.value().generationShapeConfig().minimumY(),
            settingsEntry.value().generationShapeConfig().height());
        return new NoiseChunkGenerator(biomeSource, settingsEntry);
    }
}
