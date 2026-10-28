package com.roicheese.eggandcheese.util;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.roicheese.eggandcheese.data.EggRegistryData;
import com.roicheese.eggandcheese.registry.ModItems;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class AdminCommand {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("eac")
                .requires(src -> src.isExecutedByPlayer() && PermissionUtil.isAdmin(src.getPlayer()))
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> Text.translatable(
                        "eggandcheese.cmd.use_dashboard")
                        .formatted(Formatting.YELLOW), false);
                    return 1;
                })
                .then(CommandManager.literal("give")
                    .then(CommandManager.argument("id", StringArgumentType.string())
                        .executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "id");
                            ServerPlayerEntity player = ctx.getSource().getPlayer();
                            if (player == null) return 0;
                            EggRegistryData registry = EggRegistryData.get(player.getServer());
                            registry.getEntry(id).ifPresentOrElse(entry -> {
                                ItemStack stack = new ItemStack(ModItems.ADMIN_EGG);
                                NbtCompound nbt = new NbtCompound();
                                nbt.put("dna", entry.getDna().toNbt());
                                nbt.putString("registryId", entry.getId());
                                nbt.putFloat("scale", entry.getScale());
                                stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
                                if (!player.getInventory().insertStack(stack)) player.dropItem(stack, false);
                                player.sendMessage(Text.translatable("eggandcheese.cmd.given", id), false);
                            }, () -> player.sendMessage(
                                Text.translatable("eggandcheese.cmd.unknown_id", id).formatted(Formatting.RED), false));
                            return 1;
                        })))
                .then(CommandManager.literal("reload")
                    .executes(ctx -> {
                        int count = EggRegistryData.get(ctx.getSource().getServer()).getAllEntries().size();
                        ctx.getSource().sendFeedback(() -> Text.translatable(
                            "eggandcheese.cmd.registry_count", count), false);
                        return 1;
                    }))
        );
    }
}
