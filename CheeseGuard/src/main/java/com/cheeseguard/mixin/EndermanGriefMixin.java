package com.cheeseguard.mixin;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Flag;
import net.minecraft.block.BlockState;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents enderman from picking up and placing blocks in protected regions.
 */
@Mixin(EndermanEntity.class)
public abstract class EndermanGriefMixin {

    @Shadow public abstract BlockState getCarriedBlock();

    /**
     * Intercept when enderman tries to set its carried block.
     * If in a mob-grief denied region, cancel.
     */
    @Inject(method = "setCarriedBlock", at = @At("HEAD"), cancellable = true)
    private void onSetCarriedBlock(BlockState state, CallbackInfo ci) {
        if (state == null) return; // allow clearing
        EndermanEntity self = (EndermanEntity) (Object) this;
        World world = self.getWorld();
        if (world.isClient()) return;
        BlockPos pos = self.getBlockPos();
        String worldId = world.getRegistryKey().getValue().toString();
        if (!CheeseGuardMod.getInstance().getRegionManager()
                .isFlagAllowed(worldId, pos.getX(), pos.getY(), pos.getZ(), Flag.MOB_GRIEF)) {
            ci.cancel();
        }
    }
}
