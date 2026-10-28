package com.cheeseguard.mixin;

import com.cheeseguard.CheeseGuardMod;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Blocks player interaction with Cobblemon blocks (legendary monuments/altars,
 * Pokémon PCs, apricorn trees, etc.) when the dimension has cobblemon disabled.
 *
 * This complements EntitySpawnMixin:
 *   - EntitySpawnMixin blocks the entity from actually spawning.
 *   - This mixin blocks the block interaction entirely so items are not consumed.
 */
@Mixin(ServerPlayerInteractionManager.class)
public class CobblemonBlockInteractMixin {

    @Inject(
        method = "interactBlock",
        at = @At("HEAD"),
        cancellable = true
    )
    private void cg_blockCobblemonBlockUse(
            ServerPlayerEntity player,
            World world,
            ItemStack stack,
            Hand hand,
            BlockHitResult blockHit,
            CallbackInfoReturnable<ActionResult> cir) {

        if (!(world instanceof ServerWorld sw)) return;
        var inst = CheeseGuardMod.getInstance();
        if (inst == null) return;
        var dimMgr = inst.getDimensionManager();
        if (dimMgr == null) return;

        String worldId = sw.getRegistryKey().getValue().toString();
        if (!dimMgr.isCobblemonBlocked(worldId)) return;

        // Check if the targeted block is a Cobblemon block
        BlockPos pos = blockHit.getBlockPos();
        BlockState state = world.getBlockState(pos);
        String blockNs = net.minecraft.registry.Registries.BLOCK
            .getId(state.getBlock()).getNamespace();

        if ("cobblemon".equals(blockNs)) {
            player.sendMessage(
                net.minecraft.text.Text.literal(
                    "\u26d4 Les Pok\u00e9mon sont d\u00e9sactiv\u00e9s dans cette dimension.")
                    .formatted(Formatting.RED),
                true
            );
            cir.setReturnValue(ActionResult.FAIL);
        }

        // Also block if the item in hand is a Cobblemon item (e.g. rusted key, orb)
        if (!stack.isEmpty()) {
            String itemNs = net.minecraft.registry.Registries.ITEM
                .getId(stack.getItem()).getNamespace();
            if ("cobblemon".equals(itemNs)) {
                player.sendMessage(
                    net.minecraft.text.Text.literal(
                        "\u26d4 Les Pok\u00e9mon sont d\u00e9sactiv\u00e9s dans cette dimension.")
                        .formatted(Formatting.RED),
                    true
                );
                cir.setReturnValue(ActionResult.FAIL);
            }
        }
    }

    /**
     * Also intercept interactItem (right-click in air / with item) to block
     * Pokéball throws and other item-based Cobblemon actions.
     */
    @Inject(
        method = "interactItem",
        at = @At("HEAD"),
        cancellable = true
    )
    private void cg_blockCobblemonItemUse(
            ServerPlayerEntity player,
            World world,
            ItemStack stack,
            Hand hand,
            CallbackInfoReturnable<ActionResult> cir) {

        if (!(world instanceof ServerWorld sw)) return;
        var inst = CheeseGuardMod.getInstance();
        if (inst == null) return;
        var dimMgr = inst.getDimensionManager();
        if (dimMgr == null) return;

        String worldId = sw.getRegistryKey().getValue().toString();
        if (!dimMgr.isCobblemonBlocked(worldId)) return;

        if (stack.isEmpty()) return;
        String itemNs = net.minecraft.registry.Registries.ITEM
            .getId(stack.getItem()).getNamespace();

        if ("cobblemon".equals(itemNs)) {
            player.sendMessage(
                net.minecraft.text.Text.literal(
                    "\u26d4 Les Pok\u00e9mon sont d\u00e9sactiv\u00e9s dans cette dimension.")
                    .formatted(Formatting.RED),
                true
            );
            cir.setReturnValue(ActionResult.FAIL);
        }
    }

}
