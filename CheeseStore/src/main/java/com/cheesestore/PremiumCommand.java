package com.cheesestore;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * /premium — Premium currency ("fromage") commands.
 *
 * Player commands:
 *   /premium          — show own balance
 *   /premium open     — open store in browser
 *   /premium top      — top 10 leaderboard
 *
 * Admin commands (permission level 2):
 *   /premium give <player> <amount>
 *   /premium take <player> <amount>
 *   /premium set <player> <amount>
 *   /premium balance <player>
 */
public class PremiumCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseStore");

    private static final SuggestionProvider<ServerCommandSource> PLAYER_SUGGESTIONS = (ctx, builder) ->
            CommandSource.suggestMatching(
                    ctx.getSource().getServer().getPlayerNames(),
                    builder
            );

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("premium")

                // /premium (no args) — own balance
                .executes(ctx -> executeOwnBalance(ctx.getSource()))

                // /premium open — open store in browser
                .then(CommandManager.literal("open")
                        .executes(ctx -> executeOpen(ctx.getSource())))

                // /premium top — leaderboard
                .then(CommandManager.literal("top")
                        .executes(ctx -> executeTop(ctx.getSource())))

                // /premium cosmetics — list purchasable cosmetics
                .then(CommandManager.literal("cosmetics")
                        .executes(ctx -> executeCosmetics(ctx.getSource())))

                // /premium give <player> <amount> — admin
                .then(CommandManager.literal("give")
                        .requires(src -> src.hasPermissionLevel(2))
                        .then(CommandManager.argument("player", StringArgumentType.word())
                                .suggests(PLAYER_SUGGESTIONS)
                                .then(CommandManager.argument("amount", LongArgumentType.longArg(1))
                                        .executes(ctx -> executeGive(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"),
                                                LongArgumentType.getLong(ctx, "amount"))))))

                // /premium take <player> <amount> — admin
                .then(CommandManager.literal("take")
                        .requires(src -> src.hasPermissionLevel(2))
                        .then(CommandManager.argument("player", StringArgumentType.word())
                                .suggests(PLAYER_SUGGESTIONS)
                                .then(CommandManager.argument("amount", LongArgumentType.longArg(1))
                                        .executes(ctx -> executeTake(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"),
                                                LongArgumentType.getLong(ctx, "amount"))))))

                // /premium set <player> <amount> — admin
                .then(CommandManager.literal("set")
                        .requires(src -> src.hasPermissionLevel(2))
                        .then(CommandManager.argument("player", StringArgumentType.word())
                                .suggests(PLAYER_SUGGESTIONS)
                                .then(CommandManager.argument("amount", LongArgumentType.longArg(0))
                                        .executes(ctx -> executeSet(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"),
                                                LongArgumentType.getLong(ctx, "amount"))))))

                // /premium balance <player> — admin
                .then(CommandManager.literal("balance")
                        .requires(src -> src.hasPermissionLevel(2))
                        .then(CommandManager.argument("player", StringArgumentType.word())
                                .suggests(PLAYER_SUGGESTIONS)
                                .executes(ctx -> executeBalanceOther(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player")))))
        );

        // /balance — convenience alias for "/premium" (own balance only).
        // Admins still use "/premium balance <player>" to inspect others.
        dispatcher.register(CommandManager.literal("balance")
                .executes(ctx -> executeOwnBalance(ctx.getSource())));
    }

    // ── Open Store ─────────────────────────────────────────────────────────────

    private static int executeOpen(ServerCommandSource source) {
        if (source.getPlayer() == null) {
            source.sendError(Text.translatable("cheesestore.cmd.player_only"));
            return 0;
        }
        String storeUrl = CheeseStoreMod.INSTANCE.getConfig().getStoreBaseUrl() + "/store/";
        Text link = Text.translatable("cheesestore.cmd.open_link")
                .formatted(Formatting.YELLOW, Formatting.UNDERLINE)
                .styled(s -> s
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, storeUrl))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Text.translatable("cheesestore.cmd.open_hover", storeUrl).formatted(Formatting.GRAY))));
        source.sendFeedback(() -> link, false);
        return 1;
    }

    // ── Own Balance ───────────────────────────────────────────────────────────

    private static int executeOwnBalance(ServerCommandSource source) {
        if (source.getPlayer() == null) {
            source.sendError(Text.translatable("cheesestore.cmd.player_only"));
            return 0;
        }
        ServerPlayerEntity player = source.getPlayer();
        long balance = WalletManager.getInstance().getBalance(player.getUuid());
        source.sendFeedback(() -> Text.translatable("cheesestore.cmd.balance", balance).formatted(Formatting.GOLD), false);
        return 1;
    }

    // ── Top Leaderboard ───────────────────────────────────────────────────────

    private static int executeTop(ServerCommandSource source) {
        List<WalletManager.WalletEntry> top = WalletManager.getInstance().getTop(10);
        if (top.isEmpty()) {
            source.sendFeedback(() -> Text.translatable("cheesestore.cmd.no_fromage").formatted(Formatting.GRAY), false);
            return 0;
        }
        source.sendFeedback(() -> Text.translatable("cheesestore.cmd.top_header").formatted(Formatting.GOLD), false);
        for (int i = 0; i < top.size(); i++) {
            WalletManager.WalletEntry entry = top.get(i);
            int rank = i + 1;
            source.sendFeedback(() -> Text.translatable("cheesestore.cmd.top_entry",
                    rank, entry.getPlayerName(), entry.getBalance()).formatted(Formatting.YELLOW), false);
        }
        return top.size();
    }

    // ── Cosmetics List ──────────────────────────────────────────────────────────

    private static int executeCosmetics(ServerCommandSource source) {
        List<CosmeticItem> cosmetics = CosmeticManager.getInstance().getEnabled();
        if (cosmetics.isEmpty()) {
            source.sendFeedback(() -> Text.translatable("cheesestore.cmd.cosmetics_empty").formatted(Formatting.GRAY), false);
            return 0;
        }
        source.sendFeedback(() -> Text.translatable("cheesestore.cmd.cosmetics_header").formatted(Formatting.LIGHT_PURPLE), false);
        for (CosmeticItem c : cosmetics) {
            source.sendFeedback(() -> Text.translatable("cheesestore.cmd.cosmetics_entry",
                    c.getName(), c.getType().name(), c.getPriceCredits()).formatted(Formatting.YELLOW), false);
        }
        return cosmetics.size();
    }

    // ── Give ──────────────────────────────────────────────────────────────────

    private static int executeGive(ServerCommandSource source, String playerName, long amount) {
        ResolvedPlayer resolved = resolvePlayer(source, playerName);
        if (resolved == null) return 0;

        WalletManager.getInstance().add(resolved.uuid, resolved.name, amount);
        long newBalance = WalletManager.getInstance().getBalance(resolved.uuid);

        source.sendFeedback(() -> Text.translatable("cheesestore.cmd.give",
                amount, resolved.name, newBalance).formatted(Formatting.GREEN), true);
        LOGGER.info("[CheeseStore] {} gave {} fromage to {} (new balance: {})",
                source.getName(), amount, resolved.name, newBalance);
        notifyPlayer(source, resolved.uuid, amount, newBalance, true);
        return 1;
    }

    // ── Take ──────────────────────────────────────────────────────────────────

    private static int executeTake(ServerCommandSource source, String playerName, long amount) {
        ResolvedPlayer resolved = resolvePlayer(source, playerName);
        if (resolved == null) return 0;

        long currentBalance = WalletManager.getInstance().getBalance(resolved.uuid);
        long toDeduct = Math.min(amount, currentBalance);
        WalletManager.getInstance().setBalance(resolved.uuid, resolved.name,
                Math.max(0, currentBalance - amount));
        long newBalance = WalletManager.getInstance().getBalance(resolved.uuid);

        source.sendFeedback(() -> Text.translatable("cheesestore.cmd.take",
                toDeduct, resolved.name, newBalance).formatted(Formatting.RED), true);
        LOGGER.info("[CheeseStore] {} took {} fromage from {} (new balance: {})",
                source.getName(), toDeduct, resolved.name, newBalance);
        notifyPlayer(source, resolved.uuid, toDeduct, newBalance, false);
        return 1;
    }

    // ── Set ───────────────────────────────────────────────────────────────────

    private static int executeSet(ServerCommandSource source, String playerName, long amount) {
        ResolvedPlayer resolved = resolvePlayer(source, playerName);
        if (resolved == null) return 0;

        long oldBalance = WalletManager.getInstance().getBalance(resolved.uuid);
        WalletManager.getInstance().setBalance(resolved.uuid, resolved.name, amount);
        long newBalance = WalletManager.getInstance().getBalance(resolved.uuid);

        source.sendFeedback(() -> Text.translatable("cheesestore.cmd.set",
                resolved.name, newBalance).formatted(Formatting.GOLD), true);
        LOGGER.info("[CheeseStore] {} set {} fromage for {} to {}",
                source.getName(), newBalance, resolved.name, newBalance);
        long diff = newBalance - oldBalance;
        if (diff != 0) {
            notifyPlayer(source, resolved.uuid, Math.abs(diff), newBalance, diff > 0);
        }
        return 1;
    }

    // ── Balance Other ─────────────────────────────────────────────────────────

    private static int executeBalanceOther(ServerCommandSource source, String playerName) {
        ResolvedPlayer resolved = resolvePlayer(source, playerName);
        if (resolved == null) return 0;

        long balance = WalletManager.getInstance().getBalance(resolved.uuid);
        source.sendFeedback(() -> Text.translatable("cheesestore.cmd.balance_other",
                resolved.name, balance).formatted(Formatting.GOLD), false);
        return 1;
    }

    // ── Player Resolution ─────────────────────────────────────────────────────

    /**
     * Resolves a player name to UUID + canonical name.
     * Tries online players first, then falls back to UserCache for offline players.
     */
    private static ResolvedPlayer resolvePlayer(ServerCommandSource source, String playerName) {
        // Try online player first
        ServerPlayerEntity online = source.getServer().getPlayerManager().getPlayer(playerName);
        if (online != null) {
            return new ResolvedPlayer(online.getUuid(), online.getName().getString());
        }

        // Try UserCache for offline players
        Optional<GameProfile> profile = source.getServer().getUserCache().findByName(playerName);
        if (profile.isPresent()) {
            return new ResolvedPlayer(profile.get().getId(), profile.get().getName());
        }

        source.sendError(Text.translatable("cheesestore.cmd.player_not_found", playerName));
        return null;
    }

    /**
     * Notify the target player (if online) about a balance change.
     */
    private static void notifyPlayer(ServerCommandSource source, UUID targetUuid, long amount, long newBalance, boolean isGive) {
        ServerPlayerEntity target = source.getServer().getPlayerManager().getPlayer(targetUuid);
        if (target == null) return;
        // Don't notify if the admin IS the target player (they already see the feedback)
        if (source.getPlayer() != null && source.getPlayer().getUuid().equals(targetUuid)) return;
        if (isGive) {
            target.sendMessage(Text.translatable("cheesestore.notify.received", amount, newBalance)
                    .formatted(Formatting.GREEN), false);
        } else {
            target.sendMessage(Text.translatable("cheesestore.notify.deducted", amount, newBalance)
                    .formatted(Formatting.RED), false);
        }
    }

    private record ResolvedPlayer(UUID uuid, String name) {}
}
