package com.cheeseguard.gui;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.Flag;
import com.cheeseguard.data.Region;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class FlagsGui extends ChestGui {

    private final String regionId;
    private final int returnPage;

    // Slots for 19 flags + some padding
    private static final Flag[] ORDER = Flag.values();

    public FlagsGui(ServerPlayerEntity player, String regionId, int returnPage) {
        super(player, 6);
        this.regionId = regionId;
        this.returnPage = returnPage;
    }

    @Override
    protected Text getTitle() {
        Region r = region();
        return Text.literal("🛡 Flags: " + (r != null ? r.getName() : "???")).formatted(Formatting.RED, Formatting.BOLD);
    }

    @Override
    protected void build() {
        fillAll(filler());
        Region r = region();
        if (r == null) { close(); return; }

        // Place flags in a grid (skip border slots)
        int[] flagSlots = contentSlots();

        for (int i = 0; i < ORDER.length && i < flagSlots.length; i++) {
            Flag flag = ORDER[i];
            Boolean val = r.getFlag(flag);

            String status;
            Formatting color;
            var icon = Items.LIME_DYE;

            if (val == null) {
                status = "⚫ Hérité";
                color = Formatting.GRAY;
                icon = Items.GRAY_DYE;
            } else if (val) {
                status = "✅ Autorisé";
                color = Formatting.GREEN;
                icon = Items.LIME_DYE;
            } else {
                status = "❌ Bloqué";
                color = Formatting.RED;
                icon = Items.RED_DYE;
            }

            fill(flagSlots[i], GuiHelper.item(icon,
                flag.getIcon() + " " + flag.getDisplayName(), color,
                status,
                "",
                "Clic gauche: Autorisé ✅",
                "Clic droit: Bloqué ❌",
                "Shift+clic: Hériter ⚫ (du groupe)"));
        }

        // Legend
        fill(3, GuiHelper.item(Items.LIME_DYE, "✅ Autorisé", Formatting.GREEN,
            "L'action est permise dans cette zone"));
        fill(4, GuiHelper.item(Items.RED_DYE, "❌ Bloqué", Formatting.RED,
            "L'action est bloquée dans cette zone"));
        fill(5, GuiHelper.item(Items.GRAY_DYE, "⚫ Hérité", Formatting.GRAY,
            "Hérite de la valeur du groupe",
            "ou autorisé par défaut si aucun groupe"));

        // Bottom bar
        fill(45, GuiHelper.back());
        fill(49, GuiHelper.item(Items.HOPPER, "🔄 Réinitialiser tous les flags", Formatting.YELLOW,
            "Remet tous les flags en mode",
            "Hérité (depuis le groupe)"));
    }

    @Override
    protected void onClick(int slot, int button, SlotActionType action) {
        Region r = region();
        if (r == null) { close(); return; }

        if (slot == 45) { close(); new RegionEditGui(player, regionId, returnPage).open(); return; }
        if (slot == 49) {
            for (Flag f : Flag.values()) r.setFlag(f, null);
            CheeseGuardMod.getInstance().getRegionManager().getStorage().save();
            refresh();
            return;
        }

        int[] flagSlots = contentSlots();
        for (int i = 0; i < ORDER.length && i < flagSlots.length; i++) {
            if (flagSlots[i] == slot) {
                Flag flag = ORDER[i];
                boolean shift = action == SlotActionType.PICKUP_ALL;

                if (shift) {
                    r.setFlag(flag, null); // inherit
                } else if (button == 0) {
                    r.setFlag(flag, true);  // allow
                } else if (button == 1) {
                    r.setFlag(flag, false); // deny
                }

                CheeseGuardMod.getInstance().getRegionManager().getStorage().save();
                refresh();
                return;
            }
        }
    }

    private int[] contentSlots() {
        // Rows 1-4, cols 1-7 = 28 slots
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
