package com.cheeseguard.logic;

import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-dimension seed override registry.
 *
 * Why: vanilla World.getSeed() returns server.getSaveProperties().getGeneratorOptions().getSeed()
 * — i.e. the overworld seed — for ALL worlds, regardless of the seed passed to the
 * ServerWorld constructor. ServerChunkLoadingManager calls world.getSeed() to build the
 * NoiseConfig, so without an override our custom dimensions always generate terrain
 * with the overworld seed. WorldSeedMixin reads from this registry to return the
 * configured seed for CheeseGuard custom dims.
 */
public final class SeedRegistry {
    private static final Map<RegistryKey<World>, Long> seeds = new ConcurrentHashMap<>();

    private SeedRegistry() {}

    public static void set(RegistryKey<World> key, long seed) {
        if (key == null) return;
        seeds.put(key, seed);
    }

    public static Long get(RegistryKey<World> key) {
        if (key == null) return null;
        return seeds.get(key);
    }

    public static void clear(RegistryKey<World> key) {
        if (key == null) return;
        seeds.remove(key);
    }
}
