package com.cheeseguard.mixin;
import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Flag;
import net.minecraft.block.*;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(SnowBlock.class)
public class SnowFormMixin {
    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void onSnow(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        String worldId = world.getRegistryKey().getValue().toString();
        if (!CheeseGuardMod.getInstance().getRegionManager().isFlagAllowed(worldId, pos.getX(), pos.getY(), pos.getZ(), Flag.SNOW_FORM)) ci.cancel();
    }
}
