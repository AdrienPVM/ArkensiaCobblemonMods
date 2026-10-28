package com.cheeseguard.mixin;
import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Flag;
import net.minecraft.block.*;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(FarmlandBlock.class)
public class FarmlandTrampleMixin {
    @Inject(method = "onLandedUpon", at = @At("HEAD"), cancellable = true)
    private void onTrample(World world, BlockState state, BlockPos pos, Entity entity, float fallDistance, CallbackInfo ci) {
        if (world.isClient()) return;
        String worldId = world.getRegistryKey().getValue().toString();
        if (!CheeseGuardMod.getInstance().getRegionManager().isFlagAllowed(worldId, pos.getX(), pos.getY(), pos.getZ(), Flag.TRAMPLE)) ci.cancel();
    }
}
