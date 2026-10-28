// ═══════════════════════════════════════════════════════
// BlockPlaceMixin.java
// ═══════════════════════════════════════════════════════
package com.cheeseguard.mixin;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Flag;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public class BlockPlaceMixin {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void onPlace(ItemPlacementContext ctx, CallbackInfoReturnable<ActionResult> cir) {
        if (!(ctx.getPlayer() instanceof ServerPlayerEntity player)) return;
        var pos = ctx.getBlockPos();
        var world = ctx.getWorld();
        var worldId = world.getRegistryKey().getValue().toString();
        var rm = CheeseGuardMod.getInstance().getRegionManager();
        if (!rm.isAllowed(player, worldId, pos.getX(), pos.getY(), pos.getZ(), Flag.BLOCK_PLACE)) {
            player.sendMessage(Text.literal(rm.getProtectionMessage(worldId, pos.getX(), pos.getY(), pos.getZ())), true);
            cir.setReturnValue(ActionResult.FAIL);
        }
    }
}
