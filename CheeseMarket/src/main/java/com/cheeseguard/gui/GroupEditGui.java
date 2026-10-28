package com.cheeseguard.gui;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Flag;
import com.cheeseguard.data.Region;
import com.cheeseguard.data.RegionGroup;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class GroupEditGui extends ChestGui {

    private final String groupId;
    private final int returnPage;

    public GroupEditGui(ServerPlayerEntity player, String groupId, int returnPage) {
        super(player, 6);
        this.groupId = groupId;
        this.returnPage = returnPage;
    }

    @Override
    protected Text getTitle() {
        RegionGroup g = group();
        return Text.literal("📦 " + (g != null ? g.getName() : "???")).formatted(Formatting.YELLOW, Formatting.BOLD);
    }

    @Override
    protected void build() {
        fillAll(filler());
        RegionGroup g = group();
        if (g == null) { close(); return; }

        // Name
        fill(4, GuiHelper.item(Items.NAME_TAG, "📝 Nom: " + g.getName(), Formatting.WHITE,
            "ID: " + g.getId(),
            "Cliquez pour renommer"));

        // Zones in group
        fill(10, GuiHelper.item(Items.GRASS_BLOCK, "🗺 Zones du groupe", Formatting.GREEN,
            g.getRegionIds().size() + " zone(s) dans ce groupe",
            "",
            g.getRegionIds().stream()
                .limit(5)
                .reduce("", (a, b) -> a + "• " + b + "\n"),
            g.getRegionIds().size() > 5 ? "... et " + (g.getRegionIds().size() - 5) + " autres" : ""));

        // Flags
        fill(13, GuiHelper.item(Items.SHIELD, "🛡 Flags du groupe", Formatting.RED,
            g.getFlags().size() + " flag(s) configurés",
            "",
            "Ces flags s'appliquent à toutes",
            "les zones de ce groupe.",
            "",
            "Cliquez pour éditer"));

        // Flags summary (rows 3-4)
        int[] flagSlots = {28,29,30,31,32,33,34,37,38,39,40,41,42,43};
        Flag[] flags = Flag.values();
        for (int i = 0; i < flagSlots.length && i < flags.length; i++) {
            Flag flag = flags[i];
            Boolean val = g.getFlag(flag);
            if (val != null) {
                fill(flagSlots[i], GuiHelper.toggle(flag.getIcon() + " " + flag.getDisplayName(), val,
                    val ? "Autorisé dans les zones du groupe" : "Bloqué dans les zones du groupe",
                    "",
                    "Clic gauche: Autorisé",
                    "Clic droit: Bloqué",
                    "Shift+clic: Supprimer"));
            } else {
                fill(flagSlots[i], GuiHelper.item(Items.GRAY_DYE, flag.getIcon() + " " + flag.getDisplayName(),
                    Formatting.GRAY, "⚫ Non défini", "Clic gauche: Autorisé", "Clic droit: Bloqué"));
            }
        }

        // Bottom
        fill(45, GuiHelper.back());
        fill(53, GuiHelper.item(Items.TNT, "🗑 Supprimer le groupe", Formatting.RED,
            "Supprime le groupe",
            "Les zones garderont leurs flags propres"));
    }

    @Override
    protected void onClick(int slot, int button, SlotActionType action) {
        RegionGroup g = group();
        if (g == null) { close(); return; }

        if (slot == 45) { close(); new GroupListGui(player, returnPage).open(); return; }

        // Rename
        if (slot == 4) {
            close();
            player.sendMessage(Text.literal("💬 Tapez le nouveau nom du groupe :").formatted(Formatting.YELLOW), false);
            CheeseGuardMod.getInstance().awaitChatInput(player.getUuid(), name -> {
                g.setName(name);
                CheeseGuardMod.getInstance().getRegionManager().getStorage().save();
                new GroupEditGui(player, groupId, returnPage).open();
            });
            return;
        }

        // Open flags for group
        if (slot == 13) {
            close();
            new GroupFlagsGui(player, groupId, returnPage).open();
            return;
        }

        // Delete group
        if (slot == 53) {
            CheeseGuardMod.getInstance().getRegionManager().getStorage().removeGroup(groupId);
            close();
            player.sendMessage(Text.literal("🗑 Groupe supprimé : " + g.getName()).formatted(Formatting.RED), false);
            new GroupListGui(player, returnPage).open();
            return;
        }

        // Toggle flags inline
        int[] flagSlots = {28,29,30,31,32,33,34,37,38,39,40,41,42,43};
        Flag[] flags = Flag.values();
        for (int i = 0; i < flagSlots.length && i < flags.length; i++) {
            if (flagSlots[i] == slot) {
                Flag flag = flags[i];
                boolean shift = action == SlotActionType.PICKUP_ALL;
                if (shift) {
                    g.setFlag(flag, null);
                } else if (button == 0) {
                    g.setFlag(flag, true);
                } else {
                    g.setFlag(flag, false);
                }
                CheeseGuardMod.getInstance().getRegionManager().getStorage().save();
                refresh();
                return;
            }
        }
    }

    private RegionGroup group() {
        return CheeseGuardMod.getInstance().getRegionManager().getStorage().getGroup(groupId);
    }
}
