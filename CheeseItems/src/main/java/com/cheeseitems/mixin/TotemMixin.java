package com.cheeseitems.mixin;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents CheeseItems custom items based on totem_of_undying from triggering
 * the native totem death-prevention effect.
 */
@Mixin(LivingEntity.class)
public class TotemMixin {

    @Inject(method = "tryUseTotem", at = @At("HEAD"), cancellable = true)
    private void cheeseitems$blockCustomTotem(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        boolean hasRegularTotem = false;
        boolean hasCustomTotem = false;

        for (Hand hand : Hand.values()) {
            ItemStack stack = self.getStackInHand(hand);
            if (stack.isOf(Items.TOTEM_OF_UNDYING)) {
                NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
                if (comp != null && comp.copyNbt().contains("cheeseitems")) {
                    hasCustomTotem = true;
                } else {
                    hasRegularTotem = true;
                }
            }
        }

        // Block totem effect only if the player has no regular totem
        if (hasCustomTotem && !hasRegularTotem) {
            cir.setReturnValue(false);
        }
    }
}
