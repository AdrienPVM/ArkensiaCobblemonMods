package com.cheeseguard.mixin;

import com.cheeseguard.data.ServerEvent;
import com.cheeseguard.logic.EventScoreHook;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.CraftingResultSlot;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CraftingResultSlot.class)
public class EventCraftItemMixin {

    @Shadow @Final private PlayerEntity player;

    @Inject(method = "onCrafted(Lnet/minecraft/item/ItemStack;)V", at = @At("HEAD"), require = 0)
    private void onItemCrafted(ItemStack stack, CallbackInfo ci) {
        if (stack.isEmpty()) return;
        if (!(player instanceof ServerPlayerEntity sp)) return;
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        EventScoreHook.check(sp, ServerEvent.ScoreTrigger.TriggerType.CRAFT_ITEM, itemId);
    }
}
