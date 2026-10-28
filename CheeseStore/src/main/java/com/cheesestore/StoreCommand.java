package com.cheesestore;

import com.cheesestore.auth.AccountLinker;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * /store link &lt;code&gt; — completes the OAuth account-link flow started on the website.
 */
public final class StoreCommand {

    private StoreCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("store")
            .then(CommandManager.literal("link")
                .then(CommandManager.argument("code", StringArgumentType.word())
                    .executes(ctx -> executeLink(ctx.getSource(),
                        StringArgumentType.getString(ctx, "code")))))
        );
    }

    private static int executeLink(ServerCommandSource source, String code) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.translatable("cheesestore.cmd.player_only"));
            return 0;
        }
        AccountLinker.LinkAttempt attempt = AccountLinker.getInstance()
                .completeLink(code, player.getUuid(), player.getName().getString());
        switch (attempt.result) {
            case OK:
                final AccountLinker.Link l = attempt.link;
                source.sendFeedback(() -> Text.translatable("cheesestore.cmd.link_success", l.minecraftName)
                        .formatted(Formatting.GREEN), false);
                return 1;
            case RATE_LIMITED:
                source.sendError(Text.translatable("cheesestore.cmd.link_rate_limited")
                        .formatted(Formatting.RED));
                return 0;
            case INVALID_OR_EXPIRED:
            default:
                source.sendError(Text.translatable("cheesestore.cmd.link_invalid").formatted(Formatting.RED));
                return 0;
        }
    }
}
