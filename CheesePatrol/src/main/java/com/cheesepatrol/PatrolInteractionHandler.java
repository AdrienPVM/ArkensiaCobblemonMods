package com.cheesepatrol;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

public class PatrolInteractionHandler {

    public static void register() {
        // Right-click entity with wand → select NPC
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient()) return ActionResult.PASS;
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity serverPlayer)) return ActionResult.PASS;
            if (!serverPlayer.hasPermissionLevel(2)) return ActionResult.PASS;

            ItemStack stack = serverPlayer.getMainHandStack();
            if (!PatrolWand.isWand(stack)) return ActionResult.PASS;

            if (!(entity instanceof PathAwareEntity)) {
                serverPlayer.sendMessage(
                        Text.literal("[CheesePatrol] Not a valid NPC (not PathAwareEntity).")
                                .formatted(Formatting.RED), true);
                return ActionResult.FAIL;
            }

            PatrolWand.setSelectedNpc(stack, entity.getUuid());
            PatrolData patrol = PatrolManager.getOrCreate(entity.getUuid());
            patrol.setDimension(world.getRegistryKey().getValue().toString());
            PatrolManager.save();

            String status = "";
            PatrolData existing = PatrolManager.get(entity.getUuid());
            if (existing != null && existing.isActive()) {
                status = " §c[PATROL ACTIVE — /npcpatrol clear before deleting!]";
            } else if (existing != null && !existing.getWaypoints().isEmpty()) {
                status = " §7[" + existing.getWaypoints().size() + " waypoints, inactive]";
            }

            serverPlayer.sendMessage(
                    Text.literal("[CheesePatrol] Selected: " + entity.getName().getString()
                            + " (" + entity.getUuid().toString().substring(0, 8) + "...)" + status)
                            .formatted(Formatting.GOLD), false);

            return ActionResult.SUCCESS;
        });

        // Right-click block with wand → add waypoint on top of that block
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient()) return ActionResult.PASS;
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity serverPlayer)) return ActionResult.PASS;
            if (!serverPlayer.hasPermissionLevel(2)) return ActionResult.PASS;

            ItemStack stack = serverPlayer.getMainHandStack();
            if (!PatrolWand.isWand(stack)) return ActionResult.PASS;

            UUID npcUuid = PatrolWand.getSelectedNpc(stack);
            if (npcUuid == null) {
                serverPlayer.sendMessage(
                        Text.literal("[CheesePatrol] No NPC selected. Right-click an NPC first.")
                                .formatted(Formatting.RED), true);
                return ActionResult.FAIL;
            }

            BlockPos pos = hitResult.getBlockPos().up();
            PatrolData patrol = PatrolManager.getOrCreate(npcUuid);
            patrol.addWaypoint(pos);
            PatrolManager.save();

            serverPlayer.sendMessage(
                    Text.literal("[CheesePatrol] Waypoint #" + patrol.getWaypoints().size()
                            + " added at " + pos.getX() + " " + pos.getY() + " " + pos.getZ())
                            .formatted(Formatting.GOLD), true);

            return ActionResult.SUCCESS;
        });
    }
}
