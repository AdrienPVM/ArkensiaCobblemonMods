package com.sebastian.cobblemondungeons.item;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.component.type.NbtComponent;
import com.sebastian.cobblemondungeons.config.DungeonTicketConfig;
import com.sebastian.cobblemondungeons.CobblemonDungeons;
import net.minecraft.util.Formatting;
import java.util.List;

public class DungeonTicketItem extends Item {
    public DungeonTicketItem(Settings settings) {
        super(settings);
    }

    public static ItemStack createStack(DungeonTicketConfig.TicketConfig t, int count) {
        ItemStack stack = new ItemStack(CobblemonDungeons.DUNGEON_TICKET, count);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(t.itemName).styled(s -> s.withItalic(false)));
        
        NbtCompound nbt = new NbtCompound();
        nbt.putString("DungeonId", t.dungeonId);
        nbt.putInt("Tier", t.tier);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        
        // Set CustomModelData for tiered textures
        stack.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(t.tier));
        
        return stack;
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        NbtComponent nbtComponent = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (nbtComponent != null) {
            NbtCompound nbt = nbtComponent.copyNbt();
            if (nbt.contains("DungeonId")) {
                tooltip.add(Text.translatable("cobblemondungeons.ticket.dungeon", nbt.getString("DungeonId")).formatted(Formatting.GRAY));
            }
            if (nbt.contains("Tier")) {
                tooltip.add(Text.translatable("cobblemondungeons.ticket.tier", nbt.getInt("Tier")).formatted(Formatting.GRAY));
            }
        }
        super.appendTooltip(stack, context, tooltip, type);
    }
}
