package com.cheeseguard.mixin;

import com.cheeseguard.data.ServerEvent;
import com.cheeseguard.logic.EventScoreHook;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ItemEntity.class, priority = 1100)
public class EventItemPickupScoreMixin {

    @Inject(method = "onPlayerCollision", at = @At("RETURN"))
    private void afterPickup(PlayerEntity player, CallbackInfo ci) {
        if (player.getWorld().isClient()) return;
        if (!(player instanceof ServerPlayerEntity sp)) return;
        ItemEntity self = (ItemEntity)(Object) this;
        // If item was removed (picked up successfully) or stack is empty
        if (!self.isRemoved()) return;
        String itemId = Registries.ITEM.getId(self.getStack().getItem()).toString();
        EventScoreHook.check(sp, ServerEvent.ScoreTrigger.TriggerType.ITEM_PICKUP, itemId);
    }
}
