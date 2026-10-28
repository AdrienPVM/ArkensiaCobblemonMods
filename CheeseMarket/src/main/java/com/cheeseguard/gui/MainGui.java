package com.cheeseguard.gui;

import com.cheeseguard.CheeseGuardMod;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class MainGui extends ChestGui {

    public MainGui(ServerPlayerEntity player) {
        super(player, 3);
    }

    @Override
    protected Text getTitle() {
        return Text.literal("🧀 CheeseGuard").formatted(Formatting.GOLD, Formatting.BOLD);
    }

    @Override
    protected void build() {
        fillAll(filler());

        var rm = CheeseGuardMod.getInstance().getRegionManager();
        int regionCount = (int) rm.getStorage().getRegions().stream().count();
        int groupCount  = (int) rm.getStorage().getGroups().stream().count();

        boolean visualizing = CheeseGuardMod.getInstance().getVisualizationManager().isVisualizing(player.getUuid());

        // Zones
        fill(11, GuiHelper.item(Items.GRASS_BLOCK, "📋 Zones", Formatting.GREEN,
            regionCount + " zone(s) définies",
            "Cliquez pour gérer les zones"));

        // Groups
        fill(13, GuiHelper.item(Items.BOOKSHELF, "📦 Groupes", Formatting.AQUA,
            groupCount + " groupe(s) définis",
            "Cliquez pour gérer les groupes"));

        // Wand
        fill(15, GuiHelper.item(Items.WOODEN_SWORD, "⚔ Obtenir le Wand", Formatting.YELLOW,
            "Cliquez pour recevoir l'épée",
            "de sélection CheeseGuard",
            "",
            "Clic gauche = Position 1",
            "Clic droit = Position 2"));

        // Toggle visualization
        fill(22, GuiHelper.toggle("Visualisation des zones", visualizing,
            "Affiche les bordures des zones",
            "avec des particules",
            "",
            "🟢 Zones accessibles",
            "🔴 Zones protégées"));

        // Info
        fill(4, GuiHelper.item(Items.NETHER_STAR, "🧀 CheeseGuard v1.0", Formatting.GOLD,
            "Protection de régions avancée",
            "par RoiCheese",
            "",
            "Serveur Cacademy"));
    }

    @Override
    protected void onClick(int slot, int button, SlotActionType action) {
        switch (slot) {
            case 11 -> { close(); new RegionListGui(player, 0).open(); }
            case 13 -> { close(); new GroupListGui(player, 0).open(); }
            case 15 -> {
                close();
                CheeseGuardMod.getInstance().giveWand(player);
            }
            case 22 -> {
                CheeseGuardMod.getInstance().getVisualizationManager().toggle(player.getUuid());
                refresh();
            }
        }
    }
}
