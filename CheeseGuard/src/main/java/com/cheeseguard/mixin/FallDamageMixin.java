package com.cheeseguard.mixin;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Flag;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cancels fall damage for players in regions where FALL_DAMAGE flag is denied.
 * When denied = no fall damage (protection).
 */
@Mixin(LivingEntity.class)
public class FallDamageMixin {

    @Inject(method = "handleFallDamage", at = @At("HEAD"), cancellable = true)
    private void onFallDamage(float fallDistance, float damageMultiplier, DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof ServerPlayerEntity player)) return;
        World world = player.getWorld();
        if (world.isClient()) return;
        BlockPos pos = player.getBlockPos();
        String worldId = world.getRegistryKey().getValue().toString();
        if (!CheeseGuardMod.getInstance().getRegionManager()
                .isAllowed(player, worldId, pos.getX(), pos.getY(), pos.getZ(), Flag.FALL_DAMAGE)) {
            cir.setReturnValue(false); // No fall damage
        }
    }
}
