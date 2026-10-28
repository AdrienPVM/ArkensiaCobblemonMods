package com.sebastian.cobblemondungeons.party;

import com.mojang.brigadier.CommandDispatcher;
import com.sebastian.cobblemondungeons.gui.DungeonsPartyGui;
import com.sebastian.cobblemondungeons.CobblemonDungeons;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class DungeonsPartyCommands {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("DungeonsParty")
            .requires(source -> !CobblemonDungeons.isDungeonServer(source.getServer()))
            .executes(context -> {
                ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                if (!DungeonsPartyManager.hasPokemon(player)) {
                    player.sendMessage(Text.translatable("cobblemondungeons.need_pokemon").formatted(net.minecraft.util.Formatting.RED));
                    return 1;
                }
                if (DungeonsPartyManager.getParty(player.getUuid()) == null) {
                    if (DungeonsPartyManager.createParty(player.getUuid(), player.getServer()) != null) {
                        context.getSource().sendFeedback(() -> Text.translatable("cobblemondungeons.party.created").formatted(Formatting.GREEN), false);
                        DungeonsPartyGui.open(player);
                    }
                } else {
                    DungeonsPartyGui.open(player);
                }
                return 1;
            })
            .then(CommandManager.literal("create")
                .executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                    if (!DungeonsPartyManager.hasPokemon(player)) {
                        player.sendMessage(Text.translatable("cobblemondungeons.need_pokemon").formatted(net.minecraft.util.Formatting.RED));
                        return 1;
                    }
                    if (DungeonsPartyManager.getParty(player.getUuid()) != null) {
                        context.getSource().sendError(Text.translatable("cobblemondungeons.party.already_in"));
                    } else if (DungeonsPartyManager.createParty(player.getUuid(), player.getServer()) != null) {
                        context.getSource().sendFeedback(() -> Text.translatable("cobblemondungeons.party.created"), false);
                    }
                    return 1;
                }))
            .then(CommandManager.literal("invite")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .executes(context -> {
                        ServerPlayerEntity inviter = context.getSource().getPlayerOrThrow();
                        if (!DungeonsPartyManager.hasPokemon(inviter)) {
                            inviter.sendMessage(Text.translatable("cobblemondungeons.need_pokemon").formatted(net.minecraft.util.Formatting.RED));
                            return 0;
                        }
                        ServerPlayerEntity invited = EntityArgumentType.getPlayer(context, "player");
                        if (DungeonsPartyManager.invitePlayer(inviter.getUuid(), invited.getUuid(), context.getSource().getServer())) {
                            context.getSource().sendFeedback(() -> Text.translatable("cobblemondungeons.party.invite_sent", invited.getName().getString()), false);
                        }
                        return 1;
                    })))
            .then(CommandManager.literal("join")
                .executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                    if (!DungeonsPartyManager.hasPokemon(player)) {
                        player.sendMessage(Text.translatable("cobblemondungeons.need_pokemon").formatted(net.minecraft.util.Formatting.RED));
                        return 1;
                    }
                    if (DungeonsPartyManager.joinParty(player)) {
                        context.getSource().sendFeedback(() -> Text.translatable("cobblemondungeons.party.joined"), false);
                    } else {
                        context.getSource().sendError(Text.translatable("cobblemondungeons.party.no_invites"));
                    }
                    return 1;
                })
                .then(CommandManager.argument("inviter", EntityArgumentType.player())
                    .executes(context -> {
                        ServerPlayerEntity invited = context.getSource().getPlayerOrThrow();
                        if (!DungeonsPartyManager.hasPokemon(invited)) {
                            invited.sendMessage(Text.translatable("cobblemondungeons.need_pokemon").formatted(Formatting.RED));
                            return 1;
                        }
                        ServerPlayerEntity inviter = EntityArgumentType.getPlayer(context, "inviter");
                        if (DungeonsPartyManager.joinSpecificParty(invited, inviter.getUuid())) {
                            context.getSource().sendFeedback(() -> Text.translatable("cobblemondungeons.party.joined_specific", inviter.getName().getString()), false);
                        } else {
                            context.getSource().sendError(Text.translatable("cobblemondungeons.party.no_invite_from", inviter.getName().getString()));
                        }
                        return 1;
                    })))
            .then(CommandManager.literal("leave")
                .executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                    DungeonsPartyManager.leaveParty(player.getUuid(), player.getServer());
                    return 1;
                }))
        );
    }
}
