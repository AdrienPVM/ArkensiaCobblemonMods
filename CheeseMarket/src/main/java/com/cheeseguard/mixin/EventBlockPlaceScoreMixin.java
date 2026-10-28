package com.cheeseguard.mixin;

import com.cheeseguard.data.ServerEvent;
import com.cheeseguard.logic.EventScoreHook;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BlockItem.class, priority = 1100)
public class EventBlockPlaceScoreMixin {

    @Inject(method = "place", at = @At("RETURN"))
    private void onBlockPlaced(ItemPlacementContext ctx, CallbackInfoReturnable<ActionResult> cir) {
        if (cir.getReturnValue() != ActionResult.SUCCESS && cir.getReturnValue() != ActionResult.CONSUME) return;
        if (!(ctx.getPlayer() instanceof ServerPlayerEntity player)) return;
        Block block = ((BlockItem)(Object) this).getBlock();
        String blockId = Registries.BLOCK.getId(block).toString();
        EventScoreHook.check(player, ServerEvent.ScoreTrigger.TriggerType.BLOCK_PLACE, blockId);
    }
}
