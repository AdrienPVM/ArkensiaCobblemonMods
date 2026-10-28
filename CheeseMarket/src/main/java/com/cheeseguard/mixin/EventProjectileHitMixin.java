package com.cheeseguard.mixin;

import com.cheeseguard.data.ServerEvent;
import com.cheeseguard.logic.EventScoreHook;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ProjectileEntity.class)
public class EventProjectileHitMixin {

    @Inject(method = "onEntityHit", at = @At("HEAD"), require = 0)
    private void onProjectileHit(EntityHitResult hitResult, CallbackInfo ci) {
        ProjectileEntity self = (ProjectileEntity)(Object) this;
        if (self.getWorld().isClient()) return;
        Entity owner = self.getOwner();
        if (!(owner instanceof ServerPlayerEntity sp)) return;
        Entity target = hitResult.getEntity();
        String targetId = net.minecraft.registry.Registries.ENTITY_TYPE.getId(target.getType()).toString();
        EventScoreHook.check(sp, ServerEvent.ScoreTrigger.TriggerType.PROJECTILE_HIT, targetId);
    }
}
