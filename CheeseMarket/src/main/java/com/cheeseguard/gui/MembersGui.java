package com.cheeseguard.gui;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Member;
import com.cheeseguard.data.Region;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

public class MembersGui extends ChestGui {

    private static final int PAGE_SIZE = 28;
    private final String regionId;
    private final int returnPage;
    private int page;
    private List<Member> members;

    public MembersGui(ServerPlayerEntity player, String regionId, int returnPage, int page) {
        super(player, 6);
        this.regionId = regionId;
        this.returnPage = returnPage;
        this.page = page;
    }

    @Override
    protected Text getTitle() {
        Region r = region();
        return Text.literal("👥 Membres: " + (r != null ? r.getName() : "???"))
            .formatted(Formatting.AQUA, Formatting.BOLD);
    }

    @Override
    protected void build() {
        fillAll(filler());
        Region r = region();
        if (r == null) { close(); return; }

        members = new ArrayList<>(r.getMembers());
        // Add owner as first entry (can't be removed)
        List<Member> display = new ArrayList<>();
        if (r.getOwner() != null) display.add(r.getOwner());
        display.addAll(members);

        int total = display.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) total / PAGE_SIZE));
        page = Math.max(0, Math.min(page, totalPages - 1));

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, total);
        int[] slots = contentSlots();
        int si = 0;

        for (int i = start; i < end && si < slots.length; i++, si++) {
            Member m = display.get(i);
            boolean isOwner = r.getOwner() != null && r.getOwner().getUuid().equals(m.getUuid());

            if (isOwner) {
                fill(slots[si], GuiHelper.item(Items.GOLDEN_HELMET,
                    "👑 " + m.getName() + " (Propriétaire)", Formatting.GOLD,
                    "UUID: " + m.getUuid(),
                    "Ne peut pas être retiré"));
            } else {
                fill(slots[si], GuiHelper.item(Items.PLAYER_HEAD,
                    "👤 " + m.getName(), Formatting.AQUA,
                    "UUID: " + m.getUuid(),
                    "",
                    "Clic droit: Retirer le membre"));
            }
        }

        // Bottom bar
        fill(45, GuiHelper.back());
        fill(49, GuiHelper.item(Items.EMERALD, "➕ Ajouter un membre", Formatting.GREEN,
            "Cliquez puis tapez le nom du joueur"));
        if (page > 0) fill(46, GuiHelper.prevPage(page + 1, totalPages));
        if (page < totalPages - 1) fill(52, GuiHelper.nextPage(page + 1, totalPages));
        fill(48, GuiHelper.item(Items.PAPER, "Page " + (page+1) + "/" + totalPages, Formatting.GRAY,
            total + " membre(s)"));
    }

    @Override
    protected void onClick(int slot, int button, SlotActionType action) {
        Region r = region();
        if (r == null) { close(); return; }

        if (slot == 45) { close(); new RegionEditGui(player, regionId, returnPage).open(); return; }
        if (slot == 46 && page > 0) { page--; refresh(); return; }
        if (slot == 52) { page++; refresh(); return; }

        if (slot == 49) {
            close();
            player.sendMessage(Text.literal("💬 Tapez le nom exact du joueur à ajouter :").formatted(Formatting.YELLOW), false);
            CheeseGuardMod.getInstance().awaitChatInput(player.getUuid(), name -> {
                // Look up player by name on the server
                var server = CheeseGuardMod.getInstance().getServer();
                var target = server.getPlayerManager().getPlayer(name);
                if (target == null) {
                    // Try offline lookup
                    var profile = server.getUserCache() != null
                        ? server.getUserCache().findByName(name).orElse(null) : null;
                    if (profile == null) {
                        player.sendMessage(Text.literal("❌ Joueur '" + name + "' introuvable.").formatted(Formatting.RED), false);
                    } else {
                        r.addMember(new Member(profile.getId(), profile.getName()));
                        CheeseGuardMod.getInstance().getRegionManager().getStorage().save();
                        player.sendMessage(Text.literal("✅ " + profile.getName() + " ajouté !").formatted(Formatting.GREEN), false);
                    }
                } else {
                    r.addMember(new Member(target.getUuid(), target.getName().getString()));
                    CheeseGuardMod.getInstance().getRegionManager().getStorage().save();
                    player.sendMessage(Text.literal("✅ " + target.getName().getString() + " ajouté !").formatted(Formatting.GREEN), false);
                }
                new MembersGui(player, regionId, returnPage, page).open();
            });
            return;
        }

        // Click on member = remove (right click only, skip owner)
        if (button == 1) {
            int[] slots = contentSlots();
            List<Member> display = new ArrayList<>();
            if (r.getOwner() != null) display.add(r.getOwner());
            display.addAll(r.getMembers());

            for (int i = 0; i < slots.length; i++) {
                if (slots[i] == slot) {
                    int idx = page * PAGE_SIZE + i;
                    if (idx < display.size()) {
                        Member m = display.get(idx);
                        boolean isOwner = r.getOwner() != null && r.getOwner().getUuid().equals(m.getUuid());
                        if (!isOwner) {
                            r.removeMember(m.getUuid());
                            CheeseGuardMod.getInstance().getRegionManager().getStorage().save();
                            player.sendMessage(Text.literal("✅ " + m.getName() + " retiré.").formatted(Formatting.GREEN), false);
                            refresh();
                        }
                    }
                    return;
                }
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

    private Region region() {
        return CheeseGuardMod.getInstance().getRegionManager().getStorage().getRegion(regionId);
    }
}
