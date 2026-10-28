package com.cheeseitems;

import com.cheeseitems.network.MorphoOpenGuiS2CPayload;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * /morpho — opens the Morpho Codex GUI on the calling player's client.
 * Available to all players, no permission level required.
 */
public final class MorphoCommand {

    private MorphoCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("morpho")
            .executes(ctx -> {
                ServerCommandSource src = ctx.getSource();
                ServerPlayerEntity player = src.getPlayer();
                if (player == null) {
                    src.sendError(Text.literal("This command can only be used by a player."));
                    return 0;
                }
                try {
                    ServerPlayNetworking.send(player, new MorphoOpenGuiS2CPayload());
                } catch (Exception e) {
                    player.sendMessage(Text.literal("Failed to open Morpho Codex: " + e.getMessage())
                        .formatted(Formatting.RED), false);
                    return 0;
                }
                return 1;
            })
        );
    }
}
