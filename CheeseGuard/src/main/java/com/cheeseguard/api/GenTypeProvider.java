package com.cheeseguard.api;

import com.cheeseguard.data.DimensionConfig;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.gen.chunk.ChunkGenerator;

/**
 * Plugin API — extension point for external mods to contribute
 * custom dimension generation types to CheeseGuard.
 *
 * Mods register a provider via the Fabric custom entrypoint {@code "cheeseguard-gentype"}
 * pointing to a class implementing this interface. CheeseGuard discovers all providers
 * at mod init and exposes them through the dashboard dropdown.
 *
 * See {@code AetherialBridge} for a reference implementation.
 */
public interface GenTypeProvider {

    /** Unique identifier, must be namespaced (e.g. "aetherial-islands:floating"). */
    String id();

    /** Human-readable label shown in the dashboard dropdown. */
    String label();

    /** Single-glyph emoji or symbol shown next to the label. */
    String icon();

    /** Default spawn Y for newly created dims using this provider. */
    double defaultSpawnY();

    /** Dimension type registry key (usually {@code DimensionTypes.OVERWORLD}). */
    RegistryKey<DimensionType> dimensionType();

    /**
     * Build the ChunkGenerator for a dimension configured with this provider.
     * Called from the server thread during dimension creation/recreation.
     *
     * The provider MAY rely on {@link CheeseGuardWorldgenApi} helpers
     * (TerraBlender init, surface_rule merge) to avoid duplicating infra code.
     */
    ChunkGenerator createChunkGenerator(MinecraftServer server, DimensionConfig config);
}
