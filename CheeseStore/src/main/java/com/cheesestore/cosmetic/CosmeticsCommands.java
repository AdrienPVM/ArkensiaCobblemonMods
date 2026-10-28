package com.cheesestore.cosmetic;

import com.cheesestore.WalletManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Player + admin commands for the cosmetic system.
 *
 * <p>Player:
 * <ul>
 *   <li>{@code /cosmetics} — opens an sgui chest menu: category grid → owned cosmetics
 *       per category. Click a cosmetic to equip/unequip it. Falls back to a clickable
 *       text menu if sgui is unavailable at runtime.</li>
 *   <li>{@code /cosmetics list [category]} — text list of OWNED cosmetics (clickable equip).</li>
 *   <li>{@code /cosmetics equip <id>} — equip a cosmetic you own.</li>
 *   <li>{@code /cosmetics unequip <category>} — unequip whatever is on in that category.</li>
 * </ul>
 *
 * <p>Admin (op level &ge; 2) — for testing in-game:
 * <ul>
 *   <li>{@code /cosmetics grant <player> <id>} — grant ownership of one cosmetic.</li>
 *   <li>{@code /cosmetics grantall <player>} — grant ownership of EVERY enabled cosmetic.</li>
 *   <li>{@code /cosmetics revoke <player> <id>} — remove ownership (and unequip if needed).</li>
 * </ul>
 *
 * <p>Contract: {@code public static void register()} — idempotent; registers its own
 * {@link CommandRegistrationCallback}.
 */
public final class CosmeticsCommands {

    private static final Logger LOGGER = LoggerFactory.getLogger("CheeseStore");
    private static boolean registered = false;

    private static final int ADMIN_LEVEL = 2;

    /** Categories in display order. */
    private static final String[] CATEGORIES = {
            "TRAIL", "KILL_EFFECT", "DEATH_EFFECT", "NAME_COLOR", "CHAT_TAG", "PET", "MOUNT", "CAPE"
    };

    private CosmeticsCommands() {}

    // ── Registration ────────────────────────────────────────────────────────────

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            LiteralArgumentBuilder<ServerCommandSource> root = literal("cosmetics")
                    // /cosmetics  → open menu
                    .executes(CosmeticsCommands::openMenu)
                    // /cosmetics list [category]
                    .then(literal("list")
                            .executes(ctx -> listOwned(ctx, null))
                            .then(argument("category", StringArgumentType.word())
                                    .suggests(CATEGORY_SUGGESTIONS)
                                    .executes(ctx -> listOwned(ctx, StringArgumentType.getString(ctx, "category")))))
                    // /cosmetics equip <id>
                    .then(literal("equip")
                            .then(argument("id", StringArgumentType.word())
                                    .suggests(OWNED_ID_SUGGESTIONS)
                                    .executes(CosmeticsCommands::equipCmd)))
                    // /cosmetics unequip <category>
                    .then(literal("unequip")
                            .then(argument("category", StringArgumentType.word())
                                    .suggests(CATEGORY_SUGGESTIONS)
                                    .executes(CosmeticsCommands::unequipCmd)))
                    // ── Admin ──────────────────────────────────────────────────
                    .then(literal("grant")
                            .requires(src -> src.hasPermissionLevel(ADMIN_LEVEL))
                            .then(argument("player", EntityArgumentType.player())
                                    .then(argument("id", StringArgumentType.word())
                                            .suggests(ALL_ID_SUGGESTIONS)
                                            .executes(CosmeticsCommands::grantCmd))))
                    .then(literal("grantall")
                            .requires(src -> src.hasPermissionLevel(ADMIN_LEVEL))
                            .then(argument("player", EntityArgumentType.player())
                                    .executes(CosmeticsCommands::grantAllCmd)))
                    .then(literal("revoke")
                            .requires(src -> src.hasPermissionLevel(ADMIN_LEVEL))
                            .then(argument("player", EntityArgumentType.player())
                                    .then(argument("id", StringArgumentType.word())
                                            .suggests(ALL_ID_SUGGESTIONS)
                                            .executes(CosmeticsCommands::revokeCmd))));

            dispatcher.register(root);
            // Alias: /cosmetic → /cosmetics
            dispatcher.register(literal("cosmetic").redirect(dispatcher.getRoot().getChild("cosmetics")));
        });
        LOGGER.info("[CheeseStore] CosmeticsCommands registered (/cosmetics)");
    }

    // ── Suggestion providers ─────────────────────────────────────────────────────

    private static final SuggestionProvider<ServerCommandSource> CATEGORY_SUGGESTIONS =
            (ctx, builder) -> CommandSource.suggestMatching(java.util.Arrays.asList(CATEGORIES), builder);

    private static final SuggestionProvider<ServerCommandSource> OWNED_ID_SUGGESTIONS = (ctx, builder) -> {
        ServerPlayerEntity p = ctx.getSource().getPlayer();
        if (p != null) {
            OwnershipManager om = OwnershipManager.getInstance();
            if (om != null) {
                for (String id : om.getOwned(p.getUuid())) builder.suggest(id);
            }
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<ServerCommandSource> ALL_ID_SUGGESTIONS = (ctx, builder) -> {
        CosmeticManager cm = CosmeticManager.getInstance();
        if (cm != null) for (CosmeticEntry e : cm.getAll()) builder.suggest(e.getId());
        return builder.buildFuture();
    };

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static CosmeticManager catalog() { return CosmeticManager.getInstance(); }
    private static OwnershipManager ownership() { return OwnershipManager.getInstance(); }
    private static EquipManager equips() { return EquipManager.getInstance(); }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    /** Re-apply visual managers after an equip/unequip in a given category. */
    private static void applyVisuals(ServerPlayerEntity player, String category) {
        if (player == null) return;
        String cat = norm(category);
        try {
            if ("NAME_COLOR".equals(cat) || "CHAT_TAG".equals(cat)) {
                NameTagManager.refresh(player);
            } else if ("PET".equals(cat)) {
                PetManager.refresh(player);
            } else if ("MOUNT".equals(cat)) {
                // Despawns the live mount if it is no longer equipped/owned. Never auto-summons.
                MountManager.refresh(player);
            } else if ("CAPE".equals(cat)) {
                // Cape is rendered client-side: re-broadcast the full cape-map so every client
                // updates this player's cape (or clears it on unequip). Never throws.
                com.cheesestore.net.CapeNetworking.broadcastFrom(player);
            }
            // TRAIL / KILL_EFFECT / DEATH_EFFECT are read live each tick / on event,
            // so they need no explicit refresh.
        } catch (Throwable t) {
            LOGGER.warn("[CheeseStore] applyVisuals({}) failed: {}", cat, t.toString());
        }
    }

    /** Refresh both name-driven managers (used after grantall / bulk changes). */
    private static void applyAllVisuals(ServerPlayerEntity player) {
        if (player == null) return;
        try { NameTagManager.refresh(player); } catch (Throwable ignored) {}
        try { PetManager.refresh(player); } catch (Throwable ignored) {}
        try { MountManager.refresh(player); } catch (Throwable ignored) {}
        // CAPE is client-rendered: rebroadcast the full map after any bulk change (grantall/revoke).
        try { com.cheesestore.net.CapeNetworking.broadcastFrom(player); } catch (Throwable ignored) {}
    }

    private static Formatting colorOf(CosmeticEntry e) {
        if (e == null) return Formatting.WHITE;
        Formatting f = Formatting.byName(e.param("color", "white"));
        return f == null || !f.isColor() ? Formatting.WHITE : f;
    }

    private static Item iconItem(CosmeticEntry e) {
        if (e == null) return Items.BARRIER;
        Identifier id = Identifier.tryParse(e.getIcon());
        if (id == null) return Items.PAPER;
        Item it = Registries.ITEM.get(id);
        return it == Items.AIR ? Items.PAPER : it;
    }

    private static String prettyCategory(String cat) {
        switch (norm(cat)) {
            case "TRAIL": return "Traînées";
            case "KILL_EFFECT": return "Effets de kill";
            case "DEATH_EFFECT": return "Effets de mort";
            case "NAME_COLOR": return "Couleurs de pseudo";
            case "CHAT_TAG": return "Tags de chat";
            case "PET": return "Familiers";
            case "MOUNT": return "Montures";
            case "CAPE": return "Capes";
            default: return cat;
        }
    }

    private static Item categoryIcon(String cat) {
        switch (norm(cat)) {
            case "TRAIL": return Items.BLAZE_POWDER;
            case "KILL_EFFECT": return Items.DIAMOND_SWORD;
            case "DEATH_EFFECT": return Items.SKELETON_SKULL;
            case "NAME_COLOR": return Items.NAME_TAG;
            case "CHAT_TAG": return Items.WRITABLE_BOOK;
            case "PET": return Items.BONE;
            case "MOUNT": return Items.SADDLE;
            case "CAPE": return Items.ELYTRA;
            default: return Items.CHEST;
        }
    }

    // ── /cosmetics : the menu ─────────────────────────────────────────────────────

    private static int openMenu(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendError(Text.literal("Cette commande doit être exécutée par un joueur."));
            return 0;
        }
        if (catalog() == null || ownership() == null || equips() == null) {
            player.sendMessage(Text.literal("Système de cosmétiques indisponible.").formatted(Formatting.RED), false);
            return 0;
        }
        boolean opened = false;
        try {
            opened = openCategoryGui(player);
        } catch (Throwable t) {
            LOGGER.warn("[CheeseStore] sgui category menu failed, falling back to text: {}", t.toString());
        }
        if (!opened) {
            listOwnedText(player, null);
        }
        return 1;
    }

    /** Top-level GUI: one button per category. Returns true if the GUI opened. */
    private static boolean openCategoryGui(ServerPlayerEntity player) {
        SimpleGui gui = new SimpleGui(ScreenHandlerType.GENERIC_9X3, player, false);
        gui.setTitle(Text.literal("Cosmétiques").formatted(Formatting.GOLD, Formatting.BOLD));

        UUID uuid = player.getUuid();
        // Category buttons: the 7 original on the middle row (slots 10..16) + CAPE on the row below
        // (slot 19, left of centre) so the 8th entry isn't dropped by the GENERIC_9X3 layout.
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19};
        for (int i = 0; i < CATEGORIES.length && i < slots.length; i++) {
            final String cat = CATEGORIES[i];
            int ownedCount = countOwnedInCategory(uuid, cat);
            int totalCount = catalog().getByCategory(cat).size();
            String equippedId = equips().getEquipped(uuid, cat);
            CosmeticEntry equipped = equippedId == null ? null : catalog().get(equippedId);

            GuiElementBuilder b = new GuiElementBuilder(categoryIcon(cat))
                    .setName(Text.literal(prettyCategory(cat)).formatted(Formatting.YELLOW, Formatting.BOLD))
                    .addLoreLine(Text.literal("Possédés : " + ownedCount + " / " + totalCount)
                            .formatted(Formatting.GRAY));
            if (equipped != null) {
                b.addLoreLine(Text.literal("Équipé : " + equipped.getTitle()).formatted(Formatting.GREEN));
                b.glow();
            } else {
                b.addLoreLine(Text.literal("Rien d'équipé").formatted(Formatting.DARK_GRAY));
            }
            b.addLoreLine(Text.empty());
            b.addLoreLine(Text.literal("Clic pour ouvrir").formatted(Formatting.AQUA));
            b.setCallback((index, type, action, slotGui) -> {
                try {
                    openCategoryItemsGui(player, cat);
                } catch (Throwable t) {
                    LOGGER.warn("[CheeseStore] open category items failed: {}", t.toString());
                    player.closeHandledScreen();
                    listOwnedText(player, cat);
                }
            });
            gui.setSlot(slots[i], b);
        }

        // Wallet display (slot 22, bottom-center).
        long bal = balance(player);
        gui.setSlot(22, new GuiElementBuilder(Items.GOLD_INGOT)
                .setName(Text.literal("Solde : " + bal + " crédits").formatted(Formatting.GOLD))
                .addLoreLine(Text.literal("Achète des cosmétiques sur le store web,").formatted(Formatting.GRAY))
                .addLoreLine(Text.literal("puis équipe-les ici.").formatted(Formatting.GRAY)));

        return gui.open();
    }

    /** Per-category GUI listing the cosmetics the player OWNS in that category. */
    private static void openCategoryItemsGui(ServerPlayerEntity player, String category) {
        String cat = norm(category);
        UUID uuid = player.getUuid();

        List<CosmeticEntry> owned = ownedInCategory(uuid, cat);
        SimpleGui gui = new SimpleGui(ScreenHandlerType.GENERIC_9X6, player, false);
        gui.setTitle(Text.literal(prettyCategory(cat)).formatted(Formatting.GOLD, Formatting.BOLD));

        String equippedId = equips().getEquipped(uuid, cat);

        if (owned.isEmpty()) {
            gui.setSlot(22, new GuiElementBuilder(Items.BARRIER)
                    .setName(Text.literal("Aucun cosmétique possédé").formatted(Formatting.RED))
                    .addLoreLine(Text.literal("Achète-en sur le store web,").formatted(Formatting.GRAY))
                    .addLoreLine(Text.literal("ou demande à un admin /cosmetics grant.").formatted(Formatting.GRAY)));
        } else {
            int slot = 0;
            for (CosmeticEntry e : owned) {
                if (slot >= 45) break; // leave the bottom row for controls
                final String id = e.getId();
                boolean isEquipped = id.equals(equippedId);

                GuiElementBuilder b = new GuiElementBuilder(iconItem(e))
                        .setName(Text.literal(e.getTitle()).formatted(colorOf(e), Formatting.BOLD));
                if (!e.getDescription().isEmpty()) {
                    b.addLoreLine(Text.literal(e.getDescription()).formatted(Formatting.GRAY));
                }
                b.addLoreLine(Text.empty());
                if (isEquipped) {
                    b.addLoreLine(Text.literal("✔ Équipé — clic pour retirer").formatted(Formatting.GREEN));
                    b.glow();
                } else {
                    b.addLoreLine(Text.literal("Clic pour équiper").formatted(Formatting.AQUA));
                }
                b.setCallback((index, type, action, slotGui) -> {
                    try {
                        toggleEquip(player, id, cat);
                    } catch (Throwable t) {
                        LOGGER.warn("[CheeseStore] toggleEquip failed: {}", t.toString());
                    }
                    // Re-render this category so the equipped state updates live.
                    try {
                        openCategoryItemsGui(player, cat);
                    } catch (Throwable ignored) {}
                });
                gui.setSlot(slot++, b);
            }
        }

        // Bottom row controls.
        // MOUNT-only: an "Invoquer / renvoyer la monture" button (slot 48). A mount is summoned on
        // demand — equipping in the grid only selects it; this button spawns/recalls it.
        if ("MOUNT".equals(cat)) {
            boolean hasMount = MountManager.hasMount(player);
            boolean hasEquipped = equippedId != null;
            GuiElementBuilder mountBtn = new GuiElementBuilder(Items.SADDLE)
                    .setName(Text.literal(hasMount ? "Renvoyer la monture" : "Invoquer la monture")
                            .formatted(hasMount ? Formatting.GOLD : Formatting.GREEN, Formatting.BOLD));
            if (!hasEquipped) {
                mountBtn.addLoreLine(Text.literal("Équipe d'abord une monture.").formatted(Formatting.RED));
            } else {
                mountBtn.addLoreLine(Text.literal(hasMount
                        ? "Clic pour renvoyer ta monture."
                        : "Clic pour invoquer la monture équipée.").formatted(Formatting.GRAY));
                mountBtn.addLoreLine(Text.literal("Rappelée automatiquement en combat.").formatted(Formatting.DARK_GRAY));
            }
            if (hasMount) mountBtn.glow();
            mountBtn.setCallback((index, type, action, slotGui) -> {
                try {
                    MountManager.toggle(player);
                } catch (Throwable t) {
                    LOGGER.warn("[CheeseStore] mount toggle failed: {}", t.toString());
                }
                // Close the screen so the player can see / ride the summoned mount.
                try { player.closeHandledScreen(); } catch (Throwable ignored) {}
            });
            gui.setSlot(48, mountBtn);
        }

        gui.setSlot(49, new GuiElementBuilder(Items.BARRIER)
                .setName(Text.literal("Tout retirer").formatted(Formatting.RED))
                .addLoreLine(Text.literal("Retire le cosmétique équipé de cette catégorie.").formatted(Formatting.GRAY))
                .setCallback((index, type, action, slotGui) -> {
                    try {
                        equips().unequip(uuid, cat);
                        applyVisuals(player, cat);
                        player.sendMessage(Text.literal("Retiré : " + prettyCategory(cat)).formatted(Formatting.YELLOW), true);
                    } catch (Throwable ignored) {}
                    try { openCategoryItemsGui(player, cat); } catch (Throwable ignored) {}
                }));
        gui.setSlot(53, new GuiElementBuilder(Items.ARROW)
                .setName(Text.literal("Retour").formatted(Formatting.YELLOW))
                .setCallback((index, type, action, slotGui) -> {
                    try { openCategoryGui(player); } catch (Throwable ignored) { player.closeHandledScreen(); }
                }));

        gui.open();
    }

    /** Equip if not equipped, or unequip if this exact id is the equipped one. */
    private static void toggleEquip(ServerPlayerEntity player, String id, String category) {
        UUID uuid = player.getUuid();
        String cat = norm(category);
        String current = equips().getEquipped(uuid, cat);
        if (id.equals(current)) {
            equips().unequip(uuid, cat);
            applyVisuals(player, cat);
            player.sendMessage(Text.literal("Retiré.").formatted(Formatting.YELLOW), true);
            return;
        }
        // Ownership guard (defensive; the GUI only shows owned items).
        if (!ownership().owns(uuid, id)) {
            player.sendMessage(Text.literal("Tu ne possèdes pas ce cosmétique.").formatted(Formatting.RED), true);
            return;
        }
        String affected = equips().equip(uuid, id);
        if (affected == null) {
            player.sendMessage(Text.literal("Cosmétique introuvable.").formatted(Formatting.RED), true);
            return;
        }
        applyVisuals(player, affected);
        CosmeticEntry e = catalog().get(id);
        player.sendMessage(Text.literal("Équipé : " + (e == null ? id : e.getTitle())).formatted(Formatting.GREEN), true);
    }

    // ── /cosmetics list [category] ────────────────────────────────────────────────

    private static int listOwned(CommandContext<ServerCommandSource> ctx, String category) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendError(Text.literal("Cette commande doit être exécutée par un joueur."));
            return 0;
        }
        listOwnedText(player, category);
        return 1;
    }

    private static void listOwnedText(ServerPlayerEntity player, String category) {
        if (catalog() == null || ownership() == null || equips() == null) {
            player.sendMessage(Text.literal("Système de cosmétiques indisponible.").formatted(Formatting.RED), false);
            return;
        }
        UUID uuid = player.getUuid();
        String filter = category == null ? null : norm(category);

        player.sendMessage(Text.literal("═══ Tes cosmétiques ═══").formatted(Formatting.GOLD, Formatting.BOLD), false);

        boolean any = false;
        for (String cat : CATEGORIES) {
            if (filter != null && !filter.equals(cat)) continue;
            List<CosmeticEntry> owned = ownedInCategory(uuid, cat);
            if (owned.isEmpty()) continue;
            any = true;
            String equippedId = equips().getEquipped(uuid, cat);

            player.sendMessage(Text.literal("» " + prettyCategory(cat)).formatted(Formatting.YELLOW), false);
            for (CosmeticEntry e : owned) {
                boolean isEquipped = e.getId().equals(equippedId);
                MutableText line = Text.literal("  • ").formatted(Formatting.DARK_GRAY)
                        .append(Text.literal(e.getTitle()).formatted(colorOf(e)));
                if (isEquipped) {
                    line.append(Text.literal(" [équipé — /cosmetics unequip " + cat + "]")
                            .formatted(Formatting.GREEN)
                            .styled(s -> s.withClickEvent(new net.minecraft.text.ClickEvent(
                                    net.minecraft.text.ClickEvent.Action.RUN_COMMAND,
                                    "/cosmetics unequip " + cat))));
                } else {
                    line.append(Text.literal(" [équiper]")
                            .formatted(Formatting.AQUA)
                            .styled(s -> s.withClickEvent(new net.minecraft.text.ClickEvent(
                                    net.minecraft.text.ClickEvent.Action.RUN_COMMAND,
                                    "/cosmetics equip " + e.getId()))));
                }
                player.sendMessage(line, false);
            }
        }
        if (!any) {
            player.sendMessage(Text.literal("Tu ne possèdes aucun cosmétique"
                    + (filter != null ? " dans cette catégorie." : ".")).formatted(Formatting.GRAY), false);
            player.sendMessage(Text.literal("Achète-en sur le store web (crédits).").formatted(Formatting.DARK_GRAY), false);
        }
    }

    // ── /cosmetics equip <id> ─────────────────────────────────────────────────────

    private static int equipCmd(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendError(Text.literal("Cette commande doit être exécutée par un joueur."));
            return 0;
        }
        String id = StringArgumentType.getString(ctx, "id");
        UUID uuid = player.getUuid();

        CosmeticEntry e = catalog() == null ? null : catalog().get(id);
        if (e == null) {
            player.sendMessage(Text.literal("Cosmétique inconnu : " + id).formatted(Formatting.RED), false);
            return 0;
        }
        if (!e.isEnabled()) {
            player.sendMessage(Text.literal("Ce cosmétique est désactivé.").formatted(Formatting.RED), false);
            return 0;
        }
        if (ownership() == null || !ownership().owns(uuid, id)) {
            player.sendMessage(Text.literal("Tu ne possèdes pas ce cosmétique.").formatted(Formatting.RED), false);
            return 0;
        }
        String affected = equips().equip(uuid, id);
        if (affected == null) {
            player.sendMessage(Text.literal("Impossible d'équiper ce cosmétique.").formatted(Formatting.RED), false);
            return 0;
        }
        applyVisuals(player, affected);
        player.sendMessage(Text.literal("Équipé : ").formatted(Formatting.GREEN)
                .append(Text.literal(e.getTitle()).formatted(colorOf(e))), false);
        return 1;
    }

    // ── /cosmetics unequip <category> ─────────────────────────────────────────────

    private static int unequipCmd(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendError(Text.literal("Cette commande doit être exécutée par un joueur."));
            return 0;
        }
        String category = norm(StringArgumentType.getString(ctx, "category"));
        if (!CosmeticEntry.Category.isValid(category)) {
            player.sendMessage(Text.literal("Catégorie inconnue : " + category).formatted(Formatting.RED), false);
            return 0;
        }
        UUID uuid = player.getUuid();
        if (equips().getEquipped(uuid, category) == null) {
            player.sendMessage(Text.literal("Rien d'équipé dans " + prettyCategory(category) + ".").formatted(Formatting.GRAY), false);
            return 0;
        }
        equips().unequip(uuid, category);
        applyVisuals(player, category);
        player.sendMessage(Text.literal("Retiré : " + prettyCategory(category)).formatted(Formatting.YELLOW), false);
        return 1;
    }

    // ── Admin: /cosmetics grant <player> <id> ─────────────────────────────────────

    private static int grantCmd(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        ServerPlayerEntity target;
        try {
            target = EntityArgumentType.getPlayer(ctx, "player");
        } catch (Exception ex) {
            src.sendError(Text.literal("Joueur introuvable."));
            return 0;
        }
        String id = StringArgumentType.getString(ctx, "id");
        if (catalog() == null || ownership() == null) {
            src.sendError(Text.literal("Système de cosmétiques indisponible."));
            return 0;
        }
        CosmeticEntry e = catalog().get(id);
        if (e == null) {
            src.sendError(Text.literal("Cosmétique inconnu : " + id));
            return 0;
        }
        ownership().grant(target.getUuid(), id);
        src.sendFeedback(() -> Text.literal("Accordé « " + e.getTitle() + " » à " + target.getName().getString())
                .formatted(Formatting.GREEN), true);
        target.sendMessage(Text.literal("Tu as reçu le cosmétique : ").formatted(Formatting.GREEN)
                .append(Text.literal(e.getTitle()).formatted(colorOf(e)))
                .append(Text.literal("  (/cosmetics pour l'équiper)").formatted(Formatting.GRAY)), false);
        return 1;
    }

    // ── Admin: /cosmetics grantall <player> ───────────────────────────────────────

    private static int grantAllCmd(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        ServerPlayerEntity target;
        try {
            target = EntityArgumentType.getPlayer(ctx, "player");
        } catch (Exception ex) {
            src.sendError(Text.literal("Joueur introuvable."));
            return 0;
        }
        if (catalog() == null || ownership() == null) {
            src.sendError(Text.literal("Système de cosmétiques indisponible."));
            return 0;
        }
        int count = 0;
        for (CosmeticEntry e : catalog().getEnabled()) {
            ownership().grant(target.getUuid(), e.getId());
            count++;
        }
        final int granted = count;
        src.sendFeedback(() -> Text.literal("Accordé " + granted + " cosmétiques à " + target.getName().getString())
                .formatted(Formatting.GREEN), true);
        target.sendMessage(Text.literal("Tu as reçu TOUS les cosmétiques (" + granted + "). ")
                .formatted(Formatting.GREEN)
                .append(Text.literal("/cosmetics pour les équiper.").formatted(Formatting.GRAY)), false);
        return 1;
    }

    // ── Admin: /cosmetics revoke <player> <id> ────────────────────────────────────

    private static int revokeCmd(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        ServerPlayerEntity target;
        try {
            target = EntityArgumentType.getPlayer(ctx, "player");
        } catch (Exception ex) {
            src.sendError(Text.literal("Joueur introuvable."));
            return 0;
        }
        String id = StringArgumentType.getString(ctx, "id");
        if (catalog() == null || ownership() == null || equips() == null) {
            src.sendError(Text.literal("Système de cosmétiques indisponible."));
            return 0;
        }
        UUID uuid = target.getUuid();
        ownership().revoke(uuid, id);
        // If it was equipped, unequip it so the player isn't wearing something they no longer own.
        CosmeticEntry e = catalog().get(id);
        String cat = e != null ? norm(e.getCategory()) : null;
        if (cat != null && id.equals(equips().getEquipped(uuid, cat))) {
            equips().unequip(uuid, cat);
            applyVisuals(target, cat);
        }
        String label = e != null ? e.getTitle() : id;
        src.sendFeedback(() -> Text.literal("Retiré « " + label + " » à " + target.getName().getString())
                .formatted(Formatting.YELLOW), true);
        return 1;
    }

    // ── Shared lookups ────────────────────────────────────────────────────────────

    private static List<CosmeticEntry> ownedInCategory(UUID uuid, String category) {
        List<CosmeticEntry> out = new ArrayList<>();
        if (catalog() == null || ownership() == null) return out;
        String cat = norm(category);
        for (CosmeticEntry e : catalog().getByCategory(cat)) {
            if (ownership().owns(uuid, e.getId())) out.add(e);
        }
        return out;
    }

    private static int countOwnedInCategory(UUID uuid, String category) {
        return ownedInCategory(uuid, category).size();
    }

    private static long balance(ServerPlayerEntity player) {
        try {
            WalletManager wm = WalletManager.getInstance();
            return wm == null ? 0 : wm.getBalance(player.getUuid());
        } catch (Throwable t) {
            return 0;
        }
    }
}
