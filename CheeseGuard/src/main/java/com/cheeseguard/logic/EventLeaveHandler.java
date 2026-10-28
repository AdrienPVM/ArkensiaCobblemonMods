package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.EventParticipant;
import com.cheeseguard.data.ServerEvent;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.fabricmc.fabric.api.event.player.UseItemCallback;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the "Leave Event" item: a barrier block in hotbar slot 8 that lets
 * participants/spectators/eliminated players leave the current event via right-click.
 *
 * Double-click confirmation: first click shows a warning, second click within 5s
 * actually performs the leave.
 */
public class EventLeaveHandler {

    private static final String LEAVE_NBT_KEY = "cheeseguard_event_leave";
    private static final long CONFIRM_TIMEOUT_MS = 5000;

    private static final Map<UUID, Long> pendingConfirm = new ConcurrentHashMap<>();

    public static void register() {
        // Right-click on block
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient()) return ActionResult.PASS;
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            ItemStack held = sp.getStackInHand(Hand.MAIN_HAND);
            if (!isLeaveItem(held)) return ActionResult.PASS;
            handleLeaveAttempt(sp);
            return ActionResult.SUCCESS;
        });

        // Right-click in air
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient()) return TypedActionResult.pass(player.getStackInHand(hand));
            if (hand != Hand.MAIN_HAND) return TypedActionResult.pass(player.getStackInHand(hand));
            if (!(player instanceof ServerPlayerEntity sp)) return TypedActionResult.pass(player.getStackInHand(hand));
            ItemStack held = sp.getStackInHand(Hand.MAIN_HAND);
            if (!isLeaveItem(held)) return TypedActionResult.pass(held);
            handleLeaveAttempt(sp);
            return TypedActionResult.pass(held);
        });

        // Right-click on entity
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient()) return ActionResult.PASS;
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            ItemStack held = sp.getStackInHand(Hand.MAIN_HAND);
            if (!isLeaveItem(held)) return ActionResult.PASS;
            handleLeaveAttempt(sp);
            return ActionResult.SUCCESS;
        });
    }

    /** Create the leave item (barrier with custom name + lore + NBT tag). */
    public static ItemStack createLeaveItem() {
        ItemStack item = new ItemStack(Items.BARRIER, 1);
        item.set(DataComponentTypes.CUSTOM_NAME,
            Text.translatable("cheeseguard.leave_item.name").formatted(Formatting.RED));
        item.set(DataComponentTypes.LORE, new LoreComponent(List.of(
            Text.translatable("cheeseguard.leave_item.lore1").formatted(Formatting.GRAY),
            Text.translatable("cheeseguard.leave_item.lore2").formatted(Formatting.DARK_GRAY)
        )));
        NbtCompound nbt = new NbtCompound();
        nbt.putBoolean(LEAVE_NBT_KEY, true);
        item.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        return item;
    }

    /** Check if an ItemStack is the event leave item. */
    public static boolean isLeaveItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!stack.isOf(Items.BARRIER)) return false;
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (comp == null) return false;
        NbtCompound nbt = comp.copyNbt();
        return nbt.getBoolean(LEAVE_NBT_KEY);
    }

    /** Remove all leave items from a player's inventory. */
    public static void removeLeaveItem(ServerPlayerEntity player) {
        for (int i = 0; i < player.getInventory().size(); i++) {
            if (isLeaveItem(player.getInventory().getStack(i))) {
                player.getInventory().setStack(i, ItemStack.EMPTY);
            }
        }
    }

    private static void handleLeaveAttempt(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        long now = System.currentTimeMillis();

        Long prev = pendingConfirm.get(uuid);
        if (prev != null && (now - prev) < CONFIRM_TIMEOUT_MS) {
            // Second click within timeout — perform leave
            pendingConfirm.remove(uuid);
            performLeave(player);
        } else {
            // First click — ask for confirmation
            pendingConfirm.put(uuid, now);
            player.sendMessage(Text.translatable("cheeseguard.leave_item.confirm")
                .formatted(Formatting.YELLOW), true);
            player.getWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.PLAYERS, 0.5f, 1.0f);
        }
    }

    private static void performLeave(ServerPlayerEntity player) {
        EventManager em = CheeseGuardMod.getInstance().getEventManager();
        if (em == null) return;

        // Find any event the player is in (any state, any role)
        ServerEvent event = null;
        for (ServerEvent ev : em.getEvents()) {
            if (ev.getParticipant(player.getUuid()) != null) {
                event = ev;
                break;
            }
        }

        if (event == null) {
            // Fallback: check pending restores (crash recovery)
            if (em.hasPendingRestore(player.getUuid())) {
                em.applyPendingRestoreNow(player);
                removeLeaveItem(player);
                player.sendMessage(Text.translatable("cheeseguard.cmd.event_restored")
                    .formatted(Formatting.GREEN), false);
                return;
            }

            player.sendMessage(Text.translatable("cheeseguard.leave_item.not_in_event")
                .formatted(Formatting.RED), false);
            removeLeaveItem(player);
            return;
        }

        em.leaveEvent(event, player);
    }
}
