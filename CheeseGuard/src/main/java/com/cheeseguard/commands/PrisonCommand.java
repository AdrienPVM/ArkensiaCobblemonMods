package com.cheeseguard.commands;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.PrisonEntry;
import com.cheeseguard.data.Region;
import com.cheeseguard.logic.PrisonListener;
import com.cheeseguard.logic.PrisonManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * /prison set <player> <duration> <reason...>
 * /prison release <player>
 * /prison list
 * /prison setzone <zoneId>
 * /prison info <player>
 */
public class PrisonCommand {

    public static void register(CommandDispatcher<ServerCommandSource> d) {

        LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("prison")
            .requires(src -> src.hasPermissionLevel(2))
            .executes(ctx -> { showHelp(ctx.getSource()); return 1; });

        // /prison set <player> <duration> <reason...>
        root.then(CommandManager.literal("set")
            .then(CommandManager.argument("player", StringArgumentType.word())
                .suggests(PrisonCommand::suggestOnlinePlayers)
                .then(CommandManager.argument("duration", StringArgumentType.word())
                    .then(CommandManager.argument("reason", StringArgumentType.greedyString())
                        .executes(PrisonCommand::executeSet)))));

        // /prison release <player>
        root.then(CommandManager.literal("release")
            .then(CommandManager.argument("player", StringArgumentType.word())
                .suggests(PrisonCommand::suggestPrisoners)
                .executes(PrisonCommand::executeRelease)));

        // /prison list
        root.then(CommandManager.literal("list").executes(PrisonCommand::executeList));

        // /prison info <player>
        root.then(CommandManager.literal("info")
            .then(CommandManager.argument("player", StringArgumentType.word())
                .suggests(PrisonCommand::suggestPrisoners)
                .executes(PrisonCommand::executeInfo)));

        // /prison setzone <zoneId>
        root.then(CommandManager.literal("setzone")
            .then(CommandManager.argument("zoneId", StringArgumentType.word())
                .suggests(PrisonCommand::suggestZones)
                .executes(PrisonCommand::executeSetZone)));

        // /prison setout — set release point to current player position + facing + dim
        root.then(CommandManager.literal("setout").executes(PrisonCommand::executeSetOut));

        // /prison clearout — fall back to world spawn
        root.then(CommandManager.literal("clearout").executes(PrisonCommand::executeClearOut));

        d.register(root);
    }

    // ── Executors ────────────────────────────────────────────────────────────

    private static int executeSet(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        MinecraftServer server = src.getServer();
        PrisonManager pm = CheeseGuardMod.getInstance().getPrisonManager();
        if (pm == null) return 0;

        if (pm.getPrisonZoneId() == null) {
            src.sendError(Text.translatable("cheeseguard.prison.cmd.no_zone_configured"));
            return 0;
        }
        Region zone = CheeseGuardMod.getInstance().getStorage().getRegion(pm.getPrisonZoneId());
        if (zone == null) {
            src.sendError(Text.translatable("cheeseguard.prison.cmd.zone_missing", pm.getPrisonZoneId()));
            return 0;
        }

        String name = StringArgumentType.getString(ctx, "player");
        String duration = StringArgumentType.getString(ctx, "duration");
        String reason = StringArgumentType.getString(ctx, "reason");

        long ms = PrisonManager.parseDuration(duration);
        if (ms <= 0) {
            src.sendError(Text.translatable("cheeseguard.prison.cmd.bad_duration", duration));
            return 0;
        }

        ResolvedPlayer rp = resolvePlayer(server, name);
        if (rp == null) {
            src.sendError(Text.translatable("cheeseguard.prison.cmd.player_not_found", name));
            return 0;
        }

        String issuerUuid = src.getPlayer() != null ? src.getPlayer().getUuid().toString() : "console";
        String issuerName = src.getPlayer() != null ? src.getPlayer().getName().getString() : "Console";
        PrisonEntry e = pm.imprison(rp.uuid.toString(), rp.name, reason, ms, issuerUuid, issuerName);

        // If online: TP into prison + welcome.
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(rp.uuid);
        if (online != null) {
            // Cast through public API: PrisonListener does it via the JOIN handler;
            // here we just send a message and TP on next tick via runtime command.
            // Re-use static helper indirectly: simulate join effect by sending the message
            // and letting the tick-based confinement pull them in.
            online.sendMessage(Text.translatable("cheeseguard.prison.imprisoned_announce",
                PrisonManager.formatDuration(ms), reason).formatted(Formatting.RED), false);
        }

        src.sendFeedback(() -> Text.translatable("cheeseguard.prison.cmd.imprisoned",
            rp.name, PrisonManager.formatDuration(ms))
            .formatted(Formatting.GREEN), true);
        PrisonListener.broadcastPrisonEvent("prison_added", e);
        return 1;
    }

    private static int executeRelease(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        MinecraftServer server = src.getServer();
        PrisonManager pm = CheeseGuardMod.getInstance().getPrisonManager();
        if (pm == null) return 0;

        String name = StringArgumentType.getString(ctx, "player");
        ResolvedPlayer rp = resolvePlayer(server, name);
        if (rp == null) {
            src.sendError(Text.translatable("cheeseguard.prison.cmd.player_not_found", name));
            return 0;
        }

        String byUuid = src.getPlayer() != null ? src.getPlayer().getUuid().toString() : "console";
        String byName = src.getPlayer() != null ? src.getPlayer().getName().getString() : "Console";

        PrisonEntry released;
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(rp.uuid);
        if (online != null) {
            // The helper handles release + TP + message + broadcast.
            PrisonListener.releaseAndTpToSpawn(server, online, PrisonEntry.RELEASE_PARDONED, byUuid, byName);
            released = null; // already released by helper
        } else {
            released = pm.release(rp.uuid.toString(), PrisonEntry.RELEASE_PARDONED, byUuid, byName);
            if (released != null) PrisonListener.broadcastPrisonEvent("prison_released", released);
        }

        if (released == null && online == null) {
            src.sendError(Text.translatable("cheeseguard.prison.cmd.not_imprisoned", rp.name));
            return 0;
        }
        src.sendFeedback(() -> Text.translatable("cheeseguard.prison.cmd.released", rp.name)
            .formatted(Formatting.GREEN), true);
        return 1;
    }

    private static int executeList(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        PrisonManager pm = CheeseGuardMod.getInstance().getPrisonManager();
        if (pm == null) return 0;
        var list = pm.getActive();
        src.sendFeedback(() -> Text.translatable("cheeseguard.prison.cmd.list_header", list.size())
            .formatted(Formatting.GOLD), false);
        long now = System.currentTimeMillis();
        for (PrisonEntry e : list) {
            src.sendFeedback(() -> Text.literal("• ").formatted(Formatting.GRAY)
                .append(Text.literal(e.getPlayerName()).formatted(Formatting.WHITE))
                .append(Text.literal(" — " + PrisonManager.formatDuration(e.remainingMs(now)))
                    .formatted(Formatting.YELLOW))
                .append(Text.literal(" — " + (e.getReason() == null ? "" : e.getReason()))
                    .formatted(Formatting.GRAY)),
                false);
        }
        return 1;
    }

    private static int executeInfo(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        MinecraftServer server = src.getServer();
        PrisonManager pm = CheeseGuardMod.getInstance().getPrisonManager();
        if (pm == null) return 0;
        String name = StringArgumentType.getString(ctx, "player");
        ResolvedPlayer rp = resolvePlayer(server, name);
        if (rp == null) {
            src.sendError(Text.translatable("cheeseguard.prison.cmd.player_not_found", name));
            return 0;
        }
        PrisonEntry e = pm.get(rp.uuid.toString());
        if (e == null) {
            src.sendError(Text.translatable("cheeseguard.prison.cmd.not_imprisoned", rp.name));
            return 0;
        }
        long now = System.currentTimeMillis();
        src.sendFeedback(() -> Text.translatable("cheeseguard.prison.cmd.info_header", rp.name)
            .formatted(Formatting.GOLD), false);
        src.sendFeedback(() -> Text.translatable("cheeseguard.prison.cmd.info_reason", e.getReason())
            .formatted(Formatting.GRAY), false);
        src.sendFeedback(() -> Text.translatable("cheeseguard.prison.cmd.info_remaining",
            PrisonManager.formatDuration(e.remainingMs(now))).formatted(Formatting.GRAY), false);
        src.sendFeedback(() -> Text.translatable("cheeseguard.prison.cmd.info_issuedBy",
            e.getIssuedByName() == null ? "?" : e.getIssuedByName()).formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int executeSetOut(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        PrisonManager pm = CheeseGuardMod.getInstance().getPrisonManager();
        if (pm == null) return 0;
        ServerPlayerEntity p = src.getPlayer();
        if (p == null) {
            src.sendError(net.minecraft.text.Text.translatable("cheeseguard.prison.cmd.setout_needs_player"));
            return 0;
        }
        String world = p.getServerWorld().getRegistryKey().getValue().toString();
        pm.setReleasePoint(world, p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch());
        final String wf = world;
        src.sendFeedback(() -> net.minecraft.text.Text.translatable(
            "cheeseguard.prison.cmd.setout_done",
            String.format("%.1f, %.1f, %.1f", p.getX(), p.getY(), p.getZ()),
            wf
        ).formatted(Formatting.GREEN), true);
        return 1;
    }

    private static int executeClearOut(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        PrisonManager pm = CheeseGuardMod.getInstance().getPrisonManager();
        if (pm == null) return 0;
        pm.clearReleasePoint();
        src.sendFeedback(() -> net.minecraft.text.Text.translatable(
            "cheeseguard.prison.cmd.clearout_done").formatted(Formatting.GREEN), true);
        return 1;
    }

    private static int executeSetZone(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        PrisonManager pm = CheeseGuardMod.getInstance().getPrisonManager();
        if (pm == null) return 0;
        String zoneId = StringArgumentType.getString(ctx, "zoneId");
        Region z = CheeseGuardMod.getInstance().getStorage().getRegion(zoneId);
        if (z == null) {
            src.sendError(Text.translatable("cheeseguard.prison.cmd.zone_missing", zoneId));
            return 0;
        }
        pm.setPrisonZoneId(zoneId);
        src.sendFeedback(() -> Text.translatable("cheeseguard.prison.cmd.zone_set", zoneId)
            .formatted(Formatting.GREEN), true);
        return 1;
    }

    // ── Suggestions ──────────────────────────────────────────────────────────

    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestOnlinePlayers(
            CommandContext<ServerCommandSource> ctx, SuggestionsBuilder b) {
        var server = ctx.getSource().getServer();
        if (server != null) {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                b.suggest(p.getName().getString());
            }
        }
        return b.buildFuture();
    }

    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestPrisoners(
            CommandContext<ServerCommandSource> ctx, SuggestionsBuilder b) {
        var pm = CheeseGuardMod.getInstance().getPrisonManager();
        if (pm != null) {
            for (PrisonEntry e : pm.getActive()) b.suggest(e.getPlayerName());
        }
        return b.buildFuture();
    }

    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestZones(
            CommandContext<ServerCommandSource> ctx, SuggestionsBuilder b) {
        for (Region r : CheeseGuardMod.getInstance().getStorage().getRegions()) {
            b.suggest(r.getId());
        }
        return b.buildFuture();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void showHelp(ServerCommandSource src) {
        src.sendFeedback(() -> Text.translatable("cheeseguard.prison.cmd.help_header")
            .formatted(Formatting.GOLD), false);
        src.sendFeedback(() -> Text.literal("/prison set <player> <duration> <reason>")
            .formatted(Formatting.GRAY), false);
        src.sendFeedback(() -> Text.literal("/prison release <player>")
            .formatted(Formatting.GRAY), false);
        src.sendFeedback(() -> Text.literal("/prison list").formatted(Formatting.GRAY), false);
        src.sendFeedback(() -> Text.literal("/prison info <player>").formatted(Formatting.GRAY), false);
        src.sendFeedback(() -> Text.literal("/prison setzone <zoneId>").formatted(Formatting.GRAY), false);
        src.sendFeedback(() -> Text.literal("/prison setout").formatted(Formatting.GRAY), false);
        src.sendFeedback(() -> Text.literal("/prison clearout").formatted(Formatting.GRAY), false);
    }

    /** Resolve an online player or fall back to the user cache. */
    private static ResolvedPlayer resolvePlayer(MinecraftServer server, String name) {
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(name);
        if (online != null) return new ResolvedPlayer(online.getUuid(), online.getName().getString());
        if (server.getUserCache() != null) {
            var profile = server.getUserCache().findByName(name);
            if (profile.isPresent()) {
                return new ResolvedPlayer(profile.get().getId(), profile.get().getName());
            }
        }
        return null;
    }

    private record ResolvedPlayer(UUID uuid, String name) {}
}
