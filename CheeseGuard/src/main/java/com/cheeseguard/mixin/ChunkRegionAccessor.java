package com.cheeseguard.mixin;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.ChunkRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the private `world` field of ChunkRegion so we can
 * retrieve the underlying ServerWorld from a StructureWorldAccess
 * during structure generation (where ChunkRegion is passed, not ServerWorld).
 */
@Mixin(ChunkRegion.class)
public interface ChunkRegionAccessor {
    @Accessor("world")
    ServerWorld cg_getWorld();
}
