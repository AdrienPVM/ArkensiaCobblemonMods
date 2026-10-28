package com.cheeseguard.gui;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.*;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class RegionEditGui extends ChestGui {

    private final String regionId;
    private final int returnPage;

    public RegionEditGui(ServerPlayerEntity player, String regionId, int returnPage) {
        super(player, 6);
        this.regionId = regionId;
        this.returnPage = returnPage;
    }

    @Override
    protected Text getTitle() {
        Region r = region();
        String name = r != null ? r.getName() : "???";
        return Text.literal("✏ " + name).formatted(Formatting.YELLOW, Formatting.BOLD);
    }

    @Override
    protected void build() {
        fillAll(filler());
        Region r = region();
        if (r == null) { close(); return; }

        // ── Row 1: info ──────────────────────────────────────────────────────
        fill(4, GuiHelper.item(Items.NAME_TAG, "📝 Nom: " + r.getName(), Formatting.WHITE,
            "ID: " + r.getId(),
            "Cliquez pour renommer"));

        // ── Row 2: navigation ────────────────────────────────────────────────
        fill(10, GuiHelper.item(Items.SHIELD, "🛡 Flags", Formatting.RED,
            "Gérer les permissions",
            "de protection de cette zone"));

        fill(13, GuiHelper.item(Items.PLAYER_HEAD, "👥 Membres", Formatting.AQUA,
            r.getMembers().size() + " membre(s)",
            "Gérer qui peut agir dans la zone"));

        fill(16, GuiHelper.item(Items.BOOK, "📦 Groupe", Formatting.GOLD,
            r.getGroupId() != null ? "Groupe: " + r.getGroupId() : "Aucun groupe",
            "Cliquez pour changer le groupe"));

        // ── Row 3: settings ──────────────────────────────────────────────────
        fill(28, GuiHelper.item(Items.CHAIN, "🔗 Zone parente", Formatting.LIGHT_PURPLE,
            r.getParentId() != null ? "Parent: " + r.getParentId() : "Aucun parent",
            "Cliquez pour définir la zone parente"));

        fill(31, GuiHelper.item(Items.COMPARATOR, "🏆 Priorité: " + r.getPriority(), Formatting.WHITE,
            "Clic gauche: +1",
            "Clic droit: -1",
            "Shift+clic: ±10",
            "",
            "Plus haute = prioritaire sur chevauchements"));

        fill(34, GuiHelper.item(Items.MAP, "📍 Infos de zone", Formatting.GRAY,
            "Monde: " + shortWorld(r.getWorld()),
            "Min: " + r.getMinX() + ", " + r.getMinY() + ", " + r.getMinZ(),
            "Max: " + r.getMaxX() + ", " + r.getMaxY() + ", " + r.getMaxZ(),
            "Taille: " + (r.getMaxX()-r.getMinX()+1) + "×" + (r.getMaxY()-r.getMinY()+1) + "×" + (r.getMaxZ()-r.getMinZ()+1)));

        // ── Row 4: messages ──────────────────────────────────────────────────
        fill(37, GuiHelper.toggle("Message d'entrée", r.isEntryMessageEnabled(),
            r.getEntryMessage(),
            "",
            "Clic gauche: activer/désactiver",
            "Clic droit: modifier le message"));

        fill(40, GuiHelper.toggle("Message de sortie", r.isExitMessageEnabled(),
            r.getExitMessage(),
            "",
            "Clic gauche: activer/désactiver",
            "Clic droit: modifier le message"));

        fill(43, GuiHelper.item(Items.PAPER, "💬 Message de protection", Formatting.WHITE,
            r.getProtectionMessage(),
            "",
            "Cliquez pour modifier"));

        // ── Bottom bar ───────────────────────────────────────────────────────
        fill(45, GuiHelper.back());
        fill(49, GuiHelper.item(Items.ENDER_EYE, "🔭 Téléporter ici", Formatting.GREEN,
            "Cliquez pour vous TP au centre de la zone"));
        fill(53, GuiHelper.item(Items.TNT, "🗑 Supprimer la zone", Formatting.RED,
            "Cliquez pour supprimer définitivement !",
            "Cette action est irréversible."));
    }

    @Override
    protected void onClick(int slot, int button, SlotActionType action) {
        Region r = region();
        if (r == null) { close(); return; }

        switch (slot) {
            case 45 -> { close(); new RegionListGui(player, returnPage).open(); }

            // Rename
            case 4 -> {
                close();
                player.sendMessage(Text.literal("💬 Tapez le nouveau nom dans le chat :").formatted(Formatting.YELLOW), false);
                CheeseGuardMod.getInstance().awaitChatInput(player.getUuid(), name -> {
                    r.setName(name);
                    CheeseGuardMod.getInstance().getRegionManager().getStorage().save();
                    player.sendMessage(Text.literal("✅ Zone renommée : " + name).formatted(Formatting.GREEN), false);
                    new RegionEditGui(player, regionId, returnPage).open();
                });
            }

            // Flags
            case 10 -> { close(); new FlagsGui(player, regionId, returnPage).open(); }

            // Members
            case 13 -> { close(); new MembersGui(player, regionId, returnPage, 0).open(); }

            // Group
            case 16 -> {
                close();
                player.sendMessage(Text.literal("💬 Tapez le nom du groupe (ou 'none' pour aucun) :").formatted(Formatting.YELLOW), false);
                CheeseGuardMod.getInstance().awaitChatInput(player.getUuid(), input -> {
                    var storage = CheeseGuardMod.getInstance().getRegionManager().getStorage();
                    if (input.equalsIgnoreCase("none")) {
                        if (r.getGroupId() != null) {
                            var old = storage.getGroup(r.getGroupId());
                            if (old != null) old.removeRegion(r.getId());
                        }
                        r.setGroupId(null);
                    } else {
                        var group = storage.getGroup(input);
                        if (group == null) {
                            player.sendMessage(Text.literal("❌ Groupe '" + input + "' introuvable.").formatted(Formatting.RED), false);
                        } else {
                            if (r.getGroupId() != null) {
                                var old = storage.getGroup(r.getGroupId());
                                if (old != null) old.removeRegion(r.getId());
                            }
                            r.setGroupId(input);
                            group.addRegion(r.getId());
                        }
                    }
                    storage.save();
                    new RegionEditGui(player, regionId, returnPage).open();
                });
            }

            // Parent
            case 28 -> {
                close();
                player.sendMessage(Text.literal("💬 Tapez l'ID de la zone parente (ou 'none') :").formatted(Formatting.YELLOW), false);
                CheeseGuardMod.getInstance().awaitChatInput(player.getUuid(), input -> {
                    if (input.equalsIgnoreCase("none")) {
                        r.setParentId(null);
                    } else {
                        var storage = CheeseGuardMod.getInstance().getRegionManager().getStorage();
                        if (!storage.hasRegion(input)) {
                            player.sendMessage(Text.literal("❌ Zone '" + input + "' introuvable.").formatted(Formatting.RED), false);
                        } else if (input.equals(r.getId())) {
                            player.sendMessage(Text.literal("❌ Une zone ne peut pas être son propre parent.").formatted(Formatting.RED), false);
                        } else {
                            r.setParentId(input);
                        }
                    }
                    CheeseGuardMod.getInstance().getRegionManager().getStorage().save();
                    new RegionEditGui(player, regionId, returnPage).open();
                });
            }

            // Priority
            case 31 -> {
                boolean shift = action == SlotActionType.PICKUP_ALL;
                int delta = (button == 1) ? -1 : 1;
                if (shift) delta *= 10;
                r.setPriority(r.getPriority() + delta);
                CheeseGuardMod.getInstance().getRegionManager().getStorage().save();
                refresh();
            }

            // Entry message toggle / edit
            case 37 -> {
                if (button == 1) { // right click = edit message
                    close();
                    player.sendMessage(Text.literal("💬 Tapez le message d'entrée :").formatted(Formatting.YELLOW), false);
                    CheeseGuardMod.getInstance().awaitChatInput(player.getUuid(), msg -> {
                        r.setEntryMessage(msg);
                        CheeseGuardMod.getInstance().getRegionManager().getStorage().save();
                        new RegionEditGui(player, regionId, returnPage).open();
                    });
                } else {
                    r.setEntryMessageEnabled(!r.isEntryMessageEnabled());
                    CheeseGuardMod.getInstance().getRegionManager().getStorage().save();
                    refresh();
                }
            }

            // Exit message toggle / edit
            case 40 -> {
                if (button == 1) {
                    close();
                    player.sendMessage(Text.literal("💬 Tapez le message de sortie :").formatted(Formatting.YELLOW), false);
                    CheeseGuardMod.getInstance().awaitChatInput(player.getUuid(), msg -> {
                        r.setExitMessage(msg);
                        CheeseGuardMod.getInstance().getRegionManager().getStorage().save();
                        new RegionEditGui(player, regionId, returnPage).open();
                    });
                } else {
                    r.setExitMessageEnabled(!r.isExitMessageEnabled());
                    CheeseGuardMod.getInstance().getRegionManager().getStorage().save();
                    refresh();
                }
            }

            // Protection message
            case 43 -> {
                close();
                player.sendMessage(Text.literal("💬 Tapez le message de protection :").formatted(Formatting.YELLOW), false);
                CheeseGuardMod.getInstance().awaitChatInput(player.getUuid(), msg -> {
                    r.setProtectionMessage(msg);
                    CheeseGuardMod.getInstance().getRegionManager().getStorage().save();
                    new RegionEditGui(player, regionId, returnPage).open();
                });
            }

            // Teleport
            case 49 -> {
                close();
                player.requestTeleport(r.getCenterX() + 0.5, r.getCenterY() + 1.5, r.getCenterZ() + 0.5);
                player.sendMessage(Text.literal("✅ Téléporté au centre de : " + r.getName()).formatted(Formatting.GREEN), false);
            }

            // Delete
            case 53 -> {
                CheeseGuardMod.getInstance().getRegionManager().getStorage().removeRegion(regionId);
                close();
                player.sendMessage(Text.literal("🗑 Zone supprimée : " + r.getName()).formatted(Formatting.RED), false);
                new RegionListGui(player, returnPage).open();
            }
        }
    }

    private Region region() {
        return CheeseGuardMod.getInstance().getRegionManager().getStorage().getRegion(regionId);
    }

    private String shortWorld(String world) {
        if (world.contains("overworld")) return "Overworld";
        if (world.contains("nether")) return "Nether";
        if (world.contains("end")) return "End";
        return world;
    }
}
