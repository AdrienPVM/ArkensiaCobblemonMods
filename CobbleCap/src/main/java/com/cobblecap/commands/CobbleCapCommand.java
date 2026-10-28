package com.cobblecap.commands;

import com.cobblecap.CobbleCapMod;
import com.cobblecap.data.CapConfig;
import com.cobblecap.data.CapStorage;
import com.cobblecap.data.ChampionEntry;
import com.cobblecap.data.PlayerCapData;
import com.cobblecap.logic.ChampionManager;
import com.cobblecap.logic.CobbleCapWebhook;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

public class CobbleCapCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        var root = CommandManager.literal("cobblecap");

        // ── Player commands (no permission required, must be a player) ────────

        // /cobblecap (no args) — show status
        root.executes(ctx -> showStatus(ctx.getSource()));

        // /cobblecap hud on|off|position <corner>
        root.then(CommandManager.literal("hud")
            .then(CommandManager.literal("on")
                .executes(ctx -> hudToggle(ctx.getSource(), true)))
            .then(CommandManager.literal("off")
                .executes(ctx -> hudToggle(ctx.getSource(), false)))
            .then(CommandManager.literal("position")
                .then(CommandManager.argument("corner", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (String s : new String[]{"bottom-right", "bottom-left", "top-right", "top-left"})
                            builder.suggest(s);
                        return builder.buildFuture();
                    })
                    .executes(ctx -> hudPosition(ctx.getSource(),
                        StringArgumentType.getString(ctx, "corner")))))
        );

        // ── Admin commands (level 2) ──────────────────────────────────────────

        // set <player> <level>
        root.then(CommandManager.literal("set")
            .requires(src -> src.hasPermissionLevel(2))
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .then(CommandManager.argument("level", IntegerArgumentType.integer(1, 100))
                    .executes(ctx -> {
                        ServerCommandSource src = ctx.getSource();
                        ServerPlayerEntity player = EntityArgumentType.getPlayer(ctx, "player");
                        int level = IntegerArgumentType.getInteger(ctx, "level");

                        CapStorage storage = CobbleCapMod.INSTANCE.getCapStorage();
                        storage.ensureExists(player.getUuid(), player.getName().getString());
                        storage.setCapLevel(player.getUuid(), level);

                        LOGGER.info("[CobbleCap] {} set cap for {} to {}",
                                src.getName(), player.getName().getString(), level);

                        reply(src, Text.translatable("cobblecap.msg.set",
                                player.getName().getString(), level).formatted(Formatting.GREEN));

                        CobbleCapMod.syncToClient(player);
                        return 1;
                    }))));

        // get <player>
        root.then(CommandManager.literal("get")
            .requires(src -> src.hasPermissionLevel(2))
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> {
                    ServerCommandSource src = ctx.getSource();
                    ServerPlayerEntity player = EntityArgumentType.getPlayer(ctx, "player");

                    CapStorage storage = CobbleCapMod.INSTANCE.getCapStorage();
                    Optional<PlayerCapData> data = storage.getCapData(player.getUuid());

                    if (data.isPresent()) {
                        reply(src, Text.translatable("cobblecap.msg.get",
                                player.getName().getString(), data.get().getCapLevel())
                                .formatted(Formatting.YELLOW));
                    } else {
                        reply(src, Text.translatable("cobblecap.msg.player_not_found",
                                player.getName().getString()).formatted(Formatting.RED));
                    }
                    return 1;
                })));

        // reset <player>
        root.then(CommandManager.literal("reset")
            .requires(src -> src.hasPermissionLevel(2))
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> {
                    ServerCommandSource src = ctx.getSource();
                    ServerPlayerEntity player = EntityArgumentType.getPlayer(ctx, "player");

                    CapStorage storage = CobbleCapMod.INSTANCE.getCapStorage();
                    CapConfig config = CobbleCapMod.INSTANCE.getCapConfig();
                    int startingCap = config.getStartingCap();

                    storage.ensureExists(player.getUuid(), player.getName().getString());
                    storage.setCapLevel(player.getUuid(), startingCap);
                    // Also clear champions beaten + challenges + cooldowns for full reset
                    storage.getCapData(player.getUuid()).ifPresent(data -> {
                        data.getChampionsBeaten().clear();
                        data.getChallengeBeaten().clear();
                        data.getLastChallengeTimes().clear();
                        data.setExpLocked(false);
                    });
                    storage.saveAsync();

                    // Revoke advancements and reset FTB quests for all champions
                    // so NPCs revert to normal mode dialogue
                    var server = src.getServer();
                    for (ChampionEntry champ : CobbleCapMod.INSTANCE.getChampionConfig().getChampions()) {
                        revokeChampionProgress(player, champ, server);
                    }

                    LOGGER.info("[CobbleCap] {} reset cap for {} to {}",
                            src.getName(), player.getName().getString(), startingCap);

                    reply(src, Text.translatable("cobblecap.msg.reset",
                            player.getName().getString(), startingCap).formatted(Formatting.GREEN));

                    CobbleCapMod.syncToClient(player);
                    return 1;
                })));

        // removebeaten <player> <championId> — remove a specific champion from championsBeaten
        root.then(CommandManager.literal("removebeaten")
            .requires(src -> src.hasPermissionLevel(2))
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .then(CommandManager.argument("championId", StringArgumentType.string())
                    .suggests((ctx, builder) -> {
                        for (ChampionEntry c : CobbleCapMod.INSTANCE.getChampionConfig().getChampions())
                            builder.suggest(c.id());
                        return builder.buildFuture();
                    })
                    .executes(ctx -> {
                        ServerCommandSource src = ctx.getSource();
                        ServerPlayerEntity player = EntityArgumentType.getPlayer(ctx, "player");
                        String championId = StringArgumentType.getString(ctx, "championId");

                        CapStorage storage = CobbleCapMod.INSTANCE.getCapStorage();
                        storage.ensureExists(player.getUuid(), player.getName().getString());
                        storage.getCapData(player.getUuid()).ifPresent(data -> {
                            data.getChampionsBeaten().remove(championId);
                            data.getChallengeBeaten().remove(championId);
                        });
                        storage.saveAsync();

                        LOGGER.info("[CobbleCap] {} removed beaten '{}' for {}",
                                src.getName(), championId, player.getName().getString());

                        reply(src, Text.literal("Removed '" + championId + "' from " +
                                player.getName().getString() + "'s beaten list.")
                                .formatted(Formatting.YELLOW));

                        CobbleCapMod.syncToClient(player);
                        return 1;
                    }))));

        // addbeaten <player> <championId> — add a champion to championsBeaten + raise cap if applicable
        // Useful for repairing desync after /cobblecap reset + /cobblecap set, or when
        // BATTLE_VICTORY failed to persist for any reason.
        root.then(CommandManager.literal("addbeaten")
            .requires(src -> src.hasPermissionLevel(2))
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .then(CommandManager.argument("championId", StringArgumentType.string())
                    .suggests((ctx, builder) -> {
                        for (ChampionEntry c : CobbleCapMod.INSTANCE.getChampionConfig().getChampions())
                            builder.suggest(c.id());
                        return builder.buildFuture();
                    })
                    .executes(ctx -> {
                        ServerCommandSource src = ctx.getSource();
                        ServerPlayerEntity player = EntityArgumentType.getPlayer(ctx, "player");
                        String championId = StringArgumentType.getString(ctx, "championId");

                        Optional<ChampionEntry> champOpt = CobbleCapMod.INSTANCE
                                .getChampionConfig().findById(championId);
                        if (champOpt.isEmpty()) {
                            src.sendError(Text.literal("Unknown champion id: " + championId)
                                    .formatted(Formatting.RED));
                            return 0;
                        }
                        ChampionEntry champ = champOpt.get();

                        CapStorage storage = CobbleCapMod.INSTANCE.getCapStorage();
                        storage.ensureExists(player.getUuid(), player.getName().getString());
                        PlayerCapData data = storage.getCapData(player.getUuid()).orElse(null);
                        if (data == null) return 0;

                        boolean wasBeaten = data.getChampionsBeaten().contains(championId);
                        data.addChampionBeaten(championId);
                        boolean capRaised = storage.raiseCapLevel(player.getUuid(), champ.capGranted());
                        storage.saveAsync();

                        LOGGER.info("[CobbleCap] {} added beaten '{}' for {} (wasBeaten={}, capRaised={})",
                                src.getName(), championId, player.getName().getString(),
                                wasBeaten, capRaised);

                        String suffix = wasBeaten ? " (already present)"
                                : capRaised ? " — cap raised to " + champ.capGranted() : "";
                        reply(src, Text.literal("Added '" + championId + "' to "
                                + player.getName().getString() + "'s beaten list" + suffix + ".")
                                .formatted(Formatting.GREEN));

                        CobbleCapMod.syncToClient(player);
                        return 1;
                    }))));

        // status — alias of /cobblecap (self) ; status <player> — admin debug dump
        root.then(CommandManager.literal("status")
            .executes(ctx -> showStatus(ctx.getSource()))
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .requires(src -> src.hasPermissionLevel(2))
                .executes(ctx -> showAdminStatus(ctx.getSource(),
                        EntityArgumentType.getPlayer(ctx, "player")))));

        // reload
        root.then(CommandManager.literal("reload")
            .requires(src -> src.hasPermissionLevel(2))
            .executes(ctx -> {
                ServerCommandSource src = ctx.getSource();
                CobbleCapMod.INSTANCE.getCapConfig().load();
                CobbleCapWebhook.setUrl(CobbleCapMod.INSTANCE.getCapConfig().getDiscordWebhookUrl());
                int count = CobbleCapMod.INSTANCE.getChampionConfig().reload();

                LOGGER.info("[CobbleCap] {} triggered reload — {} champions loaded",
                        src.getName(), count);

                reply(src, Text.translatable("cobblecap.msg.reload", count)
                        .formatted(Formatting.YELLOW));

                // Sync all online players (champion config may have changed next-champion names)
                src.getServer().getPlayerManager().getPlayerList()
                        .forEach(CobbleCapMod::syncToClient);
                return 1;
            }));

        // enable
        root.then(CommandManager.literal("enable")
            .requires(src -> src.hasPermissionLevel(2))
            .executes(ctx -> {
                ServerCommandSource src = ctx.getSource();
                CapConfig config = CobbleCapMod.INSTANCE.getCapConfig();

                if (config.isEnabled()) {
                    reply(src, Text.translatable("cobblecap.msg.already_enabled")
                            .formatted(Formatting.YELLOW));
                } else {
                    config.setEnabled(true);
                    LOGGER.info("[CobbleCap] {} enabled the level cap system", src.getName());
                    reply(src, Text.translatable("cobblecap.msg.enabled")
                            .formatted(Formatting.GREEN));

                    // Sync all online players (systemEnabled flag changed)
                    src.getServer().getPlayerManager().getPlayerList()
                            .forEach(CobbleCapMod::syncToClient);
                }
                return 1;
            }));

        // disable
        root.then(CommandManager.literal("disable")
            .requires(src -> src.hasPermissionLevel(2))
            .executes(ctx -> {
                ServerCommandSource src = ctx.getSource();
                CapConfig config = CobbleCapMod.INSTANCE.getCapConfig();

                if (!config.isEnabled()) {
                    reply(src, Text.translatable("cobblecap.msg.already_disabled")
                            .formatted(Formatting.YELLOW));
                } else {
                    config.setEnabled(false);
                    LOGGER.info("[CobbleCap] {} disabled the level cap system", src.getName());
                    reply(src, Text.translatable("cobblecap.msg.disabled")
                            .formatted(Formatting.RED));

                    // Sync all online players (systemEnabled flag changed)
                    src.getServer().getPlayerManager().getPlayerList()
                            .forEach(CobbleCapMod::syncToClient);
                }
                return 1;
            }));

        // /cobblecap legends — list all Pokémon League Masters
        root.then(CommandManager.literal("legends")
            .executes(ctx -> showLegends(ctx.getSource()))
            .then(CommandManager.literal("by_time")
                .executes(ctx -> showLegendsByTime(ctx.getSource()))));

        // /cobblecap statue ... and /cobblecap chrono ...
        StatueCommand.attach(root);

        // /cobblecap mode classic|ranked — applied by Alfred's Conseil 4 dialog
        // buttons. Sets the player's cobblecap_mode scoreboard score AND swaps
        // the live Régis trainer JSON to the matching variant. Self-targeted
        // (no permission), runnable by the player. Admin variant takes a target.
        root.then(CommandManager.literal("mode")
            .then(CommandManager.literal("classic")
                .executes(ctx -> applyRegisMode(ctx.getSource(), com.cobblecap.logic.RegisModeManager.Mode.CLASSIC, null)))
            .then(CommandManager.literal("ranked")
                .executes(ctx -> applyRegisMode(ctx.getSource(), com.cobblecap.logic.RegisModeManager.Mode.RANKED, null)))
            .then(CommandManager.literal("set")
                .requires(src -> src.hasPermissionLevel(2))
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .then(CommandManager.literal("classic")
                        .executes(ctx -> applyRegisMode(ctx.getSource(),
                                com.cobblecap.logic.RegisModeManager.Mode.CLASSIC,
                                EntityArgumentType.getPlayer(ctx, "player"))))
                    .then(CommandManager.literal("ranked")
                        .executes(ctx -> applyRegisMode(ctx.getSource(),
                                com.cobblecap.logic.RegisModeManager.Mode.RANKED,
                                EntityArgumentType.getPlayer(ctx, "player")))))));

        dispatcher.register(root);
    }

    /**
     * Apply a Régis fight mode (classic or ranked) for the given player —
     * or for the command-source player if {@code target} is null. Records
     * the mode on the {@code cobblecap_mode} scoreboard objective (1=classic,
     * 2=ranked) and swaps the live Régis trainer JSON via RegisModeManager.
     */
    private static int applyRegisMode(ServerCommandSource src,
                                        com.cobblecap.logic.RegisModeManager.Mode mode,
                                        ServerPlayerEntity target) {
        ServerPlayerEntity player = target;
        if (player == null) {
            try { player = src.getPlayerOrThrow(); }
            catch (Exception e) {
                src.sendError(Text.literal("This command must target a player"));
                return 0;
            }
        }
        var server = src.getServer();
        // cobblecap_mode is pre-created at boot by Conseil4Boards.initObjectives,
        // but bootstrap it via vanilla cmd if it's somehow missing (idempotent).
        int value = mode == com.cobblecap.logic.RegisModeManager.Mode.RANKED ? 2 : 1;
        try {
            var dispatcher = server.getCommandManager().getDispatcher();
            var silent = server.getCommandSource().withSilent();
            if (server.getScoreboard().getNullableObjective("cobblecap_mode") == null) {
                try { dispatcher.execute("scoreboard objectives add cobblecap_mode dummy", silent); }
                catch (Exception ignored) {}
            }
            dispatcher.execute(
                    "scoreboard players set " + player.getName().getString() + " cobblecap_mode " + value,
                    silent);
        } catch (Exception e) {
            LOGGER.warn("[CobbleCap] cobblecap_mode score set failed: {}", e.getMessage());
        }
        boolean swapped = com.cobblecap.logic.RegisModeManager.applyMode(server, mode);
        final String playerName = player.getName().getString();
        final String modeKey = mode.key();
        final boolean wasSwapped = swapped;
        src.sendFeedback(() -> Text.literal("Conseil 4 mode → " + modeKey + " for " + playerName
                + (wasSwapped ? " (JSON swapped + reload dispatched)" : " (already live)"))
                .formatted(Formatting.AQUA), false);
        LOGGER.info("[CobbleCap] {} set Régis mode {} for {} (swap={})",
                src.getName(), modeKey, playerName, wasSwapped);
        return 1;
    }

    private static int showLegendsByTime(ServerCommandSource src) {
        var legends = new java.util.ArrayList<>(CobbleCapMod.INSTANCE.getLegendsStorage().getAll());
        legends.removeIf(e -> e.bestTimeMs() <= 0);
        legends.sort((a, b) -> Long.compare(a.bestTimeMs(), b.bestTimeMs()));
        if (legends.isEmpty()) {
            reply(src, Text.translatable("cobblecap.msg.legends_empty").formatted(Formatting.YELLOW));
            return 1;
        }
        reply(src, Text.translatable("cobblecap.msg.legends_by_time_header", legends.size())
                .formatted(Formatting.GOLD, Formatting.BOLD));
        for (int i = 0; i < legends.size(); i++) {
            var e = legends.get(i);
            String t = com.cobblecap.logic.ChronoManager.formatDuration(e.bestTimeMs());
            reply(src, Text.literal(String.format("%d. §6%s§r — %s", i + 1, e.playerName(), t)));
        }
        return 1;
    }

    private static int showLegends(ServerCommandSource src) {
        var legends = CobbleCapMod.INSTANCE.getLegendsStorage().getAll();
        if (legends.isEmpty()) {
            reply(src, Text.translatable("cobblecap.msg.legends_empty")
                    .formatted(Formatting.YELLOW));
            return 1;
        }
        reply(src, Text.translatable("cobblecap.msg.legends_header", legends.size())
                .formatted(Formatting.GOLD, Formatting.BOLD));

        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("dd/MM/yyyy");
        for (int i = 0; i < legends.size(); i++) {
            var e = legends.get(i);
            String when = fmt.format(new java.util.Date(e.completedAt()));
            if (e.firstEver()) {
                reply(src, Text.translatable("cobblecap.msg.legends_entry_first",
                        e.playerName(), when).formatted(Formatting.LIGHT_PURPLE));
            } else {
                reply(src, Text.translatable("cobblecap.msg.legends_entry",
                        i + 1, e.playerName(), when));
            }
        }
        return 1;
    }

    // ── Player command handlers ───────────────────────────────────────────────

    /**
     * /cobblecap status <player> — exhaustive admin debug dump.
     * Dumps EVERYTHING relevant to diagnose why a player can/can't progress:
     * CobbleCap state, all scoreboards, command tags, position, advancements (cobblecap+cobblemon),
     * champion-by-champion gating analysis, last challenge times, HUD config, system enabled.
     */
    private static int showAdminStatus(ServerCommandSource src, ServerPlayerEntity target) {
        CapStorage storage = CobbleCapMod.INSTANCE.getCapStorage();
        CapConfig config = CobbleCapMod.INSTANCE.getCapConfig();
        Optional<PlayerCapData> dataOpt = storage.getCapData(target.getUuid());

        String name = target.getName().getString();
        UUID uuid = target.getUuid();
        if (dataOpt.isEmpty()) {
            src.sendError(Text.translatable("cobblecap.msg.player_not_found", name)
                    .formatted(Formatting.RED));
            return 0;
        }

        PlayerCapData data = dataOpt.get();

        // ── Header ─────────────────────────────────────────────────────────────
        reply(src, Text.literal("═════ CobbleCap status — " + name + " ═════")
                .formatted(Formatting.GOLD));

        // ── Identity & system ──────────────────────────────────────────────────
        reply(src, Text.literal("UUID: ").formatted(Formatting.GRAY)
                .append(Text.literal(uuid.toString()).formatted(Formatting.WHITE)));
        reply(src, Text.literal("System enabled: ").formatted(Formatting.GRAY)
                .append(Text.literal(String.valueOf(config.isEnabled()))
                        .formatted(config.isEnabled() ? Formatting.GREEN : Formatting.RED)));
        reply(src, Text.literal("Game mode: ").formatted(Formatting.GRAY)
                .append(Text.literal(target.interactionManager.getGameMode().getName())
                        .formatted(Formatting.WHITE)));
        reply(src, Text.literal("Permission level: ").formatted(Formatting.GRAY)
                .append(Text.literal(String.valueOf(getPermissionLevel(target)))
                        .formatted(Formatting.WHITE)));
        reply(src, Text.literal("Dimension: ").formatted(Formatting.GRAY)
                .append(Text.literal(target.getServerWorld().getRegistryKey().getValue().toString())
                        .formatted(Formatting.WHITE)));
        reply(src, Text.literal("Position: ").formatted(Formatting.GRAY)
                .append(Text.literal(String.format("%.1f %.1f %.1f",
                        target.getX(), target.getY(), target.getZ())).formatted(Formatting.WHITE)));

        // ── CobbleCap state ────────────────────────────────────────────────────
        reply(src, Text.literal("─── Cap state ───").formatted(Formatting.GOLD));
        reply(src, Text.literal("Cap level: ").formatted(Formatting.GRAY)
                .append(Text.literal(String.valueOf(data.getCapLevel())).formatted(Formatting.GREEN)));
        reply(src, Text.literal("XP locked: ").formatted(Formatting.GRAY)
                .append(Text.literal(String.valueOf(data.isExpLocked()))
                        .formatted(data.isExpLocked() ? Formatting.RED : Formatting.WHITE)));
        reply(src, Text.literal("HUD: ").formatted(Formatting.GRAY)
                .append(Text.literal((data.isHudVisible() ? "on" : "off")
                        + " @ pos " + data.getHudPosition()).formatted(Formatting.WHITE)));
        reply(src, Text.literal("Last cap change: ").formatted(Formatting.GRAY)
                .append(Text.literal(new java.util.Date(data.getLastCapChange()).toString())
                        .formatted(Formatting.WHITE)));

        // ── Champions beaten ───────────────────────────────────────────────────
        var allChamps = CobbleCapMod.INSTANCE.getChampionConfig().getChampions();
        reply(src, Text.literal("─── Champions ("
                + data.getChampionsBeaten().size() + "/" + allChamps.size() + " beaten) ───")
                .formatted(Formatting.GOLD));
        for (ChampionEntry c : allChamps) {
            boolean beaten = data.getChampionsBeaten().contains(c.id());
            boolean chBeaten = data.getChallengeBeaten().contains(c.id());
            String marker = beaten ? "✓" : "✗";
            String chMarker = chBeaten ? " ✦" : "";
            Formatting color = beaten ? Formatting.GREEN : Formatting.DARK_GRAY;
            reply(src, Text.literal("  " + marker + " " + c.id() + " (" + c.displayName()
                    + ", cap=" + c.capGranted() + ")" + chMarker).formatted(color));
        }

        // ── Next champion gating ───────────────────────────────────────────────
        Optional<ChampionEntry> nextOpt = CobbleCapMod.INSTANCE.getChampionConfig()
                .nextUnbeaten(data.getChampionsBeaten());
        reply(src, Text.literal("─── Next champion gating ───").formatted(Formatting.GOLD));
        if (nextOpt.isPresent()) {
            ChampionEntry champ = nextOpt.get();
            reply(src, Text.literal("Next: ").formatted(Formatting.GRAY)
                    .append(Text.literal(champ.id() + " (" + champ.displayName() + ")")
                            .formatted(Formatting.YELLOW)));
            reply(src, Text.literal("  cap granted: " + champ.capGranted()
                    + " | allowRefight: " + champ.allowRefight())
                    .formatted(Formatting.DARK_GRAY));
            reply(src, Text.literal("  victoryQuestId: " + champ.victoryQuestId())
                    .formatted(Formatting.DARK_GRAY));
            reply(src, Text.literal("  challengeId: '" + champ.challengeId() + "'")
                    .formatted(Formatting.DARK_GRAY));

            // Scoreboard requirement
            if (champ.requiredScoreboard() != null && !champ.requiredScoreboard().isEmpty()) {
                int score = ChampionManager.getPlayerScore(target, champ.requiredScoreboard());
                boolean ok = score >= champ.requiredScoreMin();
                reply(src, Text.literal("  scoreboard " + champ.requiredScoreboard() + " >= "
                        + champ.requiredScoreMin() + " (current=" + score + ") ")
                        .formatted(Formatting.DARK_GRAY)
                        .append(Text.literal(ok ? "OK" : "FAIL")
                                .formatted(ok ? Formatting.GREEN : Formatting.RED)));
            } else {
                reply(src, Text.literal("  scoreboard: (none)").formatted(Formatting.DARK_GRAY));
            }
            // Quest requirement
            if (champ.requiredQuestId() != null && !champ.requiredQuestId().isEmpty()) {
                boolean done = ChampionManager.isFtbQuestCompleted(target, champ.requiredQuestId());
                reply(src, Text.literal("  ftb quest " + champ.requiredQuestId() + ": ")
                        .formatted(Formatting.DARK_GRAY)
                        .append(Text.literal(done ? "DONE" : "NOT DONE")
                                .formatted(done ? Formatting.GREEN : Formatting.RED)));
            } else {
                reply(src, Text.literal("  ftb quest: (none)").formatted(Formatting.DARK_GRAY));
            }
            // Pokedex requirement
            if (champ.requiredPokedex() > 0) {
                int caught = ChampionManager.getPokedexCount(target);
                boolean ok = caught >= champ.requiredPokedex();
                reply(src, Text.literal("  pokedex: " + caught + "/" + champ.requiredPokedex() + " ")
                        .formatted(Formatting.DARK_GRAY)
                        .append(Text.literal(ok ? "OK" : "FAIL")
                                .formatted(ok ? Formatting.GREEN : Formatting.RED)));
            } else {
                reply(src, Text.literal("  pokedex: (none)").formatted(Formatting.DARK_GRAY));
            }
        } else {
            reply(src, Text.literal("ALL CHAMPIONS BEATEN").formatted(Formatting.GOLD));
        }

        // ── Last challenge times ───────────────────────────────────────────────
        var lct = data.getLastChallengeTimes();
        reply(src, Text.literal("─── Last challenge times (" + lct.size() + ") ───")
                .formatted(Formatting.GOLD));
        if (lct.isEmpty()) {
            reply(src, Text.literal("  (none)").formatted(Formatting.DARK_GRAY));
        } else {
            for (var e : lct.entrySet()) {
                reply(src, Text.literal("  " + e.getKey() + " → "
                        + new java.util.Date(e.getValue()).toString())
                        .formatted(Formatting.DARK_GRAY));
            }
        }

        // ── ALL scoreboard values for this player ──────────────────────────────
        reply(src, Text.literal("─── All scoreboards ───").formatted(Formatting.GOLD));
        try {
            var scoreboard = target.getServer().getScoreboard();
            int count = 0;
            java.util.List<String> lines = new java.util.ArrayList<>();
            for (var obj : scoreboard.getObjectives()) {
                var sa = scoreboard.getScore(target, obj);
                if (sa != null) {
                    lines.add("  " + obj.getName() + " = " + sa.getScore());
                    count++;
                }
            }
            java.util.Collections.sort(lines);
            for (String line : lines) reply(src, Text.literal(line).formatted(Formatting.AQUA));
            if (count == 0) reply(src, Text.literal("  (none)").formatted(Formatting.DARK_GRAY));
        } catch (Exception e) {
            reply(src, Text.literal("  ERROR: " + e.getMessage()).formatted(Formatting.RED));
        }

        // ── Player command tags ────────────────────────────────────────────────
        reply(src, Text.literal("─── Command tags (" + target.getCommandTags().size() + ") ───")
                .formatted(Formatting.GOLD));
        if (target.getCommandTags().isEmpty()) {
            reply(src, Text.literal("  (none)").formatted(Formatting.DARK_GRAY));
        } else {
            for (String tag : target.getCommandTags()) {
                reply(src, Text.literal("  • " + tag).formatted(Formatting.LIGHT_PURPLE));
            }
        }

        // ── Scoreboard team ────────────────────────────────────────────────────
        var team = target.getScoreboardTeam();
        reply(src, Text.literal("Team: ").formatted(Formatting.GRAY)
                .append(Text.literal(team == null ? "(none)" : team.getName())
                        .formatted(Formatting.WHITE)));

        // ── Cobblecap & cobblemon advancements ─────────────────────────────────
        reply(src, Text.literal("─── Relevant advancements ───").formatted(Formatting.GOLD));
        try {
            var tracker = target.getAdvancementTracker();
            var advLoader = target.getServer().getAdvancementLoader();
            int found = 0;
            java.util.List<String> advLines = new java.util.ArrayList<>();
            for (var entry : advLoader.getAdvancements()) {
                String idStr = entry.id().toString();
                if (idStr.startsWith("cobblecap:") || idStr.startsWith("cobblemon:")) {
                    var progress = tracker.getProgress(entry);
                    boolean done = progress != null && progress.isDone();
                    if (done) {
                        advLines.add("  ✓ " + idStr);
                        found++;
                    }
                }
            }
            java.util.Collections.sort(advLines);
            for (String line : advLines) reply(src, Text.literal(line).formatted(Formatting.GREEN));
            if (found == 0) reply(src, Text.literal("  (none granted)").formatted(Formatting.DARK_GRAY));
            else reply(src, Text.literal("  total: " + found).formatted(Formatting.DARK_GRAY));
        } catch (Exception e) {
            reply(src, Text.literal("  ERROR: " + e.getMessage()).formatted(Formatting.RED));
        }

        // ── Footer ─────────────────────────────────────────────────────────────
        reply(src, Text.literal("═════ end status ═════").formatted(Formatting.GOLD));
        return 1;
    }

    /** Best-effort permission level fetch (works for online players). */
    private static int getPermissionLevel(ServerPlayerEntity p) {
        for (int lvl = 4; lvl >= 0; lvl--) {
            if (p.hasPermissionLevel(lvl)) return lvl;
        }
        return 0;
    }

    /**
     * /cobblecap (no args) — show current cap, next champion, unmet conditions.
     */
    private static int showStatus(ServerCommandSource src) {
        if (!(src.getEntity() instanceof ServerPlayerEntity player)) {
            src.sendError(Text.literal("This command must be run by a player."));
            return 0;
        }

        CapStorage storage = CobbleCapMod.INSTANCE.getCapStorage();
        Optional<PlayerCapData> dataOpt = storage.getCapData(player.getUuid());

        if (dataOpt.isEmpty()) {
            src.sendError(Text.translatable("cobblecap.msg.player_not_found",
                    player.getName().getString()).formatted(Formatting.RED));
            return 0;
        }

        PlayerCapData data = dataOpt.get();

        // Current cap level
        player.sendMessage(
            Text.translatable("cobblecap.msg.status", data.getCapLevel())
                .formatted(Formatting.GREEN),
            false
        );

        // Next champion info
        Optional<ChampionEntry> nextOpt = CobbleCapMod.INSTANCE.getChampionConfig()
                .nextUnbeaten(data.getChampionsBeaten());

        if (nextOpt.isPresent()) {
            ChampionEntry champ = nextOpt.get();
            player.sendMessage(
                Text.translatable("cobblecap.msg.status_next", champ.displayName())
                    .formatted(Formatting.YELLOW),
                false
            );

            // Check conditions and show unmet ones
            if (champ.requiredPokedex() > 0) {
                int caught = ChampionManager.getPokedexCount(player);
                if (caught < champ.requiredPokedex()) {
                    player.sendMessage(
                        Text.literal("  - ").append(
                            Text.translatable("cobblecap.msg.condition_pokedex",
                                caught, champ.requiredPokedex())
                        ).formatted(Formatting.YELLOW),
                        false
                    );
                }
            }

            if (champ.requiredQuestId() != null && !champ.requiredQuestId().isEmpty()) {
                if (!ChampionManager.isFtbQuestCompleted(player, champ.requiredQuestId())) {
                    player.sendMessage(
                        Text.literal("  - ").append(
                            Text.translatable("cobblecap.msg.condition_quest")
                        ).formatted(Formatting.YELLOW),
                        false
                    );
                }
            }
        } else {
            player.sendMessage(
                Text.translatable("cobblecap.msg.status_all_beaten")
                    .formatted(Formatting.GOLD),
                false
            );
        }

        return 1;
    }

    /**
     * /cobblecap hud on|off — toggle HUD visibility.
     */
    private static int hudToggle(ServerCommandSource src, boolean visible) {
        if (!(src.getEntity() instanceof ServerPlayerEntity player)) {
            src.sendError(Text.literal("This command must be run by a player."));
            return 0;
        }

        CapStorage storage = CobbleCapMod.INSTANCE.getCapStorage();
        Optional<PlayerCapData> dataOpt = storage.getCapData(player.getUuid());

        if (dataOpt.isEmpty()) {
            src.sendError(Text.translatable("cobblecap.msg.player_not_found",
                    player.getName().getString()).formatted(Formatting.RED));
            return 0;
        }

        PlayerCapData data = dataOpt.get();

        if (data.isHudVisible() == visible) {
            player.sendMessage(
                Text.translatable(visible ? "cobblecap.msg.hud_already_on" : "cobblecap.msg.hud_already_off")
                    .formatted(Formatting.YELLOW),
                false
            );
            return 1;
        }

        storage.setHudVisible(player.getUuid(), visible);

        player.sendMessage(
            Text.translatable(visible ? "cobblecap.msg.hud_on" : "cobblecap.msg.hud_off")
                .formatted(Formatting.GREEN),
            false
        );

        CobbleCapMod.syncToClient(player);
        return 1;
    }

    /**
     * /cobblecap hud position <corner> — move HUD to a corner.
     */
    private static int hudPosition(ServerCommandSource src, String corner) {
        if (!(src.getEntity() instanceof ServerPlayerEntity player)) {
            src.sendError(Text.literal("This command must be run by a player."));
            return 0;
        }

        int posInt = switch (corner) {
            case "bottom-right" -> 0;
            case "bottom-left" -> 1;
            case "top-right" -> 2;
            case "top-left" -> 3;
            default -> -1;
        };

        if (posInt == -1) {
            src.sendError(Text.literal("Unknown position: " + corner
                    + ". Use: bottom-right, bottom-left, top-right, top-left"));
            return 0;
        }

        CapStorage storage = CobbleCapMod.INSTANCE.getCapStorage();
        Optional<PlayerCapData> dataOpt = storage.getCapData(player.getUuid());

        if (dataOpt.isEmpty()) {
            src.sendError(Text.translatable("cobblecap.msg.player_not_found",
                    player.getName().getString()).formatted(Formatting.RED));
            return 0;
        }

        storage.setHudPosition(player.getUuid(), posInt);

        player.sendMessage(
            Text.translatable("cobblecap.msg.hud_position", corner)
                .formatted(Formatting.GREEN),
            false
        );

        CobbleCapMod.syncToClient(player);
        return 1;
    }

    /**
     * Revoke all advancements and FTB quests for a champion (normal + challenge).
     * Used by /cobblecap reset to fully revert NPC dialogue state.
     */
    private static void revokeChampionProgress(ServerPlayerEntity player, ChampionEntry champ,
                                                net.minecraft.server.MinecraftServer server) {
        var dispatcher = server.getCommandManager().getDispatcher();
        var silentSrc = server.getCommandSource().withSilent();
        String playerName = player.getName().getString();
        String key = champ.displayName().toLowerCase(java.util.Locale.ROOT).replace(" ", "_");

        // Revoke normal champion advancement
        try {
            dispatcher.execute("advancement revoke " + playerName + " only cobblecap:champion/" + key, silentSrc);
        } catch (Exception ignored) {}

        // Revoke challenge advancement
        try {
            dispatcher.execute("advancement revoke " + playerName + " only cobblecap:challenge/" + key, silentSrc);
        } catch (Exception ignored) {}

        // Reset FTB victory quest
        if (champ.victoryQuestId() != null && !champ.victoryQuestId().isEmpty()) {
            try {
                dispatcher.execute("ftbquests change_progress " + playerName + " reset " + champ.victoryQuestId(), silentSrc);
            } catch (Exception ignored) {}
        }

        // Reset FTB challenge quest
        if (champ.challengeQuestId() != null && !champ.challengeQuestId().isEmpty()) {
            try {
                dispatcher.execute("ftbquests change_progress " + playerName + " reset " + champ.challengeQuestId(), silentSrc);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Sends feedback to the command source, broadcasting to ops.
     */
    private static void reply(ServerCommandSource src, Text msg) {
        src.sendFeedback(() -> msg, true);
    }
}
