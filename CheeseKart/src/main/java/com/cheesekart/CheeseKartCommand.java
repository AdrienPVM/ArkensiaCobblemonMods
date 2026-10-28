package com.cheesekart;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.concurrent.TimeUnit;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class CheeseKartCommand {

    private static final SuggestionProvider<ServerCommandSource> PRESET_SUGGESTIONS = (ctx, builder) -> {
        CheeseKartConfig config = CheeseKartMod.INSTANCE.getConfig();
        return CommandSource.suggestMatching(config.getPresets().keySet(), builder);
    };

    private static final SuggestionProvider<ServerCommandSource> PLAYER_SUGGESTIONS = (ctx, builder) -> {
        return CommandSource.suggestMatching(
                ctx.getSource().getServer().getPlayerNames(), builder);
    };

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("cheesekart")
                .requires(src -> src.hasPermissionLevel(2))
                .then(literal("start")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(PLAYER_SUGGESTIONS)
                                .then(argument("preset", StringArgumentType.word())
                                        .suggests(PRESET_SUGGESTIONS)
                                        .executes(ctx -> startKart(ctx, null))
                                        .then(argument("species", StringArgumentType.greedyString())
                                                .executes(ctx -> startKart(ctx,
                                                        StringArgumentType.getString(ctx, "species")))))))
                .then(literal("stop")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(PLAYER_SUGGESTIONS)
                                .executes(CheeseKartCommand::stopKart)))
                .then(literal("stopall")
                        .executes(CheeseKartCommand::stopAll))
                .then(literal("list")
                        .executes(CheeseKartCommand::listSessions))
                .then(literal("presets")
                        .executes(CheeseKartCommand::listPresets))
        );
    }

    private static int startKart(CommandContext<ServerCommandSource> ctx, String speciesArg) {
        ServerCommandSource src = ctx.getSource();
        String playerName = StringArgumentType.getString(ctx, "player");
        String presetId = StringArgumentType.getString(ctx, "preset");

        ServerPlayerEntity player = src.getServer().getPlayerManager().getPlayer(playerName);
        if (player == null) {
            src.sendError(Text.translatable("cheesekart.cmd.player_not_found", playerName));
            return 0;
        }

        KartPreset preset = CheeseKartMod.INSTANCE.getConfig().getPreset(presetId);
        if (preset == null) {
            src.sendError(Text.translatable("cheesekart.cmd.preset_not_found", presetId));
            return 0;
        }

        boolean ok = CheeseKartMod.INSTANCE.getKartManager().startKart(player, preset, speciesArg);
        if (ok) {
            src.sendFeedback(() -> Text.translatable("cheesekart.cmd.kart_started", playerName, presetId).formatted(Formatting.GREEN), true);
        } else {
            src.sendError(Text.translatable("cheesekart.cmd.kart_start_failed", playerName));
        }
        return ok ? 1 : 0;
    }

    private static int stopKart(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        String playerName = StringArgumentType.getString(ctx, "player");

        ServerPlayerEntity player = src.getServer().getPlayerManager().getPlayer(playerName);
        if (player == null) {
            src.sendError(Text.translatable("cheesekart.cmd.player_not_found", playerName));
            return 0;
        }

        KartManager km = CheeseKartMod.INSTANCE.getKartManager();
        if (!km.isInKart(player.getUuid())) {
            src.sendError(Text.translatable("cheesekart.cmd.not_in_kart", playerName));
            return 0;
        }

        km.stopKart(player);
        src.sendFeedback(() -> Text.translatable("cheesekart.cmd.kart_stopped", playerName).formatted(Formatting.YELLOW), true);
        return 1;
    }

    private static int stopAll(CommandContext<ServerCommandSource> ctx) {
        KartManager km = CheeseKartMod.INSTANCE.getKartManager();
        int count = km.getSessions().size();
        km.stopAll();
        ctx.getSource().sendFeedback(() -> Text.translatable("cheesekart.cmd.all_stopped", count).formatted(Formatting.YELLOW), true);
        return count;
    }

    private static int listSessions(CommandContext<ServerCommandSource> ctx) {
        KartManager km = CheeseKartMod.INSTANCE.getKartManager();
        var sessions = km.getSessions();
        if (sessions.isEmpty()) {
            ctx.getSource().sendFeedback(() -> Text.translatable("cheesekart.cmd.no_active").formatted(Formatting.GRAY), false);
            return 0;
        }
        ctx.getSource().sendFeedback(() -> Text.translatable("cheesekart.cmd.active_header", sessions.size()).formatted(Formatting.GOLD), false);
        sessions.values().forEach(s -> {
            long sec = TimeUnit.MILLISECONDS.toSeconds(s.durationMs());
            ctx.getSource().sendFeedback(() -> Text.literal(
                    "  " + s.playerName() + " | preset=" + s.presetId()
                            + (s.species().isEmpty() ? "" : " | species=" + s.species())
                            + " | " + sec + "s"
                            + (s.locked() ? " [locked]" : "")
            ).formatted(Formatting.WHITE), false);
        });
        return sessions.size();
    }

    private static int listPresets(CommandContext<ServerCommandSource> ctx) {
        var presets = CheeseKartMod.INSTANCE.getConfig().getPresets();
        if (presets.isEmpty()) {
            ctx.getSource().sendFeedback(() -> Text.translatable("cheesekart.cmd.no_presets").formatted(Formatting.GRAY), false);
            return 0;
        }
        ctx.getSource().sendFeedback(() -> Text.translatable("cheesekart.cmd.presets_header", presets.size()).formatted(Formatting.GOLD), false);
        presets.values().forEach(p -> {
            ctx.getSource().sendFeedback(() -> Text.literal(
                    "  " + p.getId() + " — " + p.getName()
                            + " | slip=" + p.getSlipperiness()
                            + " step=" + p.getStepHeight()
                            + " fwd=" + p.getForwardAccel()
                            + (p.getSpecies().isEmpty() ? "" : " | species=" + p.getSpecies())
                            + (p.isLocked() ? " [locked]" : "")
            ).formatted(Formatting.WHITE), false);
        });
        return presets.size();
    }
}
