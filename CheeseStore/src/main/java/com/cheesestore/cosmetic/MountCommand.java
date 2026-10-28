package com.cheesestore.cosmetic;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * The {@code /mount} command (alias {@code /monture}) — summon / dismiss the player's equipped
 * cosmetic {@link MountManager MOUNT}.
 *
 * <ul>
 *   <li>{@code /mount} — toggle: summon if none is out, otherwise dismiss.</li>
 *   <li>{@code /mount summon} — explicitly summon.</li>
 *   <li>{@code /mount dismiss} — explicitly dismiss.</li>
 * </ul>
 *
 * <p>Contract: {@code public static void register()} — idempotent; registers its own
 * {@link CommandRegistrationCallback}.
 */
public final class MountCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseStore");
    private static boolean registered = false;

    private MountCommand() {}

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            LiteralArgumentBuilder<ServerCommandSource> root = literal("mount")
                    .executes(MountCommand::toggle)
                    .then(literal("summon").executes(MountCommand::summon))
                    .then(literal("dismiss").executes(MountCommand::dismiss));
            dispatcher.register(root);
            // Alias: /monture → /mount
            dispatcher.register(literal("monture").redirect(dispatcher.getRoot().getChild("mount")));
        });
        LOGGER.info("[CheeseStore] MountCommand registered (/mount, /monture)");
    }

    private static ServerPlayerEntity require(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendError(Text.literal("Cette commande doit être exécutée par un joueur."));
        }
        return player;
    }

    private static int toggle(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity p = require(ctx);
        if (p == null) return 0;
        MountManager.toggle(p);
        return 1;
    }

    private static int summon(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity p = require(ctx);
        if (p == null) return 0;
        MountManager.summon(p);
        return 1;
    }

    private static int dismiss(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity p = require(ctx);
        if (p == null) return 0;
        MountManager.dismiss(p);
        return 1;
    }
}
