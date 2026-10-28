package com.cheesevanity.mixin.client;

import com.cheesevanity.CheeseVanityMod;
import com.cheesevanity.VanityClientState;
import com.cheesevanity.network.ToggleSlotC2SPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.slot.Slot;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin {

    @Shadow
    protected Slot focusedSlot;

    /**
     * Intercept '=' key press when hovering over a slot.
     * Detects trinket slots (via reflection) and vanilla armor slots.
     */
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (keyCode != GLFW.GLFW_KEY_EQUAL) return;
        if (focusedSlot == null) return;

        // Debug: log slot info (INFO level for troubleshooting)
        CheeseVanityMod.LOGGER.info("[CheeseVanity] Key '=' on slot: class={}, index={}, id={}, inv={}, hasStack={}",
                focusedSlot.getClass().getSimpleName(),
                focusedSlot.getIndex(),
                focusedSlot.id,
                focusedSlot.inventory.getClass().getSimpleName(),
                focusedSlot.hasStack());

        String slotKey = resolveSlotKey(focusedSlot);
        CheeseVanityMod.LOGGER.info("[CheeseVanity] Resolved slotKey: {}", slotKey);
        if (slotKey == null) return;

        // Send toggle request to server
        ClientPlayNetworking.send(new ToggleSlotC2SPayload(slotKey));
        cir.setReturnValue(true);
    }

    /**
     * Draw red overlay on hidden trinket/armor slots.
     */
    @Inject(method = "drawSlot", at = @At("TAIL"))
    private void afterDrawSlot(DrawContext context, Slot slot, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        String slotKey = resolveSlotKey(slot);
        if (slotKey == null) return;

        UUID playerUuid = client.player.getUuid();
        if (!VanityClientState.isHidden(playerUuid, slotKey)) return;

        // Draw red overlay on top of the item (push Z to be above the item icon at z~200)
        context.getMatrices().push();
        context.getMatrices().translate(0, 0, 300);

        int sx = slot.x;
        int sy = slot.y;

        // Semi-transparent red fill over the entire slot
        context.fill(sx, sy, sx + 16, sy + 16, 0x60FF0000);

        // Draw X with thicker lines (2px wide)
        for (int i = 0; i <= 12; i++) {
            int px = sx + 2 + i;
            int py = sy + 2 + i;
            int py2 = sy + 14 - i;
            // Diagonal top-left to bottom-right
            context.fill(px, py, px + 2, py + 2, 0xFFFF3333);
            // Diagonal top-right to bottom-left
            context.fill(px, py2 - 1, px + 2, py2 + 1, 0xFFFF3333);
        }

        context.getMatrices().pop();
    }

    /**
     * Resolve a slot to its vanity slot key.
     * Returns null if this slot is not handled by our mod.
     */
    private static String resolveSlotKey(Slot slot) {
        // Try trinket slot via reflection (to avoid hard compile-time dependency)
        try {
            Class<?> trinketSlotClass = Class.forName("dev.emi.trinkets.TrinketSlot");
            if (trinketSlotClass.isInstance(slot)) {
                Object slotType = trinketSlotClass.getMethod("getType").invoke(slot);
                String group = (String) slotType.getClass().getMethod("getGroup").invoke(slotType);
                String name = (String) slotType.getClass().getMethod("getName").invoke(slotType);
                return group + "/" + name;
            }
        } catch (ClassNotFoundException ignored) {
            // Trinkets not installed
        } catch (Exception e) {
            CheeseVanityMod.LOGGER.warn("[CheeseVanity] Trinket slot reflection error: {}", e.getMessage());
        }

        // Check vanilla armor slots
        // In PlayerInventory: feet=36, legs=37, chest=38, head=39
        if (slot.inventory instanceof PlayerInventory) {
            int idx = slot.getIndex();
            switch (idx) {
                case 39: return "helmet";
                case 38: return "chestplate";
                case 37: return "leggings";
                case 36: return "boots";
            }
        }

        return null;
    }
}
