package com.cheeseguard.mixin;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Flag;
import net.minecraft.entity.ai.goal.EatGrassGoal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents mobs (sheep, cobblemon wooloo, etc.) from eating grass
 * in protected regions when MOB_GRIEF flag is denied.
 */
@Mixin(EatGrassGoal.class)
public class EatGrassGoalMixin {

    @Shadow @Final private MobEntity mob;

    @Inject(method = "canStart", at = @At("HEAD"), cancellable = true)
    private void onCanStart(CallbackInfoReturnable<Boolean> cir) {
        World world = mob.getWorld();
        if (world.isClient()) return;
        BlockPos pos = mob.getBlockPos();
        String worldId = world.getRegistryKey().getValue().toString();
        if (!CheeseGuardMod.getInstance().getRegionManager()
                .isFlagAllowed(worldId, pos.getX(), pos.getY(), pos.getZ(), Flag.MOB_GRIEF)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void onTick(CallbackInfo ci) {
        World world = mob.getWorld();
        if (world.isClient()) return;
        BlockPos pos = mob.getBlockPos();
        String worldId = world.getRegistryKey().getValue().toString();
        if (!CheeseGuardMod.getInstance().getRegionManager()
                .isFlagAllowed(worldId, pos.getX(), pos.getY(), pos.getZ(), Flag.MOB_GRIEF)) {
            ci.cancel();
        }
    }
}
