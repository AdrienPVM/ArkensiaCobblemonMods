package com.cheeseguard.mixin;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Flag;
import net.minecraft.world.explosion.Explosion;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Explosion.class)
public class ExplosionMixin {
    @Shadow private World world;

    @Inject(method = "affectWorld", at = @At("HEAD"), cancellable = true)
    private void onExplode(boolean particles, CallbackInfo ci) {
        if (world.isClient()) return;
        String worldId = world.getRegistryKey().getValue().toString();
        var rm = CheeseGuardMod.getInstance().getRegionManager();
        Explosion ex = (Explosion)(Object)this;
        BlockPos center = BlockPos.ofFloored(ex.getPosition());
        if (!rm.isFlagAllowed(worldId, center.getX(), center.getY(), center.getZ(), Flag.EXPLOSIONS)) {
            ci.cancel();
        }
    }
}
