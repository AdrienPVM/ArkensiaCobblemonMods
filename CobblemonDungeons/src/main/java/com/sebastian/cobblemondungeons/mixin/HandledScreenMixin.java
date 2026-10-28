package com.sebastian.cobblemondungeons.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sebastian.cobblemondungeons.gui.DungeonScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin {

    @WrapOperation(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;drawSlotHighlight(Lnet/minecraft/client/gui/DrawContext;III)V"
        )
    )
    private void cobblemonDungeons$wrapDrawSlotHighlight(DrawContext context, int x, int y, int z, Operation<Void> original) {
        // If the current screen is a DungeonScreen, skip the slot highlight
        if (!((Object)this instanceof DungeonScreen)) {
            original.call(context, x, y, z);
        }
    }
}
