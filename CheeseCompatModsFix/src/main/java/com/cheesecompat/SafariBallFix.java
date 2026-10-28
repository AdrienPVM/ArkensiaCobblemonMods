package com.cheesecompat;

import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Fixes safari balls being blocked in CobbleSafari dimensions.
 *
 * Academy mod restricts safari balls to only work in academy:safari.
 * This UseItemCallback fires BEFORE Item.use(), bypassing Academy's Mixin entirely.
 * It manually triggers the PokeBall throw via reflection when a safari ball is used
 * in a cobblesafari: dimension.
 */
public class SafariBallFix {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseCompatModsFix");
    private static Method throwPokeBallMethod;

    public static void register() {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient()) return TypedActionResult.pass(ItemStack.EMPTY);

            String dimId = world.getRegistryKey().getValue().toString();
            if (!dimId.startsWith("cobblesafari:")) return TypedActionResult.pass(ItemStack.EMPTY);

            ItemStack stack = player.getStackInHand(hand);
            String itemId = Registries.ITEM.getId(stack.getItem()).toString();
            if (!itemId.contains("safari_ball")) return TypedActionResult.pass(ItemStack.EMPTY);

            // Safari ball in CobbleSafari dimension — manually throw it
            if (player instanceof ServerPlayerEntity sp) {
                try {
                    if (throwPokeBallMethod == null) {
                        // PokeBallItem.throwPokeBall(World, ServerPlayerEntity) — Cobblemon method, stable name
                        for (Method m : stack.getItem().getClass().getDeclaredMethods()) {
                            if (m.getName().equals("throwPokeBall") && m.getParameterCount() == 2) {
                                throwPokeBallMethod = m;
                                throwPokeBallMethod.setAccessible(true);
                                break;
                            }
                        }
                    }
                    if (throwPokeBallMethod != null) {
                        throwPokeBallMethod.invoke(stack.getItem(), world, sp);
                        if (!sp.getAbilities().creativeMode) {
                            stack.decrement(1);
                        }
                        return TypedActionResult.success(stack, false);
                    }
                } catch (Exception e) {
                    LOGGER.warn("[CheeseCompat] Failed to throw safari ball: {}", e.getMessage());
                }
            }
            return TypedActionResult.pass(ItemStack.EMPTY);
        });
        LOGGER.info("[CheeseCompat] Safari ball fix registered for CobbleSafari dimensions");
    }
}
