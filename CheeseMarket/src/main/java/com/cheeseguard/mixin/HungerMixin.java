package com.cheeseguard.mixin;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Flag;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents hunger from decreasing in regions where HUNGER flag is denied.
 * When denied = hunger doesn't decrease (protection).
 * Saves and restores food level + saturation before update.
 */
@Mixin(HungerManager.class)
public class HungerMixin {

    @Shadow private int foodLevel;
    @Shadow private float saturationLevel;
    @Shadow private float exhaustion;

    @Inject(method = "update", at = @At("HEAD"))
    private void onUpdateHead(PlayerEntity player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayerEntity sp)) return;
        BlockPos pos = sp.getBlockPos();
        String worldId = sp.getServerWorld().getRegistryKey().getValue().toString();
        if (!CheeseGuardMod.getInstance().getRegionManager()
                .isAllowed(sp, worldId, pos.getX(), pos.getY(), pos.getZ(), Flag.HUNGER)) {
            // Reset exhaustion to prevent hunger drain
            this.exhaustion = 0.0f;
            // Ensure food stays at max if it's below
            if (this.foodLevel < 20) this.foodLevel = 20;
            if (this.saturationLevel < 5.0f) this.saturationLevel = 5.0f;
        }
    }
}
