package com.cheesepatrol;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.UUID;

public class PatrolWand {

    private static final String WAND_TAG = "cheesepatrol_wand";
    private static final String NPC_UUID_TAG = "cheesepatrol_npc";

    public static ItemStack createWand() {
        ItemStack stack = new ItemStack(Items.BLAZE_ROD);
        NbtCompound nbt = new NbtCompound();
        nbt.putBoolean(WAND_TAG, true);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        stack.set(DataComponentTypes.CUSTOM_NAME,
                Text.literal("Patrol Wand").formatted(Formatting.GOLD, Formatting.BOLD));
        stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    public static boolean isWand(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (data == null) return false;
        NbtCompound nbt = data.copyNbt();
        return nbt.contains(WAND_TAG);
    }

    public static UUID getSelectedNpc(ItemStack stack) {
        if (!isWand(stack)) return null;
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (data == null) return null;
        NbtCompound nbt = data.copyNbt();
        if (!nbt.contains(NPC_UUID_TAG)) return null;
        try {
            return UUID.fromString(nbt.getString(NPC_UUID_TAG));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static void setSelectedNpc(ItemStack stack, UUID npcUuid) {
        if (!isWand(stack)) return;
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        NbtCompound nbt = data != null ? data.copyNbt() : new NbtCompound();
        nbt.putString(NPC_UUID_TAG, npcUuid.toString());
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
    }

    public static void clearSelectedNpc(ItemStack stack) {
        if (!isWand(stack)) return;
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        NbtCompound nbt = data != null ? data.copyNbt() : new NbtCompound();
        nbt.remove(NPC_UUID_TAG);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
    }

    public static void sendWandStatus(ServerPlayerEntity player, PatrolData patrol) {
        int count = patrol.getWaypoints().size();
        String status = patrol.isActive() ? "ACTIVE" : "INACTIVE";
        player.sendMessage(Text.literal("[CheesePatrol] NPC selected: " + patrol.getNpcUuid().toString().substring(0, 8)
                + "... | " + count + " waypoint(s) | " + status)
                .formatted(Formatting.GOLD), true);
    }
}
