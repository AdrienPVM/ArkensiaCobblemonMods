package com.cobblecap.commands;

import com.cobblecap.CobbleCapMod;
import com.cobblecap.data.LegendEntry;
import com.cobblecap.data.StatueStyle;
import com.cobblecap.logic.ChampionManager;
import com.cobblecap.logic.ChampionNpcManager;
import com.cobblecap.logic.ChronoManager;
import com.cobblecap.logic.CobbleCapWebhook;
import com.cobblecap.logic.StatueManager;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * Adds /cobblecap statue ... and /cobblecap chrono ... subtrees to the root
 * Brigadier builder owned by CobbleCapCommand.
 */
public final class StatueCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("cobblecap");

    private StatueCommand() {}

    /** Attach statue, chrono and champion subtrees to an existing /cobblecap builder. */
    public static void attach(LiteralArgumentBuilder<ServerCommandSource> root) {
        root.then(buildStatueTree());
        root.then(buildChronoTree());
        root.then(buildChampionTree());
    }

    // ── STATUE TREE ──────────────────────────────────────────────────────────

    private static LiteralArgumentBuilder<ServerCommandSource> buildStatueTree() {
        var statue = CommandManager.literal("statue").requires(s -> s.hasPermissionLevel(2));

        // /cobblecap statue place <playerName> [style] [scale]
        statue.then(CommandManager.literal("place")
            .then(CommandManager.argument("playerName", StringArgumentType.word())
                .executes(ctx -> doPlace(ctx.getSource(), StringArgumentType.getString(ctx, "playerName"),
                        StatueStyle.NATURAL, 1.0))
                .then(CommandManager.argument("style", StringArgumentType.word())
                    .suggests(StatueCommand::suggestStyles)
                    .executes(ctx -> doPlace(ctx.getSource(),
                            StringArgumentType.getString(ctx, "playerName"),
                            StatueStyle.fromKey(StringArgumentType.getString(ctx, "style")), 1.0))
                    .then(CommandManager.argument("scale", DoubleArgumentType.doubleArg(0.2, 5.0))
                        .executes(ctx -> doPlace(ctx.getSource(),
                                StringArgumentType.getString(ctx, "playerName"),
                                StatueStyle.fromKey(StringArgumentType.getString(ctx, "style")),
                                DoubleArgumentType.getDouble(ctx, "scale")))))));

        // /cobblecap statue place_player <playerName> [style] [scale]
        statue.then(CommandManager.literal("place_player")
            .then(CommandManager.argument("playerName", StringArgumentType.word())
                .executes(ctx -> doPlacePlayer(ctx.getSource(),
                        StringArgumentType.getString(ctx, "playerName"), StatueStyle.NATURAL, 1.0))
                .then(CommandManager.argument("style", StringArgumentType.word())
                    .suggests(StatueCommand::suggestStyles)
                    .executes(ctx -> doPlacePlayer(ctx.getSource(),
                            StringArgumentType.getString(ctx, "playerName"),
                            StatueStyle.fromKey(StringArgumentType.getString(ctx, "style")), 1.0))
                    .then(CommandManager.argument("scale", DoubleArgumentType.doubleArg(0.2, 5.0))
                        .executes(ctx -> doPlacePlayer(ctx.getSource(),
                                StringArgumentType.getString(ctx, "playerName"),
                                StatueStyle.fromKey(StringArgumentType.getString(ctx, "style")),
                                DoubleArgumentType.getDouble(ctx, "scale")))))));

        // /cobblecap statue place_pokemon <playerName> <slot> [style] [scale]
        statue.then(CommandManager.literal("place_pokemon")
            .then(CommandManager.argument("playerName", StringArgumentType.word())
                .then(CommandManager.argument("slot", IntegerArgumentType.integer(1, 6))
                    .executes(ctx -> doPlacePokemon(ctx.getSource(),
                            StringArgumentType.getString(ctx, "playerName"),
                            IntegerArgumentType.getInteger(ctx, "slot") - 1,
                            StatueStyle.NATURAL, 1.0))
                    .then(CommandManager.argument("style", StringArgumentType.word())
                        .suggests(StatueCommand::suggestStyles)
                        .executes(ctx -> doPlacePokemon(ctx.getSource(),
                                StringArgumentType.getString(ctx, "playerName"),
                                IntegerArgumentType.getInteger(ctx, "slot") - 1,
                                StatueStyle.fromKey(StringArgumentType.getString(ctx, "style")), 1.0))
                        .then(CommandManager.argument("scale", DoubleArgumentType.doubleArg(0.2, 5.0))
                            .executes(ctx -> doPlacePokemon(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "playerName"),
                                    IntegerArgumentType.getInteger(ctx, "slot") - 1,
                                    StatueStyle.fromKey(StringArgumentType.getString(ctx, "style")),
                                    DoubleArgumentType.getDouble(ctx, "scale"))))))));

        // /cobblecap statue remove <playerName>
        statue.then(CommandManager.literal("remove")
            .then(CommandManager.argument("playerName", StringArgumentType.word())
                .executes(ctx -> {
                    var src = ctx.getSource();
                    String name = StringArgumentType.getString(ctx, "playerName");
                    int n = mgr().removeAllForLegend(src.getServer(), name);
                    src.sendFeedback(() -> Text.literal("Removed " + n + " statue entit(y/ies) for " + name)
                            .formatted(Formatting.YELLOW), false);
                    return n;
                })));

        // /cobblecap statue remove_slot <playerName> <slot>
        statue.then(CommandManager.literal("remove_slot")
            .then(CommandManager.argument("playerName", StringArgumentType.word())
                .then(CommandManager.argument("slot", StringArgumentType.word())
                    .suggests((c, b) -> { for (String s : new String[]{"player","1","2","3","4","5","6"}) b.suggest(s); return b.buildFuture(); })
                    .executes(ctx -> {
                        var src = ctx.getSource();
                        String name = StringArgumentType.getString(ctx, "playerName");
                        String slot = normalizeSlot(StringArgumentType.getString(ctx, "slot"));
                        int n = mgr().removeSlot(src.getServer(), name, slot);
                        src.sendFeedback(() -> Text.literal("Removed " + n + " entit(y/ies)")
                                .formatted(Formatting.YELLOW), false);
                        return n;
                    }))));

        // /cobblecap statue move <playerName> <slot> <dx> <dy> <dz>
        statue.then(CommandManager.literal("move")
            .then(CommandManager.argument("playerName", StringArgumentType.word())
                .then(CommandManager.argument("slot", StringArgumentType.word())
                    .suggests((c, b) -> { for (String s : new String[]{"player","1","2","3","4","5","6","all"}) b.suggest(s); return b.buildFuture(); })
                    .then(CommandManager.argument("dx", DoubleArgumentType.doubleArg(-100, 100))
                        .then(CommandManager.argument("dy", DoubleArgumentType.doubleArg(-100, 100))
                            .then(CommandManager.argument("dz", DoubleArgumentType.doubleArg(-100, 100))
                                .executes(ctx -> {
                                    var src = ctx.getSource();
                                    String name = StringArgumentType.getString(ctx, "playerName");
                                    String slot = StringArgumentType.getString(ctx, "slot");
                                    double dx = DoubleArgumentType.getDouble(ctx, "dx");
                                    double dy = DoubleArgumentType.getDouble(ctx, "dy");
                                    double dz = DoubleArgumentType.getDouble(ctx, "dz");
                                    int n = mgr().moveSelector(src.getServer(), name,
                                            "all".equalsIgnoreCase(slot) ? null : normalizeSlot(slot),
                                            dx, dy, dz);
                                    src.sendFeedback(() -> Text.literal("Moved " + n + " entit(y/ies)")
                                            .formatted(Formatting.AQUA), false);
                                    return n;
                                })))))));

        // /cobblecap statue rotate <playerName> <slot> <yaw>
        statue.then(CommandManager.literal("rotate")
            .then(CommandManager.argument("playerName", StringArgumentType.word())
                .then(CommandManager.argument("slot", StringArgumentType.word())
                    .suggests((c, b) -> { for (String s : new String[]{"player","1","2","3","4","5","6","all"}) b.suggest(s); return b.buildFuture(); })
                    .then(CommandManager.argument("yaw", FloatArgumentType.floatArg(-360f, 360f))
                        .executes(ctx -> {
                            var src = ctx.getSource();
                            String name = StringArgumentType.getString(ctx, "playerName");
                            String slot = StringArgumentType.getString(ctx, "slot");
                            float yaw = FloatArgumentType.getFloat(ctx, "yaw");
                            int n = mgr().rotateSelector(src.getServer(), name,
                                    "all".equalsIgnoreCase(slot) ? null : normalizeSlot(slot), yaw);
                            src.sendFeedback(() -> Text.literal("Rotated " + n + " entit(y/ies)")
                                    .formatted(Formatting.AQUA), false);
                            return n;
                        })))));

        // /cobblecap statue scale <playerName> <slot> <factor>
        statue.then(CommandManager.literal("scale")
            .then(CommandManager.argument("playerName", StringArgumentType.word())
                .then(CommandManager.argument("slot", StringArgumentType.word())
                    .suggests((c, b) -> { for (String s : new String[]{"player","1","2","3","4","5","6","all"}) b.suggest(s); return b.buildFuture(); })
                    .then(CommandManager.argument("factor", DoubleArgumentType.doubleArg(0.1, 5.0))
                        .executes(ctx -> {
                            var src = ctx.getSource();
                            String name = StringArgumentType.getString(ctx, "playerName");
                            String slot = StringArgumentType.getString(ctx, "slot");
                            double factor = DoubleArgumentType.getDouble(ctx, "factor");
                            int n = mgr().scaleSelector(src.getServer(), name,
                                    "all".equalsIgnoreCase(slot) ? null : normalizeSlot(slot), factor);
                            src.sendFeedback(() -> Text.literal("Scaled " + n + " entit(y/ies)")
                                    .formatted(Formatting.AQUA), false);
                            return n;
                        })))));

        // /cobblecap statue style <playerName> <slot> <style>
        statue.then(CommandManager.literal("style")
            .then(CommandManager.argument("playerName", StringArgumentType.word())
                .then(CommandManager.argument("slot", StringArgumentType.word())
                    .suggests((c, b) -> { for (String s : new String[]{"player","1","2","3","4","5","6","all"}) b.suggest(s); return b.buildFuture(); })
                    .then(CommandManager.argument("style", StringArgumentType.word())
                        .suggests(StatueCommand::suggestStyles)
                        .executes(ctx -> {
                            var src = ctx.getSource();
                            String name = StringArgumentType.getString(ctx, "playerName");
                            String slot = StringArgumentType.getString(ctx, "slot");
                            StatueStyle style = StatueStyle.fromKey(StringArgumentType.getString(ctx, "style"));
                            int n = mgr().restyleSelector(src.getServer(), name,
                                    "all".equalsIgnoreCase(slot) ? null : normalizeSlot(slot), style);
                            src.sendFeedback(() -> Text.literal("Restyled " + n + " entit(y/ies) -> " + style.key())
                                    .formatted(Formatting.AQUA), false);
                            return n;
                        })))));

        // /cobblecap statue tp <player> — teleport to a diorama's anchor
        statue.then(CommandManager.literal("tp")
            .then(CommandManager.argument("playerName", StringArgumentType.word())
                .executes(ctx -> {
                    var src = ctx.getSource();
                    ServerPlayerEntity caster = src.getPlayer();
                    if (caster == null) { src.sendError(Text.literal("Must be run by a player")); return 0; }
                    String name = StringArgumentType.getString(ctx, "playerName");
                    var infos = mgr().list(src.getServer());
                    StatueManager.DioramaInfo target = null;
                    for (var i : infos) if (i.playerName.equalsIgnoreCase(name)) { target = i; break; }
                    if (target == null) {
                        src.sendError(Text.literal("No diorama for " + name));
                        return 0;
                    }
                    final StatueManager.DioramaInfo found = target;
                    var world = src.getServer().getWorld(net.minecraft.registry.RegistryKey.of(
                            net.minecraft.registry.RegistryKeys.WORLD,
                            net.minecraft.util.Identifier.of(found.world)));
                    if (world == null) {
                        src.sendError(Text.literal("World not loaded: " + found.world));
                        return 0;
                    }
                    caster.teleport(world, found.x, found.y + 1, found.z,
                            java.util.Set.of(), caster.getYaw(), caster.getPitch());
                    src.sendFeedback(() -> Text.literal("TP'd to diorama of " + found.playerName)
                            .formatted(Formatting.AQUA), false);
                    return 1;
                })));

        // /cobblecap statue stats
        statue.then(CommandManager.literal("stats").executes(ctx -> {
            var src = ctx.getSource();
            var infos = mgr().list(src.getServer());
            int totalEntities = 0;
            java.util.Map<String, Integer> perStyle = new java.util.HashMap<>();
            for (var i : infos) totalEntities += i.count;
            // Count entities by style tag
            for (var w : src.getServer().getWorlds()) {
                for (var e : w.iterateEntities()) {
                    if (!e.getCommandTags().contains(StatueManager.TAG_MASTER)) continue;
                    for (String t : e.getCommandTags()) {
                        if (t.startsWith(StatueManager.TAG_STYLE_PREFIX)) {
                            String style = t.substring(StatueManager.TAG_STYLE_PREFIX.length());
                            perStyle.merge(style, 1, Integer::sum);
                            break;
                        }
                    }
                }
            }
            src.sendFeedback(() -> Text.literal("─── Statue Stats ───").formatted(Formatting.GOLD), false);
            int finalTotal = totalEntities;
            src.sendFeedback(() -> Text.literal("Total dioramas: " + infos.size()
                    + " · total entities: " + finalTotal), false);
            for (var entry : perStyle.entrySet()) {
                src.sendFeedback(() -> Text.literal("  · " + entry.getKey() + ": " + entry.getValue()), false);
            }
            return infos.size();
        }));

        // /cobblecap statue styles — list available style keys
        statue.then(CommandManager.literal("styles").executes(ctx -> {
            var src = ctx.getSource();
            StringBuilder sb = new StringBuilder("Available styles: ");
            for (StatueStyle s : StatueStyle.values()) sb.append(s.key()).append(' ');
            src.sendFeedback(() -> Text.literal(sb.toString().trim()).formatted(Formatting.AQUA), false);
            return StatueStyle.values().length;
        }));

        // /cobblecap statue list
        statue.then(CommandManager.literal("list").executes(ctx -> {
            var src = ctx.getSource();
            List<StatueManager.DioramaInfo> infos = mgr().list(src.getServer());
            if (infos.isEmpty()) {
                src.sendFeedback(() -> Text.literal("No diorama currently placed.").formatted(Formatting.YELLOW), false);
                return 0;
            }
            src.sendFeedback(() -> Text.literal("─── Placed Dioramas (" + infos.size() + ") ───").formatted(Formatting.GOLD), false);
            for (var info : infos) {
                src.sendFeedback(() -> Text.literal(String.format("  %s — %s @ (%.1f, %.1f, %.1f) [%d entit(y/ies)]",
                        info.playerName, info.world, info.x, info.y, info.z, info.count)), false);
            }
            return infos.size();
        }));

        // /cobblecap statue test [style] [scale] — capture caster's team and stage a diorama
        statue.then(CommandManager.literal("test")
            .executes(ctx -> doTest(ctx.getSource(), StatueStyle.NATURAL, 1.0))
            .then(CommandManager.argument("style", StringArgumentType.word())
                .suggests(StatueCommand::suggestStyles)
                .executes(ctx -> doTest(ctx.getSource(),
                        StatueStyle.fromKey(StringArgumentType.getString(ctx, "style")), 1.0))
                .then(CommandManager.argument("scale", DoubleArgumentType.doubleArg(0.2, 5.0))
                    .executes(ctx -> doTest(ctx.getSource(),
                            StatueStyle.fromKey(StringArgumentType.getString(ctx, "style")),
                            DoubleArgumentType.getDouble(ctx, "scale"))))));

        // /cobblecap statue place_current [style] [scale] — dynamic diorama bound to the current champion.
        // Re-spawned with the new champion's skin and team every time the trainer morphs.
        statue.then(CommandManager.literal("place_current")
            .executes(ctx -> doPlaceCurrent(ctx.getSource(), StatueStyle.NATURAL, 1.0))
            .then(CommandManager.argument("style", StringArgumentType.word())
                .suggests(StatueCommand::suggestStyles)
                .executes(ctx -> doPlaceCurrent(ctx.getSource(),
                        StatueStyle.fromKey(StringArgumentType.getString(ctx, "style")), 1.0))
                .then(CommandManager.argument("scale", DoubleArgumentType.doubleArg(0.2, 5.0))
                    .executes(ctx -> doPlaceCurrent(ctx.getSource(),
                            StatueStyle.fromKey(StringArgumentType.getString(ctx, "style")),
                            DoubleArgumentType.getDouble(ctx, "scale"))))));

        // /cobblecap statue place_current_player [style] [scale] — dynamic player-only
        // diorama. Like place_current but the Pokémon row is omitted, both at initial
        // placement AND on every subsequent champion-change refresh.
        statue.then(CommandManager.literal("place_current_player")
            .executes(ctx -> doPlaceCurrentPlayer(ctx.getSource(), StatueStyle.NATURAL, 1.0))
            .then(CommandManager.argument("style", StringArgumentType.word())
                .suggests(StatueCommand::suggestStyles)
                .executes(ctx -> doPlaceCurrentPlayer(ctx.getSource(),
                        StatueStyle.fromKey(StringArgumentType.getString(ctx, "style")), 1.0))
                .then(CommandManager.argument("scale", DoubleArgumentType.doubleArg(0.2, 5.0))
                    .executes(ctx -> doPlaceCurrentPlayer(ctx.getSource(),
                            StatueStyle.fromKey(StringArgumentType.getString(ctx, "style")),
                            DoubleArgumentType.getDouble(ctx, "scale"))))));

        // /cobblecap statue wipe_all — aggressive cleanup of ALL cobblecap statues
        // (including invisible armor stands whose doppelganger never re-attached).
        // Tag-based scan, so it catches orphan trackers from prior restarts. Also
        // clears the dynamic-champion anchor list so /place_current* starts fresh.
        // Op-level only — this is a one-shot reset, not an "undo".
        statue.then(CommandManager.literal("wipe_all")
            .requires(s -> s.hasPermissionLevel(2))
            .executes(ctx -> {
                var src = ctx.getSource();
                int n = mgr().wipeAllStatues(src.getServer());
                src.sendFeedback(() -> Text.literal("Wiped " + n + " statue entit(y/ies) and cleared all anchors")
                        .formatted(Formatting.GOLD), true);
                return n;
            }));

        // /cobblecap statue test_clear — remove ONLY test-tagged statues + test legend rows.
        // Real Maître de la Ligue entries / statues for the caster are left untouched.
        statue.then(CommandManager.literal("test_clear").executes(ctx -> {
            var src = ctx.getSource();
            ServerPlayerEntity p = src.getPlayer();
            if (p == null) {
                src.sendError(Text.literal("Must be run by a player"));
                return 0;
            }
            int removed = mgr().removeTestsForLegend(src.getServer(), p.getName().getString());
            int wiped = CobbleCapMod.INSTANCE.getLegendsStorage().removeTestEntriesFor(p.getUuid());
            src.sendFeedback(() -> Text.literal("Cleared " + wiped + " test legend(s) and " + removed + " test statue entit(y/ies)")
                    .formatted(Formatting.GREEN), false);
            return wiped + removed;
        }));

        return statue;
    }

    // ── CHRONO TREE ──────────────────────────────────────────────────────────

    private static LiteralArgumentBuilder<ServerCommandSource> buildChronoTree() {
        var chrono = CommandManager.literal("chrono").requires(s -> s.hasPermissionLevel(2));

        // /cobblecap chrono start <player>
        // Force-restart semantics: ALWAYS resets any existing chrono. This makes
        // the entry-cmd idempotent and predictable — re-entering a dimension
        // restarts the timer instead of silently keeping an old one running.
        chrono.then(CommandManager.literal("start")
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> {
                    var src = ctx.getSource();
                    ServerPlayerEntity p = EntityArgumentType.getPlayer(ctx, "player");
                    Long previousStart = CobbleCapMod.INSTANCE.getChronoManager().forceRestart(p.getUuid());
                    if (previousStart != null) {
                        long previousElapsed = System.currentTimeMillis() - previousStart;
                        src.sendFeedback(() -> Text.literal("Chrono RESTARTED for " + p.getName().getString()
                                + " (was at " + ChronoManager.formatDuration(previousElapsed) + ")")
                                .formatted(Formatting.GOLD), true);
                        LOGGER.info("[CobbleCap] Chrono RESTARTED for {} (previous run was at {})",
                                p.getName().getString(),
                                ChronoManager.formatDuration(previousElapsed));
                    } else {
                        src.sendFeedback(() -> Text.literal("Chrono started for " + p.getName().getString())
                                .formatted(Formatting.AQUA), true);
                        LOGGER.info("[CobbleCap] Chrono started for {}", p.getName().getString());
                    }
                    p.sendMessage(Text.translatable("cobblecap.msg.chrono_started")
                            .formatted(Formatting.AQUA), false);
                    return 1;
                })));

        // /cobblecap chrono start_keep <player>
        // Old behavior — start ONLY if no chrono is currently running.
        // Useful as an entry-cmd if you want re-entry to NOT reset the timer.
        chrono.then(CommandManager.literal("start_keep")
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> {
                    var src = ctx.getSource();
                    ServerPlayerEntity p = EntityArgumentType.getPlayer(ctx, "player");
                    boolean started = CobbleCapMod.INSTANCE.getChronoManager().start(p.getUuid());
                    if (started) {
                        src.sendFeedback(() -> Text.literal("Chrono started for " + p.getName().getString())
                                .formatted(Formatting.AQUA), true);
                        p.sendMessage(Text.translatable("cobblecap.msg.chrono_started")
                                .formatted(Formatting.AQUA), false);
                    } else {
                        long elapsed = CobbleCapMod.INSTANCE.getChronoManager().elapsedFor(p.getUuid());
                        src.sendFeedback(() -> Text.literal("Chrono already running for "
                                + p.getName().getString() + " (elapsed: " + ChronoManager.formatDuration(elapsed) + ")")
                                .formatted(Formatting.YELLOW), false);
                    }
                    return started ? 1 : 0;
                })));

        // /cobblecap chrono stop <player>
        chrono.then(CommandManager.literal("stop")
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> {
                    var src = ctx.getSource();
                    ServerPlayerEntity p = EntityArgumentType.getPlayer(ctx, "player");
                    long elapsed = CobbleCapMod.INSTANCE.getChronoManager().stop(p.getUuid());
                    if (elapsed < 0) {
                        src.sendError(Text.literal("No active chrono for " + p.getName().getString()));
                        return 0;
                    }
                    return finalizeChrono(src.getServer(), p, elapsed);
                })));

        // /cobblecap chrono cancel <player> — discard without firing legend update
        chrono.then(CommandManager.literal("cancel")
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> {
                    var src = ctx.getSource();
                    ServerPlayerEntity p = EntityArgumentType.getPlayer(ctx, "player");
                    boolean cancelled = CobbleCapMod.INSTANCE.getChronoManager().cancel(p.getUuid());
                    src.sendFeedback(() -> Text.literal(cancelled
                            ? "Chrono cancelled for " + p.getName().getString()
                            : "No active chrono for " + p.getName().getString())
                            .formatted(cancelled ? Formatting.YELLOW : Formatting.RED), false);
                    return cancelled ? 1 : 0;
                })));

        // /cobblecap chrono wipe <player> — cancel active chrono + remove ALL
        // legend entries (test AND real) for the player. Use for test cleanup
        // when chrono stop accidentally created a real Maître de la Ligue.
        chrono.then(CommandManager.literal("wipe")
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> {
                    var src = ctx.getSource();
                    ServerPlayerEntity p = EntityArgumentType.getPlayer(ctx, "player");
                    boolean cancelled = CobbleCapMod.INSTANCE.getChronoManager().cancel(p.getUuid());
                    int wiped = CobbleCapMod.INSTANCE.getLegendsStorage().removeAllEntriesFor(p.getUuid());
                    src.sendFeedback(() -> Text.literal(String.format(
                            "Wiped %s — cancelled chrono: %s, removed legend entries: %d",
                            p.getName().getString(), cancelled ? "yes" : "no", wiped))
                            .formatted(Formatting.YELLOW), true);
                    return wiped + (cancelled ? 1 : 0);
                })));

        // /cobblecap chrono list — show all active chronos with their elapsed time
        chrono.then(CommandManager.literal("list").executes(ctx -> {
            var src = ctx.getSource();
            var chronoMgr = CobbleCapMod.INSTANCE.getChronoManager();
            int count = chronoMgr.activeCount();
            if (count == 0) {
                src.sendFeedback(() -> Text.literal("No active chronos").formatted(Formatting.GRAY), false);
                return 0;
            }
            src.sendFeedback(() -> Text.literal("─── Active chronos (" + count + ") ───")
                    .formatted(Formatting.AQUA), false);
            for (ServerPlayerEntity p : src.getServer().getPlayerManager().getPlayerList()) {
                long elapsed = chronoMgr.elapsedFor(p.getUuid());
                if (elapsed >= 0) {
                    src.sendFeedback(() -> Text.literal("  · " + p.getName().getString()
                            + " — " + ChronoManager.formatDuration(elapsed)), false);
                }
            }
            return count;
        }));

        // /cobblecap chrono status [player]
        chrono.then(CommandManager.literal("status")
            .executes(ctx -> {
                int count = CobbleCapMod.INSTANCE.getChronoManager().activeCount();
                ctx.getSource().sendFeedback(() -> Text.literal(
                        count + " active chrono(s)").formatted(Formatting.GRAY), false);
                return count;
            })
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> {
                    var src = ctx.getSource();
                    ServerPlayerEntity p = EntityArgumentType.getPlayer(ctx, "player");
                    long elapsed = CobbleCapMod.INSTANCE.getChronoManager().elapsedFor(p.getUuid());
                    if (elapsed < 0) {
                        src.sendFeedback(() -> Text.literal("No active chrono").formatted(Formatting.YELLOW), false);
                    } else {
                        src.sendFeedback(() -> Text.literal(
                                p.getName().getString() + " — " + ChronoManager.formatDuration(elapsed))
                                .formatted(Formatting.AQUA), false);
                    }
                    return elapsed >= 0 ? 1 : 0;
                })));

        return chrono;
    }

    /**
     * Finalize a chrono completion. Updates the player's legend with bestTime,
     * fires the broadcast + Discord webhook with the chrono time, and returns
     * status code (1 = success).
     * <p>
     * STRICT requirement (user 2026-05-17): a player only becomes Maître de
     * la Ligue when they've actually beaten champion_regis_challenge in this
     * run. If the chrono stop is dispatched without that requirement (admin
     * error, misconfigured FTB reward, etc.), we update bestTime for an
     * existing legend but refuse to PROMOTE a new one — beating Giovanni
     * with a chrono running should never crown someone.
     */
    private static int finalizeChrono(net.minecraft.server.MinecraftServer server,
                                       ServerPlayerEntity p, long elapsed) {
        UUID uuid = p.getUuid();
        var legends = CobbleCapMod.INSTANCE.getLegendsStorage();
        boolean isNewLegend = !legends.hasLegend(uuid);
        boolean firstEver = isNewLegend && legends.isEmpty();

        // Hard gate: must have actually beaten Régis (in either normal-victory
        // or challenge mode) to be promoted. Doesn't apply when updating an
        // EXISTING legend's bestTime. Régis routes through handleVictory in
        // the current config (challengeId=""), so championsBeaten is the
        // primary record; we also accept challengeBeaten for completeness.
        if (isNewLegend) {
            var capData = CobbleCapMod.INSTANCE.getCapStorage().getCapData(uuid);
            boolean regisBeaten = capData.isPresent()
                    && (capData.get().getChampionsBeaten().contains("champion_regis_challenge")
                        || capData.get().getChallengeBeaten().contains("champion_regis_challenge"));
            if (!regisBeaten) {
                var src = server.getPlayerManager().getPlayerList().isEmpty() ? null : p;
                LOGGER.warn("[CobbleCap] Chrono stop for {} REFUSED — champion_regis_challenge not beaten this run; bestTime ignored, no legend created",
                        p.getName().getString());
                p.sendMessage(Text.literal(
                        "⚠ Le chrono a été arrêté mais tu n'as pas (encore) battu Régis Challenger. Aucun statut de Maître de la Ligue accordé.")
                        .formatted(Formatting.YELLOW), false);
                // Refresh boards anyway so admin sees the timer was processed
                com.cobblecap.logic.Conseil4Boards.rebuild(server);
                return 0;
            }
        }

        LegendEntry entry;
        if (isNewLegend) {
            // No legend yet — chrono stop fired before the challenge milestone hook,
            // or the player has been re-runinng. Snapshot team now.
            List<LegendEntry.TeamMember> team = ChampionManager.snapshotPlayerTeam(p);
            entry = new LegendEntry(uuid, p.getName().getString(), System.currentTimeMillis(),
                    firstEver, false, elapsed, team);
            legends.add(entry);
        } else {
            // Existing legend — only update bestTime if this run is faster (or no time recorded yet)
            LegendEntry existing = legends.findLatestForPlayerName(p.getName().getString());
            if (existing == null) {
                // Defensive: shouldn't happen since hasLegend returned true
                List<LegendEntry.TeamMember> team = ChampionManager.snapshotPlayerTeam(p);
                entry = new LegendEntry(uuid, p.getName().getString(), System.currentTimeMillis(),
                        firstEver, false, elapsed, team);
                legends.add(entry);
            } else if (existing.bestTimeMs() == 0L || elapsed < existing.bestTimeMs()) {
                entry = existing.withBestTime(elapsed);
                // Replace the entry in-place: remove old and add updated (CopyOnWriteArrayList safe)
                List<LegendEntry> all = legends.getAllIncludingTests();
                for (int i = 0; i < all.size(); i++) {
                    if (all.get(i).uuid().equals(existing.uuid())
                            && all.get(i).completedAt() == existing.completedAt()) {
                        // Replace via storage (use removeTest fallback path: we'll just add and dedupe later)
                        legends.replaceEntry(i, entry);
                        break;
                    }
                }
            } else {
                // Slower run — keep old bestTime but acknowledge
                entry = existing;
                server.getPlayerManager().broadcast(
                        Text.literal("⏱ " + p.getName().getString() + " a fini en "
                                + ChronoManager.formatDuration(elapsed) + " (record perso : "
                                + ChronoManager.formatDuration(existing.bestTimeMs()) + ")")
                                .formatted(Formatting.GRAY), false);
                return 1;
            }
        }

        // Broadcast
        String timeStr = ChronoManager.formatDuration(elapsed);
        String key = firstEver ? "cobblecap.msg.league_master_first_chrono"
                                : "cobblecap.msg.league_master_chrono";
        server.getPlayerManager().broadcast(
                Text.translatable(key, p.getName().getString(), timeStr)
                        .formatted(Formatting.GOLD, Formatting.BOLD),
                false);

        // Webhook with chrono
        CobbleCapWebhook.sendLeagueMaster(entry);

        // Queue the RCT trainer morph (champion_regis_challenge) — fires when no
        // Cobblemon battle is active so an in-progress fight is never disturbed.
        CobbleCapMod.INSTANCE.getRctMorpher().request(p, elapsed);

        // Refresh Conseil 4 vanilla scoreboards + CCS-compatible JSON exports
        com.cobblecap.logic.Conseil4Boards.rebuild(server);

        LOGGER.info("[CobbleCap] Chrono stopped for {} ({}) — firstEver={}, isNewLegend={}, bestTimeUpdated={}",
                p.getName().getString(), timeStr, firstEver, isNewLegend, entry.bestTimeMs() == elapsed);
        return 1;
    }

    // ── Handlers ──────────────────────────────────────────────────────────

    private static int doPlace(ServerCommandSource src, String playerName,
                                StatueStyle style, double scale) {
        ServerPlayerEntity caster = src.getPlayer();
        if (caster == null) { src.sendError(Text.literal("Must be run by a player")); return 0; }
        LegendEntry legend = CobbleCapMod.INSTANCE.getLegendsStorage()
                .findLatestForPlayerName(playerName, /*includeTests=*/false);
        if (legend == null) {
            src.sendError(Text.literal("No legend found for player: " + playerName));
            return 0;
        }
        StatueManager.PlaceResult res = mgr().placeFullDiorama(caster, legend, style, scale);
        src.sendFeedback(() -> Text.literal(res.message()).formatted(res.ok() ? Formatting.GREEN : Formatting.RED), false);
        return res.ok() ? res.entitiesSpawned() : 0;
    }

    private static int doPlacePlayer(ServerCommandSource src, String playerName,
                                       StatueStyle style, double scale) {
        ServerPlayerEntity caster = src.getPlayer();
        if (caster == null) { src.sendError(Text.literal("Must be run by a player")); return 0; }
        LegendEntry legend = CobbleCapMod.INSTANCE.getLegendsStorage()
                .findLatestForPlayerName(playerName, /*includeTests=*/false);
        if (legend == null) {
            src.sendError(Text.literal("No legend found for player: " + playerName));
            return 0;
        }
        StatueManager.PlaceResult res = mgr().placePlayerOnly(caster, legend, style, scale);
        src.sendFeedback(() -> Text.literal(res.message()).formatted(res.ok() ? Formatting.GREEN : Formatting.RED), false);
        return res.ok() ? 1 : 0;
    }

    private static int doPlacePokemon(ServerCommandSource src, String playerName, int slotZeroBased,
                                        StatueStyle style, double scale) {
        ServerPlayerEntity caster = src.getPlayer();
        if (caster == null) { src.sendError(Text.literal("Must be run by a player")); return 0; }
        LegendEntry legend = CobbleCapMod.INSTANCE.getLegendsStorage()
                .findLatestForPlayerName(playerName, /*includeTests=*/false);
        if (legend == null) {
            src.sendError(Text.literal("No legend found for player: " + playerName));
            return 0;
        }
        StatueManager.PlaceResult res = mgr().placePokemonOnly(caster, legend, slotZeroBased, style, scale);
        src.sendFeedback(() -> Text.literal(res.message()).formatted(res.ok() ? Formatting.GREEN : Formatting.RED), false);
        return res.ok() ? 1 : 0;
    }

    private static int doPlaceCurrent(ServerCommandSource src, StatueStyle style, double scale) {
        ServerPlayerEntity caster = src.getPlayer();
        if (caster == null) { src.sendError(Text.literal("Must be run by a player")); return 0; }
        StatueManager.PlaceResult res = mgr().placeCurrentChampionDiorama(caster, style, scale);
        src.sendFeedback(() -> Text.literal("[CURRENT] " + res.message()
                + " — will auto-refresh when the champion morphs")
                .formatted(res.ok() ? Formatting.GREEN : Formatting.RED), false);
        return res.entitiesSpawned();
    }

    private static int doPlaceCurrentPlayer(ServerCommandSource src, StatueStyle style, double scale) {
        ServerPlayerEntity caster = src.getPlayer();
        if (caster == null) { src.sendError(Text.literal("Must be run by a player")); return 0; }
        StatueManager.PlaceResult res = mgr().placeCurrentChampionPlayerOnly(caster, style, scale);
        src.sendFeedback(() -> Text.literal("[CURRENT player-only] " + res.message()
                + " — auto-refreshes player skin only when champion morphs (no Pokémon)")
                .formatted(res.ok() ? Formatting.GREEN : Formatting.RED), false);
        return res.entitiesSpawned();
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildChampionTree() {
        var champ = CommandManager.literal("champion").requires(s -> s.hasPermissionLevel(2));

        // /cobblecap champion morph_now <player> — admin override: morph the trainer immediately.
        champ.then(CommandManager.literal("morph_now")
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> {
                    var src = ctx.getSource();
                    ServerPlayerEntity p = EntityArgumentType.getPlayer(ctx, "player");
                    CobbleCapMod.INSTANCE.getRctMorpher().request(p, 0L);
                    src.sendFeedback(() -> Text.literal(
                            "Queued morph of champion_regis_challenge to " + p.getName().getString()
                                    + " — applied when no battle is active")
                            .formatted(Formatting.AQUA), true);
                    return 1;
                })));

        // /cobblecap champion bind_npc <uuid> — bind a Cobblemon NPC by UUID (fallback Path A).
        champ.then(CommandManager.literal("bind_npc")
            .then(CommandManager.argument("uuid", StringArgumentType.word())
                .executes(ctx -> {
                    var src = ctx.getSource();
                    java.util.UUID uuid;
                    try { uuid = java.util.UUID.fromString(StringArgumentType.getString(ctx, "uuid")); }
                    catch (IllegalArgumentException ex) {
                        src.sendError(Text.literal("Invalid UUID")); return 0;
                    }
                    var res = CobbleCapMod.INSTANCE.getChampionNpcManager().bindByUuid(src.getServer(), uuid);
                    src.sendFeedback(() -> Text.literal("[NPC] " + res.detail())
                            .formatted(res.result() == ChampionNpcManager.MorphResult.OK
                                    ? Formatting.GREEN : Formatting.RED), false);
                    return res.result() == ChampionNpcManager.MorphResult.OK ? 1 : 0;
                })));

        // /cobblecap champion bind_npc_closest — bind the closest Cobblemon NPC.
        champ.then(CommandManager.literal("bind_npc_closest")
            .executes(ctx -> {
                var src = ctx.getSource();
                ServerPlayerEntity p = src.getPlayer();
                if (p == null) { src.sendError(Text.literal("Must be run by a player")); return 0; }
                var res = CobbleCapMod.INSTANCE.getChampionNpcManager().bindClosest(p);
                src.sendFeedback(() -> Text.literal("[NPC] " + res.detail())
                        .formatted(res.result() == ChampionNpcManager.MorphResult.OK
                                ? Formatting.GREEN : Formatting.RED), false);
                return res.result() == ChampionNpcManager.MorphResult.OK ? 1 : 0;
            }));

        // /cobblecap champion unbind_npc
        champ.then(CommandManager.literal("unbind_npc").executes(ctx -> {
            CobbleCapMod.INSTANCE.getChampionNpcState().unbind();
            ctx.getSource().sendFeedback(() -> Text.literal("Champion NPC unbound")
                    .formatted(Formatting.YELLOW), false);
            return 1;
        }));

        // /cobblecap champion status
        champ.then(CommandManager.literal("status").executes(ctx -> {
            var st = CobbleCapMod.INSTANCE.getChampionNpcState();
            String npcBound = st.isBound() ? (st.getNpcEntityUuid() + " in " + st.getDimensionId()) : "(none)";
            String lastChamp = st.getLastChampionName() == null ? "(none)" : st.getLastChampionName();
            ctx.getSource().sendFeedback(() -> Text.literal("Champion NPC: " + npcBound
                    + "\nLast morphed champion: " + lastChamp), false);
            return 1;
        }));

        // /cobblecap champion refresh_dioramas — admin trigger to re-spawn current-champion dioramas.
        champ.then(CommandManager.literal("refresh_dioramas").executes(ctx -> {
            var src = ctx.getSource();
            mgr().refreshCurrentChampionDioramas(src.getServer());
            src.sendFeedback(() -> Text.literal("Current-champion dioramas refreshed")
                    .formatted(Formatting.GREEN), false);
            return 1;
        }));

        // /cobblecap champion backup_now — snapshot the current Régis trainer
        // JSON + skin into config/cobblecap_regis_backup/ (no-op if already present).
        champ.then(CommandManager.literal("backup_now").executes(ctx -> {
            var src = ctx.getSource();
            com.cobblecap.logic.RctTrainerMorpher.ensureBackup(src.getServer());
            src.sendFeedback(() -> Text.literal(
                    "Régis backup ensured at config/cobblecap_regis_backup/ — see log for details")
                    .formatted(Formatting.AQUA), true);
            return 1;
        }));

        // /cobblecap champion restore_original — restore Régis from the backup.
        // Restores BOTH the trainer JSON and the skin PNG, then triggers reload.
        champ.then(CommandManager.literal("restore_original").executes(ctx -> {
            var src = ctx.getSource();
            String status = com.cobblecap.logic.RctTrainerMorpher.restoreOriginal(src.getServer());
            src.sendFeedback(() -> Text.literal("Régis restore: " + status)
                    .formatted(Formatting.GOLD), true);
            return 1;
        }));

        return champ;
    }

    private static int doTest(ServerCommandSource src, StatueStyle style, double scale) {
        ServerPlayerEntity caster = src.getPlayer();
        if (caster == null) { src.sendError(Text.literal("Must be run by a player")); return 0; }
        // Snapshot caster's current team
        List<LegendEntry.TeamMember> team = ChampionManager.snapshotPlayerTeam(caster);
        LegendEntry entry = new LegendEntry(caster.getUuid(), caster.getName().getString(),
                System.currentTimeMillis(), /*firstEver=*/false, /*isTest=*/true, /*bestTimeMs=*/0L, team);
        CobbleCapMod.INSTANCE.getLegendsStorage().add(entry);
        StatueManager.PlaceResult res = mgr().placeFullDiorama(caster, entry, style, scale);
        src.sendFeedback(() -> Text.literal("[TEST] Captured team (" + team.size() + " Pokémon) — "
                + res.message() + " — clear with /cobblecap statue test_clear")
                .formatted(Formatting.LIGHT_PURPLE), false);
        return res.entitiesSpawned();
    }

    private static StatueManager mgr() {
        return CobbleCapMod.INSTANCE.getStatueManager();
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
    suggestStyles(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx,
                  com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        for (StatueStyle s : StatueStyle.values()) b.suggest(s.key());
        return b.buildFuture();
    }

    private static String normalizeSlot(String input) {
        if (input == null) return "player";
        String s = input.trim().toLowerCase();
        if (s.equals("player") || s.equals("p")) return "player";
        try {
            int n = Integer.parseInt(s);
            if (n >= 1 && n <= 6) return Integer.toString(n - 1);
        } catch (NumberFormatException ignored) {}
        return s;
    }
}
