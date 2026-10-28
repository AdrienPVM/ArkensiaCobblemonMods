package com.cheeseguard.mixin;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Flag;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public class ItemPickupMixin {
    @Inject(method = "onPlayerCollision", at = @At("HEAD"), cancellable = true)
    private void onPickup(PlayerEntity player, CallbackInfo ci) {
        if (player.getWorld().isClient()) return;
        if (!(player instanceof ServerPlayerEntity sp)) return;
        ItemEntity self = (ItemEntity)(Object) this;
        BlockPos pos = self.getBlockPos();
        String worldId = player.getWorld().getRegistryKey().getValue().toString();
        var rm = CheeseGuardMod.getInstance().getRegionManager();
        if (!rm.isAllowed(sp, worldId, pos.getX(), pos.getY(), pos.getZ(), Flag.ITEM_PICKUP)) {
            ci.cancel();
        }
    }
}
