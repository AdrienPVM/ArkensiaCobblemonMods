package com.cheeseguard.mixin;
import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Flag;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(PlayerEntity.class)
public class ItemDropMixin {
    @Inject(method = "dropItem(Lnet/minecraft/item/ItemStack;ZZ)Lnet/minecraft/entity/ItemEntity;", at = @At("HEAD"), cancellable = true)
    private void onDrop(ItemStack stack, boolean throwRandomly, boolean retainOwnership, CallbackInfoReturnable<ItemEntity> cir) {
        PlayerEntity self = (PlayerEntity)(Object)this;
        if (self.getWorld().isClient()) return;
        if (stack == null || stack.isEmpty()) return;
        BlockPos pos = self.getBlockPos();
        String worldId = self.getWorld().getRegistryKey().getValue().toString();

        // Prison: prisoners cannot drop items at all.
        if (self instanceof ServerPlayerEntity sp) {
            var pm = CheeseGuardMod.getInstance().getPrisonManager();
            if (pm != null && pm.isImprisoned(sp.getUuid().toString())) {
                self.getInventory().insertStack(stack.copy());
                sp.sendMessage(Text.translatable("cheeseguard.prison.action_denied"), true);
                cir.setReturnValue(null);
                return;
            }
        }

        if (!CheeseGuardMod.getInstance().getRegionManager().isFlagAllowed(worldId, pos.getX(), pos.getY(), pos.getZ(), Flag.ITEM_DROP)) {
            // Return the item to the player's inventory
            self.getInventory().insertStack(stack.copy());
            if (self instanceof ServerPlayerEntity sp) {
                sp.sendMessage(Text.translatable("cheeseguard.protection.item_drop_denied"), true);
            }
            cir.setReturnValue(null);
        }
    }
}
