package com.cobblecap.client;

import com.mojang.brigadier.arguments.BoolArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Client-side command /pasturehide [on|off|toggle].
 * Affects ONLY the local player's view — no server packets.
 */
public final class PastureHideCommand {

    private PastureHideCommand() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, reg) -> {
            var root = ClientCommandManager.literal("pasturehide");
            root.executes(ctx -> { // no arg = toggle
                boolean now = PastureHideClient.toggle();
                feedback(ctx.getSource(), now);
                return 1;
            });
            root.then(ClientCommandManager.literal("on").executes(ctx -> {
                PastureHideClient.setHidden(true);
                feedback(ctx.getSource(), true);
                return 1;
            }));
            root.then(ClientCommandManager.literal("off").executes(ctx -> {
                PastureHideClient.setHidden(false);
                feedback(ctx.getSource(), false);
                return 1;
            }));
            root.then(ClientCommandManager.literal("toggle").executes(ctx -> {
                boolean now = PastureHideClient.toggle();
                feedback(ctx.getSource(), now);
                return 1;
            }));
            root.then(ClientCommandManager.literal("status").executes(ctx -> {
                boolean now = PastureHideClient.isHidden();
                ctx.getSource().sendFeedback(Text.literal(
                        "Pasture Pokémon hidden: " + (now ? "ON" : "OFF"))
                        .formatted(Formatting.GRAY));
                return 1;
            }));
            dispatcher.register(root);
        });
    }

    private static void feedback(FabricClientCommandSource src, boolean hidden) {
        src.sendFeedback(Text.literal(hidden
                ? "🙈 Pasture Pokémon hidden (client-only)"
                : "👁 Pasture Pokémon visible again")
                .formatted(hidden ? Formatting.YELLOW : Formatting.GREEN));
    }
}
