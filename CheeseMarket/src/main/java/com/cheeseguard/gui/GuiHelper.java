package com.cheeseguard.gui;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.ArrayList;

public class GuiHelper {

    /** Gray stained glass pane filler. */
    public static ItemStack filler() {
        return named(Items.GRAY_STAINED_GLASS_PANE, " ");
    }

    /** Create a named item with no lore. */
    public static ItemStack named(Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return stack;
    }

    /** Create a fully configured item with colored name and lore lines. */
    public static ItemStack item(Item item, String name, Formatting nameColor, String... loreLines) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME,
            Text.literal(name).formatted(nameColor));

        if (loreLines.length > 0) {
            List<Text> lore = new ArrayList<>();
            for (String line : loreLines) {
                lore.add(Text.literal(line).formatted(Formatting.GRAY));
            }
            stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
        }
        return stack;
    }

    /** Toggle item: green when true, red when false. */
    public static ItemStack toggle(String label, boolean value, String... lore) {
        Item icon = value ? Items.LIME_DYE : Items.RED_DYE;
        String prefix = value ? "✅ " : "❌ ";
        return item(icon, prefix + label, value ? Formatting.GREEN : Formatting.RED, lore);
    }

    /** A clickable button. */
    public static ItemStack button(Item item, String name, Formatting color, String... lore) {
        return item(item, name, color, lore);
    }

    /** Back button. */
    public static ItemStack back() {
        return item(Items.ARROW, "← Retour", Formatting.WHITE, "Cliquez pour revenir");
    }

    /** Next page button. */
    public static ItemStack nextPage(int current, int total) {
        return item(Items.ARROW, "→ Page suivante", Formatting.YELLOW,
            "Page " + current + "/" + total);
    }

    /** Previous page button. */
    public static ItemStack prevPage(int current, int total) {
        return item(Items.ARROW, "← Page précédente", Formatting.YELLOW,
            "Page " + current + "/" + total);
    }

    /** Head-like icon for a player. */
    public static ItemStack playerHead(String playerName, String... lore) {
        return item(Items.PLAYER_HEAD, playerName, Formatting.AQUA, lore);
    }
}
