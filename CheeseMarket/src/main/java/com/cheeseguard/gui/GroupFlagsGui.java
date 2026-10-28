package com.cheeseguard.gui;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Flag;
import com.cheeseguard.data.RegionGroup;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class GroupFlagsGui extends ChestGui {

    private final String groupId;
    private final int returnPage;
    private static final Flag[] ORDER = Flag.values();

    public GroupFlagsGui(ServerPlayerEntity player, String groupId, int returnPage) {
        super(player, 6);
        this.groupId = groupId;
        this.returnPage = returnPage;
    }

    @Override
    protected Text getTitle() {
        RegionGroup g = group();
        return Text.literal("🛡 Flags groupe: " + (g != null ? g.getName() : "???"))
            .formatted(Formatting.RED, Formatting.BOLD);
    }

    @Override
    protected void build() {
        fillAll(filler());
        RegionGroup g = group();
        if (g == null) { close(); return; }

        int[] slots = contentSlots();
        for (int i = 0; i < ORDER.length && i < slots.length; i++) {
            Flag flag = ORDER[i];
            Boolean val = g.getFlag(flag);
            var icon = val == null ? Items.GRAY_DYE : (val ? Items.LIME_DYE : Items.RED_DYE);
            String status = val == null ? "⚫ Non défini" : (val ? "✅ Autorisé" : "❌ Bloqué");
            Formatting color = val == null ? Formatting.GRAY : (val ? Formatting.GREEN : Formatting.RED);

            fill(slots[i], GuiHelper.item(icon,
                flag.getIcon() + " " + flag.getDisplayName(), color,
                status,
                "Appliqué à toutes les zones du groupe",
                "(peut être overridé par zone)",
                "",
                "Clic gauche: Autorisé ✅",
                "Clic droit: Bloqué ❌",
                "Shift+clic: Supprimer ⚫"));
        }

        fill(3, GuiHelper.item(Items.LIME_DYE, "✅ Autorisé", Formatting.GREEN, "Autorisé dans ce groupe"));
        fill(4, GuiHelper.item(Items.RED_DYE, "❌ Bloqué", Formatting.RED, "Bloqué dans ce groupe"));
        fill(5, GuiHelper.item(Items.GRAY_DYE, "⚫ Non défini", Formatting.GRAY, "Pas de règle dans ce groupe"));

        fill(45, GuiHelper.back());
        fill(49, GuiHelper.item(Items.HOPPER, "🔄 Vider tous les flags", Formatting.YELLOW,
            "Supprime toutes les règles de groupe"));
    }

    @Override
    protected void onClick(int slot, int button, SlotActionType action) {
        RegionGroup g = group();
        if (g == null) { close(); return; }

        if (slot == 45) { close(); new GroupEditGui(player, groupId, returnPage).open(); return; }
        if (slot == 49) {
            for (Flag f : Flag.values()) g.setFlag(f, null);
            CheeseGuardMod.getInstance().getRegionManager().getStorage().save();
            refresh();
            return;
        }

        int[] slots = contentSlots();
        for (int i = 0; i < ORDER.length && i < slots.length; i++) {
            if (slots[i] == slot) {
                Flag flag = ORDER[i];
                boolean shift = action == SlotActionType.PICKUP_ALL;
                if (shift) g.setFlag(flag, null);
                else if (button == 0) g.setFlag(flag, true);
                else g.setFlag(flag, false);
                CheeseGuardMod.getInstance().getRegionManager().getStorage().save();
                refresh();
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

    private RegionGroup group() {
        return CheeseGuardMod.getInstance().getRegionManager().getStorage().getGroup(groupId);
    }
}
