package com.roicheese.eggandcheese.util;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.roicheese.eggandcheese.blockentity.PedestalBlockEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

public class PedestalCommand {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("pedestal")
                .then(CommandManager.literal("scale")
                    .then(CommandManager.argument("value", FloatArgumentType.floatArg(0.5f, 3.0f))
                        .executes(ctx -> {
                            ServerPlayerEntity player = ctx.getSource().getPlayer();
                            if (player == null) {
                                ctx.getSource().sendError(Text.translatable("eggandcheese.cmd.player_only"));
                                return 0;
                            }
                            float value = FloatArgumentType.getFloat(ctx, "value");
                            PedestalBlockEntity pedestal = lookingAtPedestal(player);
                            if (pedestal == null) return 0;
                            pedestal.setDisplayScale(value);
                            player.sendMessage(Text.translatable("eggandcheese.pedestal.scale_set",
                                String.format("%.1f", value)).formatted(Formatting.GREEN), false);
                            return 1;
                        })))
                .then(CommandManager.literal("height")
                    .then(CommandManager.argument("value", FloatArgumentType.floatArg(-0.5f, 0.5f))
                        .executes(ctx -> {
                            ServerPlayerEntity player = ctx.getSource().getPlayer();
                            if (player == null) {
                                ctx.getSource().sendError(Text.translatable("eggandcheese.cmd.player_only"));
                                return 0;
                            }
                            float value = FloatArgumentType.getFloat(ctx, "value");
                            PedestalBlockEntity pedestal = lookingAtPedestal(player);
                            if (pedestal == null) return 0;
                            pedestal.setDisplayHeight(value);
                            player.sendMessage(Text.translatable("eggandcheese.pedestal.height_set",
                                String.format("%.1f", value)).formatted(Formatting.GREEN), false);
                            return 1;
                        })))
                .then(CommandManager.literal("pillar")
                    .then(CommandManager.argument("value", FloatArgumentType.floatArg(0.0f, 5.0f))
                        .executes(ctx -> {
                            ServerPlayerEntity player = ctx.getSource().getPlayer();
                            if (player == null) {
                                ctx.getSource().sendError(Text.translatable("eggandcheese.cmd.player_only"));
                                return 0;
                            }
                            float value = FloatArgumentType.getFloat(ctx, "value");
                            PedestalBlockEntity pedestal = lookingAtPedestal(player);
                            if (pedestal == null) return 0;
                            pedestal.setPillarHeight(value);
                            player.sendMessage(Text.translatable("eggandcheese.pedestal.pillar_set",
                                String.format("%.1f", value)).formatted(Formatting.GREEN), false);
                            return 1;
                        })))
                .then(CommandManager.literal("spin")
                    .executes(ctx -> {
                        ServerPlayerEntity player = ctx.getSource().getPlayer();
                        if (player == null) {
                            ctx.getSource().sendError(Text.translatable("eggandcheese.cmd.player_only"));
                            return 0;
                        }
                        PedestalBlockEntity pedestal = lookingAtPedestal(player);
                        if (pedestal == null) return 0;
                        boolean newState = !pedestal.isSpinning();
                        pedestal.setSpinning(newState);
                        player.sendMessage(Text.translatable(newState
                                ? "eggandcheese.pedestal.spin_on" : "eggandcheese.pedestal.spin_off")
                            .formatted(Formatting.GREEN), false);
                        return 1;
                    }))
        );
    }

    private static PedestalBlockEntity lookingAtPedestal(ServerPlayerEntity player) {
        HitResult hit = player.raycast(5.0, 0, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() == HitResult.Type.MISS) {
            player.sendMessage(Text.translatable("eggandcheese.pedestal.not_looking")
                .formatted(Formatting.RED), false);
            return null;
        }
        BlockPos pos = blockHit.getBlockPos();
        BlockEntity be = player.getWorld().getBlockEntity(pos);
        if (!(be instanceof PedestalBlockEntity pedestal)) {
            player.sendMessage(Text.translatable("eggandcheese.pedestal.not_looking")
                .formatted(Formatting.RED), false);
            return null;
        }
        return pedestal;
    }
}
