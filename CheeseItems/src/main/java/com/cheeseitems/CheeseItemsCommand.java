package com.cheeseitems;

import com.cheeseitems.morpho.MorphoCatalog;
import com.cheeseitems.morpho.MorphoCodex;
import com.cheeseitems.morpho.MorphoCodexEntry;
import com.cheeseitems.morpho.MorphoEntry;
import com.cheeseitems.morpho.MorphoManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandSource;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * /cheeseitem give <player> <item_id> [quantity]
 * /cheeseitem list
 * /cheeseitem reload
 */
public class CheeseItemsCommand {

    private static final SuggestionProvider<ServerCommandSource> ITEM_ID_SUGGESTIONS = (ctx, builder) -> {
        ItemManager mgr = CheeseItemsMod.INSTANCE.getItemManager();
        return CommandSource.suggestMatching(
                mgr.getAllItems().stream().map(CustomItemDefinition::getId),
                builder
        );
    };

    private static final SuggestionProvider<ServerCommandSource> PLAYER_SUGGESTIONS = (ctx, builder) ->
            CommandSource.suggestMatching(
                    ctx.getSource().getServer().getPlayerNames(),
                    builder
            );

    private static final SuggestionProvider<ServerCommandSource> MORPHO_ID_SUGGESTIONS = (ctx, builder) ->
            CommandSource.suggestMatching(
                    MorphoCatalog.getInstance().getAll().stream().map(MorphoEntry::getId),
                    builder
            );

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("cheeseitem")
                .requires(src -> src.hasPermissionLevel(2))

                // /cheeseitem give <player> <item_id> [quantity]
                .then(CommandManager.literal("give")
                        .then(CommandManager.argument("player", StringArgumentType.word())
                                .suggests(PLAYER_SUGGESTIONS)
                                .then(CommandManager.argument("item_id", StringArgumentType.word())
                                        .suggests(ITEM_ID_SUGGESTIONS)
                                        .executes(ctx -> executeGive(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"),
                                                StringArgumentType.getString(ctx, "item_id"),
                                                1))
                                        .then(CommandManager.argument("quantity", IntegerArgumentType.integer(1, 64))
                                                .executes(ctx -> executeGive(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "item_id"),
                                                        IntegerArgumentType.getInteger(ctx, "quantity")))))))

                // /cheeseitem list
                .then(CommandManager.literal("list")
                        .executes(ctx -> executeList(ctx.getSource())))

                // /cheeseitem reload
                .then(CommandManager.literal("reload")
                        .executes(ctx -> executeReload(ctx.getSource())))

                // /cheeseitem morpho grant <player> <morpho_id>
                .then(CommandManager.literal("morpho")
                        .then(CommandManager.literal("grant")
                                .then(CommandManager.argument("player", StringArgumentType.word())
                                        .suggests(PLAYER_SUGGESTIONS)
                                        .then(CommandManager.argument("morpho_id", StringArgumentType.word())
                                                .suggests(MORPHO_ID_SUGGESTIONS)
                                                .executes(ctx -> executeMorphoGrant(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "morpho_id")))))))
        );
    }

    private static int executeMorphoGrant(ServerCommandSource source, String playerName, String morphoId) {
        ServerPlayerEntity player = source.getServer().getPlayerManager().getPlayer(playerName);
        if (player == null) {
            source.sendError(Text.translatable("cheeseitems.cmd.player_not_found", playerName));
            return 0;
        }
        MorphoEntry entry = MorphoCatalog.getInstance().get(morphoId);
        if (entry == null) {
            source.sendError(Text.translatable("cheeseitems.cmd.morpho_not_found", morphoId));
            return 0;
        }
        MorphoCodex codex = CheeseItemsMod.INSTANCE.getMorphoCodex();
        if (codex.hasInCodex(player.getUuid(), morphoId)) {
            source.sendError(Text.translatable("cheeseitems.cmd.morpho_already_owned", playerName));
            return 0;
        }
        codex.addToCodex(player.getUuid(), new MorphoCodexEntry(
                morphoId, entry.getSpecies(), entry.getForm(), entry.isShiny(),
                System.currentTimeMillis()));
        MorphoManager mm = CheeseItemsMod.INSTANCE.getMorphoManager();
        if (mm != null) mm.sendSync(player);

        source.sendFeedback(() -> Text.translatable("cheeseitems.cmd.morpho_granted",
                entry.getTitle(), playerName).formatted(Formatting.GREEN), true);
        player.sendMessage(Text.translatable("cheeseitems.morpho.granted_to_you",
                entry.getTitle()).formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int executeGive(ServerCommandSource source, String playerName, String itemId, int quantity) {
        ItemManager mgr = CheeseItemsMod.INSTANCE.getItemManager();
        CustomItemDefinition def = mgr.getItem(itemId);
        if (def == null) {
            source.sendError(Text.translatable("cheeseitems.cmd.item_not_found", itemId));
            return 0;
        }

        ServerPlayerEntity player = source.getServer().getPlayerManager().getPlayer(playerName);
        if (player == null) {
            source.sendError(Text.translatable("cheeseitems.cmd.player_not_found", playerName));
            return 0;
        }

        ItemStack stack = mgr.buildItemStack(def, quantity);
        if (!player.getInventory().insertStack(stack)) {
            player.dropItem(stack, false);
        }

        source.sendFeedback(() -> Text.translatable("cheeseitems.cmd.item_given", quantity, def.getDisplayName(), playerName)
                .formatted(Formatting.GREEN), true);
        return 1;
    }

    private static int executeList(ServerCommandSource source) {
        ItemManager mgr = CheeseItemsMod.INSTANCE.getItemManager();
        var items = mgr.getAllItems();
        if (items.isEmpty()) {
            source.sendFeedback(() -> Text.translatable("cheeseitems.cmd.no_items").formatted(Formatting.GRAY), false);
            return 0;
        }
        source.sendFeedback(() -> Text.translatable("cheeseitems.cmd.items_header", items.size()).formatted(Formatting.GOLD), false);
        for (CustomItemDefinition def : items) {
            String status = def.isEnabled() ? "ON" : "OFF";
            Formatting color = def.isEnabled() ? Formatting.GREEN : Formatting.RED;
            source.sendFeedback(() -> Text.literal(" [" + status + "] ")
                    .formatted(color)
                    .append(Text.literal(def.getId()).formatted(Formatting.YELLOW))
                    .append(Text.literal(" — " + def.getDisplayName()).formatted(Formatting.WHITE))
                    .append(Text.literal(" (" + def.getBaseItem() + ")").formatted(Formatting.GRAY)), false);
        }
        return items.size();
    }

    private static int executeReload(ServerCommandSource source) {
        CheeseItemsMod.INSTANCE.getItemManager().reload();
        source.sendFeedback(() -> Text.translatable("cheeseitems.cmd.config_reloaded").formatted(Formatting.GREEN), true);
        return 1;
    }
}
