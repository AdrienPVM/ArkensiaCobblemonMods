package com.cheeseguard.gui;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.RegionGroup;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

public class GroupListGui extends ChestGui {

    private static final int PAGE_SIZE = 28;
    private int page;
    private List<RegionGroup> groups;

    public GroupListGui(ServerPlayerEntity player, int page) {
        super(player, 6);
        this.page = page;
    }

    @Override
    protected Text getTitle() {
        return Text.literal("📦 Groupes").formatted(Formatting.AQUA, Formatting.BOLD);
    }

    @Override
    protected void build() {
        fillAll(filler());
        groups = new ArrayList<>(CheeseGuardMod.getInstance().getRegionManager().getStorage().getGroups());

        int total = groups.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) total / PAGE_SIZE));
        page = Math.max(0, Math.min(page, totalPages - 1));

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, total);
        int[] slots = contentSlots();
        int si = 0;

        for (int i = start; i < end && si < slots.length; i++, si++) {
            RegionGroup g = groups.get(i);
            int flagCount = g.getFlags().size();
            fill(slots[si], GuiHelper.item(Items.BOOKSHELF,
                "📦 " + g.getName(), Formatting.YELLOW,
                "ID: " + g.getId(),
                g.getRegionIds().size() + " zone(s)",
                flagCount + " flag(s) définis",
                "",
                "Cliquez pour éditer"));
        }

        fill(45, GuiHelper.back());
        fill(49, GuiHelper.item(Items.EMERALD, "➕ Créer un groupe", Formatting.GREEN,
            "Cliquez puis tapez le nom du groupe"));
        if (page > 0) fill(46, GuiHelper.prevPage(page + 1, totalPages));
        if (page < totalPages - 1) fill(52, GuiHelper.nextPage(page + 1, totalPages));
        fill(48, GuiHelper.item(Items.PAPER, "Page " + (page+1) + "/" + totalPages, Formatting.GRAY,
            total + " groupe(s)"));
    }

    @Override
    protected void onClick(int slot, int button, SlotActionType action) {
        if (slot == 45) { close(); new MainGui(player).open(); return; }
        if (slot == 46 && page > 0) { page--; refresh(); return; }
        if (slot == 52) { page++; refresh(); return; }

        if (slot == 49) {
            close();
            player.sendMessage(Text.literal("💬 Tapez le nom du nouveau groupe :").formatted(Formatting.YELLOW), false);
            CheeseGuardMod.getInstance().awaitChatInput(player.getUuid(), name -> {
                String id = name.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
                var storage = CheeseGuardMod.getInstance().getRegionManager().getStorage();
                if (storage.hasGroup(id)) {
                    player.sendMessage(Text.literal("❌ Un groupe avec cet ID existe déjà.").formatted(Formatting.RED), false);
                } else {
                    storage.addGroup(new RegionGroup(id, name));
                    player.sendMessage(Text.literal("✅ Groupe créé : " + name).formatted(Formatting.GREEN), false);
                }
                new GroupListGui(player, page).open();
            });
            return;
        }

        int[] slots = contentSlots();
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) {
                int idx = page * PAGE_SIZE + i;
                if (idx < groups.size()) {
                    close();
                    new GroupEditGui(player, groups.get(idx).getId(), page).open();
                }
                return;
            }
        }
    }

    private int[] contentSlots() {
        int[] slots = new int[28];
        int idx = 0;
        for (int row = 1; row <= 4; row++)
            for (int col = 1; col <= 7; col++)
                slots[idx++] = row * 9 + col;
        return slots;
    }
}
