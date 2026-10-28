package com.cheeseguard.mixin;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Flag;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.ServerWorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MobEntity.class)
public class MobSpawnMixin {
    @Inject(method = "canSpawn(Lnet/minecraft/world/WorldAccess;Lnet/minecraft/entity/SpawnReason;)Z",
            at = @At("HEAD"), cancellable = true)
    private void onCanSpawn(WorldAccess world, SpawnReason reason, CallbackInfoReturnable<Boolean> cir) {
        // Only block natural/chunk-gen spawns, not spawn eggs or commands
        if (reason != SpawnReason.NATURAL && reason != SpawnReason.CHUNK_GENERATION) return;
        if (!(world instanceof ServerWorldAccess swa)) return;

        MobEntity self = (MobEntity)(Object)this;

        // Skip cobblemon entities — EntitySpawnMixin handles those separately
        // with proper owned/wild distinction
        String namespace = EntityType.getId(self.getType()).getNamespace();
        if ("cobblemon".equals(namespace)) return;

        BlockPos pos = self.getBlockPos();
        String worldId = swa.toServerWorld().getRegistryKey().getValue().toString();

        // Dimension rule: block mob spawning if disabled for this dimension
        var dimMgr = CheeseGuardMod.getInstance().getDimensionManager();
        if (dimMgr != null && !dimMgr.isMobSpawningAllowed(worldId)) {
            cir.setReturnValue(false);
            return;
        }

        // Block if mob-spawning flag is denied
        if (!CheeseGuardMod.getInstance().getRegionManager()
                .isFlagAllowed(worldId, pos.getX(), pos.getY(), pos.getZ(), Flag.MOB_SPAWNING)) {
            cir.setReturnValue(false);
        }
    }
}
