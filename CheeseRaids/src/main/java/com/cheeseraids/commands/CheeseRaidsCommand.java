package com.cheeseraids.commands;

import com.cheeseraids.CheeseRaidsMod;
import com.cheeseraids.data.RaidDefinition;
import com.cheeseraids.data.RaidInstance;
import com.cheeseraids.logic.RaidManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class CheeseRaidsCommand {
    private CheeseRaidsCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("cheeseraids")
            .then(literal("start")
                .requires(s -> s.hasPermissionLevel(2))
                .then(argument("raid", StringArgumentType.word())
                    .executes(ctx -> {
                        String id = StringArgumentType.getString(ctx, "raid");
                        boolean ok = mgr().start(id);
                        reply(ctx.getSource(), ok ? "Raid '" + id + "' démarré." : "Impossible de démarrer '" + id + "' (inconnu/désactivé/déjà actif).");
                        return ok ? 1 : 0;
                    })))
            .then(literal("stop")
                .requires(s -> s.hasPermissionLevel(2))
                .then(argument("raid", StringArgumentType.word())
                    .executes(ctx -> {
                        String id = StringArgumentType.getString(ctx, "raid");
                        boolean ok = mgr().stop(id);
                        reply(ctx.getSource(), ok ? "Raid '" + id + "' stoppé." : "Aucune instance active pour '" + id + "'.");
                        return ok ? 1 : 0;
                    })))
            .then(literal("reload")
                .requires(s -> s.hasPermissionLevel(2))
                .executes(ctx -> {
                    mgr().reload();
                    reply(ctx.getSource(), "Définitions de raids rechargées.");
                    return 1;
                }))
            .then(literal("status")
                .requires(s -> s.hasPermissionLevel(2))
                .executes(ctx -> {
                    StringBuilder sb = new StringBuilder("§dRaids:§r\n");
                    for (RaidDefinition def : mgr().definitions()) {
                        RaidInstance inst = mgr().activeInstance(def.id);
                        sb.append("• ").append(def.id)
                          .append(inst != null ? " §a[" + inst.phase + ", " + inst.participantCount() + " j]" : " §7[idle]")
                          .append(def.schedule.autoTrigger ? " §8(auto " + def.schedule.hour + "h" + String.format("%02d", def.schedule.minute) + ")" : "")
                          .append("§r\n");
                    }
                    reply(ctx.getSource(), sb.toString().trim());
                    return 1;
                }))
            .then(literal("enter")
                .then(argument("raid", StringArgumentType.word())
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) { reply(ctx.getSource(), "Réservé aux joueurs."); return 0; }
                        String id = StringArgumentType.getString(ctx, "raid");
                        return mgr().enter(p, id) ? 1 : 0;
                    })))
            .then(literal("herald")
                .requires(s -> s.hasPermissionLevel(2))
                .then(argument("raid", StringArgumentType.word())
                    .then(literal("info").executes(ctx -> {
                        reply(ctx.getSource(), mgr().heraldDiagnose(StringArgumentType.getString(ctx, "raid")));
                        return 1;
                    }))
                    .then(literal("show").executes(ctx -> {
                        int n = mgr().heraldToggle(StringArgumentType.getString(ctx, "raid"), true);
                        reply(ctx.getSource(), n < 0 ? "Raid inconnu." : (n + " EasyNPC rendu(s) visible(s)."));
                        return n > 0 ? 1 : 0;
                    }))
                    .then(literal("hide").executes(ctx -> {
                        int n = mgr().heraldToggle(StringArgumentType.getString(ctx, "raid"), false);
                        reply(ctx.getSource(), n < 0 ? "Raid inconnu." : (n + " EasyNPC masqué(s)."));
                        return n > 0 ? 1 : 0;
                    }))))
            .then(literal("testreward")
                .requires(s -> s.hasPermissionLevel(2))
                .then(argument("raid", StringArgumentType.word())
                    .executes(ctx -> {
                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                        if (p == null) { reply(ctx.getSource(), "Réservé aux joueurs."); return 0; }
                        return mgr().testReward(p, StringArgumentType.getString(ctx, "raid")) ? 1 : 0;
                    })))
        );
    }

    private static RaidManager mgr() { return CheeseRaidsMod.INSTANCE.getRaidManager(); }

    private static void reply(ServerCommandSource src, String msg) {
        src.sendFeedback(() -> Text.literal(msg).formatted(Formatting.LIGHT_PURPLE), false);
    }
}
