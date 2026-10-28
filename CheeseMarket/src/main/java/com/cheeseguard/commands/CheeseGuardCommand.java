package com.cheeseguard.commands;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.*;
import com.cheeseguard.gui.*;
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
            p.sendMessage(Text.literal("✅ Position 1 : " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()).formatted(Formatting.GREEN), false);
            return 1;
        }));

        root.then(CommandManager.literal("pos2").requires(src -> src.hasPermissionLevel(2)).executes(ctx -> {
            var p = player(ctx); if (p == null) return 0;
            var pos = p.getBlockPos();
            var wid = p.getServerWorld().getRegistryKey().getValue().toString();
            CheeseGuardMod.getInstance().getSelectionManager().setPos2(p.getUuid(), wid, pos);
            p.sendMessage(Text.literal("✅ Position 2 : " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()).formatted(Formatting.AQUA), false);
            return 1;
        }));

        root.then(CommandManager.literal("create").requires(src -> src.hasPermissionLevel(2))
            .then(CommandManager.argument("name", StringArgumentType.greedyString())
                .executes(ctx -> {
                    var p = player(ctx); if (p == null) return 0;
                    String name = StringArgumentType.getString(ctx, "name");
                    var sel = CheeseGuardMod.getInstance().getSelectionManager().getSelection(p.getUuid());
                    if (sel == null || !sel.isComplete()) {
                        p.sendMessage(Text.literal("❌ Aucune sélection ! Utilisez le wand.").formatted(Formatting.RED), false);
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
                        p.sendMessage(Text.literal("❌ Zone introuvable : " + id).formatted(Formatting.RED), false);
                        return 0;
                    }
                    storage.removeRegion(id);
                    p.sendMessage(Text.literal("🗑 Zone supprimée : " + id).formatted(Formatting.GREEN), false);
                    return 1;
                })));

        root.then(CommandManager.literal("list").requires(src -> src.hasPermissionLevel(2)).executes(ctx -> {
            var p = player(ctx); if (p == null) return 0;
            var storage = CheeseGuardMod.getInstance().getRegionManager().getStorage();
            p.sendMessage(Text.literal("═══ Zones (" + storage.getRegions().stream().count() + ") ═══").formatted(Formatting.GOLD, Formatting.BOLD), false);
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
                    if (r == null) { p.sendMessage(Text.literal("❌ Zone introuvable.").formatted(Formatting.RED), false); return 0; }
                    p.sendMessage(Text.literal("═══ " + r.getName() + " ═══").formatted(Formatting.GOLD), false);
                    p.sendMessage(Text.literal("ID: " + r.getId() + " | " + shortWorld(r.getWorld())).formatted(Formatting.GRAY), false);
                    p.sendMessage(Text.literal("Min: " + r.getMinX() + "," + r.getMinY() + "," + r.getMinZ()).formatted(Formatting.GRAY), false);
                    p.sendMessage(Text.literal("Max: " + r.getMaxX() + "," + r.getMaxY() + "," + r.getMaxZ()).formatted(Formatting.GRAY), false);
                    p.sendMessage(Text.literal("Groupe: " + (r.getGroupId() != null ? r.getGroupId() : "aucun")).formatted(Formatting.GRAY), false);
                    p.sendMessage(Text.literal("Priorité: " + r.getPriority() + " | Membres: " + r.getMembers().size()).formatted(Formatting.GRAY), false);
                    return 1;
                })));

        root.then(CommandManager.literal("tp")
            .then(CommandManager.argument("id", StringArgumentType.word())
                .executes(ctx -> {
                    var p = player(ctx); if (p == null) return 0;
                    String id = StringArgumentType.getString(ctx, "id");
                    var r = CheeseGuardMod.getInstance().getRegionManager().getStorage().getRegion(id);
                    if (r == null) { p.sendMessage(Text.literal("Zone introuvable.").formatted(Formatting.RED), false); return 0; }
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
                    p.sendMessage(Text.literal("TP vers : " + r.getName()).formatted(Formatting.GREEN), false);
                    return 1;
                })));

        root.then(CommandManager.literal("toggle").executes(ctx -> {
            var p = player(ctx); if (p == null) return 0;
            CheeseGuardMod.getInstance().getVisualizationManager().toggle(p.getUuid());
            boolean on = CheeseGuardMod.getInstance().getVisualizationManager().isVisualizing(p.getUuid());
            p.sendMessage(Text.literal("👁 Visualisation : " + (on ? "✅ Activée" : "❌ Désactivée"))
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
                            if (r == null) { p.sendMessage(Text.literal("❌ Zone introuvable.").formatted(Formatting.RED), false); return 0; }
                            Flag flag = Flag.fromId(flagId);
                            if (flag == null) { p.sendMessage(Text.literal("❌ Flag inconnu : " + flagId).formatted(Formatting.RED), false); return 0; }
                            if (val.equalsIgnoreCase("inherit") || val.equalsIgnoreCase("null")) r.setFlag(flag, null);
                            else r.setFlag(flag, Boolean.parseBoolean(val));
                            CheeseGuardMod.getInstance().getRegionManager().getStorage().save();
                            p.sendMessage(Text.literal("✅ Flag " + flagId + " = " + val).formatted(Formatting.GREEN), false);
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
                        if (r == null) { admin.sendMessage(Text.literal("❌ Zone introuvable.").formatted(Formatting.RED), false); return 0; }
                        var target = CheeseGuardMod.getInstance().getServer().getPlayerManager().getPlayer(name);
                        if (target == null) { admin.sendMessage(Text.literal("❌ Joueur introuvable.").formatted(Formatting.RED), false); return 0; }
                        r.addMember(new Member(target.getUuid(), target.getName().getString()));
                        CheeseGuardMod.getInstance().getRegionManager().getStorage().save();
                        admin.sendMessage(Text.literal("✅ " + name + " ajouté.").formatted(Formatting.GREEN), false);
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
                        if (r == null) { admin.sendMessage(Text.literal("❌ Zone introuvable.").formatted(Formatting.RED), false); return 0; }
                        r.getMembers().stream()
                            .filter(m -> m.getName().equalsIgnoreCase(name))
                            .findFirst()
                            .ifPresent(m -> r.removeMember(m.getUuid()));
                        CheeseGuardMod.getInstance().getRegionManager().getStorage().save();
                        admin.sendMessage(Text.literal("✅ " + name + " retiré.").formatted(Formatting.GREEN), false);
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
                        p.sendMessage(Text.literal("❌ Groupe déjà existant.").formatted(Formatting.RED), false);
                        return 0;
                    }
                    storage.addGroup(new RegionGroup(id, name));
                    p.sendMessage(Text.literal("✅ Groupe créé : " + name).formatted(Formatting.GREEN), false);
                    return 1;
                })));

        groupNode.then(CommandManager.literal("delete")
            .then(CommandManager.argument("id", StringArgumentType.word())
                .executes(ctx -> {
                    var p = player(ctx); if (p == null) return 0;
                    String id = StringArgumentType.getString(ctx, "id");
                    CheeseGuardMod.getInstance().getRegionManager().getStorage().removeGroup(id);
                    p.sendMessage(Text.literal("🗑 Groupe supprimé : " + id).formatted(Formatting.GREEN), false);
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
                            p.sendMessage(Text.literal("❌ Zone ou groupe introuvable.").formatted(Formatting.RED), false);
                            return 0;
                        }
                        r.setGroupId(gid);
                        g.addRegion(rid);
                        storage.save();
                        p.sendMessage(Text.literal("✅ " + r.getName() + " → groupe " + g.getName()).formatted(Formatting.GREEN), false);
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
                        admin.sendMessage(Text.literal("❌ Joueur non trouvé : " + targetName).formatted(Formatting.RED), false);
                        return 0;
                    }
                    admin.sendMessage(Text.literal("═══ Advancements de " + target.getName().getString() + " ═══").formatted(Formatting.GOLD, Formatting.BOLD), false);
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
                    admin.sendMessage(Text.literal(done + "/" + total + " advancements complétés").formatted(Formatting.YELLOW), false);
                    return 1;
                })));
        advNode.then(CommandManager.literal("list").executes(ctx -> {
            var admin = player(ctx); if (admin == null) return 0;
            admin.sendMessage(Text.literal("═══ Tous les advancements ═══").formatted(Formatting.GOLD, Formatting.BOLD), false);
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
            admin.sendMessage(Text.literal(count + " advancements au total").formatted(Formatting.YELLOW), false);
            return 1;
        }));
        root.then(advNode);

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
                p.sendMessage(Text.literal("Aucun evenement disponible a rejoindre").formatted(Formatting.YELLOW), false);
                return 0;
            })
            // /cg event join <eventId>
            .then(CommandManager.argument("eventId", StringArgumentType.word())
                .executes(ctx -> {
                    var p = player(ctx); if (p == null) return 0;
                    String eid = StringArgumentType.getString(ctx, "eventId");
                    var evt = CheeseGuardMod.getInstance().getEventManager().getEvent(eid);
                    if (evt == null) { p.sendMessage(Text.literal("Evenement introuvable").formatted(Formatting.RED), false); return 0; }
                    if (CheeseGuardMod.getInstance().getEventManager().joinEvent(evt, p, com.cheeseguard.data.EventParticipant.Role.PARTICIPANT)) return 1;
                    p.sendMessage(Text.literal("Impossible de rejoindre cet evenement").formatted(Formatting.RED), false);
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
                p.sendMessage(Text.literal("Aucun evenement disponible").formatted(Formatting.YELLOW), false);
                return 0;
            })
            // /cg event spectate <eventId>
            .then(CommandManager.argument("eventId", StringArgumentType.word())
                .executes(ctx -> {
                    var p = player(ctx); if (p == null) return 0;
                    String eid = StringArgumentType.getString(ctx, "eventId");
                    var evt = CheeseGuardMod.getInstance().getEventManager().getEvent(eid);
                    if (evt == null) { p.sendMessage(Text.literal("Evenement introuvable").formatted(Formatting.RED), false); return 0; }
                    if (CheeseGuardMod.getInstance().getEventManager().joinEvent(evt, p, com.cheeseguard.data.EventParticipant.Role.SPECTATOR)) return 1;
                    p.sendMessage(Text.literal("Impossible d'observer cet evenement").formatted(Formatting.RED), false);
                    return 0;
                })));

        eventCmd.then(CommandManager.literal("leave").executes(ctx -> {
            var p = player(ctx); if (p == null) return 0;
            for (var evt : CheeseGuardMod.getInstance().getEventManager().getEvents()) {
                if (evt.getParticipant(p.getUuid()) != null) {
                    CheeseGuardMod.getInstance().getEventManager().leaveEvent(evt, p);
                    return 1;
                }
            }
            p.sendMessage(Text.literal("Vous ne participez a aucun evenement").formatted(Formatting.YELLOW), false);
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
                            if (pl == null) { ctx.getSource().sendError(Text.literal("Joueur introuvable")); return 0; }
                            for (var evt : CheeseGuardMod.getInstance().getEventManager().getEvents()) {
                                if (evt.isActive() && evt.getParticipant(pl.getUuid()) != null) {
                                    CheeseGuardMod.getInstance().getEventManager().addScore(evt, pl.getUuid(), amount);
                                    ctx.getSource().sendFeedback(() -> Text.literal("+" + amount + " pts pour " + target).formatted(Formatting.GREEN), false);
                                    return 1;
                                }
                            }
                            ctx.getSource().sendError(Text.literal("Ce joueur ne participe a aucun evenement actif"));
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
                        .append(Text.literal("[Rejoindre]").styled(s -> s
                            .withColor(Formatting.GREEN).withBold(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cg event join " + evt.getId()))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Cliquez pour participer")))));
                    p.sendMessage(line, false);
                    found = true;
                }
            }
            if (!found) p.sendMessage(Text.literal("Aucun evenement en cours").formatted(Formatting.GRAY), false);
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
                                ctx.getSource().sendFeedback(() -> Text.literal("✅ Snapshot '" + snapId + "': " + count + " blocs").formatted(Formatting.GREEN), false);
                            } else {
                                ctx.getSource().sendError(Text.literal("Aucun bloc dans la zone " + regionId));
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
                            ctx.getSource().sendFeedback(() -> Text.literal("✅ " + count + " blocs restaures").formatted(Formatting.GREEN), false);
                            return 1;
                        }
                        ctx.getSource().sendError(Text.literal("Snapshot introuvable: " + snapId));
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
                            if (evt == null) { ctx.getSource().sendError(Text.literal("Evenement introuvable")); return 0; }
                            boolean ok = com.cheeseguard.logic.EventTemplate.save(ctx.getSource().getServer(), evt, tplName);
                            if (ok) ctx.getSource().sendFeedback(() -> Text.literal("✅ Template sauvegarde: " + tplName).formatted(Formatting.GREEN), false);
                            else ctx.getSource().sendError(Text.literal("Erreur sauvegarde template"));
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
                            ctx.getSource().sendFeedback(() -> Text.literal("✅ Evenement cree: " + loaded.getName()).formatted(Formatting.GREEN), false);
                            return 1;
                        }
                        ctx.getSource().sendError(Text.literal("Template introuvable: " + tplName));
                        return 0;
                    })))
            .then(CommandManager.literal("list")
                .executes(ctx -> {
                    var templates = com.cheeseguard.logic.EventTemplate.list(ctx.getSource().getServer());
                    if (templates.isEmpty()) {
                        ctx.getSource().sendFeedback(() -> Text.literal("Aucun template").formatted(Formatting.GRAY), false);
                    } else {
                        for (String t : templates) {
                            ctx.getSource().sendFeedback(() -> Text.literal("  📄 " + t).formatted(Formatting.YELLOW), false);
                        }
                    }
                    return 1;
                })));

        root.then(eventCmd);

        // ── /chunkstoclear — available to all players ──────────────────────────
        LiteralArgumentBuilder<ServerCommandSource> chunksCmd = CommandManager.literal("chunkstoclear");
        chunksCmd.then(CommandManager.literal("suggest").executes(ctx -> {
            ServerPlayerEntity p = player(ctx);
            if (p == null) return 0;
            ChunkSuggestionStorage suggStorage = CheeseGuardMod.getInstance().getChunkSuggestionStorage();
            if (suggStorage == null) {
                p.sendMessage(Text.literal("❌ Système de suggestions non disponible.").formatted(Formatting.RED), false);
                return 0;
            }

            // Check if player's current region is already covered by a ChunkSet
            ChunkSetStorage chunkStorage = CheeseGuardMod.getInstance().getChunkSetStorage();
            if (chunkStorage != null) {
                int playerRegionX = (int) Math.floor(p.getX()) >> 9;
                int playerRegionZ = (int) Math.floor(p.getZ()) >> 9;
                String matchedSet = null;
                outer:
                for (java.util.Map.Entry<String, ChunkSet> entry : chunkStorage.getAllSets().entrySet()) {
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
                    p.sendMessage(Text.literal(
                        "ℹ Cette région fait déjà partie du set \"" + matchedSet +
                        "\" et sera régénérée par l'administrateur. Pas besoin de suggérer !")
                        .formatted(Formatting.YELLOW), false);
                    return 0;
                }
            }

            String id = java.util.UUID.randomUUID().toString().substring(0, 8);
            String world = p.getServerWorld().getRegistryKey().getValue().toString();
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
                Text.literal("✅ Suggestion envoyée à l'administrateur ! (x:" +
                    Math.round(p.getX()) + " y:" + Math.round(p.getY()) + " z:" + Math.round(p.getZ()) + ")")
                    .formatted(Formatting.GREEN),
                false
            );
            return 1;
        }));
        d.register(chunksCmd);

        d.register(root);
    }

    private static int openGui(CommandContext<ServerCommandSource> ctx) {
        var player = player(ctx);
        if (player == null) return 0;
        // Admin only
        if (!ctx.getSource().hasPermissionLevel(2)) {
            player.sendMessage(Text.literal("═══ CheeseGuard ═══").formatted(Formatting.GOLD, Formatting.BOLD), false);
            player.sendMessage(Text.literal("/cg event join").formatted(Formatting.GREEN).append(Text.literal(" — Rejoindre un evenement").formatted(Formatting.GRAY)), false);
            player.sendMessage(Text.literal("/cg event leave").formatted(Formatting.GREEN).append(Text.literal(" — Quitter un evenement").formatted(Formatting.GRAY)), false);
            player.sendMessage(Text.literal("/cg event spectate").formatted(Formatting.GREEN).append(Text.literal(" — Observer un evenement").formatted(Formatting.GRAY)), false);
            player.sendMessage(Text.literal("/cg event list").formatted(Formatting.GREEN).append(Text.literal(" — Voir les evenements en cours").formatted(Formatting.GRAY)), false);
            return 1;
        }
        if (CheeseGuardMod.getInstance().hasClientMod(player)) {
            CheeseGuardMod.getInstance().sendMain(player);
        } else {
            new MainGui(player).open();
        }
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
