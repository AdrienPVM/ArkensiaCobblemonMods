package com.cobblecap.commands;

import com.cobblecap.CobbleCapMod;
import com.cobblecap.data.CapStorage;
import com.cobblecap.data.PlayerCapData;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Optional;

public class ExpLockCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        // /explock — player toggles their own XP lock
        dispatcher.register(CommandManager.literal("explock")
            .executes(ctx -> toggleSelf(ctx.getSource()))
            // /explock <player> — OP toggles another player's XP lock
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .requires(src -> src.hasPermissionLevel(2))
                .executes(ctx -> toggleOther(ctx.getSource(),
                    EntityArgumentType.getPlayer(ctx, "player"))))
        );
    }

    private static int toggleSelf(ServerCommandSource src) {
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) {
            src.sendError(Text.literal("This command must be run by a player."));
            return 0;
        }
        return doToggle(player, player, src);
    }

    private static int toggleOther(ServerCommandSource src, ServerPlayerEntity target) {
        return doToggle(target, target, src);
    }

    private static int doToggle(ServerPlayerEntity target, ServerPlayerEntity messageTarget,
                                 ServerCommandSource src) {
        CapStorage storage = CobbleCapMod.INSTANCE.getCapStorage();
        storage.ensureExists(target.getUuid(), target.getName().getString());
        Optional<PlayerCapData> dataOpt = storage.getCapData(target.getUuid());
        if (dataOpt.isEmpty()) {
            src.sendError(Text.literal("Player data not found."));
            return 0;
        }

        PlayerCapData data = dataOpt.get();
        boolean newState = !data.isExpLocked();
        data.setExpLocked(newState);
        storage.saveAsync();

        if (newState) {
            target.sendMessage(Text.translatable("cobblecap.msg.explock_on")
                .formatted(Formatting.GOLD), false);
        } else {
            target.sendMessage(Text.translatable("cobblecap.msg.explock_off")
                .formatted(Formatting.GREEN), false);
        }

        // If an admin toggled someone else, confirm to the admin too
        if (src.getPlayer() != null && !src.getPlayer().getUuid().equals(target.getUuid())) {
            src.sendFeedback(() -> Text.translatable("cobblecap.msg.explock_admin",
                target.getName().getString(), newState ? "ON" : "OFF")
                .formatted(Formatting.YELLOW), true);
        }

        return 1;
    }
}
