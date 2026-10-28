package com.cheeseguard.commands;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.*;
// gui.* package removed — admin GUI migrated to CheeseDash web dashboard
import com.cheeseguard.logic.SelectionManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.util.Formatting;

public class CheeseGuardCommand {

    public static void register(CommandDispatcher<ServerCommandSource> d) {

        LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("cg")
            .executes(ctx -> openGui(ctx));

        root.then(CommandManager.literal("gui").requires(src -> src.hasPermissionLevel(2)).executes(ctx -> openGui(ctx)));

        root.then(CommandManager.literal("wand").requires(src -> src.hasPermissionLevel(2)).executes(ctx -> {
            var p = player(ctx); if (p == null) return 0;
            CheeseGuardMod.getInstance().giveWand(p);
            return 1;
        }));

        root.then(CommandManager.literal("pos1").requires(src -> src.hasPermissionLevel(2)).executes(ctx -> {
            var p = player(ctx); if (p == null) return 0;
            var pos = p.getBlockPos();
            var wid = p.getServerWorld().getRegistryKey().getValue().toString();
            CheeseGuardMod.getInstance().getSelectionManager().setPos1(p.getUuid(), wid, pos);
            p.sendMessage(Text.translatable("cheeseguard.cmd.pos1_set", pos.getX(), pos.getY(), pos.getZ()).formatted(Formatting.GREEN), false);
            return 1;
        }));

        root.then(CommandManager.literal("pos2").requires(src -> src.hasPermissionLevel(2)).executes(ctx -> {
            var p = player(ctx); if (p == null) return 0;
            var pos = p.getBlockPos();
            var wid = p.getServerWorld().getRegistryKey().getValue().toString();
            CheeseGuardMod.getInstance().getSelectionManager().setPos2(p.getUuid(), wid, pos);
            p.sendMessage(Text.translatable("cheeseguard.cmd.pos2_set", pos.getX(), pos.getY(), pos.getZ()).formatted(Formatting.AQUA), false);
            return 1;
        }));

        root.then(CommandManager.literal("create").requires(src -> src.hasPermissionLevel(2))
            .then(CommandManager.argument("name", StringArgumentType.greedyString())
                .executes(ctx -> {
                    var p = player(ctx); if (p == null) return 0;
                    String name = StringArgumentType.getString(ctx, "name");
                    var sel = CheeseGuardMod.getInstance().getSelectionManager().getSelection(p.getUuid());
                    if (sel == null || !sel.isComplete()) {
                        p.sendMessage(Text.translatable("cheeseguard.cmd.no_selection").formatted(Formatting.RED), false);
                        return 0;
                    }
                    CheeseGuardMod.getInstance().createRegion(p, name, sel);
                    return 1;
                })));

        root.then(CommandManager.literal("delete").requires(src -> src.hasPermissionLevel(2))
            .then(CommandManager.argument("id", StringArgumentType.word())
                .executes(ctx -> {
                    var p = player(ctx); if (p == null) return 0;
                    String id = StringArgumentType.getString(ctx, "id");
                    var storage = CheeseGuardMod.getInstance().getRegionManager().getStorage();
                    if (!storage.hasRegion(id)) {
                        p.sendMessage(Text.translatable("cheeseguard.cmd.zone_not_found", id).formatted(Formatting.RED), false);
                        return 0;
                    }
                    storage.removeRegion(id);
                    p.sendMessage(Text.translatable("cheeseguard.cmd.zone_deleted_feedback", id).formatted(Formatting.GREEN), false);
                    return 1;
                })));

        root.then(CommandManager.literal("list").requires(src -> src.hasPermissionLevel(2)).executes(ctx -> {
            var p = player(ctx); if (p == null) return 0;
            var storage = CheeseGuardMod.getInstance().getRegionManager().getStorage();
            p.sendMessage(Text.translatable("cheeseguard.cmd.zones_header", storage.getRegions().stream().count()).formatted(Formatting.GOLD, Formatting.BOLD), false);
            for (var r : storage.getRegions()) {
                p.sendMessage(Text.literal("• ").formatted(Formatting.GRAY)
                    .append(Text.literal(r.getName()).formatted(Formatting.WHITE))
                    .append(Text.literal(" [" + r.getId() + "]").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(" – " + shortWorld(r.getWorld())).formatted(Formatting.GRAY)), false);
            }
            return 1;
        }));

        root.then(CommandManager.literal("info").requires(src -> src.hasPermissionLevel(2))
            .then(CommandManager.argument("id", StringArgumentType.word())
                .executes(ctx -> {
                    var p = player(ctx); if (p == null) return 0;
                    String id = StringArgumentType.getString(ctx, "id");
                    var r = CheeseGuardMod.getInstance().getRegionManager().getStorage().getRegion(id);
                    if (r == null) { p.sendMessage(Text.translatable("cheeseguard.cmd.zone_not_found_short").formatted(Formatting.RED), false); return 0; }
                    p.sendMessage(Text.translatable("cheeseguard.cmd.zone_info_header", r.getName()).formatted(Formatting.GOLD), false);
                    p.sendMessage(Text.translatable("cheeseguard.cmd.zone_info_id", r.getId(), shortWorld(r.getWorld())).formatted(Formatting.GRAY), false);
                    p.sendMessage(Text.translatable("cheeseguard.cmd.zone_info_min", r.getMinX(), r.getMinY(), r.getMinZ()).formatted(Formatting.GRAY), false);
                    p.sendMessage(Text.translatable("cheeseguard.cmd.zone_info_max", r.getMaxX(), r.getMaxY(), r.getMaxZ()).formatted(Formatting.GRAY), false);
                    p.sendMessage(Text.translatable("cheeseguard.cmd.zone_info_group", (r.getGroupId() != null ? r.getGroupId() : "aucun")).formatted(Formatting.GRAY), false);
                    p.sendMessage(Text.translatable("cheeseguard.cmd.zone_info_priority", r.getPriority(), r.getMembers().size()).formatted(Formatting.GRAY), false);
                    return 1;
                })));

        root.then(CommandManager.literal("tp")
            .then(CommandManager.argument("id", StringArgumentType.word())
                .executes(ctx -> {
                    var p = player(ctx); if (p == null) return 0;
                    String id = StringArgumentType.getString(ctx, "id");
                    var r = CheeseGuardMod.getInstance().getRegionManager().getStorage().getRegion(id);
                    if (r == null) { p.sendMessage(Text.translatable("cheeseguard.cmd.zone_not_found_short").formatted(Formatting.RED), false); return 0; }
                    int cx = r.getCenterX();
                    int cz = r.getCenterZ();
                    var world = p.getServerWorld();
                    int safeY = r.getCenterY();
                    for (int y = r.getMaxY(); y >= r.getMinY(); y--) {
                        var posBelow = new net.minecraft.util.math.BlockPos(cx, y, cz);
                        var posAt    = new net.minecraft.util.math.BlockPos(cx, y + 1, cz);
                        var posAbove = new net.minecraft.util.math.BlockPos(cx, y + 2, cz);
                        if (!world.getBlockState(posBelow).isAir()
                            && !world.getBlockState(posAt).isSolidBlock(world, posAt)
                            && !world.getBlockState(posAbove).isSolidBlock(world, posAbove)) {
                            safeY = y + 1;
                            break;
                        }
                    }
                    p.requestTeleport(cx + 0.5, safeY, cz + 0.5);
                    p.sendMessage(Text.translatable("cheeseguard.cmd.zone_tp", r.getName()).formatted(Formatting.GREEN), false);
                    return 1;
                })));

        root.then(CommandManager.literal("toggle").executes(ctx -> {
            var p = player(ctx); if (p == null) return 0;
            CheeseGuardMod.getInstance().getVisualizationManager().toggle(p.getUuid());
            boolean on = CheeseGuardMod.getInstance().getVisualizationManager().isVisualizing(p.getUuid());
            p.sendMessage(Text.translatable(on ? "cheeseguard.cmd.viz_enabled" : "cheeseguard.cmd.viz_disabled")
                .formatted(on ? Formatting.GREEN : Formatting.RED), false);
            return 1;
        }));

        root.then(CommandManager.literal("flag")
            .then(CommandManager.argument("region", StringArgumentType.word())
                .then(CommandManager.argument("flag", StringArgumentType.word())
                    .then(CommandManager.argument("value", StringArgumentType.word())
                        .executes(ctx -> {
                            var p = player(ctx); if (p == null) return 0;
                            String rid = StringArgumentType.getString(ctx, "region");
                            String flagId = StringArgumentType.getString(ctx, "flag");
                            String val = StringArgumentType.getString(ctx, "value");
                            var r = CheeseGuardMod.getInstance().getRegionManager().getStorage().getRegion(rid);
                            if (r == null) { p.sendMessage(Text.translatable("cheeseguard.cmd.zone_not_found_short").formatted(Formatting.RED), false); return 0; }
                            Flag flag = Flag.fromId(flagId);
                            if (flag == null) { p.sendMessage(Text.translatable("cheeseguard.cmd.flag_unknown", flagId).formatted(Formatting.RED), false); return 0; }
                            if (val.equalsIgnoreCase("inherit") || val.equalsIgnoreCase("null")) r.setFlag(flag, null);
                            else r.setFlag(flag, Boolean.parseBoolean(val));
                            CheeseGuardMod.getInstance().getRegionManager().getStorage().save();
                            p.sendMessage(Text.translatable("cheeseguard.cmd.flag_set", flagId, val).formatted(Formatting.GREEN), false);
                            return 1;
                        })))));

        root.then(CommandManager.literal("addmember")
            .then(CommandManager.argument("region", StringArgumentType.word())
                .then(CommandManager.argument("player", StringArgumentType.word())
                    .executes(ctx -> {
                        var admin = player(ctx); if (admin == null) return 0;
                        String rid = StringArgumentType.getString(ctx, "region");
                        String name = StringArgumentType.getString(ctx, "player");
                        var r = CheeseGuardMod.getInstance().getRegionManager().getStorage().getRegion(rid);
                        if (r == null) { admin.sendMessage(Text.translatable("cheeseguard.cmd.zone_not_found_short").formatted(Formatting.RED), false); return 0; }
                        var target = CheeseGuardMod.getInstance().getServer().getPlayerManager().getPlayer(name);
                        if (target == null) { admin.sendMessage(Text.translatable("cheeseguard.cmd.player_not_found").formatted(Formatting.RED), false); return 0; }
                        r.addMember(new Member(target.getUuid(), target.getName().getString()));
                        CheeseGuardMod.getInstance().getRegionManager().getStorage().save();
                        admin.sendMessage(Text.translatable("cheeseguard.cmd.member_added", name).formatted(Formatting.GREEN), false);
                        return 1;
                    }))));

        root.then(CommandManager.literal("removemember")
            .then(CommandManager.argument("region", StringArgumentType.word())
                .then(CommandManager.argument("player", StringArgumentType.word())
                    .executes(ctx -> {
                        var admin = player(ctx); if (admin == null) return 0;
                        String rid = StringArgumentType.getString(ctx, "region");
                        String name = StringArgumentType.getString(ctx, "player");
                        var r = CheeseGuardMod.getInstance().getRegionManager().getStorage().getRegion(rid);
                        if (r == null) { admin.sendMessage(Text.translatable("cheeseguard.cmd.zone_not_found_short").formatted(Formatting.RED), false); return 0; }
                        r.getMembers().stream()
                            .filter(m -> m.getName().equalsIgnoreCase(name))
                            .findFirst()
                            .ifPresent(m -> r.removeMember(m.getUuid()));
                        CheeseGuardMod.getInstance().getRegionManager().getStorage().save();
                        admin.sendMessage(Text.translatable("cheeseguard.cmd.member_removed", name).formatted(Formatting.GREEN), false);
                        return 1;
                    }))));

        // /cg group ...
        LiteralArgumentBuilder<ServerCommandSource> groupNode = CommandManager.literal("group");

        groupNode.then(CommandManager.literal("create")
            .then(CommandManager.argument("name", StringArgumentType.greedyString())
                .executes(ctx -> {
                    var p = player(ctx); if (p == null) return 0;
                    String name = StringArgumentType.getString(ctx, "name");
                    String id = name.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
                    var storage = CheeseGuardMod.getInstance().getRegionManager().getStorage();
                    if (storage.hasGroup(id)) {
                        p.sendMessage(Text.translatable("cheeseguard.cmd.group_exists").formatted(Formatting.RED), false);
                        return 0;
                    }
                    storage.addGroup(new RegionGroup(id, name));
                    p.sendMessage(Text.translatable("cheeseguard.cmd.group_created", name).formatted(Formatting.GREEN), false);
                    return 1;
                })));

        groupNode.then(CommandManager.literal("delete")
            .then(CommandManager.argument("id", StringArgumentType.word())
                .executes(ctx -> {
                    var p = player(ctx); if (p == null) return 0;
                    String id = StringArgumentType.getString(ctx, "id");
                    CheeseGuardMod.getInstance().getRegionManager().getStorage().removeGroup(id);
                    p.sendMessage(Text.translatable("cheeseguard.cmd.group_deleted", id).formatted(Formatting.GREEN), false);
                    return 1;
                })));

        groupNode.then(CommandManager.literal("add")
            .then(CommandManager.argument("region", StringArgumentType.word())
                .then(CommandManager.argument("group", StringArgumentType.word())
                    .executes(ctx -> {
                        var p = player(ctx); if (p == null) return 0;
                        String rid = StringArgumentType.getString(ctx, "region");
                        String gid = StringArgumentType.getString(ctx, "group");
                        var storage = CheeseGuardMod.getInstance().getRegionManager().getStorage();
                        var r = storage.getRegion(rid);
                        var g = storage.getGroup(gid);
                        if (r == null || g == null) {
                            p.sendMessage(Text.translatable("cheeseguard.cmd.group_or_zone_not_found").formatted(Formatting.RED), false);
                            return 0;
                        }
                        r.setGroupId(gid);
                        g.addRegion(rid);
                        storage.save();
                        p.sendMessage(Text.translatable("cheeseguard.cmd.zone_assigned_to_group", r.getName(), g.getName()).formatted(Formatting.GREEN), false);
                        return 1;
                    }))));

        root.then(groupNode);

        // /cg adv check <player>
        LiteralArgumentBuilder<ServerCommandSource> advNode = CommandManager.literal("adv");
        advNode.then(CommandManager.literal("check")
            .then(CommandManager.argument("player", StringArgumentType.word())
                .executes(ctx -> {
                    var admin = player(ctx); if (admin == null) return 0;
                    String targetName = StringArgumentType.getString(ctx, "player");
                    var target = CheeseGuardMod.getInstance().getServer().getPlayerManager().getPlayer(targetName);
                    if (target == null) {
                        admin.sendMessage(Text.translatable("cheeseguard.cmd.player_not_found").formatted(Formatting.RED), false);
                        return 0;
                    }
                    admin.sendMessage(Text.translatable("cheeseguard.cmd.adv_header", target.getName().getString()).formatted(Formatting.GOLD, Formatting.BOLD), false);
                    var tracker = target.getAdvancementTracker();
                    int done = 0, total = 0;
                    for (var adv : CheeseGuardMod.getInstance().getServer().getAdvancementLoader().getAdvancements()) {
                        if (adv.id().getPath().startsWith("recipes/")) continue;
                        total++;
                        if (tracker.getProgress(adv).isDone()) {
                            done++;
                            String name = adv.id().toString();
                            if (adv.value().display().isPresent()) {
                                name = adv.value().display().get().getTitle().getString() + " [" + adv.id() + "]";
                            }
                            admin.sendMessage(Text.literal("  ✅ " + name).formatted(Formatting.GREEN), false);
                        }
                    }
                    admin.sendMessage(Text.translatable("cheeseguard.cmd.adv_completed", done, total).formatted(Formatting.YELLOW), false);
                    return 1;
                })));
        advNode.then(CommandManager.literal("list").executes(ctx -> {
            var admin = player(ctx); if (admin == null) return 0;
            admin.sendMessage(Text.translatable("cheeseguard.cmd.adv_list_header").formatted(Formatting.GOLD, Formatting.BOLD), false);
            int count = 0;
            for (var adv : CheeseGuardMod.getInstance().getServer().getAdvancementLoader().getAdvancements()) {
                if (adv.id().getPath().startsWith("recipes/")) continue;
                String name = adv.id().toString();
                if (adv.value().display().isPresent()) {
                    name = adv.value().display().get().getTitle().getString() + " [" + adv.id() + "]";
                }
                admin.sendMessage(Text.literal("  • " + name).formatted(Formatting.GRAY), false);
                count++;
            }
            admin.sendMessage(Text.translatable("cheeseguard.cmd.adv_total", count).formatted(Formatting.YELLOW), false);
            return 1;
        }));
        root.then(advNode);

        // ── /cg playerworld delete <masterDimId> <playerName> ────────────────
        LiteralArgumentBuilder<ServerCommandSource> pwNode = CommandManager.literal("playerworld")
            .requires(src -> src.hasPermissionLevel(2));
        pwNode.then(CommandManager.literal("delete")
            .then(CommandManager.argument("masterDimId", StringArgumentType.word())
                .then(CommandManager.argument("player", StringArgumentType.word())
                    .executes(ctx -> {
                        String masterDimId = StringArgumentType.getString(ctx, "masterDimId");
                        String playerName  = StringArgumentType.getString(ctx, "player");
                        var src = ctx.getSource();
                        var dm  = CheeseGuardMod.getInstance().getDimensionManager();
                        if (dm == null) { src.sendError(Text.translatable("cheeseguard.cmd.dimmanager_unavailable")); return 0; }
                        // Resolve UUID
                        java.util.UUID targetUuid = null;
                        var online = src.getServer().getPlayerManager().getPlayer(playerName);
                        if (online != null) {
                            targetUuid = online.getUuid();
                        } else {
                            // Search whitelist of personal dim by display name
                            String personalId = null;
                            for (var pdMap : dm.getPlayerDimsForMaster(masterDimId)) {
                                if (pdMap.get("displayName").contains(playerName)) {
                                    personalId = pdMap.get("configId");
                                    break;
                                }
                            }
                            if (personalId != null) {
                                var pcfg = dm.getConfig(personalId);
                                if (pcfg != null) {
                                    for (String u : pcfg.getWhitelist()) {
                                        try { targetUuid = java.util.UUID.fromString(u); break; } catch (Exception ignored) {}
                                    }
                                }
                            }
                        }
                        if (targetUuid == null) {
                            src.sendError(Text.translatable("cheeseguard.cmd.playerworld_not_found", playerName, masterDimId));
                            return 0;
                        }
                        boolean ok = dm.deletePlayerDimension(masterDimId, targetUuid);
                        if (ok) src.sendFeedback(() -> Text.translatable("cheeseguard.cmd.playerworld_deleted", playerName).formatted(Formatting.GREEN), true);
                        else src.sendError(Text.translatable("cheeseguard.cmd.playerworld_delete_failed"));
                        return ok ? 1 : 0;
                    }))));
        pwNode.then(CommandManager.literal("list")
            .then(CommandManager.argument("masterDimId", StringArgumentType.word())
                .executes(ctx -> {
                    String masterDimId = StringArgumentType.getString(ctx, "masterDimId");
                    var dm = CheeseGuardMod.getInstance().getDimensionManager();
                    if (dm == null) return 0;
                    var list = dm.getPlayerDimsForMaster(masterDimId);
                    if (list.isEmpty()) {
                        ctx.getSource().sendFeedback(() -> Text.translatable("cheeseguard.cmd.playerworld_none", masterDimId).formatted(Formatting.GRAY), false);
                    } else {
                        ctx.getSource().sendFeedback(() -> Text.translatable("cheeseguard.cmd.playerworld_header", masterDimId, list.size()).formatted(Formatting.GOLD), false);
                        for (var m : list) {
                            ctx.getSource().sendFeedback(() -> Text.literal("  • " + m.get("displayName") + " [" + m.get("configId") + "]").formatted(Formatting.GRAY), false);
                        }
                    }
                    return 1;
                })));
        root.then(pwNode);

        // ── Event player commands (no permission required) ────────────────────
        LiteralArgumentBuilder<ServerCommandSource> eventCmd = CommandManager.literal("event")
            .requires(src -> true); // available to all players

        eventCmd.then(CommandManager.literal("join")
            // /cg event join — auto-join first available event
            .executes(ctx -> {
                var p = player(ctx); if (p == null) return 0;
                for (var evt : CheeseGuardMod.getInstance().getEventManager().getEvents()) {
                    if (evt.isRunning() && evt.getParticipant(p.getUuid()) == null) {
                        if (CheeseGuardMod.getInstance().getEventManager().joinEvent(evt, p, com.cheeseguard.data.EventParticipant.Role.PARTICIPANT)) {
                            return 1;
                        }
                    }
                }
                p.sendMessage(Text.translatable("cheeseguard.cmd.event_no_available").formatted(Formatting.YELLOW), false);
                return 0;
            })
            // /cg event join <eventId>
            .then(CommandManager.argument("eventId", StringArgumentType.word())
                .executes(ctx -> {
                    var p = player(ctx); if (p == null) return 0;
                    String eid = StringArgumentType.getString(ctx, "eventId");
                    var evt = CheeseGuardMod.getInstance().getEventManager().getEvent(eid);
                    if (evt == null) { p.sendMessage(Text.translatable("cheeseguard.cmd.event_not_found").formatted(Formatting.RED), false); return 0; }
                    if (CheeseGuardMod.getInstance().getEventManager().joinEvent(evt, p, com.cheeseguard.data.EventParticipant.Role.PARTICIPANT)) return 1;
                    p.sendMessage(Text.translatable("cheeseguard.cmd.event_cannot_join").formatted(Formatting.RED), false);
                    return 0;
                })));

        eventCmd.then(CommandManager.literal("spectate")
            // /cg event spectate — auto-spectate first running event
            .executes(ctx -> {
                var p = player(ctx); if (p == null) return 0;
                for (var evt : CheeseGuardMod.getInstance().getEventManager().getEvents()) {
                    if (evt.isRunning() && evt.getParticipant(p.getUuid()) == null) {
                        if (CheeseGuardMod.getInstance().getEventManager().joinEvent(evt, p, com.cheeseguard.data.EventParticipant.Role.SPECTATOR)) {
                            return 1;
                        }
                    }
                }
                p.sendMessage(Text.translatable("cheeseguard.cmd.event_no_available").formatted(Formatting.YELLOW), false);
                return 0;
            })
            // /cg event spectate <eventId>
            .then(CommandManager.argument("eventId", StringArgumentType.word())
                .executes(ctx -> {
                    var p = player(ctx); if (p == null) return 0;
                    String eid = StringArgumentType.getString(ctx, "eventId");
                    var evt = CheeseGuardMod.getInstance().getEventManager().getEvent(eid);
                    if (evt == null) { p.sendMessage(Text.translatable("cheeseguard.cmd.event_not_found").formatted(Formatting.RED), false); return 0; }
                    if (CheeseGuardMod.getInstance().getEventManager().joinEvent(evt, p, com.cheeseguard.data.EventParticipant.Role.SPECTATOR)) return 1;
                    p.sendMessage(Text.translatable("cheeseguard.cmd.event_cannot_spectate").formatted(Formatting.RED), false);
                    return 0;
                })));

        eventCmd.then(CommandManager.literal("announce")
            .requires(src -> src.hasPermissionLevel(2))
            .then(CommandManager.argument("eventId", StringArgumentType.word())
                .executes(ctx -> {
                    String eid = StringArgumentType.getString(ctx, "eventId");
                    var evt = CheeseGuardMod.getInstance().getEventManager().getEvent(eid);
                    if (evt == null) { ctx.getSource().sendError(Text.translatable("cheeseguard.cmd.event_not_found")); return 0; }
                    if (evt.isRunning()) { ctx.getSource().sendError(Text.translatable("cheeseguard.cmd.event_already_running")); return 0; }
                    CheeseGuardMod.getInstance().getEventManager().announceEvent(evt);
                    ctx.getSource().sendFeedback(() -> Text.translatable("cheeseguard.cmd.event_announced", eid).formatted(Formatting.GREEN), true);
                    return 1;
                })));

        // /cg event launch <eventId> — player-facing (rate limited, time window)
        eventCmd.then(CommandManager.literal("launch")
            .then(CommandManager.argument("eventId", StringArgumentType.word())
                .executes(ctx -> {
                    var p = player(ctx); if (p == null) return 0;
                    var em = CheeseGuardMod.getInstance().getEventManager();
                    String eid = StringArgumentType.getString(ctx, "eventId");
                    var evt = em.getEvent(eid);
                    if (evt == null) { p.sendMessage(Text.translatable("cheeseguard.cmd.event_not_found").formatted(Formatting.RED), false); return 0; }
                    if (!evt.isPlayerLaunchable()) { p.sendMessage(Text.translatable("cheeseguard.cmd.launch_not_allowed").formatted(Formatting.RED), false); return 0; }
                    if (evt.isRunning()) { p.sendMessage(Text.translatable("cheeseguard.cmd.event_already_running").formatted(Formatting.RED), false); return 0; }
                    // Cooldown check
                    if (evt.getCooldownMinutes() > 0 && evt.getLastRunTimestamp() > 0) {
                        long cooldownMs = evt.getCooldownMinutes() * 60_000L;
                        if (System.currentTimeMillis() - evt.getLastRunTimestamp() < cooldownMs) {
                            p.sendMessage(Text.translatable("cheeseguard.cmd.launch_cooldown").formatted(Formatting.RED), false); return 0;
                        }
                    }
                    // Admin bypass: operators skip rate limits and time window
                    if (!ctx.getSource().hasPermissionLevel(2)) {
                        String denied = em.checkLaunchAllowed(p.getUuid());
                        if (denied != null) {
                            p.sendMessage(Text.translatable("cheeseguard.cmd.launch_" + denied).formatted(Formatting.RED), false);
                            return 0;
                        }
                    }
                    em.recordLaunch(p.getUuid());
                    em.announceEvent(evt);
                    p.sendMessage(Text.translatable("cheeseguard.cmd.launch_success", evt.getName()).formatted(Formatting.GREEN), false);
                    return 1;
                })));

        eventCmd.then(CommandManager.literal("leave").executes(ctx -> {
            var p = player(ctx); if (p == null) return 0;
            var em = CheeseGuardMod.getInstance().getEventManager();

            // 1. Check active events (normal case)
            for (var evt : em.getEvents()) {
                if (evt.getParticipant(p.getUuid()) != null) {
                    em.leaveEvent(evt, p);
                    return 1;
                }
            }

            // 2. Check pending restores (crash recovery)
            if (em.hasPendingRestore(p.getUuid())) {
                em.applyPendingRestoreNow(p);
                com.cheeseguard.logic.EventLeaveHandler.removeLeaveItem(p);
                p.sendMessage(Text.translatable("cheeseguard.cmd.event_restored").formatted(Formatting.GREEN), false);
                return 1;
            }

            // 3. Last resort: just remove the barrier item if present
            com.cheeseguard.logic.EventLeaveHandler.removeLeaveItem(p);
            p.sendMessage(Text.translatable("cheeseguard.cmd.event_not_participating").formatted(Formatting.YELLOW), false);
            return 0;
        }));

        eventCmd.then(CommandManager.literal("score")
            .requires(src -> src.hasPermissionLevel(2))
            .then(CommandManager.literal("add")
                .then(CommandManager.argument("target", StringArgumentType.word())
                    .then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            String target = StringArgumentType.getString(ctx, "target");
                            int amount = IntegerArgumentType.getInteger(ctx, "amount");
                            var pl = ctx.getSource().getServer().getPlayerManager().getPlayer(target);
                            if (pl == null) { ctx.getSource().sendError(Text.translatable("cheeseguard.cmd.score_player_not_found")); return 0; }
                            for (var evt : CheeseGuardMod.getInstance().getEventManager().getEvents()) {
                                if (evt.isActive() && evt.getParticipant(pl.getUuid()) != null) {
                                    CheeseGuardMod.getInstance().getEventManager().addScore(evt, pl.getUuid(), amount);
                                    ctx.getSource().sendFeedback(() -> Text.translatable("cheeseguard.cmd.score_added", amount, target).formatted(Formatting.GREEN), false);
                                    return 1;
                                }
                            }
                            ctx.getSource().sendError(Text.translatable("cheeseguard.cmd.score_no_active_event"));
                            return 0;
                        })))));

        eventCmd.then(CommandManager.literal("list").executes(ctx -> {
            var p = player(ctx); if (p == null) return 0;
            var events = CheeseGuardMod.getInstance().getEventManager().getEvents();
            boolean found = false;
            for (var evt : events) {
                if (evt.isRunning()) {
                    int rem = evt.getRemainingSeconds();
                    Text line = Text.literal("🎉 " + evt.getName()).formatted(Formatting.GOLD)
                        .append(Text.literal(" — " + evt.getState().name() + " (" + (rem/60) + "m" + (rem%60) + "s) ").formatted(Formatting.GRAY))
                        .append(Text.translatable("cheeseguard.event.join_button").styled(s -> s
                            .withColor(Formatting.GREEN).withBold(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cg event join " + evt.getId()))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.translatable("cheeseguard.cmd.event_click_join")))));
                    p.sendMessage(line, false);
                    found = true;
                }
            }
            if (!found) p.sendMessage(Text.translatable("cheeseguard.cmd.event_no_running").formatted(Formatting.GRAY), false);
            return 1;
        }));

        // /cg event snapshot save <id> <region>
        eventCmd.then(CommandManager.literal("snapshot")
            .requires(src -> src.hasPermissionLevel(2))
            .then(CommandManager.literal("save")
                .then(CommandManager.argument("snapId", StringArgumentType.word())
                    .then(CommandManager.argument("regionId", StringArgumentType.word())
                        .executes(ctx -> {
                            String snapId = StringArgumentType.getString(ctx, "snapId");
                            String regionId = StringArgumentType.getString(ctx, "regionId");
                            var snap = new com.cheeseguard.logic.ZoneSnapshot(snapId, regionId);
                            int count = snap.capture(ctx.getSource().getServer());
                            if (count > 0) {
                                snap.saveToDisk(ctx.getSource().getServer());
                                ctx.getSource().sendFeedback(() -> Text.translatable("cheeseguard.cmd.snapshot_saved", snapId, count).formatted(Formatting.GREEN), false);
                            } else {
                                ctx.getSource().sendError(Text.translatable("cheeseguard.cmd.snapshot_empty", regionId));
                            }
                            return count > 0 ? 1 : 0;
                        }))))
            .then(CommandManager.literal("restore")
                .then(CommandManager.argument("snapId", StringArgumentType.word())
                    .executes(ctx -> {
                        String snapId = StringArgumentType.getString(ctx, "snapId");
                        var snap = com.cheeseguard.logic.ZoneSnapshot.loadFromDisk(ctx.getSource().getServer(), snapId);
                        if (snap != null) {
                            int count = snap.restore(ctx.getSource().getServer());
                            ctx.getSource().sendFeedback(() -> Text.translatable("cheeseguard.cmd.snapshot_restored", count).formatted(Formatting.GREEN), false);
                            return 1;
                        }
                        ctx.getSource().sendError(Text.translatable("cheeseguard.cmd.snapshot_not_found", snapId));
                        return 0;
                    }))));

        // /cg event template save <eventId> <name> | load <name>
        eventCmd.then(CommandManager.literal("template")
            .requires(src -> src.hasPermissionLevel(2))
            .then(CommandManager.literal("save")
                .then(CommandManager.argument("eventId", StringArgumentType.word())
                    .then(CommandManager.argument("tplName", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String eventId = StringArgumentType.getString(ctx, "eventId");
                            String tplName = StringArgumentType.getString(ctx, "tplName");
                            var evt = CheeseGuardMod.getInstance().getEventManager().getEvent(eventId);
                            if (evt == null) { ctx.getSource().sendError(Text.translatable("cheeseguard.cmd.event_not_found")); return 0; }
                            boolean ok = com.cheeseguard.logic.EventTemplate.save(ctx.getSource().getServer(), evt, tplName);
                            if (ok) ctx.getSource().sendFeedback(() -> Text.translatable("cheeseguard.cmd.template_saved", tplName).formatted(Formatting.GREEN), false);
                            else ctx.getSource().sendError(Text.translatable("cheeseguard.cmd.template_save_error"));
                            return ok ? 1 : 0;
                        }))))
            .then(CommandManager.literal("load")
                .then(CommandManager.argument("tplName", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String tplName = StringArgumentType.getString(ctx, "tplName");
                        var loaded = com.cheeseguard.logic.EventTemplate.load(ctx.getSource().getServer(), tplName);
                        if (loaded != null) {
                            CheeseGuardMod.getInstance().getEventManager().getEvents().add(loaded);
                            CheeseGuardMod.getInstance().getEventManager().save();
                            ctx.getSource().sendFeedback(() -> Text.translatable("cheeseguard.cmd.template_loaded", loaded.getName()).formatted(Formatting.GREEN), false);
                            return 1;
                        }
                        ctx.getSource().sendError(Text.translatable("cheeseguard.cmd.template_not_found", tplName));
                        return 0;
                    })))
            .then(CommandManager.literal("list")
                .executes(ctx -> {
                    var templates = com.cheeseguard.logic.EventTemplate.list(ctx.getSource().getServer());
                    if (templates.isEmpty()) {
                        ctx.getSource().sendFeedback(() -> Text.translatable("cheeseguard.cmd.template_none").formatted(Formatting.GRAY), false);
                    } else {
                        for (String t : templates) {
                            ctx.getSource().sendFeedback(() -> Text.literal("  📄 " + t).formatted(Formatting.YELLOW), false);
                        }
                    }
                    return 1;
                })));

        root.then(eventCmd);

        // ── /chunksuggest — available to all players ─────────────────────────
        // Registered as a standalone command to avoid clobbering the chunkstoclear
        // mod's permission gate (which requires operator level on its root node).
        LiteralArgumentBuilder<ServerCommandSource> chunksCmd = CommandManager.literal("chunksuggest");
        chunksCmd.executes(ctx -> {
            ServerPlayerEntity p = player(ctx);
            if (p == null) return 0;
            ChunkSuggestionStorage suggStorage = CheeseGuardMod.getInstance().getChunkSuggestionStorage();
            if (suggStorage == null) {
                p.sendMessage(Text.translatable("cheeseguard.cmd.chunk_suggest_unavailable").formatted(Formatting.RED), false);
                return 0;
            }

            // Check if player's current region is already covered by a ChunkSet (same dimension only)
            String world = p.getServerWorld().getRegistryKey().getValue().toString();
            ChunkSetStorage chunkStorage = CheeseGuardMod.getInstance().getChunkSetStorage();
            if (chunkStorage != null) {
                int playerRegionX = (int) Math.floor(p.getX()) >> 9;
                int playerRegionZ = (int) Math.floor(p.getZ()) >> 9;
                String matchedSet = null;
                outer:
                for (java.util.Map.Entry<String, ChunkSet> entry : chunkStorage.getAllSets().entrySet()) {
                    // Only check sets that target the same dimension as the player
                    if (!world.equals(entry.getValue().getDimension())) continue;
                    java.util.Set<com.cheeseguard.logic.ChunkCalculator.RegionCoord> regions =
                        com.cheeseguard.logic.ChunkCalculator.getAffectedRegions(
                            com.cheeseguard.logic.ChunkCalculator.getAffectedChunks(entry.getValue()));
                    for (com.cheeseguard.logic.ChunkCalculator.RegionCoord r : regions) {
                        if (r.x == playerRegionX && r.z == playerRegionZ) {
                            matchedSet = entry.getKey();
                            break outer;
                        }
                    }
                }
                if (matchedSet != null) {
                    p.sendMessage(Text.translatable("cheeseguard.cmd.chunk_already_covered", matchedSet)
                        .formatted(Formatting.YELLOW), false);
                    return 0;
                }
            }

            String id = java.util.UUID.randomUUID().toString().substring(0, 8);
            ChunkSuggestion suggestion = new ChunkSuggestion(
                id,
                p.getName().getString(),
                p.getUuid(),
                p.getX(), p.getY(), p.getZ(),
                world,
                System.currentTimeMillis()
            );
            suggStorage.add(suggestion);
            p.sendMessage(
                Text.translatable("cheeseguard.cmd.chunk_suggest_sent",
                    Math.round(p.getX()), Math.round(p.getY()), Math.round(p.getZ()))
                    .formatted(Formatting.GREEN),
                false
            );
            return 1;
        });
        d.register(chunksCmd);

        // ── Hot Potato commands ─────────────────────────────────────────────
        root.then(CommandManager.literal("hotpotato").requires(src -> src.hasPermissionLevel(2))
            .then(CommandManager.literal("on").executes(ctx -> {
                com.cheeseguard.logic.HotPotatoHandler.setActive(true);
                ctx.getSource().sendFeedback(() -> Text.translatable("cheeseguard.cmd.hotpotato_on").formatted(Formatting.GREEN), true);
                return 1;
            }))
            .then(CommandManager.literal("off").executes(ctx -> {
                com.cheeseguard.logic.HotPotatoHandler.setActive(false);
                var srv = ctx.getSource().getServer();
                for (ServerPlayerEntity p : srv.getPlayerManager().getPlayerList()) {
                    com.cheeseguard.logic.HotPotatoHandler.removeBombFromPlayer(p);
                }
                ctx.getSource().sendFeedback(() -> Text.translatable("cheeseguard.cmd.hotpotato_off").formatted(Formatting.GRAY), true);
                return 1;
            }))
            .then(CommandManager.literal("bomb").executes(ctx -> {
                var srv = ctx.getSource().getServer();
                ServerPlayerEntity target = com.cheeseguard.logic.HotPotatoHandler.giveBombToRandom(srv);
                if (target == null) {
                    ctx.getSource().sendError(Text.translatable("cheeseguard.cmd.hotpotato_no_player"));
                    return 0;
                }
                ctx.getSource().sendFeedback(() -> Text.translatable("cheeseguard.cmd.hotpotato_bomb_given", target.getName().getString()).formatted(Formatting.YELLOW), true);
                return 1;
            }))
            .then(CommandManager.literal("explode").executes(ctx -> {
                var srv = ctx.getSource().getServer();
                ServerPlayerEntity holder = com.cheeseguard.logic.HotPotatoHandler.explodeBombHolder(srv);
                if (holder == null) {
                    ctx.getSource().sendError(Text.translatable("cheeseguard.cmd.hotpotato_no_holder"));
                    return 0;
                }
                final String name = holder.getName().getString();
                ctx.getSource().sendFeedback(() -> Text.translatable("cheeseguard.cmd.hotpotato_exploded", name).formatted(Formatting.RED), true);
                return 1;
            }))
        );

        // ── Zone State commands ────────────────────────────────────────────────
        root.then(CommandManager.literal("state").requires(src -> src.hasPermissionLevel(2))
            // /cg state capture <zoneId> <name>
            .then(CommandManager.literal("capture")
                .then(CommandManager.argument("zoneId", StringArgumentType.word())
                    .then(CommandManager.argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            var p = player(ctx);
                            String zoneId = StringArgumentType.getString(ctx, "zoneId");
                            String name = StringArgumentType.getString(ctx, "name");
                            var mgr = CheeseGuardMod.getInstance().getZoneStateManager();
                            var state = mgr.capture(zoneId, name);
                            if (state == null) {
                                ctx.getSource().sendError(Text.translatable("cheeseguard.state.zone_not_found", zoneId));
                                return 0;
                            }
                            final int bc = state.getBlockCount(), ec = state.getEntityCount();
                            ctx.getSource().sendFeedback(() -> Text.translatable("cheeseguard.state.captured", name, bc, ec).formatted(Formatting.GREEN), true);
                            return 1;
                        })
                    )
                )
            )
            // /cg state list <zoneId>
            .then(CommandManager.literal("list")
                .then(CommandManager.argument("zoneId", StringArgumentType.word())
                    .executes(ctx -> {
                        String zoneId = StringArgumentType.getString(ctx, "zoneId");
                        var mgr = CheeseGuardMod.getInstance().getZoneStateManager();
                        var states = mgr.listStates(zoneId);
                        if (states.isEmpty()) {
                            ctx.getSource().sendFeedback(() -> Text.translatable("cheeseguard.state.empty", zoneId).formatted(Formatting.GRAY), false);
                            return 1;
                        }
                        ctx.getSource().sendFeedback(() -> Text.translatable("cheeseguard.state.list_header", zoneId).formatted(Formatting.GOLD), false);
                        for (var s : states) {
                            final String sid = s.getId(), sname = s.getName();
                            final int sbc = s.getBlockCount(), sec = s.getEntityCount();
                            ctx.getSource().sendFeedback(() -> Text.translatable("cheeseguard.state.list_entry", sid, sname, sbc, sec).formatted(Formatting.YELLOW), false);
                        }
                        return 1;
                    })
                )
            )
            // /cg state delete <zoneId> <stateId>
            .then(CommandManager.literal("delete")
                .then(CommandManager.argument("zoneId", StringArgumentType.word())
                    .then(CommandManager.argument("stateId", StringArgumentType.word())
                        .executes(ctx -> {
                            String zoneId = StringArgumentType.getString(ctx, "zoneId");
                            String stateId = StringArgumentType.getString(ctx, "stateId");
                            var mgr = CheeseGuardMod.getInstance().getZoneStateManager();
                            if (mgr.deleteState(zoneId, stateId)) {
                                ctx.getSource().sendFeedback(() -> Text.translatable("cheeseguard.state.deleted", stateId, zoneId).formatted(Formatting.YELLOW), true);
                                return 1;
                            }
                            ctx.getSource().sendError(Text.translatable("cheeseguard.state.not_found", stateId));
                            return 0;
                        })
                    )
                )
            )
            // /cg state apply <zoneId> <stateId> — instant
            // /cg state apply <zoneId> <stateId> <animated:true|false> <direction:bottom_up|top_down> <speed:int> <particle>
            .then(CommandManager.literal("apply")
                .then(CommandManager.argument("zoneId", StringArgumentType.word())
                    .then(CommandManager.argument("stateId", StringArgumentType.word())
                        // Instant apply (no animation args)
                        .executes(ctx -> applyState(ctx, false, "bottom_up", 2, "cloud", false))
                        // With animation args
                        .then(CommandManager.argument("animated", BoolArgumentType.bool())
                            .executes(ctx -> applyState(ctx,
                                BoolArgumentType.getBool(ctx, "animated"),
                                "bottom_up", 2, "cloud", false))
                            .then(CommandManager.argument("direction", StringArgumentType.word())
                                .executes(ctx -> applyState(ctx,
                                    BoolArgumentType.getBool(ctx, "animated"),
                                    StringArgumentType.getString(ctx, "direction"), 2, "cloud", false))
                                .then(CommandManager.argument("speed", IntegerArgumentType.integer(1, 100))
                                    .executes(ctx -> applyState(ctx,
                                        BoolArgumentType.getBool(ctx, "animated"),
                                        StringArgumentType.getString(ctx, "direction"),
                                        IntegerArgumentType.getInteger(ctx, "speed"), "cloud", false))
                                    .then(CommandManager.argument("particle", StringArgumentType.word())
                                        .executes(ctx -> applyState(ctx,
                                            BoolArgumentType.getBool(ctx, "animated"),
                                            StringArgumentType.getString(ctx, "direction"),
                                            IntegerArgumentType.getInteger(ctx, "speed"),
                                            StringArgumentType.getString(ctx, "particle"), false))
                                        .then(CommandManager.argument("blockbyblock", BoolArgumentType.bool())
                                            .executes(ctx -> applyState(ctx,
                                                BoolArgumentType.getBool(ctx, "animated"),
                                                StringArgumentType.getString(ctx, "direction"),
                                                IntegerArgumentType.getInteger(ctx, "speed"),
                                                StringArgumentType.getString(ctx, "particle"),
                                                BoolArgumentType.getBool(ctx, "blockbyblock")))
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        );

        // ── /cg buy <zoneId> [confirm] — zone purchase command ────────────────
        root.then(CommandManager.literal("buy")
            .then(CommandManager.argument("zoneId", StringArgumentType.word())
                .executes(ctx -> buyZone(ctx, false))
                .then(CommandManager.literal("confirm")
                    .executes(ctx -> buyZone(ctx, true))
                )
            )
        );

        root.then(CommandManager.literal("setspawn").requires(src -> src.hasPermissionLevel(2)).executes(ctx -> {
            var p = player(ctx); if (p == null) return 0;
            String worldId = p.getServerWorld().getRegistryKey().getValue().toString();
            var dimMgr = CheeseGuardMod.getInstance().getDimensionManager();
            var config = dimMgr.getConfigByWorldId(worldId);
            if (config == null) {
                p.sendMessage(Text.translatable("cheeseguard.cmd.dim_not_found", worldId).formatted(Formatting.RED), false);
                return 0;
            }
            config.setSpawnX(p.getX());
            config.setSpawnY(p.getY());
            config.setSpawnZ(p.getZ());
            config.setSpawnYaw(p.getYaw());
            config.setSpawnPitch(p.getPitch());
            dimMgr.save();
            p.sendMessage(Text.translatable("cheeseguard.cmd.spawn_set",
                String.format("%.1f", p.getX()), String.format("%.1f", p.getY()), String.format("%.1f", p.getZ()),
                config.getDisplayName()).formatted(Formatting.GREEN), false);
            return 1;
        }));

        // ── Pasture per-chunk limits ───────────────────────────────────────
        root.then(CommandManager.literal("pasture").requires(src -> src.hasPermissionLevel(2))
            .then(CommandManager.literal("limits").executes(ctx -> {
                var cfg = CheeseGuardMod.getInstance().getPastureLimitsManager().getConfig();
                ctx.getSource().sendMessage(Text.translatable("cheeseguard.cmd.pasture_limits",
                    cfg.getMaxPerChunk(), cfg.getMaxPerChunkPerPlayer()).formatted(Formatting.GOLD));
                return 1;
            }))
            .then(CommandManager.literal("chunk").then(CommandManager.argument("max", IntegerArgumentType.integer(0))
                .executes(ctx -> {
                    int v = IntegerArgumentType.getInteger(ctx, "max");
                    var mgr = CheeseGuardMod.getInstance().getPastureLimitsManager();
                    mgr.getConfig().setMaxPerChunk(v);
                    mgr.save();
                    ctx.getSource().sendMessage(Text.translatable("cheeseguard.cmd.pasture_chunk_set", v).formatted(Formatting.GREEN));
                    return 1;
                })))
            .then(CommandManager.literal("player").then(CommandManager.argument("max", IntegerArgumentType.integer(0))
                .executes(ctx -> {
                    int v = IntegerArgumentType.getInteger(ctx, "max");
                    var mgr = CheeseGuardMod.getInstance().getPastureLimitsManager();
                    mgr.getConfig().setMaxPerChunkPerPlayer(v);
                    mgr.save();
                    ctx.getSource().sendMessage(Text.translatable("cheeseguard.cmd.pasture_player_set", v).formatted(Formatting.GREEN));
                    return 1;
                }))));

        // ── /cg restoreinv — restore an inventory from cheeseguard_inv_archive ───
        // Subcommands:
        //   list <player>                          — list available archives (most recent first)
        //   apply <player>                         — apply the most recent archive
        //   apply <player> <filename>              — apply a specific archive file
        // Permission level 2 (op). Online → readNbt; offline → playerdata file swap with .bak.
        root.then(CommandManager.literal("restoreinv").requires(src -> src.hasPermissionLevel(2))
            .then(CommandManager.literal("list")
                .then(CommandManager.argument("player", StringArgumentType.word())
                    .executes(ctx -> restoreInvList(ctx, StringArgumentType.getString(ctx, "player")))))
            .then(CommandManager.literal("apply")
                .then(CommandManager.argument("player", StringArgumentType.word())
                    .executes(ctx -> restoreInvApply(ctx, StringArgumentType.getString(ctx, "player"), null))
                    .then(CommandManager.argument("filename", StringArgumentType.greedyString())
                        .executes(ctx -> restoreInvApply(ctx,
                                StringArgumentType.getString(ctx, "player"),
                                StringArgumentType.getString(ctx, "filename")))))));

        d.register(root);
    }

    private static int restoreInvList(CommandContext<ServerCommandSource> ctx, String playerName) {
        var src = ctx.getSource();
        var server = src.getServer();
        var uuidOpt = com.cheeseguard.logic.InventoryArchiveManager.resolvePlayerUuid(server, playerName);
        if (uuidOpt.isEmpty()) {
            src.sendError(Text.literal("Player not found: " + playerName
                    + " (not online, not in user cache, no matching archive)").formatted(Formatting.RED));
            return 0;
        }
        java.util.UUID playerUuid = uuidOpt.get();
        var archives = com.cheeseguard.logic.InventoryArchiveManager.findFor(server, playerUuid);
        if (archives.isEmpty()) {
            src.sendFeedback(() -> Text.literal(
                    "No archives found for " + playerName + " (UUID " + playerUuid + ")")
                    .formatted(Formatting.GRAY), false);
            return 0;
        }
        src.sendFeedback(() -> Text.literal(
                "Archives for " + playerName + " (UUID " + playerUuid + ") — most recent first:")
                .formatted(Formatting.YELLOW), false);
        for (var a : archives) {
            String filename = a.path().getFileName().toString();
            String label = "  " + a.timestamp() + "  " + filename;
            src.sendFeedback(() -> Text.literal(label)
                    .formatted(Formatting.GRAY)
                    .styled(s -> s
                            .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                                    "/cg restoreinv apply " + playerName + " " + filename))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Text.literal("Click to fill apply command")))),
                    false);
        }
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    private static int restoreInvApply(CommandContext<ServerCommandSource> ctx,
                                        String playerName, String filename) {
        var src = ctx.getSource();
        var server = src.getServer();

        var uuidOpt = com.cheeseguard.logic.InventoryArchiveManager.resolvePlayerUuid(server, playerName);
        if (uuidOpt.isEmpty()) {
            src.sendError(Text.literal("Player not found: " + playerName
                    + " (not online, not in user cache, no matching archive)").formatted(Formatting.RED));
            return 0;
        }
        java.util.UUID playerUuid = uuidOpt.get();
        // Print resolved UUID before any destructive action so the operator can sanity-check.
        src.sendFeedback(() -> Text.literal(
                "Resolved " + playerName + " → UUID " + playerUuid).formatted(Formatting.GRAY), false);

        // Resolve the archive file: explicit filename or "most recent for that UUID".
        com.cheeseguard.logic.InventoryArchiveManager.ArchiveFile archive;
        if (filename != null) {
            var a = com.cheeseguard.logic.InventoryArchiveManager.findByFilename(server, filename);
            if (a.isEmpty()) {
                src.sendError(Text.literal("Archive file not found: " + filename).formatted(Formatting.RED));
                return 0;
            }
            archive = a.get();
            // Sanity: warn (but allow) if the filename's UUID prefix doesn't match the player.
            if (!archive.uuidPrefix().equals(playerUuid.toString().substring(0, 8))) {
                src.sendFeedback(() -> Text.literal(
                        "WARNING: archive UUID prefix " + archive.uuidPrefix()
                                + " does not match " + playerName + " (" + playerUuid.toString().substring(0, 8) + ")")
                        .formatted(Formatting.GOLD), false);
            }
        } else {
            var archives = com.cheeseguard.logic.InventoryArchiveManager.findFor(server, playerUuid);
            if (archives.isEmpty()) {
                src.sendError(Text.literal("No archives found for " + playerName).formatted(Formatting.RED));
                return 0;
            }
            archive = archives.get(0);
        }

        // Read the playerData compound.
        net.minecraft.nbt.NbtCompound playerData;
        try {
            playerData = com.cheeseguard.logic.InventoryArchiveManager.readPlayerData(archive.path());
        } catch (java.io.IOException ioe) {
            src.sendError(Text.literal("Failed to read archive: " + ioe.getMessage()).formatted(Formatting.RED));
            return 0;
        }
        if (playerData == null) {
            src.sendError(Text.literal(
                    "Archive unreadable: missing playerData/Inventory or unsupported format. See server log.")
                    .formatted(Formatting.RED));
            return 0;
        }

        // Online path → readNbt + sync; offline path → playerdata file swap with .bak.
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(playerUuid);
        String archiveLabel = archive.path().getFileName().toString();
        String actor = src.getName();

        if (online != null) {
            boolean ok = com.cheeseguard.logic.InventoryArchiveManager.restoreOnline(online, playerData);
            if (!ok) {
                src.sendError(Text.literal("Restore failed — see server log").formatted(Formatting.RED));
                return 0;
            }
            CheeseGuardMod.LOGGER.info(
                    "[CG] /cg restoreinv: actor={} target={} ({}) archive={} mode=online",
                    actor, playerName, playerUuid, archiveLabel);
            src.sendFeedback(() -> Text.literal(
                    "Inventory restored online for " + playerName + " from " + archiveLabel)
                    .formatted(Formatting.GREEN), true);
            online.sendMessage(Text.literal(
                    "Your inventory has been restored from a CheeseGuard archive (" + archive.timestamp() + ").")
                    .formatted(Formatting.AQUA), false);
            return 1;
        } else {
            try {
                java.nio.file.Path safety = com.cheeseguard.logic.InventoryArchiveManager.restoreOffline(
                        server, playerUuid, playerData);
                CheeseGuardMod.LOGGER.info(
                        "[CG] /cg restoreinv: actor={} target={} ({}) archive={} mode=offline backup={}",
                        actor, playerName, playerUuid, archiveLabel,
                        safety == null ? "none" : safety.getFileName());
                String safetyMsg = safety == null
                        ? " (no prior playerdata file)"
                        : " (safety copy: " + safety.getFileName() + ")";
                src.sendFeedback(() -> Text.literal(
                        "Offline inventory swap done for " + playerName + " from " + archiveLabel + safetyMsg)
                        .formatted(Formatting.GREEN), true);
                return 1;
            } catch (java.io.IOException ioe) {
                src.sendError(Text.literal("Offline restore failed: " + ioe.getMessage()).formatted(Formatting.RED));
                CheeseGuardMod.LOGGER.error(
                        "[CG] /cg restoreinv offline failed: actor={} target={} archive={}",
                        actor, playerName, archiveLabel, ioe);
                return 0;
            }
        }
    }

    private static int applyState(CommandContext<ServerCommandSource> ctx, boolean animated,
                                   String dirStr, int speed, String particleStr, boolean blockByBlock) {
        String zoneId = StringArgumentType.getString(ctx, "zoneId");
        String stateId = StringArgumentType.getString(ctx, "stateId");
        var mgr = CheeseGuardMod.getInstance().getZoneStateManager();
        var dir = com.cheeseguard.logic.ZoneStateManager.Direction.fromString(dirStr);
        var particle = com.cheeseguard.logic.ZoneStateManager.StateParticle.fromString(particleStr);
        // Pass the command source's world so per-player dimensions are handled correctly
        String sourceWorldId = ctx.getSource().getWorld().getRegistryKey().getValue().toString();
        String error = mgr.applyState(zoneId, stateId, animated, dir, speed, particle, blockByBlock, sourceWorldId);
        if (error != null) {
            ctx.getSource().sendError(Text.translatable("cheeseguard.state.error", error));
            return 0;
        }
        if (animated) {
            ctx.getSource().sendFeedback(() -> Text.translatable("cheeseguard.state.animating", stateId, zoneId).formatted(Formatting.AQUA), false);
        } else {
            ctx.getSource().sendFeedback(() -> Text.translatable("cheeseguard.state.applied", stateId, zoneId).formatted(Formatting.GREEN), false);
        }
        return 1;
    }

    private static int buyZone(CommandContext<ServerCommandSource> ctx, boolean confirm) {
        ServerPlayerEntity player = player(ctx);
        if (player == null) return 0;
        String zoneId = StringArgumentType.getString(ctx, "zoneId");
        var storage = CheeseGuardMod.getInstance().getStorage();
        var region = storage.getRegion(zoneId);

        if (region == null) {
            player.sendMessage(Text.translatable("cheeseguard.sale.zoneNotFound"), false);
            return 0;
        }
        if (region.getSaleConfig() == null || !region.getSaleConfig().isEnabled()) {
            player.sendMessage(Text.translatable("cheeseguard.sale.notForSale"), false);
            return 0;
        }
        if (region.getSaleConfig().isSold()) {
            player.sendMessage(Text.translatable("cheeseguard.sale.alreadySold"), false);
            return 0;
        }

        if (!confirm) {
            com.cheeseguard.logic.SaleManager.sendPurchasePrompt(player, region);
            return 1;
        }

        // Execute purchase
        var tagStorage = CheeseGuardMod.getInstance().getTagConfigStorage();
        String error = com.cheeseguard.logic.SaleManager.processPurchase(player, region, storage, tagStorage);
        if (error != null) {
            player.sendMessage(Text.literal(error).formatted(Formatting.RED), false);
        } else {
            player.sendMessage(Text.translatable("cheeseguard.sale.purchaseSuccess", region.getName())
                .formatted(Formatting.GREEN), false);
        }
        return 1;
    }

    private static int openGui(CommandContext<ServerCommandSource> ctx) {
        var player = player(ctx);
        if (player == null) return 0;
        // Admin only
        if (!ctx.getSource().hasPermissionLevel(2)) {
            player.sendMessage(Text.literal("═══ CheeseGuard ═══").formatted(Formatting.GOLD, Formatting.BOLD), false);
            player.sendMessage(Text.literal("/cg event join").formatted(Formatting.GREEN).append(Text.literal(" — ").formatted(Formatting.GRAY)).append(Text.translatable("cheeseguard.cmd.event_help_join").formatted(Formatting.GRAY)), false);
            player.sendMessage(Text.literal("/cg event leave").formatted(Formatting.GREEN).append(Text.literal(" — ").formatted(Formatting.GRAY)).append(Text.translatable("cheeseguard.cmd.event_help_leave").formatted(Formatting.GRAY)), false);
            player.sendMessage(Text.literal("/cg event spectate").formatted(Formatting.GREEN).append(Text.literal(" — ").formatted(Formatting.GRAY)).append(Text.translatable("cheeseguard.cmd.event_help_spectate").formatted(Formatting.GRAY)), false);
            player.sendMessage(Text.literal("/cg event list").formatted(Formatting.GREEN).append(Text.literal(" — ").formatted(Formatting.GRAY)).append(Text.translatable("cheeseguard.cmd.event_help_list").formatted(Formatting.GRAY)), false);
            return 1;
        }
        player.sendMessage(Text.translatable("cheeseguard.cmd.dashboard_notice").formatted(Formatting.GOLD), false);
        return 1;
    }

    private static ServerPlayerEntity player(CommandContext<ServerCommandSource> ctx) {
        try { return ctx.getSource().getPlayerOrThrow(); }
        catch (Exception e) { return null; }
    }

    private static String shortWorld(String w) {
        if (w.contains("overworld")) return "Overworld";
        if (w.contains("nether")) return "Nether";
        if (w.contains("end")) return "End";
        return w;
    }
}
