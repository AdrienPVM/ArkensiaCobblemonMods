package com.cheeseguard.mixin;

import com.cheeseguard.CheeseGuardMod;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blocks structure generation in dimensions where generateStructures = false.
 *
 * During worldgen, Minecraft passes a ChunkRegion (not a ServerWorld) as the
 * StructureWorldAccess. ChunkRegion exposes its underlying ServerWorld via the
 * ChunkRegionAccessor mixin. We handle both cases explicitly.
 *
 * Covers vanilla structures, datapack structures, and all mod-added structures
 * because StructureStart.place() is the universal entry point.
 */
@Mixin(StructureStart.class)
public class StructureStartMixin {

    @Inject(
        method = "place(Lnet/minecraft/world/StructureWorldAccess;Lnet/minecraft/world/gen/StructureAccessor;Lnet/minecraft/world/gen/chunk/ChunkGenerator;Lnet/minecraft/util/math/random/Random;Lnet/minecraft/util/math/BlockBox;Lnet/minecraft/util/math/ChunkPos;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void cg_blockStructureStart(
            StructureWorldAccess world, StructureAccessor sa,
            ChunkGenerator cg, Random rng,
            BlockBox box, ChunkPos chunkPos,
            CallbackInfo ci) {

        // Resolve the underlying ServerWorld — handles both direct ServerWorld
        // calls and ChunkRegion (used during worldgen on chunk threads).
        ServerWorld sw;
        if (world instanceof ServerWorld direct) {
            sw = direct;
        } else if (world instanceof ChunkRegionAccessor cr) {
            sw = cr.cg_getWorld();
        } else {
            return; // Unknown context — skip safely
        }

        var inst = CheeseGuardMod.getInstance();
        if (inst == null) return;
        var dimMgr = inst.getDimensionManager();
        if (dimMgr == null) return;
        String worldId = sw.getRegistryKey().getValue().toString();
        var cfg = dimMgr.getConfigByWorldId(worldId);
        if (cfg != null && !cfg.isGenerateStructures()) {
            ci.cancel();
        }
    }
}
