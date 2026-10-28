package com.cheeseguard.mixin;

import com.cheeseguard.data.ServerEvent;
import com.cheeseguard.logic.EventScoreHook;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class EventMobKillMixin {

    @Inject(method = "onDeath", at = @At("HEAD"))
    private void onDeath(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity)(Object) this;
        if (self.getWorld().isClient()) return;
        if (!(source.getAttacker() instanceof ServerPlayerEntity killer)) return;

        // Player killed another player
        if (self instanceof ServerPlayerEntity) {
            EventScoreHook.check(killer, ServerEvent.ScoreTrigger.TriggerType.PLAYER_KILL);
            return;
        }

        // Player killed a mob
        String entityId = Registries.ENTITY_TYPE.getId(self.getType()).toString();
        EventScoreHook.check(killer, ServerEvent.ScoreTrigger.TriggerType.KILL_MOB, entityId);
    }
}
