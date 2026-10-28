package com.cheeseguard.gui;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Region;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

public class RegionListGui extends ChestGui {

    private static final int PAGE_SIZE = 28; // 4 rows of 7 items
    private int page;
    private List<Region> regions;

    public RegionListGui(ServerPlayerEntity player, int page) {
        super(player, 6);
        this.page = page;
    }

    @Override
    protected Text getTitle() {
        return Text.literal("📋 Zones").formatted(Formatting.GREEN, Formatting.BOLD);
    }

    @Override
    protected void build() {
        fillAll(filler());
        regions = new ArrayList<>(CheeseGuardMod.getInstance().getRegionManager().getStorage().getRegions());

        int total = regions.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) total / PAGE_SIZE));
        page = Math.max(0, Math.min(page, totalPages - 1));

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, total);

        // Content slots: rows 1-4, cols 1-7 = slots 10-16, 19-25, 28-34, 37-43
        int[] slots = contentSlots();
        int si = 0;

        for (int i = start; i < end && si < slots.length; i++, si++) {
            Region r = regions.get(i);
            String group = r.getGroupId() != null ? "Groupe: " + r.getGroupId() : "Pas de groupe";
            String size = (r.getMaxX()-r.getMinX()+1) + "×" + (r.getMaxY()-r.getMinY()+1) + "×" + (r.getMaxZ()-r.getMinZ()+1);

            fill(slots[si], GuiHelper.item(Items.GRASS_BLOCK, "🗺 " + r.getName(), Formatting.WHITE,
                "ID: " + r.getId(),
                group,
                "Monde: " + shortWorld(r.getWorld()),
                "Taille: " + size,
                "Priorité: " + r.getPriority(),
                "",
                "Cliquez pour éditer"));
        }

        // Toolbar (bottom row 54-62)
        fill(45, GuiHelper.back());
        fill(49, GuiHelper.item(Items.EMERALD, "➕ Créer une zone", Formatting.GREEN,
            "Utilisez le wand pour sélectionner",
            "puis /cg create <nom>",
            "ou cliquez ici (sélection requise)"));

        if (page > 0) fill(46, GuiHelper.prevPage(page + 1, totalPages));
        if (page < totalPages - 1) fill(52, GuiHelper.nextPage(page + 1, totalPages));

        // Page indicator
        fill(48, GuiHelper.item(Items.PAPER, "Page " + (page+1) + "/" + totalPages, Formatting.GRAY,
            total + " zone(s) au total"));
    }

    @Override
    protected void onClick(int slot, int button, SlotActionType action) {
        if (slot == 45) { close(); new MainGui(player).open(); return; }
        if (slot == 46 && page > 0) { page--; refresh(); return; }
        if (slot == 52) { page++; refresh(); return; }
        if (slot == 49) {
            // Create region from current selection
            close();
            player.sendMessage(Text.literal("💬 Tapez le nom de la nouvelle zone dans le chat :").formatted(Formatting.YELLOW), false);
            CheeseGuardMod.getInstance().awaitChatInput(player.getUuid(), name -> {
                var sel = CheeseGuardMod.getInstance().getSelectionManager().getSelection(player.getUuid());
                if (sel == null || !sel.isComplete()) {
                    player.sendMessage(Text.literal("❌ Aucune sélection active ! Utilisez le wand d'abord.").formatted(Formatting.RED), false);
                } else {
                    CheeseGuardMod.getInstance().createRegion(player, name, sel);
                }
                new RegionListGui(player, page).open();
            });
            return;
        }

        int[] slots = contentSlots();
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) {
                int regionIndex = page * PAGE_SIZE + i;
                if (regionIndex < regions.size()) {
                    Region r = regions.get(regionIndex);
                    close();
                    new RegionEditGui(player, r.getId(), page).open();
                }
                return;
            }
        }
    }

    private int[] contentSlots() {
        // 4 rows × 7 cols: cols 1-7 in rows 1-4
        int[] slots = new int[28];
        int idx = 0;
        for (int row = 1; row <= 4; row++) {
            for (int col = 1; col <= 7; col++) {
                slots[idx++] = row * 9 + col;
            }
        }
        return slots;
    }

    private String shortWorld(String world) {
        if (world.contains("overworld")) return "Overworld";
        if (world.contains("nether")) return "Nether";
        if (world.contains("end")) return "End";
        return world;
    }
}
