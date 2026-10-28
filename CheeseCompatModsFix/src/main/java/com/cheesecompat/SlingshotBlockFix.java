package com.cheesecompat;

import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.TypedActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Blocks academy:slingshot usage in CobbleSafari dimensions
 * (domedimension and dungeon_underground).
 */
public class SlingshotBlockFix {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseCompatModsFix");

    public static void register() {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient()) return TypedActionResult.pass(ItemStack.EMPTY);

            String dimId = world.getRegistryKey().getValue().toString();
            if (!dimId.startsWith("cobblesafari:")) return TypedActionResult.pass(ItemStack.EMPTY);

            ItemStack stack = player.getStackInHand(hand);
            String itemId = Registries.ITEM.getId(stack.getItem()).toString();
            if (!itemId.equals("academy:slingshot")) return TypedActionResult.pass(ItemStack.EMPTY);

            // Block slingshot in CobbleSafari dimensions
            if (player instanceof ServerPlayerEntity sp) {
                sp.sendMessage(
                    Text.translatable("cheesecompat.slingshot.blocked").formatted(Formatting.RED),
                    true);
            }
            return TypedActionResult.fail(stack);
        });
        LOGGER.info("[CheeseCompat] Slingshot block registered for CobbleSafari dimensions");
    }
}
