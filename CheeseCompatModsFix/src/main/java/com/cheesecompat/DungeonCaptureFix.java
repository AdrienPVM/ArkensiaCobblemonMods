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
 * Blocks all PokeBall throws in cobblesafari:dungeon_underground.
 * Players must battle the spawner Pokemon, not capture them.
 */
public class DungeonCaptureFix {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseCompatModsFix");

    public static void register() {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient()) return TypedActionResult.pass(ItemStack.EMPTY);

            String dimId = world.getRegistryKey().getValue().toString();
            if (!dimId.equals("cobblesafari:dungeon_underground")) return TypedActionResult.pass(ItemStack.EMPTY);

            ItemStack stack = player.getStackInHand(hand);
            String itemId = Registries.ITEM.getId(stack.getItem()).toString();

            // Block any PokeBall item (cobblemon:*_ball or cobblemon:poke_ball, etc.)
            if (itemId.startsWith("cobblemon:") && itemId.contains("ball")) {
                if (player instanceof ServerPlayerEntity sp) {
                    sp.sendMessage(
                        Text.translatable("cheesecompat.dungeon.capture_blocked").formatted(Formatting.RED),
                        true);
                }
                return TypedActionResult.fail(stack);
            }

            return TypedActionResult.pass(ItemStack.EMPTY);
        });
        LOGGER.info("[CheeseCompat] Dungeon capture block registered for dungeon_underground");
    }
}
