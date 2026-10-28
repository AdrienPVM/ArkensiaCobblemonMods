package com.cheesemarket.command;

import com.cheesemarket.data.ShopData;
import com.cheesemarket.manager.ShopManager;
import com.cheesemarket.network.ShopPayloads.*;
import com.cheesemarket.server.ShopGuiHandler;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class ShopCommands {

    /**
     * Register all CheeseMarket commands.
     *
     * /cmadmin           — Opens admin dashboard (requires op level 2)
     * /shop open <id>    — Opens a shop for a player (op only by design)
     * /shop list         — Lists all shops in chat
     * /shop reload       — Reloads shops from disk
     */
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                 CommandRegistryAccess access,
                                 CommandManager.RegistrationEnvironment env) {
        // ── /cmadmin ──────────────────────────────────────────────────────────
        dispatcher.register(
            CommandManager.literal("cmadmin")
                .requires(src -> src.hasPermissionLevel(2))
                .executes(ShopCommands::openAdmin)
        );

        // ── /shop ─────────────────────────────────────────────────────────────
        dispatcher.register(
            CommandManager.literal("shop")
                .requires(src -> src.hasPermissionLevel(2))

                .then(CommandManager.literal("open")
                    .then(CommandManager.argument("shopId", StringArgumentType.word())
                        .executes(ShopCommands::openShopForPlayer)))

                .then(CommandManager.literal("list")
                    .executes(ShopCommands::listShops))

                .then(CommandManager.literal("reload")
                    .requires(src -> src.hasPermissionLevel(4))
                    .executes(ShopCommands::reloadShops))

                .then(CommandManager.literal("create")
                    .requires(src -> src.hasPermissionLevel(2))
                    .then(CommandManager.argument("shopId", StringArgumentType.word())
                        .executes(ShopCommands::createShop)))

                .then(CommandManager.literal("delete")
                    .requires(src -> src.hasPermissionLevel(4))
                    .then(CommandManager.argument("shopId", StringArgumentType.word())
                        .executes(ShopCommands::deleteShop)))
        );
    }

    // ── /cmadmin ──────────────────────────────────────────────────────────────

    private static int openAdmin(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) { ctx.getSource().sendError(Text.translatable("cheesemarket.cmd.player_required")); return 0; }

        // Tell client to open admin GUI, then send shop list data
        ServerPlayNetworking.send(player, new ShopOpenPayload(true));
        ShopGuiHandler.handle(new ShopActionPayload("open_shop_list"), player);
        return 1;
    }

    // ── /shop open <shopId> ───────────────────────────────────────────────────

    private static int openShopForPlayer(CommandContext<ServerCommandSource> ctx) {
        String shopId = StringArgumentType.getString(ctx, "shopId");
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) { ctx.getSource().sendError(Text.translatable("cheesemarket.cmd.player_required")); return 0; }

        ShopData shop = ShopManager.getInstance().getShop(shopId);
        if (shop == null) {
            ctx.getSource().sendError(Text.translatable("cheesemarket.cmd.shop_not_found", shopId));
            return 0;
        }
        // Auto-populate from morpho catalog when the shop has the flag turned on.
        shop = com.cheesemarket.server.MorphoShopRenderer.materialize(shop);

        // Build player-facing view data and open GUI
        ServerPlayNetworking.send(player, new ShopOpenPayload(false));

        // Build shop view data (reuse ShopGuiHandler's method — includes resolvedCheeseItems, images, etc.)
        String json = ShopGuiHandler.buildPlayerShopViewJson(shop, null, player);
        ServerPlayNetworking.send(player, new ShopSyncPayload("SHOP_VIEW", json));
        return 1;
    }

    // ── /shop list ────────────────────────────────────────────────────────────

    private static int listShops(CommandContext<ServerCommandSource> ctx) {
        var shops = ShopManager.getInstance().getAllShops();
        if (shops.isEmpty()) {
            ctx.getSource().sendFeedback(() -> Text.translatable("cheesemarket.cmd.no_shops").formatted(Formatting.YELLOW), false);
        } else {
            ctx.getSource().sendFeedback(() -> Text.translatable("cheesemarket.cmd.shops_header").formatted(Formatting.GOLD), false);
            for (ShopData shop : shops) {
                ctx.getSource().sendFeedback(() -> Text.literal(
                    shop.getId() + " \u2014 " + shop.getName() +
                    " (" + shop.getSets().size() + " sets)"
                ).formatted(Formatting.YELLOW), false);
            }
        }
        return 1;
    }

    // ── /shop reload ──────────────────────────────────────────────────────────

    private static int reloadShops(CommandContext<ServerCommandSource> ctx) {
        ShopManager.getInstance().loadAll();
        ctx.getSource().sendFeedback(() -> Text.translatable("cheesemarket.cmd.shops_reloaded").formatted(Formatting.GREEN), true);
        return 1;
    }

    // ── /shop create ──────────────────────────────────────────────────────────

    private static int createShop(CommandContext<ServerCommandSource> ctx) {
        String id = StringArgumentType.getString(ctx, "shopId");
        if (ShopManager.getInstance().shopExists(id)) {
            ctx.getSource().sendError(Text.translatable("cheesemarket.cmd.shop_exists", id));
            return 0;
        }
        ShopData shop = ShopManager.getInstance().createShop(id);
        ctx.getSource().sendFeedback(() -> Text.translatable("cheesemarket.cmd.shop_created", shop.getId()).formatted(Formatting.GREEN), true);
        return 1;
    }

    // ── /shop delete ──────────────────────────────────────────────────────────

    private static int deleteShop(CommandContext<ServerCommandSource> ctx) {
        String id = StringArgumentType.getString(ctx, "shopId");
        if (!ShopManager.getInstance().shopExists(id)) {
            ctx.getSource().sendError(Text.translatable("cheesemarket.cmd.shop_not_found", id));
            return 0;
        }
        ShopManager.getInstance().deleteShop(id);
        ctx.getSource().sendFeedback(() -> Text.translatable("cheesemarket.cmd.shop_deleted", id).formatted(Formatting.RED), true);
        return 1;
    }
}
