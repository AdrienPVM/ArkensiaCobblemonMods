package com.roicheese.eggandcheese.util;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;

public class NbtUtils {

    /** Returns the sub-compound at key, creating it if absent */
    public static NbtCompound getOrCreate(ItemStack stack, String key) {
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        NbtCompound root = comp != null ? comp.copyNbt() : new NbtCompound();
        if (!root.contains(key)) root.put(key, new NbtCompound());
        NbtCompound sub = root.getCompound(key);
        root.put(key, sub);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(root));
        return sub;
    }

    /** Sets a sub-compound on the item's custom data */
    public static void setSubNbt(ItemStack stack, String key, NbtCompound data) {
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        NbtCompound root = comp != null ? comp.copyNbt() : new NbtCompound();
        root.put(key, data);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(root));
    }
}
