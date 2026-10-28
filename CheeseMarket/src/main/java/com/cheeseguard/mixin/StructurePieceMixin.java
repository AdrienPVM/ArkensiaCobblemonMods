package com.cheeseguard.mixin;

import com.cheeseguard.CheeseGuardMod;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePiece;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Layer 2 structure block: cancels each individual structure piece before
 * it writes any blocks into the world.
 *
 * Being abstract, this injection is applied to ALL concrete subclasses of
 * StructurePiece — covering vanilla, Fabric mods, and datapack structures alike.
 *
 * This is a belt-and-suspenders fallback: if a mod bypasses StructureStart.place()
 * and calls individual piece generation directly, this catches it.
 */
@Mixin(StructurePiece.class)
public abstract class StructurePieceMixin {

    @Inject(
        method = "generate(Lnet/minecraft/world/StructureWorldAccess;Lnet/minecraft/world/gen/StructureAccessor;Lnet/minecraft/world/gen/chunk/ChunkGenerator;Lnet/minecraft/util/math/random/Random;Lnet/minecraft/util/math/BlockBox;Lnet/minecraft/util/math/ChunkPos;Lnet/minecraft/util/math/BlockPos;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void cg_blockStructurePiece(
            StructureWorldAccess world, StructureAccessor sa,
            ChunkGenerator cg, Random rng,
            BlockBox box, ChunkPos chunkPos, BlockPos pivot,
            CallbackInfo ci) {

        if (!(world instanceof ServerWorld sw)) return;
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
