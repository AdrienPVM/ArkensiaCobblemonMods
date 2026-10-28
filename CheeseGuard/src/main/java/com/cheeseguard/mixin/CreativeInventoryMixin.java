package com.cheeseguard.mixin;

import com.cheeseguard.CheeseGuardMod;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blocks forbidden items from entering a builder's inventory via the creative menu
 * when they are in a construction-mode dimension.
 * This intercepts the packet BEFORE the item touches the inventory, making it
 * truly impossible to obtain forbidden items (coins, ender chests, command blocks, etc.)
 * regardless of how fast the player acts.
 */
@Mixin(ServerPlayNetworkHandler.class)
public class CreativeInventoryMixin {

    @Shadow
    public ServerPlayerEntity player;

    @Inject(method = "onCreativeInventoryAction", at = @At("HEAD"), cancellable = true)
    private void onCreativeGive(CreativeInventoryActionC2SPacket packet, CallbackInfo ci) {
        var stack = packet.stack();
        if (stack == null || stack.isEmpty()) return;

        // Only relevant for builder players in construction dimensions
        var dimMgr = CheeseGuardMod.getInstance().getDimensionManager();
        if (dimMgr == null) return;

        String worldId = player.getServerWorld().getRegistryKey().getValue().toString();
        var cfg = dimMgr.getConfigByWorldId(worldId);
        if (cfg == null || !cfg.isConstructionMode()) return;
        if (!cfg.isConstructionBuilder(player.getUuid().toString())) return;

        // Check if the item being given is forbidden
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        if (dimMgr.isConstructionForbiddenItem(itemId)) {
            ci.cancel(); // Drop the packet — item never enters inventory
            player.sendMessage(
                Text.translatable("cheeseguard.protection.construction_forbidden_item", itemId)
                    .formatted(Formatting.RED),
                true
            );
        }
    }
}
