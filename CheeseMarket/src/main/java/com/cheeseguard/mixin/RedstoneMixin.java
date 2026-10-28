package com.cheeseguard.mixin;
import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Flag;
import net.minecraft.block.*;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(AbstractRedstoneGateBlock.class)
public class RedstoneMixin {
    @Inject(method = "canPlaceAt", at = @At("HEAD"), cancellable = true)
    private void onRedstone(BlockState state, WorldView world, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!(world instanceof ServerWorld sw)) return;
        String worldId = sw.getRegistryKey().getValue().toString();
        if (!CheeseGuardMod.getInstance().getRegionManager().isFlagAllowed(worldId, pos.getX(), pos.getY(), pos.getZ(), Flag.REDSTONE)) cir.setReturnValue(false);
    }
}
