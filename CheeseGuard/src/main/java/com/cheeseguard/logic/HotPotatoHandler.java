package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Handles the Hot Potato mechanic: right-click on a player within range to pass the bomb.
 *
 * The "bomb" is a TNT item with a special custom data tag "cheeseguard_hot_potato" = true.
 * When active, the bomb carrier gets Glowing + Slowness I.
 * Right-clicking a nearby player transfers the bomb item + effects.
 *
 * Activated/deactivated by event timeline blocks or commands.
 */
public class HotPotatoHandler {

    private static final String POTATO_NBT_KEY = "cheeseguard_hot_potato";
    private static final double DEFAULT_PASS_RANGE = 3.0;

    private static volatile boolean active = false;

    public static void register() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!active || world.isClient()) return ActionResult.PASS;
            if (!(entity instanceof ServerPlayerEntity target)) return ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity source)) return ActionResult.PASS;
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;

            ItemStack held = source.getStackInHand(Hand.MAIN_HAND);
            if (!isHotPotato(held)) return ActionResult.PASS;

            double range = DEFAULT_PASS_RANGE;
            if (source.squaredDistanceTo(target) > range * range) {
                source.sendMessage(Text.translatable("cheeseguard.hotpotato.too_far").formatted(Formatting.RED), true);
                return ActionResult.FAIL;
            }

            // Transfer the bomb
            source.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
            target.getInventory().offerOrDrop(held);

            // Transfer effects: remove from source, apply to target
            source.removeStatusEffect(StatusEffects.GLOWING);
            source.removeStatusEffect(StatusEffects.SLOWNESS);
            target.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, Integer.MAX_VALUE, 0, true, false, true));
            target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, Integer.MAX_VALUE, 0, true, false, true));

            // Sound + visual feedback
            world.playSound(null, target.getX(), target.getY(), target.getZ(),
                SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 1.0f, 0.5f);

            source.sendMessage(Text.translatable("cheeseguard.hotpotato.bomb_passed", target.getName().getString()).formatted(Formatting.GREEN), true);
            target.sendMessage(Text.translatable("cheeseguard.hotpotato.you_have_bomb").formatted(Formatting.RED), true);

            CheeseGuardMod.LOGGER.debug("[HotPotato] {} \u2192 {}", source.getName().getString(), target.getName().getString());
            return ActionResult.SUCCESS;
        });
    }

    /** Create a Hot Potato bomb item (TNT with special custom data + custom name). */
    public static ItemStack createBombItem() {
        ItemStack bomb = new ItemStack(Items.TNT, 1);
        bomb.set(DataComponentTypes.CUSTOM_NAME, Text.translatable("cheeseguard.hotpotato.bomb_name").formatted(Formatting.RED, Formatting.BOLD));
        NbtCompound nbt = new NbtCompound();
        nbt.putBoolean(POTATO_NBT_KEY, true);
        bomb.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        return bomb;
    }

    /** Check if an ItemStack is the Hot Potato bomb. */
    public static boolean isHotPotato(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!stack.isOf(Items.TNT)) return false;
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (comp == null) return false;
        NbtCompound nbt = comp.copyNbt();
        return nbt.getBoolean(POTATO_NBT_KEY);
    }

    public static void setActive(boolean value) {
        active = value;
        CheeseGuardMod.LOGGER.info("[HotPotato] Handler {}", value ? "ACTIVATED" : "DEACTIVATED");
    }

    public static boolean isActive() { return active; }

    /** Remove bomb items and effects from a player. */
    public static void removeBombFromPlayer(ServerPlayerEntity player) {
        for (int i = 0; i < player.getInventory().size(); i++) {
            if (isHotPotato(player.getInventory().getStack(i))) {
                player.getInventory().setStack(i, ItemStack.EMPTY);
            }
        }
        player.removeStatusEffect(StatusEffects.GLOWING);
        player.removeStatusEffect(StatusEffects.SLOWNESS);
    }

    /** Give bomb to a random player, remove from others first. */
    public static ServerPlayerEntity giveBombToRandom(MinecraftServer server) {
        List<ServerPlayerEntity> players = new ArrayList<>(server.getPlayerManager().getPlayerList());
        if (players.isEmpty()) return null;

        // Remove existing bombs first
        for (ServerPlayerEntity p : players) {
            removeBombFromPlayer(p);
        }

        // Give bomb to random player
        ServerPlayerEntity target = players.get(new Random().nextInt(players.size()));
        ItemStack bomb = createBombItem();
        target.getInventory().offerOrDrop(bomb);

        // Apply effects
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, Integer.MAX_VALUE, 0, true, false, true));
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, Integer.MAX_VALUE, 0, true, false, true));
        target.sendMessage(Text.translatable("cheeseguard.hotpotato.you_have_bomb").formatted(Formatting.RED, Formatting.BOLD), true);

        return target;
    }

    /** Find and explode the bomb holder. */
    public static ServerPlayerEntity explodeBombHolder(MinecraftServer server) {
        ServerPlayerEntity bombHolder = null;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            for (int i = 0; i < player.getInventory().size(); i++) {
                if (isHotPotato(player.getInventory().getStack(i))) {
                    bombHolder = player;
                    break;
                }
            }
            if (bombHolder != null) break;
        }

        if (bombHolder == null) return null;

        // Remove bomb and effects
        removeBombFromPlayer(bombHolder);

        // Explosion sound and kill
        bombHolder.getWorld().playSound(null, bombHolder.getX(), bombHolder.getY(), bombHolder.getZ(),
            SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 1.5f, 1.0f);
        bombHolder.damage(bombHolder.getDamageSources().magic(), Float.MAX_VALUE);

        // Broadcast
        String name = bombHolder.getName().getString();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            p.sendMessage(Text.translatable("cheeseguard.hotpotato.exploded", name).formatted(Formatting.RED, Formatting.BOLD), false);
        }

        return bombHolder;
    }
}
