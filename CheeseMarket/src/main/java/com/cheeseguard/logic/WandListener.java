package com.cheeseguard.logic;

import com.cheeseguard.CheeseGuardMod;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

public class WandListener {

    public void register() {

        // ── Left Click = Pos1 ────────────────────────────────────────────────
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClient()) return ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            if (!sp.hasPermissionLevel(2)) return ActionResult.PASS;
            if (!isWand(sp.getMainHandStack())) return ActionResult.PASS;

            String worldId = world.getRegistryKey().getValue().toString();
            CheeseGuardMod.getInstance().getSelectionManager().setPos1(sp.getUuid(), worldId, pos);

            sp.sendMessage(Text.literal("✅ ")
                .append(Text.literal("Position 1").formatted(Formatting.GREEN))
                .append(Text.literal(" définie : ").formatted(Formatting.GRAY))
                .append(Text.literal("(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")")
                    .formatted(Formatting.YELLOW)), false);

            return ActionResult.SUCCESS; // consume = don't break block
        });

        // ── Right Click = Pos2 ───────────────────────────────────────────────
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient()) return ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            if (!sp.hasPermissionLevel(2)) return ActionResult.PASS;
            if (!isWand(sp.getStackInHand(hand))) return ActionResult.PASS;

            BlockPos pos = hitResult.getBlockPos();
            String worldId = world.getRegistryKey().getValue().toString();
            CheeseGuardMod.getInstance().getSelectionManager().setPos2(sp.getUuid(), worldId, pos);

            sp.sendMessage(Text.literal("✅ ")
                .append(Text.literal("Position 2").formatted(Formatting.AQUA))
                .append(Text.literal(" définie : ").formatted(Formatting.GRAY))
                .append(Text.literal("(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")")
                    .formatted(Formatting.YELLOW)), false);

            // Show selection size
            SelectionManager.Selection sel = CheeseGuardMod.getInstance().getSelectionManager().getSelection(sp.getUuid());
            if (sel != null && sel.isComplete()) {
                int dx = Math.abs(sel.pos2.getX() - sel.pos1.getX()) + 1;
                int dy = Math.abs(sel.pos2.getY() - sel.pos1.getY()) + 1;
                int dz = Math.abs(sel.pos2.getZ() - sel.pos1.getZ()) + 1;
                long vol = (long) dx * dy * dz;
                sp.sendMessage(Text.literal("📐 Sélection : ")
                    .formatted(Formatting.GRAY)
                    .append(Text.literal(dx + "×" + dy + "×" + dz + " (" + vol + " blocs)")
                        .formatted(Formatting.WHITE)), false);
                sp.sendMessage(Text.literal("💡 Tapez ")
                    .formatted(Formatting.GRAY)
                    .append(Text.literal("/cg create <nom>").formatted(Formatting.GOLD))
                    .append(Text.literal(" pour créer la zone").formatted(Formatting.GRAY)), false);
            }

            return ActionResult.SUCCESS;
        });
    }

    public static boolean isWand(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!stack.getItem().equals(Items.WOODEN_SWORD)) return false;
        return stack.contains(net.minecraft.component.DataComponentTypes.CUSTOM_NAME)
            && stack.getName().getString().contains("[CheeseGuard Wand]");
    }
}
